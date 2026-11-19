package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class DvdProxyTest {
    @Test fun segmentOpensWithValidPatAndPmtAndKeepsAc3Bytes() {
        val mux = DvdTsMux()
        val audio = ByteArray(1024) { it.toByte() }
        mux.audio(1_200_000, audio)
        mux.video(1_210_000, byteArrayOf(0, 0, 0, 1, 0x65))
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
