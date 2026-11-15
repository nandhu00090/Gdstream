package com.gdstream

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class IsoUpstreamTest {
    private val start = 42000000000L
    private open class Connection(val status: Int, val range: String?, val body: ByteArray) : HttpURLConnection(URL("http://origin/download.aspx?token=secret")) {
        var closed = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = if (name == "Content-Range") range else null
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    @Test fun retriesResetAndTimeoutWithBackoffAndExact64BitOffsets() {
        val failures = ArrayList<IsoRangeException>()
        val pauses = ArrayList<Long>()
        var attempts = 0
        val data = IsoRangeReader.readFully("http://origin/download.aspx?token=secret", start, start + 3, open = { from, to ->
            assertEquals(start, from); assertEquals(start + 3, to)
            attempts++
            object : Connection(206, "bytes $from-$to/50000000000", byteArrayOf(1, 2, 3, 4)) {
                override fun getInputStream(): InputStream = when (attempts) {
                    1 -> throw SocketException("Connection reset")
                    2 -> throw SocketTimeoutException("Read timed out")
                    else -> super.getInputStream()
                }
            }
        }, onFailure = { failures.add(it) }, pause = { pauses.add(it) })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), data)
        assertEquals(listOf(150L, 300L), pauses)
        assertEquals(listOf(206, 206), failures.map { it.status })
        assertTrue(isoFailureDetails(failures[0]).contains("java.net.SocketException: Connection reset"))
        assertFalse(isoFailureDetails(failures[0]).contains("token=secret"))
    }

    @Test fun capped206ResponsesContinueAtNextByteAndPermanentFailuresStop() {
        val positions = ArrayList<Long>()
        val result = IsoRangeReader.readFully("http://origin/download.aspx", start, start + 7, open = { from, _ ->
            positions.add(from)
            Connection(206, "bytes $from-${from + 1}/50000000000", byteArrayOf((from - start).toByte(), (from - start + 1).toByte()))
        })
        assertEquals(listOf(start, start + 2, start + 4, start + 6), positions)
        assertArrayEquals(ByteArray(8) { it.toByte() }, result)
        for (status in listOf(200, 404, 416, 503)) {
            var attempts = 0
            val failures = ArrayList<IsoRangeException>()
            assertThrows(IsoRangeException::class.java) {
                IsoRangeReader.readFully("http://origin/download.aspx", start, start + 3,
                    open = { _, _ -> attempts++; Connection(status, null, ByteArray(0)) },
                    onFailure = { failures.add(it) }, pause = {})
            }
            assertEquals(if (status == 404 || status == 503) 3 else 1, attempts)
            assertTrue(failures.all { it.status == status && it.start == start })
        }
    }

    @Test fun parallelProbesNeverExceedTwoUpstreamReads() {
        val pool = Executors.newFixedThreadPool(8)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        try {
            val jobs = (1..8).map {
                pool.submit<ByteArray> {
                    IsoRangeReader.readFully("http://origin/download.aspx", start, start + 3, open = { from, to ->
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        object : Connection(206, "bytes $from-$to/50000000000", ByteArray(4)) {
                            override fun getInputStream(): InputStream = object : ByteArrayInputStream(ByteArray(4)) {
                                override fun close() { active.decrementAndGet(); super.close() }
                            }
                        }
                    })
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, peak.get())
            release.countDown()
            jobs.forEach { assertEquals(4, it.get(5, TimeUnit.SECONDS).size) }
            assertEquals(2, peak.get()); assertEquals(0, active.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun signedRedirectsPreserveQueryRangeUserAgentAndSessionCookiesOnReopen() {
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val requests = ArrayList<String>()
            val job = pool.submit {
                repeat(4) { index ->
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = ArrayList<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                        requests.add(lines.joinToString("\n"))
                        val reply = if (index % 2 == 0) {
                            "HTTP/1.1 302 Found\r\nLocation: /signed/download.aspx?token=a%2Fb+c&v=$index\r\nSet-Cookie: route=next; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${start + 3}/50000000000\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA"
                        socket.getOutputStream().write(reply.toByteArray())
                    }
                }
            }
            try {
                val connections = RangeConnections({ mapOf("Cookie" to "session=keep", "X-GD-Shield" to "shield", "User-Agent" to "GDStream-Test") })
                repeat(2) {
                    val result = IsoRangeReader.readFully("http://127.0.0.1:${server.localPort}/download.aspx?token=original%2F+value#name=Disc.iso", start, start + 3,
                        open = { from, to -> connections.open("http://127.0.0.1:${server.localPort}/download.aspx?token=original%2F+value#name=Disc.iso", "bytes=$from-$to") })
                    assertEquals("DATA", result.toString(Charsets.US_ASCII))
                }
                job.get(5, TimeUnit.SECONDS)
                assertTrue(requests[0].startsWith("GET /download.aspx?token=original%2F+value HTTP"))
                assertTrue(requests[2].startsWith("GET /download.aspx?token=original%2F+value HTTP"))
                assertTrue(requests[1].startsWith("GET /signed/download.aspx?token=a%2Fb+c&v=0 HTTP"))
                requests.forEach {
                    assertTrue(it.contains("Range: bytes=$start-${start + 3}"))
                    assertTrue(it.contains("User-Agent: GDStream-Test"))
                    assertTrue(it.contains("Connection: Keep-Alive", true))
                    assertTrue(it.contains("session=keep"))
                    assertTrue(it.contains("X-GD-Shield: shield"))
                }
                assertTrue(requests[1].contains("route=next"))
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun clientBrokenPipeIsIgnoredButUpstreamResetHasVirtualAndPhysicalDiagnostics() {
        val file = IsoVirtualFile(listOf(start to 192L, (start + 500) to 192L), true)
        val pool = Executors.newCachedThreadPool()
        try {
            file.serve("GET", "bytes=187-200", "video/mp2t", object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) { throw SocketException("Broken pipe") }
            }, pool) { _, length -> ByteArray(length) }
            val error = assertThrows(IsoProxyReadException::class.java) {
                file.serve("GET", "bytes=187-200", "video/mp2t", ByteArrayOutputStream(), pool) { _, _ ->
                    throw IsoRangeException(start + 500, start + 691, 206, "http://origin/download.aspx?token=secret", 3, SocketException("ECONNRESET"))
                }
            }
            assertEquals(187L, error.start)
            assertEquals("$start-${start + 191},${start + 500}-${start + 691}", file.physicalRanges(error.start, error.count))
            assertTrue(isoFailureDetails(error).contains("HTTP 206"))
            assertTrue(isoFailureDetails(error).contains("java.net.SocketException: ECONNRESET"))
        } finally { pool.shutdownNow() }
    }
}
