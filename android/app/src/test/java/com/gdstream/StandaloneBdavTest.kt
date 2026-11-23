package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class StandaloneBdavTest {
    private fun packet(pid: Int, pts: Long) = ByteArray(192).apply {
        this[4] = 0x47
        this[5] = (64 or (pid ushr 8)).toByte()
        this[6] = pid.toByte()
        this[7] = 0x10
        byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (33 or ((pts ushr 29).toInt() and 14)).toByte(), (pts ushr 22).toByte(),
            ((pts ushr 14) or 1).toByte(), (pts ushr 7).toByte(), ((pts shl 1) or 1).toByte()).copyInto(this, 8)
    }

    @Test fun endpointsUseTheSamePidAndHandlePtsRollover() {
        val head = packet(0x1011, (1L shl 33) - 90000) + packet(0x1100, 0) + packet(0x1011, 100)
        val tail = packet(0x1011, 180000) + packet(0x1100, 99999999)
        val profile = StandaloneBdav.profile(head, tail, "opaque download")
        assertEquals(3000000L, profile.durationUs)
        assertFalse(StandaloneBdav.matches(ByteArray(192 * 3).apply { this[4] = 0x47 }))
        val size = 188L * 100
        val hls = BluRayHls.create(profile, size, standalone = true)!!
        assertEquals(3000000L, hls.segments.single().durationUs)
        assertEquals(size, hls.segments.single().end)
    }

    @Test fun sparsePcrIndexInterpolatesVbrInVirtualPacketCoordinatesAcrossRollover() {
        val pid = 0x1001
        val mask = (1L shl 33) - 1
        val origin = mask - 90_000
        fun pcr(seconds: Int, clockPid: Int = pid) = ByteArray(192).apply {
            this[4] = 0x47
            this[5] = (clockPid ushr 8).toByte()
            this[6] = clockPid.toByte()
            this[7] = 0x20
            this[8] = 183.toByte()
            this[9] = 0x10
            val t = (origin + seconds * 90_000L) and mask
            for (i in 0..3) this[10 + i] = (t ushr (25 - i * 8)).toByte()
            this[14] = (((t and 1) shl 7) or 0x7e).toByte()
        }
        val packets = StandaloneBdav.HLS_TARGET_PACKETS * 25L
        val index = StandalonePcrIndex.fromEndpoints(pcr(0), pcr(100), 192L * (packets + 1), pid)!!
        assertEquals(100_000_000L, index.spanUs)
        val middle = 188L * (packets / 2)
        assertNull(index.sample(pcr(15, 0x1002), 192, middle))
        assertEquals(BluRayPoint(20_000_000L, middle), index.sample(pcr(20), 192, middle))
        val profile = BluRayProfile(100_000_000L, emptyList(), emptyList(), pcrIndex = index)
        val segments = BluRayHls.create(profile, 188L * packets, standalone = true)!!.segments
        assertEquals(1_600_000L, segments.first().durationUs)
        assertEquals(100_000_000L, segments.sumOf { it.durationUs })
        assertTrue(segments.last().durationUs > segments.first().durationUs * 3)
        assertEquals(BluRayPoint(60_000_000L, 188L * (packets * 3 / 4)), index.estimate(61_000_000))
        assertNull(index.sample(pcr(10), 192, middle + 188))
        assertEquals(BluRayPoint(70_000_000, 188L * (packets * 825 / 1000)),
            index.sample(pcr(70).copyOfRange(4, 192), 188, 188L * (packets * 825 / 1000)))
    }

    @Test fun seekPrefixKeepsSplitParameterSetsAndJoinsTheFirstSourcePesCounter() {
        val pid = 0x1011
        fun packet(cc: Int, start: Boolean, data: ByteArray) = ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[1] = ((pid ushr 8) or if (start) 64 else 0).toByte()
            this[2] = pid.toByte()
            this[3] = (0x30 or cc).toByte()
            this[4] = (183 - data.size).toByte()
            this[5] = 0
            data.copyInto(this, 188 - data.size)
        }
        val pes = byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0, 0)
        val startCode = byteArrayOf(0, 0, 0, 1)
        val sps = byteArrayOf(0x67, 0x64, 0, 0x28) + ByteArray(190) { 0x55 }
        val pps = byteArrayOf(0x68, 0x3c)
        val ts = packet(12, true, pes + startCode + sps.copyOf(100)) +
            packet(13, false, sps.copyOfRange(100, sps.size) + byteArrayOf(0, 0)) +
            packet(14, true, pes.copyOf(5)) + // PES header and Annex B prefix both straddle packets
            packet(15, false, pes.copyOfRange(5, pes.size) + byteArrayOf(1) + pps + startCode + byteArrayOf(0x65, 1)) +
            packet(0, true, pes + startCode + byteArrayOf(0x65, 2))
        val program = BluRayPsi.program(javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes())!!
        val psi = program.prefix(program.streams, null, true)
        val avc = requireNotNull(StandaloneBdav.avcConfig(ts, pid))
        assertEquals("avc1.640028", avc.codec)
        assertNull(StandaloneBdav.avcConfig(ts.copyOf(2 * 188), pid))
        fun read(pos: Long, n: Int) = ts.copyOfRange(pos.toInt(), pos.toInt() + n)
        val size = ts.size.toLong()
        val grid = 3L * 188 // inside a split PES
        val (second, prefix) = avc.videoSegment(BluRayHls.Segment(grid, size, 1), psi, size, ::read)
        assertEquals(4L * 188, second.start)
        val (first, _) = avc.videoSegment(BluRayHls.Segment(0, grid, 1), psi, size) { _, _ -> error("cut was cached") }
        assertEquals(first.end, second.start)
        assertArrayEquals(ts, first.read(byteArrayOf(), 0, first.end.toInt(), ::read) +
            second.read(byteArrayOf(), 0, (second.end - second.start).toInt(), ::read))
        assertArrayEquals(psi, prefix.copyOf(psi.size))
        val config = prefix.copyOfRange(psi.size, prefix.size)
        val payload = java.io.ByteArrayOutputStream()
        for (p in config.indices step 188) {
            assertEquals(pid, (config[p+1].toInt() and 31) * 256 + (config[p+2].toInt() and 255))
            assertEquals(p == 0, config[p+1].toInt() and 64 != 0)
            assertEquals((14 + p / 188) and 15, config[p+3].toInt() and 15)
            val at = p + 4 + if (config[p+3].toInt() and 32 != 0) 1 + (config[p+4].toInt() and 255) else 0
            payload.write(config, at, p + 188 - at)
        }
        val bytes = payload.toByteArray()
        assertEquals(bytes.size - 6, (bytes[4].toInt() and 255) * 256 + (bytes[5].toInt() and 255))
        assertArrayEquals(startCode + sps + startCode + pps, bytes.copyOfRange(9, bytes.size))

        val tracks = listOf(BluRayStream(pid, 0x1b), BluRayStream(4352, 0x81), BluRayStream(4353, 0x82))
        val profile = BluRayProfile(8_000_000, emptyList(), emptyList(), tracks).also { it.avc = avc }
        val hls = requireNotNull(BluRayHls.create(profile, 1880, standalone = true, convertDts = true))
        val master = String(hls.master()).lines()
        assertTrue(master.single { it.endsWith("audio_4352.m3u8\"") }.contains("DEFAULT=YES,AUTOSELECT=YES"))
        val secondTrack = master.single { it.endsWith("audio_4353.m3u8\"") }
        assertTrue(secondTrack.contains("DEFAULT=NO,AUTOSELECT=YES"))
        assertTrue(secondTrack.contains("Audio 2 • DTS -> AC-3"))
        assertTrue(master.single { it.startsWith("#EXT-X-STREAM-INF:") }.contains("CODECS=\"avc1.640028,ac-3\""))
        assertTrue(String(hls.playlist(4353)).contains("audio_4353_seg_0.ts"))
        assertTrue(hls.transcodes(4353))
        assertFalse(hls.transcodes(4352))
    }

    @Test fun externalSeekWarmsExactlyThreePhysicalBlocksAndClipsAtEof() {
        val block = IsoChunkCache.BLOCK_BYTES.toLong()
        val position = (10 * block + block / 2) / 192 * 188
        assertEquals(10 * block until 13 * block, StandaloneBdav.readAhead(position, 20 * block))
        assertEquals(10 * block until 11 * block + 192, StandaloneBdav.readAhead(position, 11 * block + 192))
    }

    @Test fun virtualPlaylistCoversEveryPacketOfLargeVariableDurationFile() {
        val size = 188L * 111111111
        val duration = 60123456L
        val hls = BluRayHls.create(BluRayProfile(duration, emptyList(), emptyList()), size, true)!!
        assertTrue(hls.segments.dropLast(1).all { it.end - it.start == 8_388_560L })
        assertTrue(hls.segments.last().let { it.end - it.start in 188L..8_388_560L })
        assertEquals(duration, hls.segments.sumOf { it.durationUs })
        assertEquals(size, hls.segments.sumOf { it.end - it.start })
        assertTrue(hls.segments.zipWithNext().all { (a,b) -> a.end == b.start && b.start % 188 == 0L })
    }
}
