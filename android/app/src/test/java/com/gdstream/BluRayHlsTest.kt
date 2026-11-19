package com.gdstream

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class BluRayHlsTest {
    @Test fun clpiVodKeepsExactFractionalDurationAndPacketAlignedCoverageAbove4Gb() {
        val size = 188L * 300_000_000
        val points = (0..100).map { BluRayPoint(it * 1_000_000L, it * 2_000_000L * 188) }
        val profile = BluRayProfile(100_123_456, points, emptyList())
        val hls = BluRayHls.create(profile, size)!!
        val playlist = String(hls.playlist())
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:4"))
        assertTrue(playlist.endsWith("#EXT-X-ENDLIST\n"))
        assertEquals(100_123_456, hls.segments.sumOf { it.durationUs })
        assertEquals(size, hls.segments.sumOf { it.end - it.start })
        assertEquals(0, hls.segments.first().start)
        assertEquals(size, hls.segments.last().end)
        assertTrue(hls.segments.zipWithNext().all { (a, b) -> a.end == b.start })
        assertTrue(hls.segments.all { it.start % 188 == 0L && it.end % 188 == 0L })
        assertTrue(playlist.contains("#EXTINF:0.123456,"))
    }
    @Test fun absentOrInvalidClpiRequiresRawTsFallback() {
        assertNull(BluRayHls.create(null, 18800))
        for (points in listOf(emptyList(), listOf(BluRayPoint(0, 0)), listOf(BluRayPoint(0, 0), BluRayPoint(2, 189)),
            listOf(BluRayPoint(0, 0), BluRayPoint(0, 188)), listOf(BluRayPoint(0, 0), BluRayPoint(10, 188)))) {
            assertNull(BluRayHls.create(BluRayProfile(10, points, emptyList()), 18800))
        }
    }
    @Test fun everySegmentAndByteRangeStartsWithValidExternalPsiAndKeepsExactPayload() {
        val bytes = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes()
        val prefix = BluRayPsi.prefix(bytes)!!
        assertEquals(0, prefix.size % 188)
        assertEquals(0x47, prefix[0].toInt())
        // Reparse the generated prefix, including adaptation fields and rewritten PMT CRC.
        assertArrayEquals(prefix, BluRayPsi.prefix(prefix))
        val hls = BluRayHls.create(BluRayProfile(30_250_000, listOf(BluRayPoint(0, 0),
            BluRayPoint(9_000_000, 188 * 100L), BluRayPoint(18_000_000, 188 * 200L), BluRayPoint(27_000_000, 188 * 300L)), emptyList()), bytes.size.toLong())!!
        val executor = Executors.newCachedThreadPool()
        try {
            for (segment in hls.segments) {
                val expected = prefix + bytes.copyOfRange(segment.start.toInt(), segment.end.toInt())
                for (range in listOf<String?>(null, "bytes=180-401", "bytes=-190")) {
                    val out = ByteArrayOutputStream()
                    IsoVirtualFile(listOf(0L to segment.size(prefix)), false).serve("GET", range, "video/MP2T", out, executor) { start, count ->
                        segment.read(prefix, start, count) { from, length -> bytes.copyOfRange(from.toInt(), from.toInt() + length) }
                    }
                    val response = out.toByteArray()
                    val split = response.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
                    val requested = HttpByteRange.parse(range, expected.size.toLong())
                    assertArrayEquals(expected.copyOfRange(requested.start.toInt(), requested.end.toInt() + 1), response.copyOfRange(split, response.size))
                }
            }
        } finally { executor.shutdownNow() }
        assertEquals("id", ArchiveProxyStreams.streamId("http://127.0.0.1:99/hls/id/index.m3u8"))
    }
}
