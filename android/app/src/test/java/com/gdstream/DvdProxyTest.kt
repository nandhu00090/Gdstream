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
        assertThrows(java.io.IOException::class.java) { mux.finish() }
    }

    @Test fun avcConfigurationRecordAndLengthPrefixedSlicesBecomeAnnexB() {
        val mux = DvdTsMux()
        // Two-byte lengths on samples, separate SPS and PPS in avcC.
        mux.video(0, byteArrayOf(1, 66, 0, 30, -3, -31, 0, 2, 0x67, 66, 1, 0, 2, 0x68, 1), true)
        mux.video(90_000, byteArrayOf(0, 2, 0x65, 42))
        val bytes = mux.finish()
        val expected = listOf(9, 0xf0, 0x67, 66, 0x68, 1, 0x65, 42).chunked(2)
            .flatMap { listOf(0, 0, 0, 1) + it }.map(Int::toByte).toByteArray()
        assertArrayEquals(expected, bytes.takeLast(expected.size).toByteArray())
        assertEquals(1, mux.frames)
    }

    @Test fun warmSegmentsRetainBothAnnexBParameterSetsAndTheSharedPesClock() {
        val mux = DvdTsMux()
        val config = byteArrayOf(0, 0, 1, 0x67, 66, 0, 0, 1, 0x68, 1)
        mux.video(0, config, true) // One INFO_OUTPUT_FORMAT_CHANGED for two segments.
        val clock = DvdPesClock(DvdCellClock(0xfffffff0L, 90_000), 0xfffffff0L)
        fun segment(audio: Long, video: Long, expected: List<Long>) {
            mux.beginSegment()
            mux.audio(clock.ticks(audio) * 1000 / 90, byteArrayOf(0x0b, 0x77))
            mux.video(clock.ticks(video), byteArrayOf(0, 0, 1, 0x65, 42))
            val ts = mux.finish()
            val timestamps = mutableListOf<Long>()
            for (p in ts.indices step 188) {
                val pid = ((ts[p + 1].toInt() and 31) shl 8) or (ts[p + 2].toInt() and 255)
                if (pid !in 257..258) continue
                val pes = p + 5 + (ts[p + 4].toInt() and 255)
                val t = pes + 9
                fun u(i: Int) = ts[t + i].toLong() and 255
                timestamps += ((u(0) and 14) shl 29) or (u(1) shl 22) or ((u(2) and 254) shl 14) or
                    (u(3) shl 7) or (u(4) shr 1)
                if (pid == 257) assertArrayEquals(
                    byteArrayOf(0,0,0,1,9,-16, 0,0,0,1,0x67,66, 0,0,0,1,0x68,1, 0,0,0,1,0x65,42),
                    ts.copyOfRange(pes + 14, p + 188))
            }
            assertEquals(expected, timestamps)
        }
        clock.vobu(0xfffffff0L, 0x8c90)
        segment(0x1ffffffe0, 0x100000000, listOf(89_984, 90_016))
        // Next VOBU resets its PTM. Preserve the audio lead over video.
        clock.vobu(1_000, 37_000)
        segment(984, 1000, listOf(125_984, 126_000))
    }

    @Test fun videoOverrunningTwoSequentialAudioWindowsIsScaledBeforeWritingPesAndPcr() {
        val mux = DvdTsMux()
        mux.video(0, byteArrayOf(0, 0, 1, 0x67, 66, 0, 0, 1, 0x68, 1), true)
        for (segment in 0..1) {
            val start = 90_000L + segment * 360_000
            mux.beginSegment()
            repeat(125) { mux.audio((start + it * 2880) * 1000 / 90, byteArrayOf(11, 119)) }
            val count = if (segment == 0) 240 else 225
            repeat(count) {
                mux.video(start + 1_080_000 + it * 3600, byteArrayOf(0, 0, 1, if (it == 0) 0x65 else 0x41, 42))
            }
            val ts = mux.finish(start, start + 360_000)
            val timestamps = mutableListOf<Long>()
            for (p in ts.indices step 188) {
                fun u(i: Int) = ts[p + i].toLong() and 255
                if (u(1) != 0x41L || u(2) != 1L) continue
                val pcr = (u(6) shl 25) or (u(7) shl 17) or (u(8) shl 9) or (u(9) shl 1) or (u(10) ushr 7)
                val t = 14 + u(4).toInt()
                val pts = ((u(t) and 14) shl 29) or (u(t + 1) shl 22) or ((u(t + 2) and 254) shl 14) or
                    (u(t + 3) shl 7) or (u(t + 4) shr 1)
                assertEquals(pts, pcr)
                timestamps += pts
            }
            assertEquals((0 until count).map { start + 360_000L * it / count }, timestamps)
        }
    }

    @Test fun warmPcrIgnoresSmallAudioSeamsAndSlewsLargeOnesWithoutResettingCounters() {
        val mux = DvdTsMux()
        mux.video(0, byteArrayOf(0, 0, 1, 0x67, 66, 0, 0, 1, 0x68, 1), true)
        var lastCc = -1
        fun segment(start: Long): List<Long> {
            mux.beginSegment()
            repeat(4) { mux.video(start + it * 3600, byteArrayOf(0, 0, 1, if (it == 0) 0x65 else 0x41, 42)) }
            val ts = mux.finish(start, start + 14400)
            return (ts.indices step 188).filter { ts[it + 2] == 1.toByte() }.map { p ->
                fun u(i: Int) = ts[p + i].toLong() and 255
                assertEquals((lastCc + 1) and 15, u(3).toInt() and 15)
                lastCc++
                (u(6) shl 25) or (u(7) shl 17) or (u(8) shl 9) or (u(9) shl 1) or (u(10) ushr 7)
            }
        }
        assertEquals(100_800L, segment(90_000).last())
        assertEquals(listOf(104_400L, 108_000L, 111_600L, 115_200L), segment(106_000))
        assertEquals(listOf(118_800L, 122_625L, 126_450L, 130_275L), segment(128_800))
    }

    @Test fun speculativeSuccessIsReusedAndFailureIsRetriedOnDemand() {
        val cache = DvdSegmentCache()
        val session = IsoReadSession()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val prefetched = byteArrayOf(1)
        fun load(index: Int, owner: IsoReadSession): ByteArray {
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
        assertSame(prefetched, cache.segment("dvd", 1, 2, session, true) { _, _ ->
            attempted.countDown()
            throw java.io.IOException("transient")
        })
        assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(2), cache.segment("dvd", 2, 2, session, false) { _, _ -> byteArrayOf(2) })
    }

    @Test fun segmentOpensWithValidPatAndPmtAndKeepsAc3Bytes() {
        val mux = DvdTsMux()
        val audio = ByteArray(1024) { it.toByte() }
        mux.audio(1_200_000, audio)
        mux.video(0, byteArrayOf(0, 0, 1, 0x67, 66), true)
        mux.video(0, byteArrayOf(0, 0, 1, 0x68, 1), true)
        mux.video(108_900, byteArrayOf(0, 0, 0, 1, 0x65, 1))
        val bytes = mux.finish()
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
