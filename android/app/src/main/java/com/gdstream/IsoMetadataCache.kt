package com.gdstream

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Scan-scoped, bounded read-ahead. A lock coalesces concurrent reads of the same block. */
internal class IsoMetadataReadAhead(private val size: Long, private val readRange: (Long, Long) -> ByteArray) {
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

    @Synchronized
    fun read(start: Long, end: Long): ByteArray {
        if (start < 0 || end < start || end >= size || end - start >= 8L * 1024 * 1024) {
            throw IOException("Invalid ISO metadata range")
        }
        val result = ByteArray((end - start + 1).toInt())
        var position = start
        while (position <= end) {
            val aligned = position / BLOCK_SIZE * BLOCK_SIZE
            val block = blocks[aligned] ?: run {
                val last = minOf(size - 1, aligned + BLOCK_SIZE - 1)
                val bytes = readRange(aligned, last)
                if (bytes.size.toLong() != last - aligned + 1) throw IOException("Truncated ISO metadata block")
                blocks[aligned] = bytes
                if (blocks.size > MAX_BLOCKS) blocks.remove(blocks.keys.first())
                bytes
            }
            val offset = (position - aligned).toInt()
            val count = minOf(end - position + 1, (block.size - offset).toLong()).toInt()
            block.copyInto(result, (position - start).toInt(), offset, offset + count)
            position += count
        }
        return result
    }

    companion object {
        const val BLOCK_SIZE = 512L * 1024
        private const val MAX_BLOCKS = 16 // 8 MiB per scan, never retains movie payload.
    }
}

/** Resolved entry/profile blob, separate from transient proxy URLs and legacy ZIP identity rules. */
internal class IsoIndexDiskCache(private val directory: File) {
    @Synchronized
    fun get(url: String, size: Long): String? {
        if (size <= 0) return null // Unknown size cannot safely identify a cached image.
        return try {
            val file = File(directory, key(url, size))
            if (file.length() !in 1..MAX_ENTRY_BYTES) null else file.readText(Charsets.UTF_8)
        } catch (_: IOException) { null }
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
        const val VERSION = 7
        private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        fun key(url: String, size: Long): String {
            // Keep query parameters: two downloads may differ only by id/token. Never persist redirects.
            val identity = "$VERSION\n${url.substringBefore('#')}\n$size"
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) } + ".json"
        }
    }
}
