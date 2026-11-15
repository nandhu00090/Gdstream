package com.gdstream

import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal class IsoClientDisconnected(cause: IOException) : IOException("Client closed proxy response", cause)
internal class IsoProxyReadException(val start: Long, val count: Int, cause: Throwable) :
    IOException("ISO proxy read failed: virtual bytes=$start-${start + count - 1L}", cause)

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

    fun physicalRanges(start: Long, length: Int): String {
        val rawStart = if (bdav) (start / 188L) * 192L else start
        val rawEnd = if (bdav) ((start + length - 1) / 188L + 1) * 192L else start + length
        var cursor = 0L
        return extents.mapNotNull { (offset, size) ->
            val from = maxOf(cursor, rawStart)
            val until = minOf(cursor + size, rawEnd)
            val mapped = if (from < until) {
                if (offset < 0) "zero:${until - from}" else "${offset + from - cursor}-${offset + until - cursor - 1}"
            } else null
            cursor += size
            mapped
        }.joinToString(",")
    }

    /** Preflight the first chunk before committing 200/206; later failures close a truncated body. */
    fun serve(
        method: String,
        rangeHeader: String?,
        mimeType: String,
        out: OutputStream,
        executor: ExecutorService,
        session: IsoReadSession = IsoReadSession(),
        read: (Long, Int) -> ByteArray
    ) {
        session.log("ISO_PROXY_REQUEST", "method=$method requestedRange=${rangeHeader ?: "full"} size=$size")
        fun write(bytes: ByteArray) {
            try { out.write(bytes) } catch (e: IOException) { throw IsoClientDisconnected(e) }
        }
        fun flush() {
            try { out.flush() } catch (e: IOException) { throw IsoClientDisconnected(e) }
        }
        fun error(status: String, extra: String = "") {
            session.log("ISO_PROXY_STATUS", "HTTP=$status requestedRange=${rangeHeader ?: "full"}")
            try {
                write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            } catch (_: IsoClientDisconnected) { }
        }
        if (method != "GET" && method != "HEAD") { error("405 Method Not Allowed", "Allow: GET, HEAD\r\n"); return }
        val range = try { HttpByteRange.parse(if (method == "HEAD") null else rangeHeader, size) } catch (_: IOException) {
            error("416 Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
            return
        }
        var pendingStart = 0L
        var pendingCount = 0
        var sentHeaders = false
        var pending: Future<ByteArray>? = null
        try {
            var cursor = range.start
            fun awaitChunk(future: Future<ByteArray>?): ByteArray? {
                if (future == null) return null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                while (true) {
                    session.checkActive()
                    try { return future.get(250, TimeUnit.MILLISECONDS) } catch (timeout: java.util.concurrent.TimeoutException) {
                        if (System.nanoTime() >= deadline) throw timeout
                    }
                }
            }
            fun fetchNext(): Future<ByteArray>? {
                if (cursor > range.end || method == "HEAD") return null
                val start = cursor
                // Packet-aligned chunks avoid repeatedly reading a packet at chunk boundaries.
                val limit = if (cursor == range.start) 188 * 2788 else 188 * 10922
                val count = minOf(limit.toLong(), range.end - cursor + 1).toInt()
                cursor += count
                pendingStart = start
                pendingCount = count
                session.log("ISO_PROXY_MAP", "virtual=$start-${start + count - 1} physical=${physicalRanges(start, count)}")
                return executor.submit<ByteArray> {
                    session.checkActive()
                    read(start, count).also { if (it.size != count) throw EOFException("Short ISO proxy read") }
                }
            }
            pending = fetchNext()
            var chunk = awaitChunk(pending)
            val headers = buildString {
                append(if (range.partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\nAccept-Ranges: bytes\r\nContent-Length: ${range.length}\r\n")
                if (range.partial) append("Content-Range: bytes ${range.start}-${range.end}/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            session.log("ISO_PROXY_STATUS", "HTTP=${if (range.partial) 206 else 200} virtual=${range.start}-${range.end} length=${range.length}")
            sentHeaders = true
            write(headers.toByteArray(Charsets.US_ASCII))
            var delivered = 0L
            while (chunk != null) {
                pending = fetchNext()
                write(chunk)
                flush()
                delivered += chunk.size
                session.log("ISO_PROXY_PROGRESS", "sent=$delivered/${range.length} virtual=${range.start}-${range.end}")
                chunk = awaitChunk(pending)
            }
            flush()
        } catch (error: IsoClientDisconnected) {
            session.cancel("client write: ${isoFailureDetails(error)}")
            return
        } catch (e: Exception) {
            if (session.isCancelled) return
            if (!sentHeaders) try { error("502 Bad Gateway") } catch (_: IsoClientDisconnected) { }
            throw IsoProxyReadException(pendingStart, pendingCount, e)
        } finally {
            session.cancel()
            pending?.cancel(true)
        }
    }
}
