package com.gdstream

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Semaphore

internal fun safeStreamUrl(url: String): String = url.substringBefore('#').substringBefore('?') +
    if ('?' in url.substringBefore('#')) "?[redacted]" else ""

internal class RangeConnectionException(val status: Int?, val endpoint: String, cause: Throwable) :
    IOException("Upstream connection: ${safeStreamUrl(endpoint)} HTTP ${status ?: "unavailable"}", cause)

/** Keeps signed query strings verbatim and reapplies Range to every redirect hop. */
internal class RangeConnections(
    private val headers: () -> Map<String, String>,
    private val configure: (HttpURLConnection) -> Unit = {},
    private val receiveCookies: (List<String>) -> Unit = {}
) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val seededCookies = HashMap<String, String>()

    fun openIsoRange(url: String, start: Long, end: Long, session: IsoReadSession): HttpURLConnection =
        open(url, "bytes=$start-$end", connectTimeout = 25_000, readTimeout = IsoRangeReader.READ_STALL_MS, session = session)

    fun open(rawUrl: String, range: String?, connectTimeout: Int = 15000, readTimeout: Int = 25000, session: IsoReadSession? = null): HttpURLConnection {
        val original = URL(rawUrl.substringBefore('#'))
        val initialHeaders = headers()
        val initialCookies = initialHeaders["Cookie"].orEmpty()
        synchronized(cookies) {
            val key = original.protocol + "://" + original.authority
            if (seededCookies[key] != initialCookies) {
                initialCookies.split(';').map { it.trim() }.filter { '=' in it }.forEach {
                    val cookie = HttpCookie(it.substringBefore('='), it.substringAfter('='))
                    cookie.version = 0
                    cookie.path = "/"
                    cookies.cookieStore.add(original.toURI(), cookie)
                }
                seededCookies[key] = initialCookies
            }
        }
        var current = original
        for (redirect in 0..5) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Range request cancelled")
            val conn = current.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            var status: Int? = null
            try {
                session?.track(conn)
                session?.checkActive()
                session?.log("ISO_UPSTREAM_REQUEST", "range=$range url=${safeStreamUrl(current.toString())} redirect=$redirect")
                conn.connectTimeout = connectTimeout
                conn.readTimeout = readTimeout
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                val sameOrigin = current.protocol == original.protocol && current.authority == original.authority
                for ((name, value) in initialHeaders) {
                    if (!name.equals("Cookie", true) && (sameOrigin ||
                            (!name.equals("Authorization", true) && !name.equals("X-GD-Shield", true)))) {
                        conn.setRequestProperty(name, value)
                    }
                }
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Connection", if (session != null) "close" else "Keep-Alive")
                if (session != null) conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("User-Agent", initialHeaders["User-Agent"] ?: "GDStream-Native-Engine/3.0")
                if (range != null) conn.setRequestProperty("Range", range)
                synchronized(cookies) {
                    cookies.get(current.toURI(), emptyMap()).forEach { (name, values) ->
                        if (values.isNotEmpty()) conn.setRequestProperty(name, values.joinToString("; "))
                    }
                }
                configure(conn)
                session?.checkActive()
                status = conn.responseCode
                session?.log("ISO_UPSTREAM_STATUS", "HTTP=$status range=$range contentRange=${conn.getHeaderField("Content-Range")} url=${safeStreamUrl(current.toString())}")
                val setCookies = conn.headerFields.entries.filter { it.key?.equals("Set-Cookie", true) == true }.flatMap { it.value }
                synchronized(cookies) { cookies.put(current.toURI(), conn.headerFields) }
                if (sameOrigin && setCookies.isNotEmpty()) receiveCookies(setCookies)
                if (status !in listOf(301, 302, 303, 307, 308)) return conn
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                if (redirect == 5) throw IOException("Too many range redirects")
                current = URL(current, location)
                if (current.protocol != "http" && current.protocol != "https") throw IOException("Unsupported range redirect scheme")
                if (session != null) session.disconnect(conn) else conn.disconnect()
            } catch (e: Exception) {
                if (session != null) session.disconnect(conn) else conn.disconnect()
                val failure = RangeConnectionException(status, conn.url.toString(), e)
                session?.log("ISO_UPSTREAM_CONNECT_ERR", "range=$range ${isoFailureDetails(failure)}")
                throw failure
            }
        }
        throw IOException("Too many range redirects")
    }
}

