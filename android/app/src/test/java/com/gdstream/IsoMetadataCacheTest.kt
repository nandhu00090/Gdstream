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
    @Test fun hundredAdjacentSectorsUseOneAligned512KiBRange() {
        val ranges = ArrayList<Pair<Long, Long>>()
        val cache = IsoMetadataReadAhead(2 * IsoMetadataReadAhead.BLOCK_SIZE) { start, end ->
            ranges.add(start to end)
            ByteArray((end - start + 1).toInt()) { ((start + it) % 251).toByte() }
        }
        repeat(100) { sector ->
            val start = 524288L + sector * 2048
            val actual = cache.read(start, start + 2047)
            assertArrayEquals(ByteArray(2048) { ((start + it) % 251).toByte() }, actual)
        }
        assertEquals(listOf(524288L to 1048575L), ranges)
    }
    @Test fun crossBlockAndPartialFinalBlockAreExactAndParallelReadsCoalesce() {
        val ranges = ArrayList<Pair<Long, Long>>()
        val size = IsoMetadataReadAhead.BLOCK_SIZE + 123
        val cache = IsoMetadataReadAhead(size) { start, end ->
            ranges.add(start to end)
            ByteArray((end - start + 1).toInt()) { ((start + it) % 251).toByte() }
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 24).map { pool.submit<ByteArray> { cache.read(524280, size - 1) } }
            jobs.forEach { assertArrayEquals(ByteArray(131) { ((524280L + it) % 251).toByte() }, it.get()) }
            assertEquals(listOf(0L to 524287L, 524288L to size - 1), ranges)
        } finally { pool.shutdownNow() }
        assertThrows(IOException::class.java) { cache.read(size - 1, size) }
        assertThrows(IOException::class.java) { cache.read(-1, 2) }
    }
    @Test fun failedReadsAreNotCachedAndEvictionIsBounded() {
        var calls = 0
        val cache = IsoMetadataReadAhead(20 * IsoMetadataReadAhead.BLOCK_SIZE) { start, end ->
            calls++
            if (calls == 1) ByteArray(2) else ByteArray((end - start + 1).toInt())
        }
        assertThrows(IOException::class.java) { cache.read(0, 10) }
        cache.read(0, 10)
        repeat(17) { cache.read(it * IsoMetadataReadAhead.BLOCK_SIZE, it * IsoMetadataReadAhead.BLOCK_SIZE + 1) }
        cache.read(0, 10)
        assertEquals(19, calls)
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
        val restored = JSONObject(reopened.get(url, size)!!)
        assertEquals(profile, BluRayProfile.fromJson(restored.getJSONArray("targets").getJSONObject(0).getJSONObject("bluRay")))
        assertTrue(restored.getJSONArray("rawJson").getJSONObject(0).getJSONObject("mediaInfo").getBoolean("hasDV"))
        assertNull(reopened.get(url, size + 1))
        assertNull(reopened.get(url.replace("id=one", "id=two"), size))
        assertNull(reopened.get(url, 0))
        assertEquals(blob, reopened.get(url.substringBefore('#'), size))
        assertFalse(dir.listFiles()!!.single().name.contains("library"))
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
