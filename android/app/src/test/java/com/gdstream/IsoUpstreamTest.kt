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
                    2, 3 -> throw SocketTimeoutException("Read timed out")
                    else -> super.getInputStream()
                }
            }
        }, onFailure = { failures.add(it) }, pause = { pauses.add(it) })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), data)
        assertEquals(listOf(150L, 300L, 450L), pauses)
        assertEquals(listOf(206, 206, 206), failures.map { it.status })
        assertTrue(isoFailureDetails(failures[0]).contains("java.net.SocketException: Connection reset"))
        assertFalse(isoFailureDetails(failures[0]).contains("token=secret"))
    }

    @Test fun partial206BodyTimesOutAndRetriesOnAnotherSocket() {
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val job = pool.submit {
                fun reply(body: String) = "HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\n" +
                    "Content-Range: bytes 0-3/4\r\nConnection: close\r\n\r\n$body"
                fun accept() = server.accept().also { socket ->
                    val headers = socket.getInputStream().bufferedReader()
                    while (!headers.readLine().isNullOrEmpty()) { }
                }
                accept().use { first ->
                    first.getOutputStream().write(reply("XX").toByteArray())
                    // Keep the first response open while the client reconnects.
                    accept().use { it.getOutputStream().write(reply("DATA").toByteArray()) }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/disc.iso"
            val connections = RangeConnections({ emptyMap() })
            val session = IsoReadSession()
            val failures = ArrayList<IsoRangeException>()
            try {
                val data = IsoRangeReader.readFully(url, 0, 3, open = { from, to ->
                    connections.openIsoRange(url, from, to, session)
                }, onFailure = { failures.add(it) }, pause = {}, session = session)
                assertEquals("DATA", data.toString(Charsets.US_ASCII))
                assertTrue(failures.single().cause is SocketTimeoutException)
                assertEquals(206, failures.single().status)
                job.get(1, TimeUnit.SECONDS)
            } finally { session.cancel(); pool.shutdownNow() }
        }
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
            assertEquals(if (status == 404 || status == 503) 4 else 1, attempts)
            assertTrue(failures.all { it.status == status && it.start == start })
        }
    }

    @Test fun parallelProbesNeverExceedSixUpstreamReads() {
        val pool = Executors.newFixedThreadPool(8)
        val entered = CountDownLatch(6)
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
                            override fun disconnect() {
                                if (!closed) active.decrementAndGet()
                                super.disconnect()
                            }
                        }
                    })
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(6, peak.get())
            release.countDown()
            jobs.forEach { assertEquals(4, it.get(5, TimeUnit.SECONDS).size) }
            assertEquals(6, peak.get()); assertEquals(0, active.get())
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
                    val session = IsoReadSession()
                    val result = IsoRangeReader.readFully("http://127.0.0.1:${server.localPort}/download.aspx?token=original%2F+value#name=Disc.iso", start, start + 3,
                        open = { from, to -> connections.open("http://127.0.0.1:${server.localPort}/download.aspx?token=original%2F+value#name=Disc.iso", "bytes=$from-$to", session = session) }, session = session)
                    assertEquals("DATA", result.toString(Charsets.US_ASCII))
                }
                job.get(5, TimeUnit.SECONDS)
                assertTrue(requests[0].startsWith("GET /download.aspx?token=original%2F+value HTTP"))
                assertTrue(requests[2].startsWith("GET /download.aspx?token=original%2F+value HTTP"))
                assertTrue(requests[1].startsWith("GET /signed/download.aspx?token=a%2Fb+c&v=0 HTTP"))
                requests.forEach {
                    assertTrue(it.contains("Range: bytes=$start-${start + 3}"))
                    assertTrue(it.contains("User-Agent: GDStream-Test"))
                    assertTrue(it.contains("Connection: close", true))
                    assertTrue(it.contains("Cache-Control: no-cache", true))
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

    @Test fun largePhysicalReadsAreSplitIntoBoundedWindowsAndNeverDrainInputOnClose() {
        val requested = ArrayList<Pair<Long, Long>>()
        val connections = ArrayList<Connection>()
        val length = IsoRangeReader.WINDOW_BYTES * 2 + 17
        val data = IsoRangeReader.readFully("http://origin/download.aspx?expiry=1&mac=secret", start, start + length - 1,
            open = { from, to ->
                requested.add(from to to)
                object : Connection(206, "bytes $from-$to/50000000000", ByteArray((to - from + 1).toInt()) { 7 }) {
                    override fun getInputStream(): InputStream = object : ByteArrayInputStream(body) {
                        override fun close() { fail("InputStream.close would drain the origin response") }
                    }
                }.also { connections.add(it) }
            })
        assertEquals(length, data.size)
        assertTrue(data.all { it == 7.toByte() })
        assertEquals(3, requested.size)
        assertEquals(start, requested.first().first)
        assertEquals(start + length - 1, requested.last().second)
        assertTrue(requested.all { (from, to) -> to - from + 1 <= 8 * 1024 * 1024 })
        assertTrue(connections.all { it.closed })
    }

    @Test fun abortedClientSocketsDisconnectBlockedReadsAndImmediatelyReleaseAllSixPermits() {
        assertCancelledProbes { clients -> clients.forEach { it.close() } }
    }

    @Test fun serviceShutdownClosesProxySocketsAndReleasesAllFourUpstreamPermits() {
        try { assertCancelledProbes { SecurityVaultModule.stopArchiveStreams("Recents or idle timeout") } }
        finally { ArchiveProxyStreams.registry.resume() }
    }

    private fun assertCancelledProbes(abort: (List<java.net.Socket>) -> Unit) {
        val pool = Executors.newCachedThreadPool()
        val entered = CountDownLatch(6)
        val disconnected = CountDownLatch(6)
        val sessions = List(6) { IsoReadSession() }
        val clients = ArrayList<java.net.Socket>()
        val accepted = ArrayList<java.net.Socket>()
        val watches = ArrayList<IsoClientWatch>()
        val connections = ArrayList<Connection>()
        val registrations = ArrayList<AutoCloseable>()
        ArchiveProxyStreams.registry.resume()
        ServerSocket(0).use { listener ->
            try {
                val jobs = sessions.map { session ->
                    clients.add(java.net.Socket("127.0.0.1", listener.localPort))
                    val socket = listener.accept().also { accepted.add(it) }
                    registrations.add(ArchiveProxyStreams.registry.register("cancel-test-${session.id}", true) { reason -> socket.close(); session.cancel(reason) })
                    watches.add(IsoClientWatch(socket, session, pool))
                    val unblock = CountDownLatch(1)
                    val connection = object : Connection(206, "bytes $start-${start + 3}/50000000000", ByteArray(4)) {
                        override fun disconnect() {
                            if (!closed) { super.disconnect(); disconnected.countDown(); unblock.countDown() }
                        }
                        override fun getInputStream(): InputStream = object : InputStream() {
                            override fun read(): Int {
                                entered.countDown()
                                // Model Android socket reads that ignore worker interruption.
                                while (unblock.count > 0) try { unblock.await() } catch (_: InterruptedException) { }
                                throw SocketException("Socket closed by disconnect")
                            }
                            override fun close() { fail("Must disconnect, never drain InputStream") }
                        }
                    }.also { connections.add(it) }
                    pool.submit {
                        assertThrows(java.io.InterruptedIOException::class.java) {
                            IsoRangeReader.readFully("http://origin/download.aspx", start, start + 3,
                                open = { _, _ -> connection }, session = session)
                        }
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                abort(clients) // Actual socket EOF or the service's production shutdown entry point.
                assertTrue("All upstream sockets must disconnect promptly", disconnected.await(1, TimeUnit.SECONDS))
                jobs.forEach { it.get(1, TimeUnit.SECONDS) }
                sessions.forEach { it.cancel("duplicate cancellation must not double-release") }
                val next = pool.submit<ByteArray> {
                    IsoRangeReader.readFully("http://origin/download.aspx", start, start + 3,
                        open = { from, to -> Connection(206, "bytes $from-$to/50000000000", byteArrayOf(1, 2, 3, 4)) })
                }
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), next.get(1, TimeUnit.SECONDS))
                assertTrue(connections.all { it.closed })
                // Also proves cancelled workers did not double-release and raise the six-request limit.
                parallelProbesNeverExceedSixUpstreamReads()
            } finally {
                sessions.forEach { it.cancel("test cleanup") }
                watches.forEach { it.close() }
                registrations.forEach { it.close() }
                clients.forEach { it.close() }; accepted.forEach { it.close() }
                pool.shutdownNow()
            }
        }
    }

    @Test fun proxyAndUpstreamDiagnosticsReachThePersistentLogAndExport() {
        val file = java.io.File.createTempFile("gdstream-debug-", ".log.txt").apply { deleteOnExit() }
        val store = DebugLogStore(file)
        val bootExported = CountDownLatch(1)
        val mirrored = CountDownLatch(1)
        var exported = ""
        var appended = false
        DebugLogger.attach(store) { text, append ->
            exported = if (append) exported + text else text
            if ("APP_BOOT" in text) bootExported.countDown()
            if ("ISO_UPSTREAM_ERR" in text) { appended = append; mirrored.countDown() }
        }
        DebugLogger.log("APP_BOOT", "before playback")
        assertTrue(bootExported.await(3, TimeUnit.SECONDS))
        val pool = Executors.newCachedThreadPool()
        try {
            val virtual = IsoVirtualFile(listOf(start to 8L), false)
            val session = IsoReadSession()
            virtual.serve("GET", "bytes=1-3", "video/mp2t", ByteArrayOutputStream(), pool, session) { offset, count ->
                virtual.readRaw(offset, count) { from, to ->
                    IsoRangeReader.readFully("http://origin/download.aspx?expiry=1&mac=secret", from, to,
                        open = { a, b -> Connection(206, "bytes $a-$b/50000000000", ByteArray(count)) }, session = session)
                }
            }
            assertThrows(IsoRangeException::class.java) {
                IsoRangeReader.readFully("http://origin/download.aspx?mac=secret", start, start + 3,
                    open = { _, _ -> Connection(403, null, ByteArray(0)) })
            }
            val text = store.snapshot()
            for (tag in listOf("ISO_PROXY_REQUEST", "ISO_PROXY_MAP", "ISO_PROXY_STATUS", "ISO_PROXY_CHUNK_COMPLETE",
                "ISO_UPSTREAM_RANGE", "ISO_UPSTREAM_STATUS", "ISO_UPSTREAM_COMPLETE", "ISO_UPSTREAM_ERR")) {
                assertTrue("Missing $tag in persisted log", text.contains("[$tag]"))
            }
            assertTrue(text.contains("physical=${start + 1}-${start + 3}"))
            assertTrue(text.contains("HTTP 403"))
            assertTrue(text.contains("java.io.IOException: Expected HTTP 206, got 403"))
            assertFalse(text.contains("mac=secret"))
            assertTrue(mirrored.await(3, TimeUnit.SECONDS))
            assertTrue(exported.contains("ISO_PROXY_CHUNK_COMPLETE"))
            assertTrue(exported.contains("APP_BOOT"))
            assertTrue("Playback must append after the earlier APP_BOOT export", appended)
            DebugLogStore(file).append("APP_BOOT", "reopened")
            assertFalse(DebugLogStore(file).snapshot().contains("ISO_UPSTREAM_ERR"))
        } finally { pool.shutdownNow() }
    }
}
