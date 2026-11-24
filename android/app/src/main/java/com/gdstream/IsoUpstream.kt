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

internal fun expiredDownloadStatus(status: Int?) = status == 403 || status == 404 || status == 410

/** Redirected library links use the same seek window as direct SharePoint links. */
internal object IsoHostPolicy {
    val slots = Semaphore(3, true)
    private val redirects = object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 128
    }

    fun throttled(url: URL): Boolean = url.path.substringAfterLast('/').equals("download.aspx", true) ||
        url.host.endsWith(".sharepoint.com", true) || url.host.equals("sharepoint.com", true)

    @Synchronized fun remember(url: String) { redirects[url] = true }
    @Synchronized fun throttled(url: String): Boolean = redirects[url] == true || throttled(URL(url))
}

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
        val first = openOnce(original, range, connectTimeout, readTimeout, session, false)
        val status = first.responseCode
        // The ranged reader reopens the original URL on 404 with its shared backoff budget.
        if ((session != null && status == 404) || !expiredDownloadStatus(status) || first.url.toExternalForm() == original.toExternalForm() ||
            !first.url.path.substringAfterLast('/').equals("download.aspx", true)) return first

        // Each open starts at the library URL. Never keep a signed Location,
        // and force intermediaries to revalidate the worker response on refresh.
        if (session != null) session.disconnect(first) else first.disconnect()
        session?.checkActive()
        session?.log("ISO_REDIRECT_REFRESH", "HTTP=$status source=${safeStreamUrl(rawUrl)}")
        return openOnce(original, range, connectTimeout, readTimeout, session, true)
    }

    private fun openOnce(original: URL, range: String?, connectTimeout: Int, readTimeout: Int,
                         session: IsoReadSession?, refresh: Boolean): HttpURLConnection {
        val rawUrl = original.toExternalForm()
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
        var limited = session != null && IsoHostPolicy.throttled(rawUrl)
        var current = original
        for (redirect in 0..5) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Range request cancelled")
            val conn = current.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            var status: Int? = null
            try {
                if (session != null && !limited && IsoHostPolicy.throttled(current)) {
                    limited = true
                    IsoHostPolicy.remember(rawUrl)
                }
                session?.let { it.track(conn, if (limited) IsoHostPolicy.slots else null) }
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
                if (refresh || session != null) conn.setRequestProperty("Cache-Control", if (refresh) "no-cache, no-store" else "no-cache")
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

internal fun Throwable.isoRangeFailure() = generateSequence(this) { it.cause }.take(12).filterIsInstance<IsoRangeException>().firstOrNull()

internal fun isoProxyErrorStatus(failure: Throwable): String = when (failure.isoRangeFailure()?.status) {
    404 -> "404 Not Found"
    429 -> "429 Too Many Requests"
    503 -> "503 Service Unavailable"
    else -> "502 Bad Gateway"
}

internal fun isoFailureDetails(error: Throwable): String = generateSequence(error) { it.cause }.take(12).joinToString(" <- ") {
    val message = (it.message ?: "<no message>").replace(Regex("https?://[^\\s]+")) { match -> safeStreamUrl(match.value) }
    "${it.javaClass.name}: $message"
}

internal object IsoRangeReader {
    internal const val WINDOW_BYTES = 8 * 1024 * 1024
    internal const val MAX_CONCURRENT_READS = 3
    internal const val READ_STALL_MS = 25_000
    private val upstreamSlots = Semaphore(MAX_CONCURRENT_READS, true)
    private val retryDelaysMs = longArrayOf(400, 1000, 2000)
    private val activeReads = java.util.concurrent.ConcurrentHashMap<Any, IsoReadSession>()
    fun cancelAll(reason: String) { activeReads.values.toSet().forEach { it.cancel(reason) } }

    private fun response(conn: HttpURLConnection, start: Long, end: Long, session: IsoReadSession,
                         result: ByteArray, offset: Int, progress: (Int) -> Unit = {}): Int {
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
        var pos = 0
        while (pos < length) {
            session.checkActive()
            val count = input.read(result, offset + pos, minOf(64 * 1024, length - pos))
            if (count <= 0) throw EOFException("Truncated ISO HTTP range: $pos/$length bytes")
            pos += count
            progress(offset + pos)
        }
        session.log("ISO_UPSTREAM_COMPLETE", "HTTP=$status physical=$start-$actualEnd bytes=$pos")
        session.checkActive()
        return pos
    }

    fun read(conn: HttpURLConnection, start: Long, end: Long): ByteArray {
        val session = IsoReadSession()
        session.track(conn)
        return try {
            require(end >= start && end - start < Int.MAX_VALUE)
            ByteArray((end - start + 1).toInt()).also {
                if (response(conn, start, end, session, it, 0) != it.size) throw EOFException("Incomplete ISO range")
            }
        } finally { session.cancel() }
    }

    fun readFully(
        url: String,
        start: Long,
        end: Long,
        open: (Long, Long) -> HttpURLConnection,
        onFailure: (IsoRangeException) -> Unit = {},
        pause: ((Long) -> Unit)? = null,
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
        pause: ((Long) -> Unit)? = null,
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
                val offset = filled
                val count = response(conn, next, windowEnd, session, result, offset) { available ->
                    session.publishPrefix?.let { publish ->
                        // These bytes can already be on a client socket. Resume a failed range
                        // at this watermark so a retry cannot overwrite a published prefix.
                        filled = available
                        publish(result, available)
                    }
                }
                filled = offset + count
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
                if (failure.status == 403 || failure.status == 410) throw failure
                retry = failure.status in listOf(404, 408, 429, 500, 502, 503, 504) ||
                    causes.any { it is SocketException || it is SocketTimeoutException || it is EOFException || it is UnknownHostException }
                if (!retry || failures > retryDelaysMs.size || requests > 4096) throw failure
            } finally {
                try { conn?.let { session.disconnect(it) } } finally { permit?.close() }
            }
            if (retry) {
                session.checkActive()
                val delay = retryDelaysMs[failures - 1]
                session.log("ISO_UPSTREAM_RETRY", "physical=$next-$windowEnd backoffMs=$delay")
                try { if (pause != null) pause(delay) else session.awaitRetry(delay) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("ISO retry cancelled").apply { initCause(interrupted) }
                }
            }
        }
        return result
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
        return listOf(start, start + block).filter { it < size }.map { it until minOf(it + block, size) }
    }

    fun playbackWindow(position: Long, size: Long): LongRange {
        val block = IsoChunkCache.BLOCK_BYTES.toLong()
        val start = (position / block + 1) * block
        return start until minOf(size, start + 3 * block)
    }
}
