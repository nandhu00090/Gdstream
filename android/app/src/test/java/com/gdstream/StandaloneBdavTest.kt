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
        val index = StandalonePcrIndex.fromEndpoints(pcr(0), pcr(100), 192L * 1001, pid)!!
        assertEquals(100_000_000L, index.spanUs)
        val middle = 188L * 500
        assertNull(index.sample(pcr(15, 0x1002), 192, middle))
        assertEquals(BluRayPoint(20_000_000L, middle), index.sample(pcr(20), 192, middle))
        val profile = BluRayProfile(100_000_000L, emptyList(), emptyList(), pcrIndex = index)
        val segments = BluRayHls.create(profile, 188L * 1001, standalone = true)!!.segments
        assertEquals(1_600_000L, segments.first().durationUs)
        assertEquals(100_000_000L, segments.sumOf { it.durationUs })
        assertTrue(segments.last().durationUs > segments.first().durationUs * 3)
        assertEquals(BluRayPoint(60_000_000L, 188L * 750), index.estimate(61_000_000))
        assertNull(index.sample(pcr(10), 192, middle + 188))
        assertEquals(BluRayPoint(70_000_000, 188L * 825),
            index.sample(pcr(70).copyOfRange(4, 192), 188, 188L * 825))
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
        assertTrue(hls.segments.all { it.end - it.start in 188L..4L * 1024 * 1024 })
        assertEquals(duration, hls.segments.sumOf { it.durationUs })
        assertEquals(size, hls.segments.sumOf { it.end - it.start })
        assertTrue(hls.segments.zipWithNext().all { (a,b) -> a.end == b.start && b.start % 188 == 0L })
    }
}
