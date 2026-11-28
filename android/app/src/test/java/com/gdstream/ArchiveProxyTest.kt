package com.gdstream

import com.facebook.react.bridge.ReactApplicationContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

/** Exercise the real SecurityVaultModule routing and legacy ZIP/RAR body writers over loopback. */
class ArchiveProxyTest {
    private fun field(module: SecurityVaultModule, name: String): Any =
        requireNotNull(SecurityVaultModule::class.java.getDeclaredField(name).apply { isAccessible = true }.get(module))

    private fun serve(format: String, compression: Int, plain: ByteArray, stored: ByteArray): ByteArray {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newCachedThreadPool()
        val bind = InetAddress.getByName("127.0.0.1")
        val upstream = ServerSocket(0, 1, bind)
        val local = ServerSocket(0, 1, bind)
        try {
            val image = ByteArray(128) + stored
            val upstreamJob = pool.submit {
                upstream.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    var requested: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) requested = line.substringAfter(':').trim()
                    }
                    val range = HttpByteRange.parse(requested, image.size.toLong())
                    val body = image.copyOfRange(range.start.toInt(), range.end.toInt() + 1)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nContent-Range: bytes ${range.start}-${range.end}/${image.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
            val target = SecurityVaultModule.ArchiveEntryTarget(
                "fixture", "http://127.0.0.1:${upstream.localPort}/archive", "movie.mp4", format,
                compression, stored.size.toLong(), plain.size.toLong(), 0L, 128L
            )
            @Suppress("UNCHECKED_CAST")
            val registry = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
            registry[target.id] = target
            val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java)
                .apply { isAccessible = true }
            val localJob = pool.submit { handler.invoke(module, local.accept()) }
            val bytes = Socket(bind, local.localPort).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /zip/fixture/movie.mp4?probe=1 HTTP/1.1\r\nConnection: close\r\nHost: localhost\r\nRange: bytes=17-2000\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes()
            }
            upstreamJob.get(5, TimeUnit.SECONDS)
            localJob.get(5, TimeUnit.SECONDS)
            val split = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            assertTrue(split >= 0)
            val headers = bytes.copyOfRange(0, split).toString(Charsets.US_ASCII)
            assertTrue(headers.startsWith("HTTP/1.1 206 Partial Content"))
            assertTrue(headers.contains("Content-Range: bytes 17-2000/${plain.size}"))
            assertTrue(headers.contains("Content-Length: 1984"))
            return bytes.copyOfRange(split + 4, bytes.size)
        } finally {
            upstream.close()
            local.close()
            pool.shutdownNow()
            (field(module, "ioExecutor") as ExecutorService).shutdownNow()
        }
    }

    @Test fun storedZipAndRarRangesRemainByteExact() {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        for (format in listOf("ZIP", "RAR")) {
            assertArrayEquals(payload.copyOfRange(17, 2001), serve(format, 0, payload, payload))
        }
    }

    @Test fun deflatedZipStillStreamsItsUncompressedRange() {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        val deflater = Deflater(6, true)
        val compressed = try {
            deflater.setInput(payload)
            deflater.finish()
            val bytes = ByteArray(16384)
            bytes.copyOf(deflater.deflate(bytes))
        } finally { deflater.end() }
        assertArrayEquals(payload.copyOfRange(17, 2001), serve("ZIP", 8, payload, compressed))
    }

    @Test fun isoProxyDeliversMultipleChunksPastTheFirstKeyframeAt42GbPhysicalOffset() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newCachedThreadPool()
        val base = 42_000_000_000L
        val payload = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }
        ServerSocket(0).use { origin ->
            ServerSocket(0).use { local ->
                try {
                    origin.soTimeout = 5000
                    val requests = ArrayList<HttpByteRange>()
                    val upstreamJob = pool.submit {
                        repeat((payload.size + IsoChunkCache.BLOCK_BYTES - 1) / IsoChunkCache.BLOCK_BYTES) {
                            origin.accept().use { socket ->
                                val reader = socket.getInputStream().bufferedReader()
                                val headers = ArrayList<String>()
                                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.add(line) }
                                assertTrue(headers.any { it.equals("Connection: close", true) })
                                val range = HttpByteRange.parse(headers.first { it.startsWith("Range:", true) }.substringAfter(':').trim(), base + payload.size)
                                assertTrue(range.length <= IsoChunkCache.BLOCK_BYTES)
                                requests.add(range)
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 206 Partial Content\r\nContent-Length: ${range.length}\r\nContent-Range: bytes ${range.start}-${range.end}/${base + payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(payload, (range.start - base).toInt(), range.length.toInt())
                                    flush()
                                }
                            }
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val registry = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
                    registry["disc"] = SecurityVaultModule.ArchiveEntryTarget("disc",
                        "http://127.0.0.1:${origin.localPort}/download.aspx?expiry=1&mac=fixture", "feature.ts", "ISO", 0,
                        payload.size.toLong(), payload.size.toLong(), 0, segments = listOf(base to payload.size.toLong()))
                    val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java).apply { isAccessible = true }
                    val proxy = pool.submit { handler.invoke(module, local.accept()) }
                    val response = Socket("127.0.0.1", local.localPort).use { client ->
                        client.soTimeout = 5000
                        client.getOutputStream().write("GET /zip/disc/feature.ts HTTP/1.1\r\nConnection: close\r\nHost: localhost\r\nRange: bytes=0-\r\n\r\n".toByteArray())
                        client.getInputStream().readBytes()
                    }
                    proxy.get(5, TimeUnit.SECONDS)
                    upstreamJob.get(5, TimeUnit.SECONDS)
                    val split = response.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                    val headers = response.copyOfRange(0, split).toString(Charsets.US_ASCII)
                    assertTrue(headers.startsWith("HTTP/1.1 206 Partial Content"))
                    assertTrue(headers.contains("Content-Range: bytes 0-${payload.size - 1}/${payload.size}"))
                    assertArrayEquals(payload, response.copyOfRange(split + 4, response.size))
                    assertEquals(base, requests.minOf { it.start })
                    assertEquals(base + payload.size - 1, requests.maxOf { it.end })
                } finally {
                    pool.shutdownNow()
                    (field(module, "ioExecutor") as ExecutorService).shutdownNow()
                }
            }
        }
    }

    @Test fun subtitleProbesAndWrongFilenamesReturn404WithoutOpeningTheIso() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newCachedThreadPool()
        ArchiveProxyStreams.registry.resume()
        try {
            val name = "Movie + Tamil.m2ts"
            @Suppress("UNCHECKED_CAST")
            val registry = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
            registry["disc"] = SecurityVaultModule.ArchiveEntryTarget("disc", "http://127.0.0.1:1/must-not-open", name,
                "ISO", 0, 41_400_000_000L, 41_400_000_000L, 0, segments = listOf(1L to 41_400_000_000L))
            val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java).apply { isAccessible = true }
            ServerSocket(0).use { local ->
                for (filename in listOf("Movie.srt", "Movie.eng.srt", "Movie.ssa", "Movie.ass", "Movie.vtt", "Other.m2ts", "%invalid")) {
                    val work = pool.submit { handler.invoke(module, local.accept()) }
                    val response = Socket("127.0.0.1", local.localPort).use { client ->
                        client.soTimeout = 1000
                        client.getOutputStream().write("GET /zip/disc/$filename HTTP/1.1\r\nConnection: close\r\nHost: localhost\r\n\r\n".toByteArray())
                        client.getInputStream().readBytes().toString(Charsets.US_ASCII)
                    }
                    work.get(1, TimeUnit.SECONDS)
                    assertTrue(response.startsWith("HTTP/1.1 404 Not Found"))
                    assertTrue(response.endsWith("Content-Length: 0\r\n\r\n"))
                }
                val work = pool.submit { handler.invoke(module, local.accept()) }
                val response = Socket("127.0.0.1", local.localPort).use { client ->
                    client.soTimeout = 1000
                    client.getOutputStream().write("HEAD /zip/disc/Movie%20%2B%20Tamil.m2ts HTTP/1.1\r\nConnection: close\r\nHost: localhost\r\n\r\n".toByteArray())
                    client.getInputStream().readBytes().toString(Charsets.US_ASCII)
                }
                work.get(1, TimeUnit.SECONDS)
                assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            }
        } finally { pool.shutdownNow(); (field(module, "ioExecutor") as ExecutorService).shutdownNow() }
    }

    @Test fun isoCacheInvalidationAndRestorationKeepProxyIdsAndLengthsConsistent() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        try {
            val restore = SecurityVaultModule::class.java.getDeclaredMethod("restoreArchiveFromDisk", String::class.java, String::class.java)
                .apply { isAccessible = true }
            val target = JSONObject().put("id", "disc").put("fileName", "Movie.m2ts").put("archiveFormat", "ISO")
                .put("compressionMethod", 0).put("compressedSize", 384).put("uncompressedSize", 376)
                .put("isBdavM2ts", true).put("segments", JSONArray("[[1000,200],[2000,184]]"))
            target.put("bluRay", BluRayProfile(5_000_000, listOf(BluRayPoint(0, 0), BluRayPoint(2_000_000, 188)),
                emptyList()).toJson())
            val item = JSONObject().put("id", "disc").put("name", "Movie.m2ts").put("link", "http://127.0.0.1:1111/zip/obsolete/Movie.m2ts")
            val cache = JSONObject().put("rawJson", JSONArray().put(item).toString()).put("targets", JSONArray().put(target))
            assertNull(restore.invoke(module, cache.toString(), "https://example/disc.iso"))
            cache.put("isoIndexVersion", IsoIndexDiskCache.VERSION)
            val restored = JSONArray(restore.invoke(module, cache.toString(), "https://example/disc.iso?fresh=1") as String).getJSONObject(0)
            val activePort = field(module, "archiveProxyPort") as Int
            assertTrue(activePort > 0)
            assertEquals("http://127.0.0.1:$activePort/zip/disc/Movie.m2ts", restored.getString("link"))
            assertEquals("ISO", restored.getString("archiveFormat"))
            @Suppress("UNCHECKED_CAST")
            val registry = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
            assertNotNull(registry["disc"]!!.hls)
            val direct = restored.getString("link")
            val oldMaster = "http://127.0.0.1:$activePort/hls/disc/index.m3u8"
            assertEquals(direct, SecurityVaultModule.externalPlayerUrl(direct, "com.brouken.player"))
            assertEquals(direct, SecurityVaultModule.externalPlayerUrl(oldMaster))
            assertEquals("https://example/disc.iso?fresh=1", registry["disc"]!!.archiveUrl)
            assertEquals(376L, registry["disc"]!!.uncompressedSize)
            target.put("uncompressedSize", 188) // Old per-extent rounding must not survive validation.
            assertNull(restore.invoke(module, cache.toString(), "https://example/disc.iso"))
            target.put("archiveFormat", "ZIP").put("isBdavM2ts", false)
            cache.remove("isoIndexVersion")
            assertNotNull(restore.invoke(module, cache.toString(), "https://example/archive.zip"))
        } finally {
            (field(module, "archiveProxyServer") as ServerSocket).close()
            (field(module, "ioExecutor") as ExecutorService).shutdownNow()
        }
    }

    @Test fun externalHeadOptionsAndSubtitleProbesPreserveActiveIsoWithoutOriginReads() {
        val log = java.io.File.createTempFile("gdstream-handshake", ".log")
        DebugLogger.attach(DebugLogStore(log))
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newSingleThreadExecutor()
        val rawSize = 220_000_000L * 192
        val exposedSize = 220_000_000L * 188
        val target = SecurityVaultModule.ArchiveEntryTarget("handshake", "http://127.0.0.1:1/never-fetch", "movie.m2ts", "ISO",
            0, rawSize, exposedSize, 0, 0, listOf(0L to rawSize), true,
            BluRayProfile(9995_000_000L, emptyList(), emptyList()))
        @Suppress("UNCHECKED_CAST")
        val entries = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
        entries[target.id] = target
        ArchiveProxyStreams.registry.resume()
        var cancelled = false
        val active = ArchiveProxyStreams.registry.register(target.id, true) { cancelled = true }
        try {
            ServerSocket(0).use { server ->
                val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java).apply { isAccessible = true }
                for ((method, name, status) in listOf(Triple("HEAD", "movie.m2ts", 200), Triple("OPTIONS", "movie.m2ts", 200), Triple("HEAD", "movie.ts", 200), Triple("GET", "movie.eng.srt", 404))) {
                    val job = pool.submit { handler.invoke(module, server.accept()) }
                    val result = Socket("127.0.0.1", server.localPort).use { client ->
                        client.soTimeout = 5000
                        client.getOutputStream().write("$method /zip/handshake/$name HTTP/1.1\r\nConnection: close\r\nUser-Agent: VLC/3.6 test\r\nRange: bytes=0-\r\n\r\n".toByteArray())
                        client.getInputStream().readBytes().toString(Charsets.US_ASCII)
                    }
                    job.get(5, TimeUnit.SECONDS)
                    assertTrue(result.startsWith("HTTP/1.1 $status "))
                    assertTrue(result.endsWith("\r\n\r\n"))
                    assertTrue(result.contains("Content-Length: ${if (method == "HEAD") exposedSize else 0}\r\n"))
                    if (status != 404) assertTrue(result.contains("Accept-Ranges: bytes"))
                    if (method == "HEAD") {
                        assertTrue(result.contains("X-Content-Duration: 9995\r\n"))
                        assertTrue(result.contains("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0\r\n"))
                    }
                    assertFalse(cancelled)
                    assertSame(target, entries[target.id])
                }
            }
            assertTrue(log.readText().contains("userAgent=VLC/3.6 test"))
        } finally {
            active.close(); pool.shutdownNow(); (field(module, "ioExecutor") as ExecutorService).shutdownNow(); log.delete()
        }
    }

    @Test fun opaqueStandaloneUrlUsesBdavProxyForPlaybackInfoAndSeparateHlsAudio() {
        val ts = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes()
        val raw = ByteArray(ts.size / 188 * 192)
        for (i in 0 until ts.size / 188) ts.copyInto(raw, i * 192 + 4, i * 188, (i + 1) * 188)
        val origin = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val listener = Executors.newSingleThreadExecutor()
        listener.submit {
            while (!origin.isClosed) origin.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                requests.incrementAndGet()
                val range = HttpByteRange.parse(headers.first { it.startsWith("Range:", true) }.substringAfter(':').trim(), raw.size.toLong())
                socket.getOutputStream().apply {
                    write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes ${range.start}-${range.end}/${raw.size}\r\nContent-Length: ${range.length}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(raw, range.start.toInt(), range.length.toInt())
                }
            }
        }
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val promise = mock(com.facebook.react.bridge.Promise::class.java)
        val ready = java.util.concurrent.CompletableFuture<String>()
        org.mockito.Mockito.doAnswer { ready.complete(it.getArgument(0)); null }
            .`when`(promise).resolve(org.mockito.Mockito.any())
        ArchiveProxyStreams.registry.resume()
        fun get(url: String) = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY).apply {
            connectTimeout = 5000; readTimeout = 5000; setRequestProperty("Connection", "close")
        }.getInputStream().use { it.readBytes() }
        try {
            module.resolveStandaloneDisc("http://127.0.0.1:${origin.localPort}/download.aspx?file=opaque",
                "Untitled feature", raw.size.toDouble(), "ta", promise)
            val url = ready.get(10, TimeUnit.SECONDS)
            assertTrue(SecurityVaultModule.isIsoStream(url))
            assertArrayEquals(ts, get(url))
            val initialRequests = requests.get()
            ArchiveProxyStreams.stop(url)
            ArchiveProxyStreams.prepare(url)
            assertArrayEquals(ts, get(url))
            assertEquals("warm reopen contacted origin", initialRequests, requests.get())
            val profile = SecurityVaultModule.bluRayProfile(url)!!
            assertTrue(profile.durationUs > 0)
            assertEquals("tam", profile.audio.first().language)
            assertTrue(profile.audio.drop(1).all { it.language != "tam" })
            assertEquals("Tamil", (SecurityVaultModule.isoMediaInfo(url)!!["languages"] as List<*>).first())
            assertTrue(profile.streams.any { it.audio && it.kbps > 0 })
            assertTrue((SecurityVaultModule.isoMediaInfo(url)!!["videoBitrateMbps"] as Double) > 0)
            val master = SecurityVaultModule.externalPlayerUrl(url)
            assertTrue(String(get(master)).contains("#EXT-X-MEDIA:TYPE=AUDIO"))
            val video = get(master.replace("index.m3u8", "seg_0.ts"))
            assertTrue(BluRayPsi.streams(video).any { it.video })
            assertFalse(BluRayPsi.streams(video).any { it.audio })
            // LPCM is available to the direct extractor. Choose an advertised HLS rendition.
            val pid = Regex("audio_([0-9]+)\\.m3u8").find(String(get(master)))!!.groupValues[1].toInt()
            assertEquals(pid, BluRayPsi.streams(get(master.replace("index.m3u8", "audio_${pid}_seg_0.ts"))).single().pid)
        } finally {
            origin.close(); listener.shutdownNow()
            val server = SecurityVaultModule::class.java.getDeclaredField("archiveProxyServer").apply { isAccessible = true }
            (server.get(module) as? ServerSocket)?.close()
            (field(module, "ioExecutor") as ExecutorService).shutdownNow()
        }
    }

    @Test fun openEndedIsoSeeksDisplaceAllThreeReadsWhileBoundedProbesLeavePlaybackActive() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newCachedThreadPool()
        val block = IsoChunkCache.BLOCK_BYTES.toLong()
        val sourceSize = 24 * block
        val id = "seek-permits"
        val bind = InetAddress.getByName("127.0.0.1")
        val origin = ServerSocket(0, 16, bind)
        val proxy = ServerSocket(0, 16, bind)
        val reads = java.util.concurrent.LinkedBlockingQueue<Long>()
        val clients = mutableListOf<Socket>()
        val upstreams = java.util.concurrent.ConcurrentLinkedQueue<Socket>()
        val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val entries = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
        // A sparse first block serves startup and the small probe locally. The next three
        // origin GETs remain stalled until the proxy disconnects them on a seek.
        entries[id] = SecurityVaultModule.ArchiveEntryTarget(id, "http://127.0.0.1:${origin.localPort}/download.aspx",
            "title.vob", "ISO", 0, sourceSize, sourceSize, 0, segments = listOf(-1L to block, 0L to sourceSize - block),
            sourceSize = sourceSize)
        ArchiveProxyStreams.registry.resume()
        pool.submit {
            while (!origin.isClosed) {
                val socket = origin.accept()
                upstreams.add(socket)
                pool.submit {
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        val headers = generateSequence { input.readLine() }.takeWhile { line -> line.isNotEmpty() }
                        val range = headers.toList().single { line -> line.startsWith("Range:", true) }
                        val from = range.substringAfter('=').substringBefore('-').toLong()
                        // Stall some reads in the body and others waiting for response headers.
                        if (from / block % 2 == 0L) {
                            it.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\nContent-Length: $block\r\n" +
                                "Content-Range: bytes $from-${from + block - 1}/$sourceSize\r\n\r\nx").toByteArray())
                        }
                        reads.put(from / block)
                        input.read()
                    }
                }
            }
        }
        fun request(range: String?): Pair<Socket, java.util.concurrent.Future<*>> {
            val job = pool.submit { handler.invoke(module, proxy.accept()) }
            val client = Socket(bind, proxy.localPort)
            clients.add(client)
            client.soTimeout = 3000
            val header = range?.let { "Range: bytes=$it\r\n" }.orEmpty()
            client.getOutputStream().write("GET /zip/$id/title.vob HTTP/1.1\r\n${header}Connection: close\r\n\r\n".toByteArray())
            return client to job
        }
        fun assertWindow(start: Long) {
            val actual = List(3) { reads.poll(3, TimeUnit.SECONDS) }
            assertEquals((start until start + 3).toSet(), actual.toSet())
        }
        try {
            val (_, full) = request(null)
            assertWindow(0)
            val (probe, done) = request("0-524287")
            assertTrue(probe.getInputStream().readBytes().toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 206"))
            done.get(1, TimeUnit.SECONDS)
            assertFalse(full.isDone)
            val (_, seek) = request("${8 * block}-")
            assertWindow(7)
            full.get(1, TimeUnit.SECONDS)
            request("${16 * block}-")
            assertWindow(15)
            seek.get(1, TimeUnit.SECONDS)
        } finally {
            clients.forEach { it.close() }
            origin.close()
            upstreams.forEach { it.close() }
            proxy.close()
            ArchiveProxyStreams.registry.cancelStream(id, "test finished")
            pool.shutdownNow()
            (field(module, "ioExecutor") as ExecutorService).shutdownNow()
        }
    }

    @Test fun isoKeepAliveHandlesHeadThenTwoRangesOnOneSocket() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newSingleThreadExecutor()
        @Suppress("UNCHECKED_CAST")
        val entries = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
        entries["persistent"] = SecurityVaultModule.ArchiveEntryTarget("persistent", "http://unused/", "movie.m2ts", "ISO",
            0, 192000, 188000, 0, 0, listOf(-1L to 192000L), true)
        ArchiveProxyStreams.registry.resume()
        try {
            ServerSocket(0).use { server ->
                val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java).apply { isAccessible = true }
                val job = pool.submit { handler.invoke(module, server.accept()) }
                Socket("127.0.0.1", server.localPort).use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream()
                    for (method in listOf("HEAD", "GET", "GET")) {
                        client.getOutputStream().write("$method /zip/persistent/movie.ts HTTP/1.1\r\nRange: bytes=188-191\r\n\r\n".toByteArray())
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) { val b = input.read(); assertTrue(b >= 0); header.append(b.toChar()) }
                        assertTrue(header.contains("Connection: keep-alive"))
                        assertTrue(header.contains("Accept-Ranges: bytes"))
                        assertTrue(header.contains("Content-Type: video/MP2T"))
                        if (method == "HEAD") assertTrue(header.contains("Content-Length: 188000")) else {
                            assertTrue(header.contains("206 Partial Content"))
                            assertTrue(header.contains("Content-Length: 4"))
                            repeat(4) { assertEquals(0, input.read()) }
                        }
                    }
                }
                job.get(5, TimeUnit.SECONDS)
                SecurityVaultModule.stopArchiveStreams("idle pause", true)
                assertNotNull(entries["persistent"])
                ArchiveProxyStreams.registry.register("persistent", false) {}.close()
            }
        } finally { pool.shutdownNow(); (field(module, "ioExecutor") as ExecutorService).shutdownNow() }
    }

    @Test fun dvdSystemChooserRoutesBetweenMuxedVlcAndMultivariantMedia3ByUserAgent() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        val pool = Executors.newSingleThreadExecutor()
        val resolvedInfo = mapOf(
            "videoCodec" to "MPEG-2",
            "width" to 720,
            "height" to 480,
            "audioTracks" to listOf(
                mapOf("codec" to "AC-3", "languageCode" to "tam", "channelCount" to 6, "substreamId" to 0x80),
                mapOf("codec" to "DTS", "languageCode" to "eng", "channelCount" to 6, "substreamId" to 0x89)
            ),
            "subtitleTracks" to listOf(mapOf("substreamId" to 0x20, "languageCode" to "eng"))
        )
        val target = SecurityVaultModule.ArchiveEntryTarget(
            id = "dvd_chooser",
            archiveUrl = "http://127.0.0.1:1/never-fetch",
            fileName = "Movie [DVD Main].vob",
            archiveFormat = "ISO",
            compressionMethod = 0,
            compressedSize = 4096,
            uncompressedSize = 4096,
            localHeaderOffset = 0,
            segments = listOf(0L to 4096L),
            resolvedInfo = resolvedInfo,
            dvdSegments = listOf(DvdProxy.Segment(0, 2048, 4_000_000)),
            dvdEncoderOverride = DvdProxy.Encoder("c2.test.avc", 0)
        )
        @Suppress("UNCHECKED_CAST")
        val entries = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
        entries[target.id] = target
        ArchiveProxyStreams.registry.resume()
        try {
            val baseUrl = "http://127.0.0.1:9090/zip/${target.id}/Movie%20%5BDVD%20Main%5D.vob"
            assertEquals("http://127.0.0.1:9090/hls/${target.id}/dvd.m3u8", SecurityVaultModule.externalPlayerUrl(baseUrl, null))
            assertEquals("http://127.0.0.1:9090/hls/${target.id}/dvd.m3u8?muxed=1", SecurityVaultModule.externalPlayerUrl(baseUrl, "org.videolan.vlc"))

            ServerSocket(0).use { server ->
                val handler = SecurityVaultModule::class.java.getDeclaredMethod("handleArchiveProxyClient", Socket::class.java)
                    .apply { isAccessible = true }
                fun request(path: String, userAgent: String): String {
                    val job = pool.submit { handler.invoke(module, server.accept()) }
                    val raw = Socket("127.0.0.1", server.localPort).use { client ->
                        client.soTimeout = 5000
                        client.getOutputStream().write(
                            "GET $path HTTP/1.1\r\nConnection: close\r\nHost: 127.0.0.1\r\nUser-Agent: $userAgent\r\n\r\n".toByteArray()
                        )
                        client.getInputStream().readBytes().toString(Charsets.UTF_8)
                    }
                    job.get(5, TimeUnit.SECONDS)
                    return raw
                }

                for (vlcAgent in listOf("LibVLC/3.0.21 (Linux; Android 14)", "VLC/3.6.0")) {
                    val vlcResponse = request("/hls/${target.id}/dvd.m3u8", vlcAgent)
                    assertTrue(vlcResponse.startsWith("HTTP/1.1 200 OK"))
                    assertTrue(vlcResponse.contains("dvd_0.ts?muxed=1"))
                    assertFalse(vlcResponse.contains("#EXT-X-MEDIA:TYPE=AUDIO"))
                }

                val media3Response = request("/hls/${target.id}/dvd.m3u8", "Dalvik/2.1.0 (Linux; U; Android 14)")
                assertTrue(media3Response.startsWith("HTTP/1.1 200 OK"))
                assertTrue(media3Response.contains("#EXT-X-MEDIA:TYPE=AUDIO"))
                assertTrue(media3Response.contains("dvd_video.m3u8"))
                assertFalse(media3Response.contains("muxed=1"))

                assertTrue(media3Response.contains("URI=\"dvd_sub_0.m3u8\""))
                val subPlaylist = request("/hls/${target.id}/dvd_sub_0.m3u8", "JustPlayer")
                assertTrue(subPlaylist.contains("\ndvd_sub_0_0.vtt\n"))
                val cache = field(module, "dvdChunks") as DvdSegmentCache
                cache.segment(target.id, 0, 0, IsoReadSession(), false, shared = true) { _, _, _ ->
                    DvdProxy.Encoded(byteArrayOf(), DvdSubtitleWindow(0, 4_000_000, emptyList()), 90_000)
                }
                val segment = request("/hls/${target.id}/dvd_sub_0_0.vtt", "JustPlayer")
                assertTrue(segment.contains("Content-Type: text/vtt\r\n"))
                assertTrue(segment.endsWith("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:90000\n\n"))

                val explicitMuxedResponse = request("/hls/${target.id}/dvd.m3u8?muxed=1", "Dalvik/2.1.0 (Linux; U; Android 14)")
                assertTrue(explicitMuxedResponse.startsWith("HTTP/1.1 200 OK"))
                assertTrue(explicitMuxedResponse.contains("dvd_0.ts?muxed=1"))
            }
        } finally {
            entries.remove(target.id)
            pool.shutdownNow()
            (field(module, "ioExecutor") as ExecutorService).shutdownNow()
        }
    }

}
