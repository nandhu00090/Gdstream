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
        mux.video(1_000_000, byteArrayOf(0, 2, 0x65, 42))
        val bytes = mux.finish()
        val expected = listOf(9, 0xf0, 0x67, 66, 0x68, 1, 0x65, 42).chunked(2)
            .flatMap { listOf(0, 0, 0, 1) + it }.map(Int::toByte).toByteArray()
        assertArrayEquals(expected, bytes.takeLast(expected.size).toByteArray())
        assertEquals(1, mux.frames)
    }

    @Test fun segmentOpensWithValidPatAndPmtAndKeepsAc3Bytes() {
        val mux = DvdTsMux()
        val audio = ByteArray(1024) { it.toByte() }
        mux.audio(1_200_000, audio)
        mux.video(0, byteArrayOf(0, 0, 1, 0x67, 66), true)
        mux.video(0, byteArrayOf(0, 0, 1, 0x68, 1), true)
        mux.video(1_210_000, byteArrayOf(0, 0, 0, 1, 0x65, 1))
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
