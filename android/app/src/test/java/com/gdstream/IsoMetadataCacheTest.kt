package com.gdstream

import java.io.IOException
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import org.json.JSONArray

class IsoMetadataCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun crossingTwoAlignedBlocksFetchesInParallelAndReopeningSharesTheBytes() {
        val pool = Executors.newCachedThreadPool()
        val size = IsoChunkCache.METADATA_BLOCK_BYTES + 123L
        val shared = IsoChunkCache()
        val calls = java.util.concurrent.ConcurrentLinkedQueue<LongRange>()
        val bothEntered = java.util.concurrent.CountDownLatch(2)
        val session = IsoReadSession()
        try {
            val metadata = IsoMetadataReadAhead(size, shared, session, pool) { from, to, _ ->
                calls.add(from..to)
                bothEntered.countDown()
                assertTrue(bothEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                ByteArray((to - from + 1).toInt()) { ((from + it) % 251).toByte() }
            }
            val first = IsoChunkCache.METADATA_BLOCK_BYTES - 8L
            val expected = ByteArray(131) { ((first + it) % 251).toByte() }
            repeat(2) { assertArrayEquals(expected, metadata.read(first, size - 1)) }
            assertEquals(setOf(0L until IsoChunkCache.METADATA_BLOCK_BYTES, IsoChunkCache.METADATA_BLOCK_BYTES.toLong() until size), calls.toSet())
            assertEquals(2, calls.size)
            assertThrows(IOException::class.java) { metadata.read(first, size) }
        } finally { session.cancel(); shared.clear(); pool.shutdownNow() }
    }

    @Test fun diskReopenRestoresMainMovieSpecsAndMediaInfoWithoutReadingSource() {
        val dir = folder.newFolder()
        val url = "https://library/disc.iso?id=one#name=Movie.iso"
        val size = 45L * 1024 * 1024 * 1024
        val profile = BluRayProfile(7200000000, listOf(BluRayPoint(0, 0), BluRayPoint(1000000, 188000)), emptyList(),
            listOf(BluRayStream(0x1011, 0x24, 8), BluRayStream(0x1015, 0x24, 6)), "MOVIE", true)
        val blob = JSONObject().put("isoIndexVersion", IsoIndexDiskCache.VERSION)
            .put("targets", JSONArray().put(JSONObject().put("bluRay", profile.toJson())
                .put("segments", JSONArray().put(JSONArray().put(800000).put(size - 800000)))))
            .put("rawJson", JSONArray().put(JSONObject().put("name", "Main Movie.m2ts")
                .put("mediaInfo", JSONObject(profile.mediaInfo(size))))).toString()
        IsoIndexDiskCache(dir).put(url, size, blob)
        val reopened = IsoIndexDiskCache(dir)
        assertEquals(size, reopened.lastKnownSize(url))
        val restored = JSONObject(reopened.get(url, size)!!)
        assertEquals(profile, BluRayProfile.fromJson(restored.getJSONArray("targets").getJSONObject(0).getJSONObject("bluRay")))
        assertTrue(restored.getJSONArray("rawJson").getJSONObject(0).getJSONObject("mediaInfo").getBoolean("hasDV"))
        assertNull(reopened.get(url, size + 1))
        assertNull(reopened.get(url.replace("id=one", "id=two"), size))
        assertNull(reopened.get(url, 0))
        assertEquals(blob, reopened.get(url.substringBefore('#'), size))
        assertFalse(dir.listFiles()!!.single().name.contains("library"))
    }
    @Test fun migratesPr13ProfileAndPersistsLaterInspectorResultsBySourceIdentity() {
        val legacy = folder.newFolder()
        val url = "https://library/movie.iso?id=17"
        val size = 12345L
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("8\n$url\n$size".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val item = JSONObject().put("name", "movie.mkv")
        java.io.File(legacy, "$hash.json").writeText(JSONObject().put("isoIndexVersion", 8)
            .put("rawJson", JSONArray().put(item).toString()).toString())
        val current = folder.newFolder()
        val cache = IsoIndexDiskCache(current, legacy)
        assertEquals(size, JSONObject(cache.get(url, size)!!).getLong("sourceSize"))
        cache.rememberInfo(url, size, "movie.mkv", JSONObject().put("durationSec", 1234))
        val restored = JSONObject(IsoIndexDiskCache(current).get(url, size)!!)
        assertEquals(1234, JSONArray(restored.getString("rawJson")).getJSONObject(0).getJSONObject("mediaInfo").getInt("durationSec"))
        assertNull(cache.get(url, size+1))
    }

    @Test fun emptyOrOversizedDiskEntryIsACacheMiss() {
        val dir = folder.newFolder()
        val file = java.io.File(dir, IsoIndexDiskCache.key("https://library/a.iso", 100))
        file.writeText("")
        assertNull(IsoIndexDiskCache(dir).get("https://library/a.iso", 100))
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertNull(IsoIndexDiskCache(dir).get("https://library/a.iso", 100))
    }
}
