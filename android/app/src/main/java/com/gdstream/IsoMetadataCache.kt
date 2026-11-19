package com.gdstream

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Split a logical metadata read at physical block boundaries; UDF entries, CLPI, IFO
 * and audio sampling all join the same block flights as playback. */
internal class IsoMetadataReadAhead(
    private val size: Long,
    private val cache: IsoChunkCache,
    private val session: IsoReadSession,
    private val executor: java.util.concurrent.ExecutorService,
    private val fetch: (Long, Long, IsoReadSession) -> ByteArray
) {
    fun read(first: Long, last: Long): ByteArray {
        if (first < 0 || last >= size || last < first || last - first >= 8L * 1024 * 1024)
            throw IOException("Invalid ISO metadata range")
        fun slice(start: Long, end: Long) = cache.read(start, end, 0, size - 1, session, fetch)
        val block = IsoChunkCache.BLOCK_BYTES
        if (first / block == last / block) return slice(first, last)
        val jobs = (first / block..last / block).map { index ->
            executor.submit<ByteArray> { slice(maxOf(first, index * block), minOf(last, (index + 1) * block - 1)) }
        }
        val result = ByteArray((last - first + 1).toInt())
        var offset = 0
        try {
            for (job in jobs) {
                val bytes = job.get()
                bytes.copyInto(result, offset)
                offset += bytes.size
            }
        } finally { jobs.forEach { it.cancel(true) } }
        return result
    }
}

/** Resolved entry/profile blob, separate from transient proxy URLs and legacy ZIP identity rules. */
internal class IsoIndexDiskCache(private val directory: File, private val legacy: File? = null) {
    // Directory listings occasionally omit size. Reuse the most recently resolved identity
    // until a listing supplies a fresh size, retaining the size check on every actual lookup.
    @Synchronized
    fun lastKnownSize(url: String): Long? {
        val prefix = identity(url) + "-"
        return directory.listFiles()?.filter { it.name.startsWith(prefix) && it.extension == "json" }
            ?.maxByOrNull { it.lastModified() }?.nameWithoutExtension?.removePrefix(prefix)?.toLongOrNull()
    }

    @Synchronized
    fun get(url: String, size: Long): String? {
        if (size <= 0) return null // Unknown size cannot safely identify a cached image.
        return try {
            val file = File(directory, key(url, size))
            if (file.length() in 1..MAX_ENTRY_BYTES) file.readText(Charsets.UTF_8) else {
                val previous = File(legacy ?: return null, digest("8\n${url.substringBefore('#')}\n$size") + ".json")
                if (previous.length() !in 1..MAX_ENTRY_BYTES) return null
                val root = org.json.JSONObject(previous.readText(Charsets.UTF_8))
                if (root.optInt("isoIndexVersion") != 8) return null
                root.put("isoIndexVersion", VERSION).put("sourceSize", size).toString().also { put(url, size, it) }
            }
        } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
    }

    @Synchronized
    fun rememberInfo(url: String, size: Long, fileName: String, info: org.json.JSONObject) {
        val root = get(url, size)?.let { org.json.JSONObject(it) } ?: return
        val items = org.json.JSONArray(root.getString("rawJson"))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("name") == fileName) {
                item.put("mediaInfo", info)
                put(url, size, root.put("rawJson", items.toString()).toString())
                return
            }
        }
    }

    @Synchronized
    fun put(url: String, size: Long, blob: String) {
        if (size <= 0 || blob.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) return
        var temporary: File? = null
        try {
            directory.mkdirs()
            temporary = File.createTempFile("index-", ".tmp", directory)
            temporary.writeText(blob, Charsets.UTF_8)
            if (!temporary.renameTo(File(directory, key(url, size)))) return
            directory.listFiles()?.filter { it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(64)?.forEach { it.delete() }
        } catch (_: IOException) {
            // Cache failure must not prevent playback.
        } finally { temporary?.delete() }
    }

    companion object {
        const val VERSION = 9
        private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        fun key(url: String, size: Long) = "${identity(url)}-$size.json"
        private fun identity(url: String): String {
            // Keep query parameters: two downloads may differ only by id/token. Never persist redirects.
            val canonical = runCatching { java.net.URI(url.substringBefore('#')).normalize().toASCIIString() }.getOrDefault(url.substringBefore('#'))
            return digest("$VERSION\n$canonical")
        }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
