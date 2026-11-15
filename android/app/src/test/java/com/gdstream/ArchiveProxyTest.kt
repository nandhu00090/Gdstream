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
                socket.getOutputStream().write("GET /zip/fixture/movie.mp4?probe=1 HTTP/1.1\r\nHost: localhost\r\nRange: bytes=17-2000\r\n\r\n".toByteArray())
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

    @Test fun isoCacheInvalidationAndRestorationKeepProxyIdsAndLengthsConsistent() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        try {
            SecurityVaultModule::class.java.getDeclaredField("archiveProxyPort").apply { isAccessible = true }.setInt(module, 45678)
            val restore = SecurityVaultModule::class.java.getDeclaredMethod("restoreArchiveFromDisk", String::class.java, String::class.java)
                .apply { isAccessible = true }
            val target = JSONObject().put("id", "disc").put("fileName", "Movie.m2ts").put("archiveFormat", "ISO")
                .put("compressionMethod", 0).put("compressedSize", 384).put("uncompressedSize", 376)
                .put("isBdavM2ts", true).put("segments", JSONArray("[[1000,200],[2000,184]]"))
            val item = JSONObject().put("id", "disc").put("name", "Movie.m2ts").put("link", "http://127.0.0.1:1111/zip/obsolete/Movie.m2ts")
            val cache = JSONObject().put("rawJson", JSONArray().put(item).toString()).put("targets", JSONArray().put(target))
            assertNull(restore.invoke(module, cache.toString(), "https://example/disc.iso"))
            cache.put("isoIndexVersion", 4)
            val restored = JSONArray(restore.invoke(module, cache.toString(), "https://example/disc.iso?fresh=1") as String).getJSONObject(0)
            assertEquals("http://127.0.0.1:45678/zip/disc/Movie.m2ts", restored.getString("link"))
            assertEquals("ISO", restored.getString("archiveFormat"))
            @Suppress("UNCHECKED_CAST")
            val registry = field(module, "archiveEntryRegistry") as ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
            assertEquals("https://example/disc.iso?fresh=1", registry["disc"]!!.archiveUrl)
            assertEquals(376L, registry["disc"]!!.uncompressedSize)
            target.put("uncompressedSize", 188) // Old per-extent rounding must not survive validation.
            assertNull(restore.invoke(module, cache.toString(), "https://example/disc.iso"))
            target.put("archiveFormat", "ZIP").put("isBdavM2ts", false)
            cache.remove("isoIndexVersion")
            assertNotNull(restore.invoke(module, cache.toString(), "https://example/archive.zip"))
        } finally { (field(module, "ioExecutor") as ExecutorService).shutdownNow() }
    }

    @Test fun pmtRewriteIsIdenticalWhenSeekingWithoutAnEarlierPat() {
        val module = SecurityVaultModule(mock(ReactApplicationContext::class.java))
        try {
            fun crc(bytes: ByteArray): Int {
                var value = -1
                for (b in bytes) {
                    value = value xor ((b.toInt() and 255) shl 24)
                    repeat(8) { value = (value shl 1) xor (if (value < 0) 0x04c11db7 else 0) }
                }
                return value
            }
            val section = byteArrayOf(2, 0xb0.toByte(), 18, 0, 1, 0xc1.toByte(), 0, 0,
                0xe1.toByte(), 1, 0xf0.toByte(), 0, 0x86.toByte(), 0xe1.toByte(), 0, 0xf0.toByte(), 0)
            val checksum = crc(section)
            val packet = ByteArray(192) { 0xff.toByte() }
            packet[4] = 0x47; packet[5] = 0x50; packet[6] = 1; packet[7] = 0x10; packet[8] = 0
            section.copyInto(packet, 9)
            repeat(4) { packet[9 + section.size + it] = (checksum ushr (24 - 8 * it)).toByte() }
            val patch = SecurityVaultModule::class.java.getDeclaredMethod("patchBluRayPmtForTs", ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val seek = packet.copyOf()
            val sequential = packet.copyOf()
            patch.invoke(module, seek, 4, -1)
            patch.invoke(module, sequential, 4, 0x1001)
            assertArrayEquals(sequential, seek)
            assertEquals(0x8a, seek[21].toInt() and 255)
            assertEquals(0, crc(seek.copyOfRange(9, 30)))
        } finally { (field(module, "ioExecutor") as ExecutorService).shutdownNow() }
    }
}
