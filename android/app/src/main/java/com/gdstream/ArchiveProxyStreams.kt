package com.gdstream

import java.io.InterruptedIOException
import java.net.URLDecoder

internal fun archiveFilenameMatches(encoded: String?, expected: String, bdavAlias: Boolean = false): Boolean = try {
    val decoded = encoded?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    decoded == expected || (bdavAlias && expected.endsWith(".m2ts", true) && decoded == expected.dropLast(5) + ".ts")
} catch (_: IllegalArgumentException) { false }

internal fun dvdPlayerNeedsAvc(player: String, platformMpeg2: () -> Boolean): Boolean = when {
    player == "org.videolan.vlc" || player.startsWith("org.videolan.vlc.") ||
        player == "is.xyz.mpv" || player.startsWith("is.xyz.mpv.") || player == "live.mehiz.mpvkt" ||
        player == "org.xbmc.kodi" -> false
    player == "com.brouken.player" || player.startsWith("com.brouken.player.") ||
        player.contains("exoplayer", ignoreCase = true) || player.startsWith("androidx.media3.") -> true
    else -> !platformMpeg2()
}

internal fun externalDvdUrl(url: String, id: String, fileName: String, avc: Boolean = false): String {
    val uri = java.net.URI(url)
    if (avc) return "${uri.scheme}://${uri.rawAuthority}/hls/$id/dvd.m3u8"
    val name = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    return "${uri.scheme}://${uri.rawAuthority}/zip/$id/$name"
}

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
            uri.rawPath.split('/').takeIf { it.size >= 3 && it[1] in listOf("zip", "hls") }?.get(2)
    } catch (_: Exception) { null }

    private var lastPlayback: String? = null
    @Volatile private var dvdHlsEntry: String? = null

    fun skipDvdTrackProbe(url: String): Boolean {
        val active = dvdHlsEntry ?: return false
        if (streamId(url) != active) return false
        val path = java.net.URI(url).rawPath
        return path.startsWith("/zip/$active/") && path.endsWith(".vob", true)
    }

    @Synchronized fun prepare(url: String) {
        registry.resume()
        val next = streamId(url)
        lastPlayback?.takeIf { it != next }?.let { registry.cancelStream(it, "playback source changed") }
        next?.let { registry.cancelStream(it, "new playback / external handoff") }
        lastPlayback = next
        dvdHlsEntry = next?.takeIf { java.net.URI(url).rawPath == "/hls/$it/dvd.m3u8" }
    }
    @Synchronized fun stop(url: String) {
        val id = streamId(url) ?: return
        if (id == lastPlayback) {
            lastPlayback = null
            dvdHlsEntry = null
        }
        registry.cancelStream(id, "player stopped")
    }
    @Synchronized fun stopAll(reason: String) {
        dvdHlsEntry = null
        lastPlayback = null
        registry.stopAll(reason)
    }
}