internal class IsoRangeException(
    val start: Long,
    val end: Long,
    val status: Int?,
    val endpoint: String,
    val attempt: Int,
    cause: Throwable
) : IOException("ISO upstream bytes=$start-$end HTTP ${status ?: "unavailable"} attempt=$attempt url=${safeStreamUrl(endpoint)}", cause)

internal fun isoFailureDetails(error: Throwable): String = generateSequence(error) { it.cause }.take(12).joinToString(" <- ") {
    val message = (it.message ?: "<no message>").replace(Regex("https?://[^\\s]+")) { match -> safeStreamUrl(match.value) }
    "${it.javaClass.name}: $message"
}

internal object IsoRangeReader {
    internal const val WINDOW_BYTES = 8 * 1024 * 1024
    internal const val MAX_CONCURRENT_READS = 6
    internal const val READ_STALL_MS = 25_000
    private val upstreamSlots = Semaphore(MAX_CONCURRENT_READS, true)
    private val activeReads = java.util.concurrent.ConcurrentHashMap<Any, IsoReadSession>()
    fun cancelAll(reason: String) { activeReads.values.toSet().forEach { it.cancel(reason) } }

    private fun response(conn: HttpURLConnection, start: Long, end: Long, session: IsoReadSession): ByteArray {
        require(start >= 0 && end >= start && end - start < Int.MAX_VALUE)
        val status = conn.responseCode
        session.log("ISO_UPSTREAM_STATUS", "HTTP=$status physical=$start-$end contentRange=${conn.getHeaderField("Content-Range")} url=${safeStreamUrl(conn.url.toString())}")
        if (status != 206) throw IOException("Expected HTTP 206, got $status")
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
            .matchEntire(conn.getHeaderField("Content-Range")?.trim() ?: "")
            ?: throw IOException("Missing or invalid Content-Range")
        val actualStart = match.groupValues[1].toLongOrNull()
        val actualEnd = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (actualStart != start || actualEnd == null || actualEnd < start || actualEnd > end || total == null || total <= actualEnd) {
            throw IOException("Mismatched Content-Range: ${match.value} (requested $start-$end)")
        }
        val length = (actualEnd - start + 1).toInt()
        val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
        if (contentLength != null && contentLength != length.toLong()) throw IOException("Mismatched Content-Length: $contentLength/$length")
        val encoding = conn.getHeaderField("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw IOException("Encoded ISO range response: $encoding")
        val input = conn.inputStream
        val result = ByteArray(length)
        var pos = 0
        while (pos < length) {
            session.checkActive()
            val count = input.read(result, pos, minOf(64 * 1024, length - pos))
            if (count <= 0) throw EOFException("Truncated ISO HTTP range: $pos/$length bytes")
            pos += count
        }
        session.log("ISO_UPSTREAM_COMPLETE", "HTTP=$status physical=$start-$actualEnd bytes=$pos")
        session.checkActive()
        return result
    }

    fun read(conn: HttpURLConnection, start: Long, end: Long): ByteArray {
        val session = IsoReadSession()
        session.track(conn)
        return try {
            response(conn, start, end, session).also {
                if (it.size.toLong() != end - start + 1) throw EOFException("Incomplete ISO range")
            }
        } finally { session.cancel() }
    }

    fun readFully(
        url: String,
        start: Long,
        end: Long,
        open: (Long, Long) -> HttpURLConnection,
        onFailure: (IsoRangeException) -> Unit = {},
        pause: (Long) -> Unit = { Thread.sleep(it) },
        session: IsoReadSession = IsoReadSession(),
        cancelWithPlayback: Boolean = true
    ): ByteArray {
        val registration = Any()
        if (cancelWithPlayback) activeReads[registration] = session
        return try { readFullyInternal(url, start, end, open, onFailure, pause, session) }
        finally { activeReads.remove(registration) }
    }

    private fun readFullyInternal(
        url: String,
        start: Long,
        end: Long,
        open: (Long, Long) -> HttpURLConnection,
        onFailure: (IsoRangeException) -> Unit = {},
        pause: (Long) -> Unit = { Thread.sleep(it) },
        session: IsoReadSession = IsoReadSession()
    ): ByteArray {
        require(start >= 0 && end >= start && end - start < Int.MAX_VALUE)
        val result = ByteArray((end - start + 1).toInt())
        var filled = 0
        var failures = 0
        var requests = 0
        while (filled < result.size) {
            session.checkActive()
            val next = start + filled.toLong()
            val windowEnd = next + minOf(WINDOW_BYTES.toLong() - 1, end - next)
            var conn: HttpURLConnection? = null
            var status: Int? = null
            var permit: IsoReadSession.Permit? = null
            var retry = false
            try {
                permit = session.acquire(upstreamSlots)
                session.checkActive()
                if (++requests > 4096) throw IOException("Too many capped upstream ranges")
                session.log("ISO_UPSTREAM_RANGE", "physical=$next-$windowEnd attempt=${failures + 1} url=${safeStreamUrl(url)}")
                conn = open(next, windowEnd)
                session.track(conn)
                status = conn.responseCode
                val data = response(conn, next, windowEnd, session)
                data.copyInto(result, filled)
                filled += data.size
                failures = 0
            } catch (e: Exception) {
                if (session.isCancelled || Thread.currentThread().isInterrupted || e is InterruptedException ||
                    (e is InterruptedIOException && e !is SocketTimeoutException)) {
                    session.log("ISO_UPSTREAM_CANCEL", "physical=$next-$windowEnd HTTP=${status ?: "unavailable"} ${isoFailureDetails(e)}")
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    throw InterruptedIOException("ISO read cancelled").apply { initCause(e) }
                }
                failures++
                val connectionError = e as? RangeConnectionException
                val failure = IsoRangeException(next, windowEnd, status ?: connectionError?.status,
                    conn?.url?.toString() ?: connectionError?.endpoint ?: url, failures, e)
                session.log("ISO_UPSTREAM_ERR", isoFailureDetails(failure))
                onFailure(failure)
                val causes = generateSequence<Throwable>(e) { it.cause }.take(12).toList()
                retry = failure.status in listOf(404, 408, 429, 500, 502, 503, 504) ||
                    causes.any { it is SocketException || it is SocketTimeoutException || it is EOFException || it is UnknownHostException }
                if (!retry || failures >= 4 || requests > 4096) throw failure
            } finally {
                try { conn?.let { session.disconnect(it) } } finally { permit?.close() }
            }
            if (retry) {
                session.checkActive()
                session.log("ISO_UPSTREAM_RETRY", "physical=$next-$windowEnd backoffMs=${150L * failures}")
                try { pause(150L * failures) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("ISO retry cancelled").apply { initCause(interrupted) }
                }
            }
        }
        return result
    }
}

/** Keep the current source warm. Two recently closed sources keep only their startup block. */
internal class IsoChunkCaches {
    private data class Source(val url: String, val size: Long)
    private class Disc(val chunks: IsoChunkCache = IsoChunkCache(), var readers: Int = 0)
    private val discs = LinkedHashMap<Source, Disc>(4, 0.75f, true)
    private var latest: Source? = null

