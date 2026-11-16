package com.gdstream

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class BluRaySpecsTest {
    @Test fun selectsOneHighestByteFeatureRatherThanLongestBonus() {
        val candidates = listOf("bonus" to 3_000_000_000L, "movie" to 45_000_000_000L, "feature2" to 12_000_000_000L)
        assertEquals("movie", BluRayPlaylist.largest(candidates) { it.second }?.first)
        assertNull(BluRayPlaylist.largest(emptyList<Long>()) { it })
    }

    private fun clpi(streams: List<Pair<Int, ByteArray>>): ByteArray {
        val data = ByteArray(512)
        "HDMV0300".toByteArray().copyInto(data)
        fun put(p: Int, n: Long, bytes: Int = 4) { TestBits(data, p * 8).put(n, bytes * 8) }
        put(8, 32); put(32, 22); put(37, 1, 1); put(42, 1, 1)
        put(50, 27_000_000); put(54, 27_000_000 + 9995L * 45000)
        put(12, 64); put(69, 1, 1); put(76, streams.size.toLong(), 1)
        var p = 78
        streams.forEach { (pid, descriptor) ->
            put(p, pid.toLong(), 2); put(p + 2, descriptor.size.toLong(), 1)
            descriptor.copyInto(data, p + 3); p += 3 + descriptor.size
        }
        put(64, (p - 68).toLong())
        return data
    }

    @Test fun clpiKeepsUhdBaseResolutionDualTrackDvLanguagesAndDuration() {
        val entries = listOf(0x1011 to byteArrayOf(0x24, 0x81.toByte(), 0, 0x20),
            0x1015 to byteArrayOf(0x24, 0x61, 0, 0)) +
            listOf("eng", "fra", "spa", "tam", "hin").mapIndexed { i, lang ->
                (0x1100 + i) to (byteArrayOf(0x86.toByte(), 0x61) + lang.toByteArray())
            } + listOf(0x1200 to (byteArrayOf(0x90.toByte()) + "tam".toByteArray()))
        val profile = BluRayClipInfo.parseRaw(clpi(entries), 45_000_000_000L)
        val mi = profile.mediaInfo(44_000_000_000L)
        assertEquals(9995_000_000L, profile.durationUs)
        assertEquals(2160, mi["height"])
        assertEquals(true, mi["hasDV"]); assertEquals(true, mi["hasHDR"])
        assertEquals(listOf("English", "French", "Spanish", "Tamil", "Hindi"), mi["languages"])
        assertEquals("Tamil", ((mi["subtitleTracks"] as List<*>).first() as Map<*, *>)["language"])
        assertEquals(profile, BluRayProfile.fromJson(profile.toJson()))
    }

    @Test fun sdrHdrAndDiscLabelEvidenceDoNotDependOnOuterFilename() {
        val sdr = BluRayProfile(1, emptyList(), emptyList(), listOf(BluRayStream(0x1011, 0x1b, 6)))
        assertEquals("1080p", sdr.mediaInfo(1)["resolution"])
        assertEquals(false, sdr.mediaInfo(1)["hasDV"])
        assertEquals(false, sdr.mediaInfo(1)["hasHDR"])
        val hdr = sdr.copy(streams = listOf(BluRayStream(0x1011, 0x24, 8, dynamicRange = 2), BluRayStream(0x1012, 0x24, 6)))
        assertEquals(false, hdr.mediaInfo(1)["hasDV"]) // arbitrary secondary video is not DV
        assertEquals(true, hdr.mediaInfo(1)["hasHDR"])
        assertEquals(true, hdr.copy(discLabel = "MOVIE_UHD_DOLBY_VISION_HDR10").mediaInfo(1)["hasDV"])
        assertEquals("Unknown", bluRayLanguage("und"))
    }

    @Test fun mplsStnDescriptorsSupplementMissingClpiMetadata() {
        val data = ByteArray(128)
        fun put(p: Int, n: Long, bytes: Int = 1) { TestBits(data, p * 8).put(n, bytes * 8) }
        put(38, 1); put(39, 1); put(40, 1); put(45, 1) // video/audio/PGS/DV counts
        var p = 50
        for ((pid, desc) in listOf(0x1011 to byteArrayOf(0x24, 0x81.toByte(), 0x20),
            0x1100 to (byteArrayOf(0x80.toByte(), 0x61) + "tam".toByteArray()),
            0x1200 to (byteArrayOf(0x90.toByte()) + "hin".toByteArray()))) {
            put(p++, 3); put(p++, 1); put(p, pid.toLong(), 2); p += 2
            put(p++, desc.size.toLong()); desc.copyInto(data, p); p += desc.size
        }
        put(34, (p - 36).toLong(), 2)
        val specs = BluRayMplsSpecs.parseItem(data, 0, p)
        assertTrue(specs.dolbyVision)
        assertEquals(2160, specs.streams.first().height)
        assertEquals(2, specs.streams.first().dynamicRange)
        assertEquals(listOf("tam", "hin"), specs.streams.drop(1).map { it.language })
        val merged = mergeBluRayStreams(listOf(BluRayStream(0x1100, 0x80, language = "und")) + specs.streams)
        assertEquals("tam", merged.first().language)
        assertThrows(IllegalArgumentException::class.java) { BluRayMplsSpecs.parseItem(data, 0, p - 1) }
    }

    @Test fun udfDstringsDecodeBothCompressionForms() {
        fun field(text: String, unicode: Boolean): ByteArray {
            val bytes = text.toByteArray(if (unicode) Charsets.UTF_16BE else Charsets.ISO_8859_1)
            return ByteArray(128).apply { this[0] = if (unicode) 16 else 8; bytes.copyInto(this, 1); this[127] = (bytes.size + 1).toByte() }
        }
        for (unicode in listOf(false, true)) assertEquals("MOVIE_UHD_DV_HDR10", UdfReader.decodeVolumeLabel(field("MOVIE_UHD_DV_HDR10", unicode)))
        assertEquals("", UdfReader.decodeVolumeLabel(ByteArray(128)))
    }

    @Test fun headAndPartialContentAdvertiseFullDurationAndDlnaWithoutChangingByteRanges() {
        val file = IsoVirtualFile(listOf(6_000_000_000L to 42_000_000_000L), false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (method in listOf("HEAD", "GET")) {
                val out = ByteArrayOutputStream()
                file.serve(method, if (method == "GET") "bytes=41000000000-41000000003" else null,
                    "video/mp2t", out, executor, durationUs = 9995_123456L) { _, length -> ByteArray(length) }
                val response = out.toString("US-ASCII")
                assertTrue(response.contains("X-Content-Duration: 9995.123456\r\n"))
                assertTrue(response.contains("Content-Duration: 9995.123456\r\n"))
                assertTrue(response.contains("transferMode.dlna.org: Streaming\r\n"))
                assertTrue(response.contains("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0\r\n"))
                assertTrue(response.contains("Accept-Ranges: bytes\r\n"))
                assertTrue(response.contains("Content-Length: ${if (method == "HEAD") 42000000000L else 4}\r\n"))
                if (method == "GET") assertTrue(response.contains("Content-Range: bytes 41000000000-41000000003/42000000000"))
            }
        } finally { executor.shutdownNow() }
    }
}
