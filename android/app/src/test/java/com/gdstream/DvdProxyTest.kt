package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class DvdProxyTest {
    @Test fun playlistRemainsContinuousInsideACellAndOmitsSubtitleRenditions() {
        val playlist = String(DvdProxy.playlist(listOf(DvdProxy.Segment(0, 2048, 4000000),
            DvdProxy.Segment(2048, 4096, 4000000), DvdProxy.Segment(4096, 6144, 4000000, true))))
        assertTrue(playlist.contains("#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n"))
        assertEquals(1, playlist.lines().count { it == "#EXT-X-DISCONTINUITY" })
        assertFalse(playlist.contains("SUBTITLES"))
    }

    @Test fun audioAndCodecConfigurationAloneCannotProduceASegment() {
        val mux = DvdTsMux()
        mux.audio(0, ByteArray(100))
        mux.video(0, byteArrayOf(0, 0, 1, 0x67, 66), true)
        assertEquals(0, mux.frames)
        assertThrows(java.io.IOException::class.java) { mux.finish(0, 3600) }
    }

    @Test fun avcConfigurationRecordAndLengthPrefixedSlicesBecomeAnnexB() {
        val mux = DvdTsMux()
        // Two-byte lengths on samples, separate SPS and PPS in avcC.
        mux.video(0, byteArrayOf(1, 66, 0, 30, -3, -31, 0, 2, 0x67, 66, 1, 0, 2, 0x68, 1), true)
        mux.video(90_000, byteArrayOf(0, 2, 0x65, 42))
        val bytes = mux.finish(90_000, 93_600)
        val expected = listOf(9, 0xf0, 0x67, 66, 0x68, 1, 0x65, 42).chunked(2)
            .flatMap { listOf(0, 0, 0, 1) + it }.map(Int::toByte).toByteArray()
        assertArrayEquals(expected, bytes.takeLast(expected.size).toByteArray())
        assertEquals(1, mux.frames)
    }

    private fun timestamps(ts: ByteArray, pid: Int): List<Long> = (ts.indices step 188).mapNotNull { p ->
        fun u(i: Int) = ts[p + i].toLong() and 255
        if ((u(1) and 0x40) == 0L || (u(1) and 31) * 256 + u(2) != pid.toLong()) return@mapNotNull null
        val pes = 4 + if (u(3) and 32 != 0L) 1 + u(4).toInt() else 0
        val t = pes + 9
        val pts = ((u(t) and 14) shl 29) or (u(t + 1) shl 22) or ((u(t + 2) and 254) shl 14) or
            (u(t + 3) shl 7) or (u(t + 4) shr 1)
        if (pid == 257) {
            val pcr = (u(6) shl 25) or (u(7) shl 17) or (u(8) shl 9) or (u(9) shl 1) or (u(10) shr 7)
            assertEquals(pts, pcr)
        }
        pts
    }

    private val config = byteArrayOf(0, 0, 1, 0x67, 66, 0, 0, 1, 0x68, 1)
    private fun picture(idr: Boolean) = byteArrayOf(0, 0, 1, if (idr) 0x65 else 0x41, 42)

    @Test fun mynaaPesOverlapsAndGapCannotMoveEitherWarmOutputClock() {
        val mux = DvdTsMux()
        val source = listOf(90_000L..648_558, 461_657L..1_047_242, 860_244L..1_198_081, 1_257_940L..1_595_778)
        var nextVideo = source.first().first
        var nextAudio = nextVideo - 900
        var lastCc = -1
        source.forEachIndexed { index, raw ->
            // Reopening an encoder for EOS fallback must also retain the mux timeline.
            val newEncoder = index == 0 || index == 2
            mux.beginSegment(newEncoder)
            if (newEncoder) mux.video(0, config, true)
            val frames = listOf(155, 163, 94, 94)[index]
            val audioFrames = listOf(194, 204, 117, 118)[index]
            repeat(frames) { mux.video(raw.first + it * 3600, picture(it == 0)) }
            repeat(audioFrames) { mux.audio((raw.first - 900 + it * 2880) * 1000 / 90, byteArrayOf(11, 119)) }
            val ts = mux.finish(raw.first, raw.first + frames * 3600, raw.last - raw.first)
            assertEquals((0 until frames).map { nextVideo + it * 3600 }, timestamps(ts, 257))
            assertEquals((0 until audioFrames).map { nextAudio + it * 2880 }, timestamps(ts, 258))
            for (p in ts.indices step 188) if (ts[p + 2] == 1.toByte()) {
                assertEquals((++lastCc) and 15, ts[p + 3].toInt() and 15)
            }
            nextVideo += frames * 3600
            nextAudio += audioFrames * 2880
            assertEquals(nextVideo, mux.timing!!.nextVideo)
            assertEquals(nextAudio, mux.timing!!.nextAudio)
        }
    }

    @Test fun coldSeekUsesFirstVideoPesAndUnwrapsAudioAcrossThe33BitBoundary() {
        val vob = javaClass.getResourceAsStream("/dvd/interlaced.vob.bin")!!.readBytes()
        assertEquals(48_600L, DvdProxy.sourceTiming(vob, -1).basePts)
        val mux = DvdTsMux()
        val wrap = 1L shl 33
        mux.video(0, config, true)
        mux.video(16, picture(true))
        mux.audio((wrap - 16) * 1000 / 90, byteArrayOf(11, 119))
        var ts = mux.finish(16, 3616)
        assertEquals(listOf(16L), timestamps(ts, 257))
        assertEquals(listOf(wrap - 16), timestamps(ts, 258))
        mux.beginSegment()
        mux.video(3616, picture(true))
        mux.audio(100_000, byteArrayOf(11, 119))
        ts = mux.finish(90_000, 7216)
        assertEquals(listOf(3616L), timestamps(ts, 257))
        assertEquals(listOf(2864L), timestamps(ts, 258))
    }

    @Test fun videoOverrunningTwoSequentialAudioWindowsIsScaledBeforeWritingPesAndPcr() {
        val mux = DvdTsMux()
        mux.video(0, config, true)
        for (segment in 0..1) {
            val start = 90_000L + segment * 360_000
            mux.beginSegment()
            repeat(125) { mux.audio((start + it * 2880) * 1000 / 90, byteArrayOf(11, 119)) }
            val count = if (segment == 0) 240 else 225
            val native = start + 1_080_000
            repeat(count) { mux.video(native + it * 3600, picture(it == 0)) }
            val ts = mux.finish(start, native + count * 3600L)
            assertEquals((0 until count).map { start + 360_000L * it / count }, timestamps(ts, 257))
        }
    }

    @Test fun finalRepeatFieldDeterminesTheNextVideoPtsEvenWithoutAudio() {
        val mux = DvdTsMux()
        mux.video(0, config, true)
        mux.video(1000, picture(true))
        mux.video(4600, picture(false))
        mux.finish(1000, 10_000)
        mux.beginSegment()
        mux.video(10_000, picture(true))
        assertEquals(listOf(10_000L), timestamps(mux.finish(500_000, 13_600), 257))
    }

    @Test fun speculativeSuccessIsReusedAndFailureIsRetriedOnDemand() {
        val cache = DvdSegmentCache()
        val session = IsoReadSession()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val prefetched = byteArrayOf(1)
        fun load(index: Int, owner: IsoReadSession, sequence: Long): ByteArray {
            assertTrue(sequence > 0)
            if (index == 1) {
                entered.countDown()
                assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
            owner.checkActive()
            return if (index == 1) prefetched else byteArrayOf(0)
        }
        cache.segment("dvd", 0, 2, session, true, ::load)
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // The in-flight next segment must never hold the completed-cache lock.
        assertArrayEquals(byteArrayOf(0), cache.segment("dvd", 0, 2, session, false, ::load))
        release.countDown()
        val attempted = java.util.concurrent.CountDownLatch(1)
        assertSame(prefetched, cache.segment("dvd", 1, 2, session, true) { _, _, _ ->
            attempted.countDown()
            throw java.io.IOException("transient")
        })
        assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(2), cache.segment("dvd", 2, 2, session, false) { _, _, _ -> byteArrayOf(2) })
    }

    @Test fun aSeekToACachedSegmentStartsANewTimeline() {
        val cache = DvdSegmentCache()
        val session = IsoReadSession()
        fun request(index: Int) = cache.segment("dvd", index, 20, session, false) { _, _, sequence ->
            byteArrayOf(sequence.toByte())
        }
        val first = request(5)
        assertArrayEquals(first, request(6))
        val back = request(5)
        assertFalse(first.contentEquals(back))
        assertSame(back, request(5))
        assertArrayEquals(back, request(6))
    }

    @Test fun segmentOpensWithValidPatAndPmtAndKeepsAc3Bytes() {
        val mux = DvdTsMux()
        val audio = ByteArray(1024) { it.toByte() }
        mux.audio(1_200_000, audio)
        mux.video(0, byteArrayOf(0, 0, 1, 0x67, 66), true)
        mux.video(0, byteArrayOf(0, 0, 1, 0x68, 1), true)
        mux.video(108_900, byteArrayOf(0, 0, 0, 1, 0x65, 1))
        val bytes = mux.finish(108_900, 112_500)
        assertEquals(0, bytes.size % 188)
        val streams = BluRayPsi.streams(bytes)
        assertEquals(listOf(0x1b, 0x81), streams.map { it.type })
        val payload = java.io.ByteArrayOutputStream()
        for (p in bytes.indices step 188) {
            assertEquals(0x47, bytes[p].toInt())
            if (((bytes[p+1].toInt() and 31) shl 8) + (bytes[p+2].toInt() and 255) != 258) continue
            val at = p + 4 + if (bytes[p+3].toInt() and 32 != 0) 1 + (bytes[p+4].toInt() and 255) else 0
            payload.write(bytes, at, p + 188 - at)
        }
        assertArrayEquals(audio, payload.toByteArray().copyOfRange(14, 14 + audio.size))
    }
}
