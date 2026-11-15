package com.gdstream

import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal data class HttpByteRange(val start: Long, val end: Long, val partial: Boolean) {
    val length: Long get() = end - start + 1

    companion object {
        fun parse(header: String?, size: Long): HttpByteRange {
            require(size >= 0)
            if (header == null) return HttpByteRange(0, size - 1, false)
            val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(header.trim())
                ?: throw IOException("Invalid or multiple byte ranges")
            val (first, last) = match.destructured
            val start: Long
            val end: Long
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: throw IOException("Invalid suffix range")
                if (suffix <= 0 || size == 0L) throw IOException("Unsatisfiable suffix range")
                start = (size - suffix).coerceAtLeast(0)
                end = size - 1
            } else {
                start = first.toLongOrNull() ?: throw IOException("Invalid range start")
                end = if (last.isEmpty()) size - 1 else
                    (last.toLongOrNull() ?: throw IOException("Invalid range end")).coerceAtMost(size - 1)
                if (start >= size || start > end) throw IOException("Unsatisfiable byte range")
            }
            return HttpByteRange(start, end, true)
        }
    }
}

/** A concatenation of file extents, with one continuous 192 -> 188 byte packet address space. */
internal class IsoVirtualFile(
    private val extents: List<Pair<Long, Long>>,
    private val bdav: Boolean
) {
    val rawSize: Long = extents.fold(0L) { sum, (offset, length) ->
        require(offset >= -1 && length > 0 && (offset < 0 || offset <= Long.MAX_VALUE - length))
        Math.addExact(sum, length)
    }
    val size: Long = if (bdav) (rawSize / 192L) * 188L else rawSize

    fun readRaw(start: Long, length: Int, fetch: (Long, Long) -> ByteArray): ByteArray {
        require(start >= 0 && length >= 0 && start <= rawSize - length)
        val output = ByteArray(length)
        var skip = start
        var pos = 0
        for ((offset, size) in extents) {
            if (skip >= size) { skip -= size; continue }
            val take = minOf(size - skip, (length - pos).toLong()).toInt()
            if (offset >= 0 && take > 0) {
                val data = fetch(offset + skip, offset + skip + take - 1)
                if (data.size != take) throw EOFException("Truncated ISO extent: ${data.size}/$take bytes")
                data.copyInto(output, pos)
            }
            pos += take
            if (pos == length) return output
            skip = 0
        }
        if (pos != length) throw EOFException("Incomplete ISO extent map")
        return output
    }

    fun read(start: Long, length: Int, fetch: (Long, Long) -> ByteArray, patchPacket: (ByteArray, Int) -> Unit): ByteArray {
        require(start >= 0 && length >= 0 && start <= size - length)
        if (!bdav || length == 0) return readRaw(start, length, fetch)
        val firstPacket = start / 188L
        val lastPacket = (start + length - 1) / 188L
        val raw = readRaw(firstPacket * 192L, ((lastPacket - firstPacket + 1) * 192L).toInt(), fetch)
        val result = ByteArray(length)
        var output = 0
        var skip = (start % 188L).toInt()
        for (p in raw.indices step 192) {
            patchPacket(raw, p + 4)
            val take = minOf(188 - skip, length - output)
            raw.copyInto(result, output, p + 4 + skip, p + 4 + skip + take)
            output += take
            skip = 0
        }
        return result
    }

    /** Preflight the first chunk before committing 200/206; later failures close a truncated body. */
    fun serve(
        method: String,
        rangeHeader: String?,
        mimeType: String,
        out: OutputStream,
        executor: ExecutorService,
        read: (Long, Int) -> ByteArray
    ) {
        fun error(status: String, extra: String = "") {
            out.write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        if (method != "GET" && method != "HEAD") { error("405 Method Not Allowed", "Allow: GET, HEAD\r\n"); return }
        val range = try { HttpByteRange.parse(if (method == "HEAD") null else rangeHeader, size) } catch (_: IOException) {
            error("416 Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
            return
        }
        var sentHeaders = false
        var pending: Future<ByteArray>? = null
        try {
            var cursor = range.start
            fun fetchNext(): Future<ByteArray>? {
                if (cursor > range.end || method == "HEAD") return null
                val start = cursor
                // Packet-aligned chunks avoid repeatedly reading a packet at chunk boundaries.
                val limit = if (cursor == range.start) 188 * 2788 else 188 * 22310
                val count = minOf(limit.toLong(), range.end - cursor + 1).toInt()
                cursor += count
                return executor.submit<ByteArray> {
                    read(start, count).also { if (it.size != count) throw EOFException("Short ISO proxy read") }
                }
            }
            pending = fetchNext()
            var chunk = pending?.get(45, TimeUnit.SECONDS)
            val headers = buildString {
                append(if (range.partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\nAccept-Ranges: bytes\r\nContent-Length: ${range.length}\r\n")
                if (range.partial) append("Content-Range: bytes ${range.start}-${range.end}/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            sentHeaders = true
            out.write(headers.toByteArray(Charsets.US_ASCII))
            while (chunk != null) {
                pending = fetchNext()
                out.write(chunk)
                out.flush()
                chunk = pending?.get(45, TimeUnit.SECONDS)
            }
            out.flush()
        } catch (e: Exception) {
            if (!sentHeaders) error("502 Bad Gateway")
            throw IOException("ISO proxy read failed", e)
        } finally {
            pending?.cancel(true)
        }
    }
}

/** ISO seeks must never silently download and discard tens of GB when a server ignores Range. */
internal object IsoRangeReader {
    fun read(conn: HttpURLConnection, start: Long, end: Long): ByteArray {
        try {
            require(start >= 0 && end >= start && end - start < Int.MAX_VALUE)
            val code = conn.responseCode
            if (code != 206) throw IOException("ISO requires HTTP 206 for bytes=$start-$end (got $code)")
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                .matchEntire(conn.getHeaderField("Content-Range")?.trim() ?: "")
                ?: throw IOException("Missing or invalid ISO Content-Range")
            val actualStart = match.groupValues[1].toLongOrNull()
            val actualEnd = match.groupValues[2].toLongOrNull()
            val total = match.groupValues[3].toLongOrNull()
            if (actualStart != start || actualEnd != end || total == null || total <= end) {
                throw IOException("Mismatched ISO Content-Range for bytes=$start-$end")
            }
            val length = (end - start + 1).toInt()
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
            if (contentLength != null && contentLength != length.toLong()) throw IOException("Mismatched ISO Content-Length")
            val encoding = conn.getHeaderField("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", true)) throw IOException("Encoded ISO range response")
            return conn.inputStream.use { input ->
                val result = ByteArray(length)
                var p = 0
                while (p < length) {
                    if (Thread.currentThread().isInterrupted) throw IOException("ISO read cancelled")
                    val count = input.read(result, p, length - p)
                    if (count <= 0) throw EOFException("Truncated ISO HTTP range: $p/$length bytes")
                    p += count
                }
                result
            }
        } finally {
            conn.disconnect()
        }
    }
}