    @Synchronized
    fun acquire(url: String, session: IsoReadSession, size: Long = 0): IsoChunkCache {
        session.checkActive()
        val source = Source(canonicalIsoSourceUrl(url), size)
        val disc = discs.getOrPut(source) { Disc() }
        val changed = latest != source
        latest = source
        disc.readers++
        if (changed) trimIdle()
        session.onCancel {
            synchronized(this) {
                disc.readers--
                if (disc.readers == 0 && source != latest) trimIdle()
            }
        }
        return disc.chunks
    }

    private fun trimIdle() {
        val idle = discs.entries.filter { it.key != latest && it.value.readers == 0 }
        idle.dropLast(2).forEach { discs.remove(it.key)?.chunks?.clear() }
        idle.takeLast(2).forEach { it.value.chunks.retainStartupBlock() }
    }

    @Synchronized
    fun cancelFlights() { discs.values.forEach { it.chunks.cancelFlights() } }

    @Synchronized
    fun clear() {
        discs.values.forEach { it.chunks.clear() }
        discs.clear()
        latest = null
    }
}

/** Detect seeks in client byte coordinates. Fetch blocks on the physical BDAV grid. */
internal class M2tsReadCursor {
    private var request = 0L
    private var nextByte = 0L
    @Synchronized fun begin(id: Long, target: Long): Boolean {
        if (id < request) return false
        request = id
        val seek = kotlin.math.abs(target - nextByte) > 4L * 1024 * 1024
        nextByte = target
        return seek
    }
    @Synchronized fun advance(id: Long, next: Long): Boolean {
        if (request != id) return false
        nextByte = next
        return true
    }

