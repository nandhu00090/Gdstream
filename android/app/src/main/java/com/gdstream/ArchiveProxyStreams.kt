package com.gdstream

import java.io.InterruptedIOException
import java.net.URLDecoder

internal fun archiveFilenameMatches(encoded: String?, expected: String): Boolean = try {
    encoded != null && URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") == expected
} catch (_: IllegalArgumentException) { false }

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
