package com.gdstream

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class IsoVirtualFileTest {
    private val noPatch: (ByteArray, Int) -> Unit = { _, _ -> }

    private class Media(packets: Int = 12000) {
        val raw = ByteArray(packets * 192) { i -> if (i % 192 < 4) 0 else ((i / 192 + i % 192) % 251).toByte() }
        val expected = ByteArray(packets * 188) { i -> raw[(i / 188) * 192 + i % 188 + 4] }
        // Two clips, fragmented across physical extents at non-packet boundaries.
        val cuts = listOf(0, 2048, 65536, raw.size / 2, raw.size - 100, raw.size)
        val offsets = cuts.dropLast(1).indices.map { 5L * 1024 * 1024 * 1024 + it * 10000000L }
        val extents = cuts.zipWithNext().mapIndexed { i, (start, end) -> offsets[i] to (end - start).toLong() }
        val file = IsoVirtualFile(extents, true)
        fun fetch(start: Long, end: Long): ByteArray {
            val i = extents.indexOfFirst { (offset, length) -> start >= offset && end < offset + length }
            check(i >= 0) { "Crossed a physical extent in one remote request" }
            val p = cuts[i] + (start - offsets[i]).toInt()
            return raw.copyOfRange(p, p + (end - start + 1).toInt())
        }
    }

    private data class Response(val headers: String, val body: ByteArray)
    private fun response(bytes: ByteArray): Response {
        val split = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        assertTrue(split >= 0)
        return Response(bytes.copyOfRange(0, split).toString(Charsets.US_ASCII), bytes.copyOfRange(split + 4, bytes.size))
    }

    @Test fun packetMappingAcrossExtentsAndUnalignedSeeksIsByteExact() {
        val media = Media()
        assertEquals(media.expected.size.toLong(), media.file.size)
        val random = java.util.Random(7)
        val starts = listOf(0, 187, 188, 1999, 64000, media.expected.size / 2 - 19, media.expected.size - 189) +
            List(30) { random.nextInt(media.expected.size - 5000) }
        for (start in starts) {
            val count = minOf(4097, media.expected.size - start)
            assertArrayEquals(media.expected.copyOfRange(start, start + count), media.file.read(start.toLong(), count, media::fetch, noPatch))
        }
        val repeated = IsoVirtualFile(media.extents + media.extents, true)
        assertArrayEquals(media.expected.takeLast(100).toByteArray() + media.expected.take(200).toByteArray(),
            repeated.read(media.file.size - 100, 300, media::fetch, noPatch))
    }

    @Test fun readsAbove40GiBWithoutIntOverflowAndFillsSparseBytes() {
        val size = 44L * 1024 * 1024 * 1024
        val file = IsoVirtualFile(listOf(8L to size, -1L to 9L, 100L to 20L), false)
        val reads = ArrayList<Pair<Long, Long>>()
        val bytes = file.readRaw(size - 3, 20) { start, end ->
            reads.add(start to end)
            ByteArray((end - start + 1).toInt()) { start.toByte() }
        }
        assertEquals(listOf((size + 5) to (size + 7), 100L to 107L), reads)
        assertArrayEquals(ByteArray(3) { (size + 5).toByte() } + ByteArray(9) + ByteArray(8) { 100 }, bytes)
    }

    @Test fun servesFullOpenEndedBoundedSuffixHeadAnd416Responses() {
        val media = Media()
        val executor = Executors.newCachedThreadPool()
        try {
            val cases = listOf(null to (0 to media.expected.size), "bytes=187-190" to (187 to 4),
                "bytes=-199" to (media.expected.size - 199 to 199),
                "bytes=${media.expected.size - 100}-" to (media.expected.size - 100 to 100),
                "bytes=${media.expected.size - 3}-9999999999" to (media.expected.size - 3 to 3))
            for ((header, span) in cases) {
                val out = ByteArrayOutputStream()
                media.file.serve("GET", header, "video/mp2t", out, executor) { start, length -> media.file.read(start, length, media::fetch, noPatch) }
                val result = response(out.toByteArray())
                assertTrue(result.headers.startsWith(if (header == null) "HTTP/1.1 200 " else "HTTP/1.1 206 "))
                assertTrue(result.headers.contains("Content-Length: ${span.second}"))
                if (header != null) assertTrue(result.headers.contains("Content-Range: bytes ${span.first}-${span.first + span.second - 1}/${media.file.size}"))
                assertArrayEquals(media.expected.copyOfRange(span.first, span.first + span.second), result.body)
            }
            val head = ByteArrayOutputStream()
            media.file.serve("HEAD", "bytes=2-7", "video/mp2t", head, executor) { _, _ -> error("HEAD must not fetch") }
            assertTrue(response(head.toByteArray()).headers.startsWith("HTTP/1.1 200 "))
            assertEquals(0, response(head.toByteArray()).body.size)
            for (header in listOf("bytes=${media.file.size}-", "bytes=9-2", "bytes=-0", "bytes=a-b", "bytes=0-1,5-6", "bytes=9223372036854775808-")) {
                val out = ByteArrayOutputStream()
                media.file.serve("GET", header, "video/mp2t", out, executor) { _, _ -> error("Invalid range must not fetch") }
                assertTrue(response(out.toByteArray()).headers.startsWith("HTTP/1.1 416 "))
                assertTrue(response(out.toByteArray()).headers.contains("Content-Range: bytes */${media.file.size}"))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun loopback206CarriesExactLengthAndBytes() {
        val media = Media(1000)
        val executor = Executors.newCachedThreadPool()
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                val serving = executor.submit {
                    server.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        input.readLine()
                        var range: String? = null
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:")) range = line.substringAfter(':').trim()
                        }
                        media.file.serve("GET", range, "video/mp2t", client.getOutputStream(), executor) { start, length ->
                            media.file.read(start, length, media::fetch, noPatch)
                        }
                    }
                }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET /zip/test/movie.m2ts HTTP/1.1\r\nRange: bytes=1999-4100\r\nHost: localhost\r\n\r\n".toByteArray())
                    val reply = response(socket.getInputStream().readBytes())
                    assertTrue(reply.headers.startsWith("HTTP/1.1 206 "))
                    assertTrue(reply.headers.contains("Content-Length: 2102"))
                    assertArrayEquals(media.expected.copyOfRange(1999, 4101), reply.body)
                }
                serving.get()
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun failuresBeforeHeadersReturn502AndLaterFailuresDoNotAppendAnotherStatus() {
        val executor = Executors.newCachedThreadPool()
        try {
            val file = IsoVirtualFile(listOf(0L to (IsoRangeReader.WINDOW_BYTES * 2L)), false)
            for (failFirst in listOf(true, false)) {
                val out = ByteArrayOutputStream()
                assertThrows(IOException::class.java) {
                    file.serve("GET", "bytes=0-", "video/mp2t", out, executor) { start, length ->
                        if (failFirst || start > 0) throw IOException("upstream 404")
                        ByteArray(length)
                    }
                }
                val reply = response(out.toByteArray())
                assertTrue(reply.headers.startsWith(if (failFirst) "HTTP/1.1 502 " else "HTTP/1.1 206 "))
                assertEquals(if (failFirst) 0 else IsoRangeReader.WINDOW_BYTES, reply.body.size)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun prefetchesThreeChunksAheadBeforeFirstReadCompletesAndWritesInOrder() {
        val workers = Executors.newCachedThreadPool()
        val entered = java.util.concurrent.CountDownLatch(4)
        val release = java.util.concurrent.CountDownLatch(1)
        val starts = java.util.concurrent.ConcurrentSkipListSet<Long>()
        val length = 4 * IsoRangeReader.WINDOW_BYTES + 17
        val file = IsoVirtualFile(listOf(42_000_000_000L to length.toLong()), false)
        val out = ByteArrayOutputStream()
        try {
            val task = workers.submit {
                file.serve("GET", "bytes=0-", "video/mp2t", out, workers) { start, count ->
                    starts.add(start)
                    entered.countDown()
                    assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    ByteArray(count) { ((start + it) % 251).toByte() }
                }
            }
            assertTrue("Current chunk plus three look-ahead reads must overlap", entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(4, starts.size)
            assertEquals(0, out.size())
            release.countDown()
            task.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertArrayEquals(ByteArray(length) { (it % 251).toByte() }, response(out.toByteArray()).body)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    private class Connection(val status: Int, val range: String?, val data: ByteArray) : HttpURLConnection(URL("http://localhost/image.iso")) {
        var closed = false
        var bodyRead = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = if (name == "Content-Range") range else null
        override fun getInputStream(): ByteArrayInputStream { bodyRead = true; return ByteArrayInputStream(data) }
    }

    @Test fun validatesUpstreamStatusContentRangeAndTruncatedBodies() {
        val valid = Connection(206, "bytes 40000000000-40000000003/50000000000", byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(valid.data, IsoRangeReader.read(valid, 40000000000L, 40000000003L))
        assertTrue(valid.closed)
        for (bad in listOf(Connection(200, null, ByteArray(4)), Connection(404, null, ByteArray(4)),
            Connection(206, "bytes 0-3/50000000000", ByteArray(4)), Connection(206, null, ByteArray(4)),
            Connection(206, "bytes 40000000000-40000000003/50000000000", ByteArray(2)))) {
            assertThrows(IOException::class.java) { IsoRangeReader.read(bad, 40000000000L, 40000000003L) }
            assertTrue(bad.closed)
            if (bad.status != 206) assertFalse(bad.bodyRead)
        }
    }
}