    fun seekBlocks(target: Long, size: Long): List<LongRange> {
        val block = IsoChunkCache.BLOCK_BYTES.toLong()
        val start = target / block * block
        val ahead = (0..2).map { start + it * block }.filter { it < size }.toMutableList()
        // The previous block contains the beginning of the PCR search window near a boundary.
        if (start > 0 && target - start < 600 * 192) ahead.add(start - block)
        return ahead.map { it until minOf(it + block, size) }
    }
}

/** Physical blocks are immutable here: packet rewriting happens on each caller's copy. */
internal class IsoChunkCache(
    private val maxBytes: Int = 128 * 1024 * 1024,
    private val executor: java.util.concurrent.ExecutorService = sharedExecutor
) {
    companion object {
        const val BLOCK_BYTES = 8 * 1024 * 1024
        const val METADATA_BLOCK_BYTES = 2 * 1024 * 1024
        private val warmWorkers = java.util.concurrent.Executors.newFixedThreadPool(4) { task ->
            Thread(task, "ISOReadAhead").apply { isDaemon = true }
        }
        private val sharedExecutor = java.util.concurrent.Executors.newFixedThreadPool(IsoRangeReader.MAX_CONCURRENT_READS) { task ->
            Thread(task, "ISOChunkFetch").apply { isDaemon = true }
        }
    }
    private data class Key(val start: Long, val end: Long)
    private class Flight {
        val owner = IsoReadSession()
        lateinit var task: java.util.concurrent.FutureTask<ByteArray>
        var readers = 0
        val warmers = HashMap<IsoReadSession, AutoCloseable>()
        var scheduled = false
        var foreground = false
        fun abort(reason: String) {
            owner.cancel(reason)
            task.cancel(true)
        }
    }
    private val lock = Any()
    private val cached = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private val flights = HashMap<Key, Flight>()
    private var bytes = 0
    private var window = emptyList<LongRange>()

    fun prefetch(ranges: List<LongRange>, sourceSize: Long, session: IsoReadSession,
                 priority: Boolean = false, fetch: (Long, Long, IsoReadSession) -> ByteArray): List<java.util.concurrent.Future<ByteArray>> {
        val target = ranges.firstOrNull()?.first ?: return emptyList()
        val desired = LinkedHashSet<Key>()
        for (range in ranges) {
            var start = range.first / BLOCK_BYTES * BLOCK_BYTES
            while (start <= range.last && start < sourceSize) {
                desired.add(Key(start, minOf(start + BLOCK_BYTES, sourceSize) - 1))
                start += BLOCK_BYTES
            }
        }
        val abandoned = synchronized(lock) {
            session.checkActive()
            val far = window.isNotEmpty() && window.all {
                target < it.first - 32L * 1024 * 1024 || target > it.last + 32L * 1024 * 1024
            }
            window = ranges
            if (priority || far) flights.filter { (key, flight) -> flight.readers == 0 && key !in desired }
                .also { flights.keys.removeAll(it.keys) }.values else emptyList()
        }
        abandoned.forEach { it.abort(if (priority) "M2TS timeline seek" else "abandoned read-ahead after seek") }
        return synchronized(lock) {
            session.checkActive()
            desired.mapNotNull { key ->
                if (key in cached) return@mapNotNull null
                val flight = flight(key, fetch)
                if (session !in flight.warmers) {
                    val registration = session.onCancel { release(key, flight, session) }
                    if (session.isCancelled) { registration.close(); session.checkActive() }
                    flight.warmers[session] = registration
                }
                schedule(flight, priority)
                flight.task
            }
        }
    }

    // FutureTask runs only once, even when demand promotes a queued speculative block.
    private fun schedule(flight: Flight, priority: Boolean) {
        if (priority && !flight.foreground) {
            flight.foreground = true
            executor.execute(flight.task)
        } else if (!priority && !flight.foreground && !flight.scheduled) {
            flight.scheduled = true
            warmWorkers.execute(flight.task)
        }
    }

    private fun flight(key: Key, fetch: (Long, Long, IsoReadSession) -> ByteArray): Flight =
        flights.getOrPut(key) {
            Flight().also { created ->
                created.task = object : java.util.concurrent.FutureTask<ByteArray>({
                    created.owner.checkActive()
                    val data = fetch(key.start, key.end, created.owner)
                    if (data.size.toLong() != key.end - key.start + 1) throw EOFException("Incomplete ISO cache block")
                    synchronized(lock) {
                        if (flights[key] === created && data.size <= maxBytes) {
                            cached[key] = data
                            bytes += data.size
                            val entries = cached.entries.iterator()
                            while (bytes > maxBytes && entries.hasNext()) {
                                val entry = entries.next()
                                // Retain the initial playback block across seeks and long sequential reads.
                                if (entry.key.start == 0L && entry.value.size == BLOCK_BYTES) continue
                                bytes -= entry.value.size
                                entries.remove()
                            }
                        }
                    }
                    data
                }) {
                    override fun done() {
                        synchronized(lock) {
                            flights.remove(key, created)
                            created.warmers.values.forEach { it.close() }
                            created.warmers.clear()
                        }
                    }
                }
            }
        }

    private fun release(key: Key, flight: Flight, warmer: IsoReadSession?) {
        val orphan = synchronized(lock) {
            if (warmer == null) flight.readers-- else flight.warmers.remove(warmer)
            flight.readers == 0 && flight.warmers.isEmpty() && flights.remove(key, flight)
        }
        if (orphan) flight.abort("last proxy client closed")
    }

    fun read(start: Long, end: Long, extentStart: Long, extentEnd: Long,
             session: IsoReadSession, blockBytes: Int = BLOCK_BYTES, borrowFullBlock: Boolean = false,
             fetch: (Long, Long, IsoReadSession) -> ByteArray): ByteArray {
        require(start >= extentStart && end >= start && end <= extentEnd && end - start < Int.MAX_VALUE)
        if (borrowFullBlock && (start - extentStart) % blockBytes == 0L &&
            end - start == minOf(blockBytes - 1L, extentEnd - start)) {
            // IsoVirtualFile copies physical extents before stripping packet headers or editing the PMT.
            return block(Key(start, end), session, fetch)
        }
        val result = ByteArray((end - start + 1).toInt())
        var cursor = start
        while (cursor <= end) {
            session.checkActive()
            val from = extentStart + ((cursor - extentStart) / blockBytes) * blockBytes
            val to = from + minOf(blockBytes.toLong() - 1, extentEnd - from)
            val data = block(Key(from, to), session, fetch)
            session.checkActive()
            val offset = (cursor - from).toInt()
            val count = minOf(end - cursor + 1, (data.size - offset).toLong()).toInt()
            if (count <= 0) throw EOFException("Short cached ISO block")
            data.copyInto(result, (cursor - start).toInt(), offset, offset + count)
            cursor += count
        }
        return result
    }

    private fun block(
        key: Key,
        session: IsoReadSession,
        fetch: (Long, Long, IsoReadSession) -> ByteArray
    ): ByteArray {
        val flight = synchronized(lock) {
            session.checkActive()
            cached[key]?.let { return it }
            flight(key, fetch).also { it.readers++; schedule(it, true) }
        }
        val released = java.util.concurrent.atomic.AtomicBoolean()
        fun detach() { if (released.compareAndSet(false, true)) release(key, flight, null) }
        val registration = session.onCancel(::detach)
        try {
            while (true) {
                session.checkActive()
                try { return flight.task.get(100, java.util.concurrent.TimeUnit.MILLISECONDS).also { session.checkActive() } }
                catch (_: java.util.concurrent.TimeoutException) { }
                catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
            }
        } finally {
            registration.close()
            detach()
        }
    }

    internal fun activeReaders(): Int = synchronized(lock) { flights.values.sumOf { it.readers } }
    internal fun cachedBytes(): Int = synchronized(lock) { bytes }
    fun retainStartupBlock() {
        synchronized(lock) {
            cached.entries.removeAll { it.key.start != 0L }
            bytes = cached.values.sumOf { it.size }
        }
    }

    fun cancelFlights() {
        val active = synchronized(lock) {
            window = emptyList()
            flights.values.toList().also { flights.clear() }
        }
        active.forEach { it.abort("playback session ended") }
    }
    fun clear() {
        cancelFlights()
        synchronized(lock) { cached.clear(); bytes = 0 }
    }
}
