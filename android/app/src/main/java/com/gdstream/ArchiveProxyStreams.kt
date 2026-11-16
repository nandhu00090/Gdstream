package com.gdstream

import java.io.InterruptedIOException
import java.net.URLDecoder

internal fun archiveFilenameMatches(encoded: String?, expected: String, bdavAlias: Boolean = false): Boolean = try {
    val decoded = encoded?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    decoded == expected || (bdavAlias && expected.endsWith(".m2ts", true) && decoded == expected.dropLast(5) + ".ts")
} catch (_: IllegalArgumentException) { false }

/** Rewrite only the registered Blu-ray path; retain encoded names, query and fragment verbatim. */
internal fun externalIsoTsUrl(url: String, expected: String, bdav: Boolean): String = try {
    val uri = java.net.URI(url)
    val path = uri.rawPath.orEmpty()
    if (!bdav || ArchiveProxyStreams.streamId(url) == null || !path.endsWith(".m2ts", true) ||
        !archiveFilenameMatches(path.substringAfterLast('/'), expected)) url else
        "${uri.scheme}://${uri.rawAuthority}${path.dropLast(5)}.ts" +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
} catch (_: Exception) { url }

/** Owns downstream sockets, including writes blocked on a player that never closes its socket. */
internal class ProxyStreamRegistry {
    private data class Active(val streamId: String, val full: Boolean, val cancel: (String) -> Unit)
    private val lock = Any()
    private val active = LinkedHashMap<Any, Active>()
    @Volatile private var accepting = true

    fun resume() { synchronized(lock) { accepting = true } }
    fun checkActive() { if (!accepting) throw InterruptedIOException("Archive playback stopped") }

    fun register(streamId: String, full: Boolean, cancel: (String) -> Unit): AutoCloseable {
        val key = Any()
        val stale = synchronized(lock) {
            checkActive()
            val previous = active.filterValues { full && it.full && it.streamId == streamId }
            previous.keys.forEach { active.remove(it) }
            active[key] = Active(streamId, full, cancel)
            previous.values.toList()
        }
        cancel(stale, "superseded full stream: $streamId")
        return AutoCloseable { synchronized(lock) { active.remove(key) }; Unit }
    }

    private fun cancel(streams: List<Active>, reason: String) {
        streams.forEach { stream ->
            try { stream.cancel(reason) } catch (error: Exception) {
                DebugLogger.log("ARCHIVE_CANCEL_ERR", "stream=${stream.streamId} ${isoFailureDetails(error)}")
            }
        }
    }

    fun cancelStream(streamId: String, reason: String) {
        val old = synchronized(lock) {
            val matching = active.filterValues { it.streamId == streamId }
            matching.keys.forEach { active.remove(it) }
            matching.values.toList()
        }
        cancel(old, reason)
    }

    fun stopAll(reason: String) {
        val old = synchronized(lock) {
            accepting = false
            active.values.toList().also { active.clear() }
        }
        cancel(old, reason)
    }
}

internal object ArchiveProxyStreams {
    val registry = ProxyStreamRegistry()
    fun streamId(url: String): String? = try {
        val uri = java.net.URI(url)
        if (uri.host !in listOf("127.0.0.1", "localhost")) null else
            uri.rawPath.split('/').takeIf { it.size >= 3 && it[1] == "zip" }?.get(2)
    } catch (_: Exception) { null }

    fun prepare(url: String) {
        registry.resume()
        streamId(url)?.let { registry.cancelStream(it, "new playback / external handoff") }
    }
    fun stop(url: String) { streamId(url)?.let { registry.cancelStream(it, "player stopped") } }
}
