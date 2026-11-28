package com.gdstream

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class SecurityVaultModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val pcrProbes = Executors.newFixedThreadPool(IsoRangeReader.MAX_CONCURRENT_READS)
    private val ioExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var nativeSessionLoggedIn = false

    @Volatile
    private var sessionCookieHeader: String = ""

    @Volatile
    private var cachedShieldToken: String = ""

    @Volatile
    private var cachedSslFactory: SSLSocketFactory? = null

    data class DirectTrailerStream(
        val label: String,
        val height: Int,
        val videoUrl: String
    )

    data class TrailerLanguageOption(
        val langCode: String,
        val langName: String,
        val videoId: String,
        val label: String,
        val isOriginal: Boolean
    )

    data class VerifiedTrailerInfo(
        val engine: String,
        val videoId: String,
        val directStreams: List<DirectTrailerStream>,
        val maxQualityLabel: String,
        val languages: List<TrailerLanguageOption> = emptyList(),
        val activeLangName: String = ""
    )

    internal data class ArchiveEntryTarget(
        val id: String,
        val archiveUrl: String,
        val fileName: String,
        val archiveFormat: String, // "ZIP", "RAR", or "ISO"
        val compressionMethod: Int, // 0 = STORE (Uncompressed), 8 = DEFLATE (ZIP), >0 = RAR Compressed
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        @Volatile var dataStartOffset: Long = -1L,
        val segments: List<Pair<Long, Long>>? = null,
        val isBdavM2ts: Boolean = false,
        val bluRay: BluRayProfile? = null,
        val resolvedInfo: Map<String, Any>? = null,
        val sourceSize: Long = 0L,
        val standalone: Boolean = false,
        val dvdSegments: List<DvdProxy.Segment> = emptyList(),
        internal val dvdEncoderOverride: DvdProxy.Encoder? = null
    ) {
        // Accessed only under the DVD encoder lock.
        internal var dvdSequenceHeader: ByteArray? = null
        internal val directCursor = M2tsReadCursor()
        private var program: BluRayPsi.Program? = bluRay?.program
        @Synchronized fun program(load: () -> ByteArray): BluRayPsi.Program =
            program ?: (BluRayPsi.program(load()) ?: throw java.io.IOException("Missing Blu-ray PAT/PMT")).also { program = it }

        val dvdPlaylist by lazy {
            if (dvdSegments.isNotEmpty()) dvdSegments else if (fileName.endsWith(".vob", true)) {
                val seconds = (resolvedInfo?.get("durationSec") as? Number)?.toDouble() ?: 0.0
                DvdProxy.estimatedSegments(uncompressedSize, (seconds * 1_000_000).toLong())
            } else emptyList()
        }
        val dvdStartsUs by lazy { dvdPlaylist.runningFold(0L) { start, segment -> start + segment.durationUs } }
        @Volatile var rawSubtitle: Pair<Int, DvdSubtitleWindow>? = null
        val dvdHls by lazy { DvdHls(DvdStreams.from(resolvedInfo.orEmpty())) }
        val dvdEncoder by lazy {
            dvdEncoderOverride ?: run {
                val audio = resolvedInfo?.get("audioTracks") as? List<*>
                val supportedAudio = audio.isNullOrEmpty() || audio.any { (it as? Map<*, *>)?.get("codec") in setOf("AC-3", "DTS", "LPCM", "MPEG Audio") }
                if (!supportedAudio || resolvedInfo == null || dvdPlaylist.isEmpty() || resolvedInfo["videoCodec"] != "MPEG-2") null else
                    DvdProxy.encoder((resolvedInfo.getValue("width") as Number).toInt(), (resolvedInfo.getValue("height") as Number).toInt())
            }
        }
        val bluRayStartsUs by lazy { hls?.segments?.runningFold(0L) { start, segment -> start + segment.durationUs }.orEmpty() }
        internal val hls: BluRayHls? by lazy { if (isBdavM2ts) BluRayHls.create(bluRay, uncompressedSize, standalone,
            standalone && bluRay?.streams?.any { it.dts } == true && FfmpegBridge.available) else null }
    }

    private val verifiedTrailerCache = ConcurrentHashMap<String, VerifiedTrailerInfo>()
    data class YoutubeProxyTarget(
        val id: String,
        val rawUrl: String,
        val userAgent: String,
        val totalSize: Long
    )

    private val youtubeStreamRegistry = ConcurrentHashMap<String, YoutubeProxyTarget>()
    private val youtubeChunkCache = ConcurrentHashMap<String, ByteArray>()
    private val youtubeChunkInflight = ConcurrentHashMap<String, java.util.concurrent.Future<ByteArray?>>()
    private val archiveEntryRegistry = ConcurrentHashMap<String, ArchiveEntryTarget>()
    private val offlineStreams = ProxyStreamRegistry()
    private val archiveEntryCounter = AtomicInteger(1)

    @Volatile
    private var archiveProxyPort: Int = 0
    @Volatile private var archiveProxyServer: ServerSocket? = null

    private val archiveProxyLock = Any()

    companion object {
        private val isoChunkCaches = IsoChunkCaches()
        internal fun evictIsoChunks() = isoChunkCaches.evict()
        @Volatile private var bluRayProfile: ((String) -> BluRayProfile?)? = null
        internal fun bluRayProfile(url: String): BluRayProfile? = ArchiveProxyStreams.streamId(url)?.let { bluRayProfile?.invoke(it) }
        @Volatile private var isoMediaInfo: ((String) -> Map<String, Any>?)? = null
        internal fun isoMediaInfo(url: String): Map<String, Any>? = isoMediaInfo?.invoke(url)
        @Volatile private var saveMediaInfo: ((String, com.facebook.react.bridge.ReadableMap) -> Unit)? = null
        internal fun rememberIsoInfo(url: String, info: com.facebook.react.bridge.ReadableMap) {
            ArchiveProxyStreams.streamId(url)?.let { saveMediaInfo?.invoke(it, info) }
        }
        @Volatile private var externalIsoUrl: ((String, String?) -> String)? = null
        @Volatile private var externalPrefetch: ((String) -> Unit)? = null
        internal fun prefetchExternalLaunch(url: String) { externalPrefetch?.invoke(url) }
        @Volatile private var restoreOffline: ((String) -> String)? = null
        internal fun downloadUrl(url: String): String =
            if (ArchiveProxyStreams.streamId(url) != null && java.net.URI(url).rawQuery == "offline=m2ts")
                checkNotNull(restoreOffline) { "ISO resolver unavailable" }(url) else url
        internal fun externalPlayerUrl(url: String, player: String? = null): String = externalIsoUrl?.invoke(url, player) ?: url
        @Volatile private var archiveFormat: ((String) -> String?)? = null
        internal fun isIsoStream(url: String): Boolean =
            ArchiveProxyStreams.streamId(url)?.let { archiveFormat?.invoke(it) } == "ISO"
        internal fun stopArchiveStreams(reason: String, resumable: Boolean = false) {
            ArchiveProxyStreams.stopAll(reason)
            IsoRangeReader.cancelAll(reason)
            isoChunkCaches.clear()
            DvdProxy.stop()
            if (resumable) ArchiveProxyStreams.registry.resume()
            DebugLogger.log("ARCHIVE_PROXY_STOP_ALL", reason)
        }
        init {
            try {
                System.loadLibrary("gdcore")
                // Keep cookies isolated in sessionCookieHeader so React Native never clears them
            } catch (_: Throwable) {}
        }
    }

    override fun getName(): String = "SecurityVault"

    private fun appendDebugLog(tag: String, msg: String) = DebugLogger.log(tag, msg)

    @ReactMethod
    fun writeDebugLog(tag: String, msg: String) {
        appendDebugLog(tag, msg)
    }

    init {
        DebugLogger.initialize(reactContext)
        archiveFormat = { id -> archiveEntryRegistry[id]?.archiveFormat }
        externalIsoUrl = { url, player ->
            val entry = ArchiveProxyStreams.streamId(url)?.let { archiveEntryRegistry[it] }
            if (entry?.archiveFormat == "ISO" && entry.fileName.endsWith(".vob", true)) {
                val vlc = dvdVlcPlayer(player)
                val avc = ((vlc || player == null) && !entry.standalone && entry.dvdEncoder != null) ||
                    (player != null && dvdPlayerNeedsAvc(player) { DvdProxy.canDecodeMpeg2(entry.resolvedInfo) })
                check(!avc || entry.dvdEncoder != null) { "AVC conversion unavailable for this DVD. Open it in VLC." }
                externalDvdUrl(url, entry.id, entry.fileName, avc, muxed = vlc)
            } else if (entry == null) url else if (entry.archiveFormat == "ISO" && entry.isBdavM2ts &&
                entry.hls != null) {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.rawAuthority}/hls/${entry.id}/index.m3u8"
            } else externalIsoTsUrl(url, entry.fileName, entry.archiveFormat == "ISO" && entry.isBdavM2ts)
        }
        externalPrefetch = { url ->
            val entry = ArchiveProxyStreams.streamId(url)?.let { archiveEntryRegistry[it] }
            if (entry != null && entry.archiveFormat == "ISO" && entry.segments != null && entry.sourceSize > 0) {
                val warmSession = IsoReadSession()
                val registration = ArchiveProxyStreams.registry.register(entry.id, false, warmSession::cancel)
                ioExecutor.submit {
                    try {
                        val file = IsoVirtualFile(entry.segments, entry.isBdavM2ts)
                        val cache = isoChunkCaches.acquire(entry.archiveUrl, warmSession, entry.sourceSize)
                        val firstSegment = if (entry.standalone) entry.hls?.segments?.firstOrNull() else null
                        val length = minOf(file.size, firstSegment?.end ?: (32L * 1024 * 1024)).toInt()
                        val flights = cache.prefetch(file.mappedRanges(0L, length), entry.sourceSize, warmSession) { from, to, owner ->
                            if (entry.standalone) readStandalone(entry.archiveUrl, from, to, owner)
                            else fetchIsoByteRange(entry.archiveUrl, from, to, owner)
                        }
                        appendDebugLog("EXTERNAL_PREFETCH", "entry=${entry.id} bytes=$length")
                        flights.forEach { it.get() }
                    } catch (_: Exception) {
                        // Foreground playback retries any failed speculative block.
                    } finally {
                        warmSession.cancel()
                        registration.close()
                    }
                }
            }
        }
        restoreOffline = { url ->
            val uri = java.net.URI(url)
            val id = ArchiveProxyStreams.streamId(url)
            val port = ensureArchiveProxyRunning()
            val active = archiveEntryRegistry[id]
            val link = if (active?.isBdavM2ts == true) "http://127.0.0.1:$port${uri.rawPath}" else {
                val descriptor = uri.rawFragment.orEmpty()
                val source = java.net.URLDecoder.decode(descriptor.substringAfter(':'), "UTF-8")
                val size = descriptor.substringBefore(':').toLongOrNull()?.takeIf { it > 0 } ?: discSize(source, library = true)
                val restored = isoDiskCache.get(source, size)?.let { restoreArchiveFromDisk(it, source) }
                val item = if (restored != null) JSONArray(restored).getJSONObject(0) else
                    parseIsoDiscEntries(source, size, port, null).first.single()
                require(item.getString("name") == uri.path.substringAfterLast('/')) { "Resolved ISO movie changed" }
                item.getString("link")
            }
            "$link?offline=m2ts"
        }
        bluRayProfile = { id -> archiveEntryRegistry[id]?.bluRay }
        saveMediaInfo = { id, info ->
            archiveEntryRegistry[id]?.takeIf { it.archiveFormat == "ISO" && !it.standalone }?.let { entry ->
                try {
                    val json = JSONObject(info.toHashMap())
                    isoDiskCache.rememberInfo(entry.archiveUrl, entry.sourceSize, entry.fileName, json)
                    archiveEntryRegistry.replace(id, entry, entry.copy(resolvedInfo = discInfoMap(json)))
                } catch (error: Exception) { appendDebugLog("ISO_CACHE_WRITE", isoFailureDetails(error)) }
            }
        }
        isoMediaInfo = { url -> ArchiveProxyStreams.streamId(url)?.let { archiveEntryRegistry[it] }?.let { entry ->
            entry.resolvedInfo ?: entry.bluRay?.mediaInfo(entry.uncompressedSize)
        } }
        ioExecutor.submit {
            appendDebugLog("APP_BOOT", "GDStream Native Engine ready (SDK=" + Build.VERSION.SDK_INT + ")")
        }
    }

    @ReactMethod
    fun resolveStandaloneDisc(url: String, name: String, sizeHint: Double, originalLanguage: String, promise: Promise) {
        ioExecutor.submit {
            val inspection = IsoReadSession()
            try {
                val local = if (url.startsWith("file:")) java.io.File(requireNotNull(android.net.Uri.parse(url).path)) else null
                val size = local?.length() ?: sizeHint.toLong().takeIf { it > 0 } ?: discSize(url)
                val vob = name.endsWith(".vob", true) || URL(url).path.endsWith(".vob", true)
                val stride = if (vob) 2048 else 192
                val alignedEnd = size / stride * stride
                if (alignedEnd < stride * 3L) { promise.resolve(url); return@submit }
                val cache = isoChunkCaches.acquire(url, inspection, size)
                fun probe(from: Long, to: Long) = cache.readProbe(from, to, inspection) { first, last, owner ->
                    readStandalone(url, first, last, owner, cancelWithPlayback = false)
                }
                fun sample(window: Int): Pair<ByteArray, ByteArray> {
                    val length = minOf(alignedEnd, window.toLong() / stride * stride)
                    fun read(from: Long) = probe(from, from + length - 1)
                    if (alignedEnd == length) return read(0).let { it to it }
                    val tail = ioExecutor.submit<ByteArray> { read(alignedEnd - length) }
                    val head = read(0)
                    return head to try { tail.get() } catch (error: java.util.concurrent.ExecutionException) {
                        if (vob || !StandaloneBdav.matches(head)) throw error
                        ByteArray(0)
                    }
                }
                if (vob) {
                    val (head, tail) = sample(512 * 1024)
                    val disc = DvdProxy.standalone(head, tail, size, name)
                    val fileName = if (name.endsWith(".vob", true)) name else "$name.vob"
                    val item = registerIsoStreamTarget(url, size, fileName, size, listOf(0L to size),
                        ensureArchiveProxyRunning(), "video/mpeg", dvdInfo = disc?.info,
                        standalone = true, dvdSegments = disc?.segments.orEmpty())
                    promise.resolve(item.getString("link"))
                    return@submit
                }
                val (head, tail) = sample(65536)
                if (!StandaloneBdav.matches(head)) { promise.resolve(url); return@submit }
                var profile = StandaloneBdav.profile(head, tail, name, alignedEnd, originalLanguage)
                if (profile.durationUs == 0L || profile.pcrIndex == null || profile.streams.isEmpty() ||
                    profile.streams.any { it.audio && it.layout.isEmpty() } || profile.mainAvcPid != null && profile.avc == null) {
                    try {
                        val (largerHead, largerTail) = sample(2 * 1024 * 1024)
                        profile = StandaloneBdav.profile(largerHead, largerTail, name, alignedEnd, originalLanguage)
                    } catch (error: Exception) {
                        if (profile.streams.isEmpty()) throw error
                        appendDebugLog("BDAV_PROBE", "Using first PMT after optional probe failed: ${isoFailureDetails(error)}")
                    }
                }
                // A high-bitrate opening GOP can put its first IDR beyond both probes.
                // Cache its parameter sets at resolution so even the first request can be a seek.
                val avcPid = profile.mainAvcPid
                if (avcPid != null && profile.avc == null && alignedEnd > 2L * 1024 * 1024 / 192 * 192) {
                    val length = minOf(alignedEnd, StandaloneBdav.HLS_TARGET_PACKETS * 192L)
                    try {
                        val opening = probe(0, length - 1)
                        profile.avc = StandaloneBdav.avcConfig(StandaloneBdav.transportPackets(opening), avcPid)
                    } catch (error: java.io.IOException) {
                        appendDebugLog("BDAV_PROBE", "Opening AVC probe failed: ${isoFailureDetails(error)}")
                    }
                }
                // The head and tail bracket fifteen interior PCR samples on the BDAV packet grid.
                profile.pcrIndex?.let { index ->
                    val length = 64 * 1024
                    if (alignedEnd >= 16L * length) (1 until 16).map { point ->
                        val from = alignedEnd / 192 * point / 16 * 192
                        pcrProbes.submit {
                            try {
                                // A 64 KiB probe can fall between PCR packets at high bitrates.
                                val strideBytes = length / 192 * 192L
                                val limit = minOf(alignedEnd, from + 8 * strideBytes, alignedEnd / 192 * (point + 1) / 16 * 192)
                                var offset = from
                                while (offset < limit) {
                                    val end = minOf(offset + length, alignedEnd) - 1
                                    val bytes = probe(offset, end)
                                    if (index.sample(bytes, 192, offset / 192 * 188) != null) break
                                    offset += strideBytes
                                }
                            } catch (e: java.io.IOException) { appendDebugLog("BDAV_PCR_PROBE", isoFailureDetails(e)) }
                        }
                    }.forEach { it.get() }
                }
                val fileName = if (name.endsWith(".m2ts", true)) name else "$name.m2ts"
                val item = registerIsoStreamTarget(url, size, fileName, size, listOf(0L to size),
                    ensureArchiveProxyRunning(), "video/mp2t", bluRay = profile, standalone = true)
                promise.resolve(item.getString("link"))
            } catch (error: Exception) { promise.reject("E_BDAV_PROBE", error) }
            finally { inspection.cancel() }
        }
    }

    @ReactMethod
    fun dvdPlaybackUrl(url: String, promise: Promise) {
        ioExecutor.submit {
            try {
                val entry = ArchiveProxyStreams.streamId(url)?.let { archiveEntryRegistry[it] }
                if (entry == null || !entry.fileName.endsWith(".vob", true)) promise.resolve(url)
                else if (entry.dvdEncoder != null) promise.resolve("http://127.0.0.1:${ensureArchiveProxyRunning()}/hls/${entry.id}/dvd.m3u8")
                else if (DvdProxy.canDecodeMpeg2(entry.resolvedInfo)) promise.resolve(url)
                else promise.reject("DVD_MPEG2_UNSUPPORTED", "DVD decoder unavailable")
            } catch (error: Exception) { promise.reject("DVD_MPEG2_UNSUPPORTED", error) }
        }
    }

    private fun dvdTitle(url: String) = ArchiveProxyStreams.streamId(url)?.let { archiveEntryRegistry[it] }
        ?.takeIf { it.archiveFormat == "ISO" && !it.standalone && it.fileName.endsWith(".vob", true) }

    @ReactMethod
    fun dvdSubtitleTracks(url: String, promise: Promise) {
        val tracks = dvdTitle(url)?.dvdHls?.tracks?.subtitles.orEmpty()
        promise.resolve(Arguments.fromList(tracks.map { bluRayLanguage(it.language) }))
    }

    @ReactMethod
    fun dvdSubtitleWindow(url: String, track: Int, timeUs: Double, promise: Promise) {
        ioExecutor.execute {
            try {
                val entry = dvdTitle(url)
                val window = if (entry == null || track !in entry.dvdHls.tracks.subtitles.indices) null
                    else if (java.net.URI(url).rawPath.startsWith("/hls/")) dvdSubtitles.at(entry.id, timeUs.toLong())
                    else rawDvdSubtitles(entry, track, timeUs.toLong())
                promise.resolve(window?.let { Arguments.makeNativeMap(it.render(track)) })
            } catch (error: Exception) { promise.reject("DVD_SUBTITLE", error) }
        }
    }

    private fun rawDvdSubtitles(entry: ArchiveEntryTarget, track: Int, timeUs: Long): DvdSubtitleWindow? {
        val starts = entry.dvdStartsUs
        if (starts.size < 2 || timeUs !in 0L until starts.last() || entry.sourceSize <= 0) return null
        val previous = entry.rawSubtitle?.takeIf { it.first == track }?.second
        if (previous != null && timeUs in previous.startUs until previous.endUs) return previous
        val search = starts.binarySearch(timeUs)
        val index = if (search < 0) -search - 2 else search
        val owner = IsoReadSession()
        val lease = ArchiveProxyStreams.registry.register(entry.id, false, owner::cancel)
        try {
            val file = IsoVirtualFile(checkNotNull(entry.segments), false)
            val cache = isoChunkCaches.acquire(entry.archiveUrl, owner, entry.sourceSize)
            val height = (entry.resolvedInfo?.get("height") as? Number)?.toInt()
            return DvdProxy.rawSubtitleWindow(entry.dvdPlaylist, starts, index, entry.dvdHls.tracks, track,
                if (height == 576 || height == 288) 576 else 480, previous) { start, length ->
                file.readRaw(start, length) { from, to ->
                    cache.read(from, to, 0, entry.sourceSize - 1, owner, borrowFullBlock = true) { a, b, session ->
                        fetchIsoByteRange(entry.archiveUrl, a, b, session)
                    }
                }
            }.also { entry.rawSubtitle = track to it }
        } finally { owner.cancel(); lease.close() }
    }

    private val dvdSubtitles = DvdSubtitleWindows()
    private val dvdChunks = DvdSegmentCache()

    /** Timeline gestures can precede the first HLS request (or seek within a player's buffer).
     * Read the physical blocks for the destination concurrently and retire obsolete speculative I/O. */
    @ReactMethod
    fun seekIsoPlayback(url: String, seconds: Double) {
        val entry = ArchiveProxyStreams.streamId(url)?.let { archiveEntryRegistry[it] } ?: return
        if (entry.archiveFormat != "ISO" || entry.standalone || entry.sourceSize <= 0 || entry.segments == null) return
        val dvd = entry.fileName.endsWith(".vob", true)
        val starts = if (dvd) entry.dvdStartsUs else entry.bluRayStartsUs
        if (starts.size < 2 || !seconds.isFinite()) return
        val result = starts.binarySearch((seconds * 1_000_000).toLong())
        val index = (if (result < 0) -result - 2 else result).coerceIn(0, starts.lastIndex - 1)
        if (dvd) dvdChunks.cancelPrefetch(entry.id)
        val file = IsoVirtualFile(entry.segments, entry.isBdavM2ts)
        val start = if (dvd) entry.dvdPlaylist[index].start else entry.hls!!.segments[index].start
        val owner = IsoReadSession()
        val registration = try { ArchiveProxyStreams.registry.register(entry.id, false, owner::cancel) }
            catch (_: java.io.InterruptedIOException) { return }
        try {
            val cache = isoChunkCaches.acquire(entry.archiveUrl, owner, entry.sourceSize)
            val flights = cache.prefetchSeek(file, start, entry.sourceSize, owner) { from, to, session ->
                fetchIsoByteRange(entry.archiveUrl, from, to, session)
            }
            ioExecutor.execute {
                try { flights.forEach { it.get() } } catch (_: Exception) {
                    // A subsequent demand read can retry an interrupted speculative fetch.
                } finally { registration.close(); owner.cancel() }
            }
        } catch (error: Exception) {
            registration.close()
            owner.cancel()
            appendDebugLog("ISO_SEEK_PREFETCH", isoFailureDetails(error))
        }
    }

    @ReactMethod
    fun prepareArchivePlayback(url: String, promise: Promise) {
        ioExecutor.submit {
            ArchiveProxyStreams.prepare(url)
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun stopArchivePlayback(url: String, promise: Promise) {
        ArchiveProxyStreams.stop(url)
        ArchiveProxyStreams.streamId(url)?.let { id ->
            DvdProxy.stop(id)
            isoChunkCaches.evict()
        }
        promise.resolve(true)
    }

    private external fun nSync(slot: Int, sigSeed: Int): String

    private fun buildPinnedSystemSslFactory(): SSLSocketFactory {
        cachedSslFactory?.let { return it }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val defaultTm = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            ?: throw CertificateException("No default X509TrustManager")

        val androidCaStore = try {
            KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        } catch (_: Throwable) {
            null
        }

        val strictTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTm.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty cert chain")
                defaultTm.checkServerTrusted(chain, authType)

                for (cert in chain) {
                    cert.checkValidity()
                    val issuer = cert.issuerDN.name.uppercase(Locale.ROOT)
                    if (issuer.contains("HTTP CANARY") ||
                        issuer.contains("HTTPCANARY") ||
                        issuer.contains("REQABLE") ||
                        issuer.contains("PORTSWIGGER") ||
                        issuer.contains("BURP") ||
                        issuer.contains("CHARLES") ||
                        issuer.contains("MITMPROXY") ||
                        issuer.contains(" FIDDLER")
                    ) {
                        throw CertificateException("MITM proxy certificate rejected")
                    }

                    if (androidCaStore != null) {
                        val alias = try { androidCaStore.getCertificateAlias(cert) } catch (_: Throwable) { null }
                        if (alias != null && alias.startsWith("user:", ignoreCase = true)) {
                            throw CertificateException("User-installed MITM CA rejected")
                        }
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(strictTm), SecureRandom())
        val factory = sslContext.socketFactory
        cachedSslFactory = factory
        return factory
    }

    private fun isSnifferPackageInstalled(): Boolean {
        val blockedPackages = arrayOf(
            "com.reqable.android",
            "com.guoshi.httpcanary",
            "com.guoshi.httpcanary.premium",
            "com.emanuelef.remote_capture",
            "app.greyshirts.sslcapture",
            "com.minhui.networkcapture",
            "org.sandrob.drony",
            "de.robv.android.xposed.installer"
        )
        val pm = reactContext.packageManager
        for (pkg in blockedPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun verifyCertificateAndEnv(): Int {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return 0

            val proxyHost = System.getProperty("http.proxyHost")
            val proxyPort = System.getProperty("http.proxyPort")
            if (!proxyHost.isNullOrBlank() || (!proxyPort.isNullOrBlank() && proxyPort != "-1")) {
                return 0
            }

            if (isSnifferPackageInstalled()) return 0

            val tokens = arrayOf(
                String(charArrayOf('f', 'r', 'i', 'd', 'a')),
                String(charArrayOf('x', 'p', 'o', 's', 'e', 'd')),
                String(charArrayOf('s', 'u', 'b', 's', 't', 'r', 'a', 't', 'e'))
            )
            BufferedReader(FileReader("/proc/self/maps")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lower = line!!.lowercase(Locale.ROOT)
                    if (lower.contains(tokens[0]) || lower.contains(tokens[1]) || lower.contains(tokens[2])) {
                        return 0
                    }
                }
            }

            val pkgName = reactContext.packageName
            if (pkgName != "com.gdstream") return 0

            val pm = reactContext.packageManager
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                val sigs = info.signingInfo?.apkContentsSigners
                if (!sigs.isNullOrEmpty()) sigs[0].toByteArray() else null
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
                val sigs = info.signatures
                if (!sigs.isNullOrEmpty()) sigs[0].toByteArray() else null
            }

            if (certBytes != null) {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                val subject = cert.subjectDN.name.uppercase(Locale.ROOT)
                if (!subject.contains("GDSTREAM") && !subject.contains("ANDROID DEBUG")) {
                    return 0
                }
            }

            return 0x5A3C9E71
        } catch (_: Throwable) {
            return 0
        }
    }

    private fun executePinnedPost(
        urlStr: String,
        bodyBytes: ByteArray,
        contentType: String,
        shieldToken: String,
        session: IsoReadSession? = null
    ): String {
        session?.checkActive()
        val url = URL(urlStr)
        val conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = if (session != null) 10000 else 12000
            readTimeout = if (session != null) 10000 else 15000
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = session != null && contentType == "application/json"
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("User-Agent", "GDStream-Native-Engine/3.0")
            setRequestProperty("X-GD-Shield", shieldToken)
            if (sessionCookieHeader.isNotEmpty()) {
                setRequestProperty("Cookie", sessionCookieHeader)
            }
            if (this is HttpsURLConnection) {
                sslSocketFactory = buildPinnedSystemSslFactory()
                hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            }
        }

        try {
            session?.track(conn)
            session?.checkActive()
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }

            val setCookies = conn.headerFields["Set-Cookie"] ?: conn.headerFields["set-cookie"]
            if (!setCookies.isNullOrEmpty()) {
                val cookieParts = setCookies.mapNotNull { it.substringBefore(";").trim().takeIf { s -> s.isNotEmpty() } }
                if (cookieParts.isNotEmpty()) {
                    sessionCookieHeader = cookieParts.joinToString("; ")
                }
            }

            val code = conn.responseCode
            if (session != null && (code == 429 || code >= 500)) throw java.io.IOException("Directory HTTP $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return ""
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            if (session == null) conn.disconnect() else session.disconnect(conn)
        }
    }

    private fun performNativeLogin(baseUrl: String, seed: Int, shieldToken: String, session: IsoReadSession? = null) {
        val user = nSync(2, seed)
        val pass = nSync(3, seed)
        if (user.isEmpty() || pass.isEmpty()) throw SecurityException("Sync check failed")
        cachedShieldToken = shieldToken
        val formBody = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
        executePinnedPost(
            "$baseUrl/login",
            formBody.toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded",
            shieldToken, session
        )
        nativeSessionLoggedIn = true
    }

    private val directoryReads = ConcurrentHashMap<String, IsoReadSession>()

    @ReactMethod
    fun cancelDirectoryRequest(id: String) {
        directoryReads.remove(id)?.let { session -> ioExecutor.execute { session.cancel("directory timeout") } }
    }

    @ReactMethod
    fun securePostDirectory(path: String, pageIndex: Int, id: String, promise: Promise) {
        val session = IsoReadSession()
        directoryReads[id] = session
        ioExecutor.submit {
            try {
                val seed = verifyCertificateAndEnv()
                if (seed != 0x5A3C9E71) {
                    promise.reject("E_SEC", "Security shield active")
                    return@submit
                }

                val baseUrl = nSync(1, seed)
                val shieldToken = nSync(5, seed)
                if (baseUrl.isEmpty() || shieldToken.isEmpty()) {
                    promise.reject("E_SEC", "Security shield active")
                    return@submit
                }
                cachedShieldToken = shieldToken

                if (!nativeSessionLoggedIn) {
                    performNativeLogin(baseUrl, seed, shieldToken, session)
                }

                val jsonPayload = "{\"page_index\":$pageIndex}".toByteArray(Charsets.UTF_8)
                var responseText = executePinnedPost(
                    "$baseUrl$path",
                    jsonPayload,
                    "application/json",
                    shieldToken, session
                )

                if (!responseText.contains("\"files\"")) {
                    performNativeLogin(baseUrl, seed, shieldToken, session)
                    responseText = executePinnedPost(
                        "$baseUrl$path",
                        jsonPayload,
                        "application/json",
                        shieldToken, session
                    )
                }

                try {
                    val rootObj = JSONObject(responseText)
                    val dataObj = rootObj.optJSONObject("data")
                    val filesArr = dataObj?.optJSONArray("files")
                    var nextToken = rootObj.optString("nextPageToken", "").takeIf { it.isNotEmpty() && it != "null" }
                        ?: dataObj?.optString("nextPageToken", "")?.takeIf { it.isNotEmpty() && it != "null" }
                        ?: ""

                    var curPage = pageIndex + 1
                    while (filesArr != null && nextToken.isNotEmpty() && curPage < 25) {
                        val nextPayloadObj = JSONObject().apply {
                            put("page_index", curPage)
                            put("page_token", nextToken)
                        }
                        val nextRespText = executePinnedPost(
                            "$baseUrl$path",
                            nextPayloadObj.toString().toByteArray(Charsets.UTF_8),
                            "application/json",
                            shieldToken, session
                        )
                        if (!nextRespText.contains("\"files\"")) throw java.io.IOException("Invalid directory page")
                        val nextRoot = JSONObject(nextRespText)
                        val nextData = nextRoot.optJSONObject("data")
                        val nextFiles = nextData?.optJSONArray("files") ?: throw java.io.IOException("Invalid directory listing")
                        if (nextFiles.length() == 0) break
                        for (i in 0 until nextFiles.length()) {
                            filesArr.put(nextFiles.get(i))
                        }
                        nextToken = nextRoot.optString("nextPageToken", "").takeIf { it.isNotEmpty() && it != "null" }
                            ?: nextData.optString("nextPageToken", "").takeIf { it.isNotEmpty() && it != "null" }
                            ?: ""
                        curPage++
                    }
                    promise.resolve(rootObj.toString())
                    return@submit
                } catch (_: org.json.JSONException) { throw java.io.IOException("Invalid directory response") }

            } catch (e: Exception) {
                promise.reject("E_NET", e.message ?: "Network error")
            } finally { directoryReads.remove(id, session) }
        }
    }

    @ReactMethod
    fun getVaultConfig(promise: Promise) {
        try {
            val seed = verifyCertificateAndEnv()
            val map = Arguments.createMap()
            val baseUrl = if (seed == 0x5A3C9E71) nSync(1, seed) else ""
            if (seed != 0x5A3C9E71 || baseUrl.isEmpty()) {
                map.putBoolean("s", true)
                map.putString("a", "")
                map.putString("b", "")
                map.putString("c", "")
                map.putString("d", "")
                promise.resolve(map)
                return
            }

            cachedShieldToken = nSync(5, seed)
            map.putBoolean("s", false)
            map.putString("a", baseUrl)
            map.putString("b", "")
            map.putString("c", "")
            map.putString("d", nSync(4, seed))
            promise.resolve(map)
        } catch (_: Throwable) {
            promise.reject("E_SYNC", "Calibration error")
        }
    }

    // =========================================================================
    // 📦 NATIVE ZIP / ZIP64 + RAR4 / RAR5 INSPECTOR & 127.0.0.1 STREAMING PROXY
    // =========================================================================

    private fun readUInt16LE(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long {
        return (b[off].toLong() and 0xFFL) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readInt64LE(b: ByteArray, off: Int): Long {
        return (b[off].toLong() and 0xFFL) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24) or
            ((b[off + 4].toLong() and 0xFFL) shl 32) or
            ((b[off + 5].toLong() and 0xFFL) shl 40) or
            ((b[off + 6].toLong() and 0xFFL) shl 48) or
            ((b[off + 7].toLong() and 0xFFL) shl 56)
    }

    // Reads a RAR 5.0 variable-length integer (vint), returning Pair(value, bytesConsumed)
    private fun readRar5Vint(b: ByteArray, startOff: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var count = 0
        var idx = startOff
        while (idx < b.size && count < 10) {
            val byteVal = b[idx].toInt() and 0xFF
            result = result or ((byteVal and 0x7F).toLong() shl shift)
            count++
            idx++
            if ((byteVal and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, count)
    }

    private fun ensureVaultSessionReady() {
        val seed = verifyCertificateAndEnv()
        if (seed == 0x5A3C9E71) {
            val baseUrl = nSync(1, seed)
            val shieldToken = nSync(5, seed)
            if (shieldToken.isNotEmpty()) cachedShieldToken = shieldToken
            if ((!nativeSessionLoggedIn || sessionCookieHeader.isEmpty()) && baseUrl.isNotEmpty() && shieldToken.isNotEmpty()) {
                try { performNativeLogin(baseUrl, seed, shieldToken) } catch (_: Throwable) {}
            }
        }
    }

    private val rangeConnections by lazy {
        RangeConnections(
            headers = { mapOf("User-Agent" to "GDStream-Native-Engine/3.0", "Cookie" to sessionCookieHeader, "X-GD-Shield" to cachedShieldToken) },
            configure = { conn ->
                if (conn is HttpsURLConnection) {
                    conn.sslSocketFactory = buildPinnedSystemSslFactory()
                    conn.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                }
            },
            receiveCookies = { updated ->
                synchronized(this) {
                    val merged = sessionCookieHeader.split(';').map { it.trim() }.filter { '=' in it }
                        .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
                    updated.forEach {
                        val pair = it.substringBefore(';')
                        if ('=' in pair) merged[pair.substringBefore('=').trim()] = pair.substringAfter('=')
                    }
                    sessionCookieHeader = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
                }
            }
        )
    }

    private fun openRangeConnectionInternal(urlStr: String, rangeHeader: String?): HttpURLConnection =
        rangeConnections.open(urlStr, rangeHeader)

    private fun openRangeConnection(urlStr: String, rangeHeader: String?): HttpURLConnection {
        ensureVaultSessionReady()
        var conn = openRangeConnectionInternal(urlStr, rangeHeader)
        val cType = (conn.contentType ?: "").lowercase(Locale.ROOT)
        var code = conn.responseCode
        if (expiredDownloadStatus(code)) return conn
        // Renew the library session if the worker serves a login page.
        if (code == 401 || cType.contains("text/html")) {
            conn.disconnect()
            nativeSessionLoggedIn = false
            ensureVaultSessionReady()
            conn = openRangeConnectionInternal(urlStr, rangeHeader)
            code = conn.responseCode
        }
        // If cold Worker isolate returned 5xx on direct file path, warm parent folder cache and retry once!
        if (code in 500..504) {
            conn.disconnect()
            try {
                val seed = verifyCertificateAndEnv()
                val baseUrl = if (seed == 0x5A3C9E71) nSync(1, seed) else ""
                if (baseUrl.isNotEmpty() && urlStr.startsWith(baseUrl) && cachedShieldToken.isNotEmpty()) {
                    val relPath = urlStr.removePrefix(baseUrl).substringBefore("?")
                    val parentDir = relPath.substringBeforeLast('/', "/0:") + "/"
                    executePinnedPost(
                        baseUrl + parentDir,
                        "{\"page_index\":0}".toByteArray(Charsets.UTF_8),
                        "application/json",
                        cachedShieldToken
                    )
                }
                Thread.sleep(250L)
            } catch (_: Throwable) {}
            conn = openRangeConnectionInternal(urlStr, rangeHeader)
        }
        return conn
    }

    private fun readExactBytes(stream: InputStream, maxLen: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        while (total < maxLen) {
            val toRead = minOf(buf.size, maxLen - total)
            val r = stream.read(buf, 0, toRead)
            if (r <= 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    private fun fetchRemoteByteRange(archiveUrl: String, start: Long, endInclusive: Long): ByteArray {
        val len = (endInclusive - start + 1L).toInt()
        if (len <= 0) return ByteArray(0)
        val conn = openRangeConnection(archiveUrl, "bytes=$start-$endInclusive")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP " + code + " on range " + start + "-" + endInclusive)
            }
            return conn.inputStream.use { stream ->
                if (code == 200 && start > 0L) {
                    var skipLeft = start
                    val skipBuf = ByteArray(16384)
                    while (skipLeft > 0L) {
                        val s = stream.read(skipBuf, 0, minOf(skipBuf.size.toLong(), skipLeft).toInt())
                        if (s <= 0) break
                        skipLeft -= s.toLong()
                    }
                }
                readExactBytes(stream, len)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openLibraryIsoRange(url: String, from: Long, to: Long, session: IsoReadSession): HttpURLConnection {
        session.checkActive()
        ensureVaultSessionReady()
        var conn = rangeConnections.openIsoRange(url, from, to, session)
        if (conn.responseCode == 401) {
            session.log("ISO_UPSTREAM_AUTH", "HTTP=401 physical=$from-$to; refreshing session and source redirect once")
            session.disconnect(conn)
            nativeSessionLoggedIn = false
            ensureVaultSessionReady()
            conn = rangeConnections.openIsoRange(url, from, to, session)
        }
        return conn
    }

    private fun fetchIsoByteRange(isoUrl: String, start: Long, endInclusive: Long,
                                  session: IsoReadSession = IsoReadSession(), cancelWithPlayback: Boolean = true): ByteArray =
        IsoRangeReader.readFully(isoUrl, start, endInclusive, open = { from, to ->
            if (cancelWithPlayback) ArchiveProxyStreams.registry.checkActive()
            openLibraryIsoRange(isoUrl, from, to, session)
        }, session = session, cancelWithPlayback = cancelWithPlayback)

    private val publicRanges = RangeConnections({ emptyMap() })
    private fun librarySource(url: String): Boolean {
        val seed = verifyCertificateAndEnv()
        if (seed != 0x5A3C9E71) return false
        val base = java.net.URI(nSync(1, seed))
        val source = URL(url)
        return base.scheme.equals(source.protocol, true) && base.rawAuthority.equals(source.authority, true)
    }

    private fun discSize(url: String, library: Boolean = librarySource(url)): Long {
        val session = IsoReadSession()
        var size: Long? = null
        try {
            IsoRangeReader.readFully(url, 0, 1, open = { from, to ->
                val conn = if (library) openLibraryIsoRange(url, from, to, session)
                    else publicRanges.openIsoRange(url, from, to, session)
                size = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                conn
            }, session = session, cancelWithPlayback = false)
            return checkNotNull(size) // readFully validated the 206 and its Content-Range.
        } finally { session.cancel() }
    }

    private fun readStandalone(url: String, start: Long, end: Long, session: IsoReadSession,
                               cancelWithPlayback: Boolean = true): ByteArray {
        if (url.startsWith("file:")) return java.io.RandomAccessFile(java.io.File(requireNotNull(android.net.Uri.parse(url).path)), "r").use { file ->
            session.checkActive()
            file.seek(start)
            ByteArray(Math.toIntExact(end - start + 1)).also(file::readFully)
        }
        if (librarySource(url)) return fetchIsoByteRange(url, start, end, session, cancelWithPlayback)
        return IsoRangeReader.readFully(url, start, end, open = { from, to ->
            publicRanges.openIsoRange(url, from, to, session)
        }, session = session, cancelWithPlayback = cancelWithPlayback)
    }

    private fun detectRemoteFileSize(archiveUrl: String): Long {
        val conn = openRangeConnection(archiveUrl, "bytes=0-1")
        try {
            if (expiredDownloadStatus(conn.responseCode)) throw java.io.IOException("Source size probe HTTP ${conn.responseCode}")
            val contentRange = conn.getHeaderField("Content-Range") ?: conn.getHeaderField("content-range") ?: ""
            if (contentRange.contains("/")) {
                val slashVal = contentRange.substringAfterLast("/").trim()
                val parsed = slashVal.toLongOrNull()
                if (parsed != null && parsed > 0L) return parsed
            }
            return conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveEntryDataStartOffset(entry: ArchiveEntryTarget): Long {
        if (entry.dataStartOffset >= 0L) return entry.dataStartOffset
        try {
            val headerBytes = fetchRemoteByteRange(
                entry.archiveUrl,
                entry.localHeaderOffset,
                entry.localHeaderOffset + 63L
            )
            if (headerBytes.size >= 30 &&
                headerBytes[0] == 0x50.toByte() &&
                headerBytes[1] == 0x4b.toByte() &&
                headerBytes[2] == 0x03.toByte() &&
                headerBytes[3] == 0x04.toByte()
            ) {
                val nameLen = readUInt16LE(headerBytes, 26)
                val extraLen = readUInt16LE(headerBytes, 28)
                val computed = entry.localHeaderOffset + 30L + nameLen.toLong() + extraLen.toLong()
                entry.dataStartOffset = computed
                return computed
            }
        } catch (_: Throwable) {}
        // Fallback if local header probe had any transient issue
        val fallbackOffset = entry.localHeaderOffset + 30L + entry.fileName.toByteArray(StandardCharsets.UTF_8).size.toLong()
        entry.dataStartOffset = fallbackOffset
        return fallbackOffset
    }

    private fun ensureArchiveProxyRunning(): Int {
        synchronized(archiveProxyLock) {
            archiveProxyServer?.takeUnless { it.isClosed }?.let { return it.localPort }
            val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            archiveProxyServer = server
            archiveProxyPort = server.localPort
            appendDebugLog("ARCHIVE_PROXY_START", "Listening on 127.0.0.1:$archiveProxyPort")
            Thread {
                try {
                    while (!server.isClosed) {
                        val client = server.accept()
                        ioExecutor.submit { handleArchiveProxyClient(client) }
                    }
                } catch (error: Exception) {
                    appendDebugLog("ARCHIVE_PROXY_SERVER_ERR", isoFailureDetails(error))
                } finally {
                    try { server.close() } catch (_: java.io.IOException) { }
                    synchronized(archiveProxyLock) {
                        if (archiveProxyServer === server) { archiveProxyServer = null; archiveProxyPort = 0 }
                    }
                    appendDebugLog("ARCHIVE_PROXY_STOP", "Listener closed port=${server.localPort}")
                }
            }.apply { isDaemon = true; name = "GDArchiveProxy" }.start()
            return archiveProxyPort
        }
    }

    // Neutralizes MKV SeekHead Cues (1C 53 BB 6B) & Tags (12 54 C3 67) pointers in the first 8KB of a compressed ZIP stream
    // so ExoPlayer's MatroskaExtractor NEVER aborts playback to seek to the end of a multi-GB compressed archive!
    private fun neutralizeMkvTailSeekPointers(buf: ByteArray, len: Int) {
        val limit = len - 7
        var i = 0
        while (i < limit) {
            if (buf[i] == 0x53.toByte() && buf[i + 1] == 0xAB.toByte() && buf[i + 2] == 0x84.toByte()) {
                val b0 = buf[i + 3]
                val b1 = buf[i + 4]
                val b2 = buf[i + 5]
                val b3 = buf[i + 6]
                val isCues = (b0 == 0x1C.toByte() && b1 == 0x53.toByte() && b2 == 0xBB.toByte() && b3 == 0x6B.toByte())
                val isTags = (b0 == 0x12.toByte() && b1 == 0x54.toByte() && b2 == 0xC3.toByte() && b3 == 0x67.toByte())
                if (isCues || isTags) {
                    buf[i + 3] = 0x53.toByte()
                    buf[i + 4] = 0xAB.toByte()
                    buf[i + 5] = 0x00.toByte()
                    buf[i + 6] = 0x00.toByte()
                }
            }
            i++
        }
    }

    private fun proxyOutput(socket: Socket): java.io.OutputStream = object : java.io.OutputStream() {
        private val output = socket.getOutputStream()
        private val counter = HttpBodyCounter()
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            output.write(bytes, offset, length)
            ArchiveStreamService.bytesTransferred(counter.bodyBytes(bytes, offset, length).toLong())
        }
        override fun flush() = output.flush()
    }

    private class ProxyConnection(val socket: Socket) {
        val input = java.io.PushbackInputStream(socket.getInputStream())
        var reusable = false
    }
    private fun handleArchiveProxyClient(socket: Socket) {
        val connection = ProxyConnection(socket)
        try {
            do {
                connection.reusable = false
                handleArchiveProxyRequest(connection)
            } while (connection.reusable && !socket.isClosed)
        } finally { try { socket.close() } catch (_: java.io.IOException) {} }
    }

    private fun handleArchiveProxyRequest(connection: ProxyConnection) {
        val socket = connection.socket
        val remoteConnToClean = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        var inflaterToClean: Inflater? = null
        var requestDetails = "unparsed request"
        var isoFile: IsoVirtualFile? = null
        var isoSession: IsoReadSession? = null
        var clientWatch: IsoClientWatch? = null
        var registration: AutoCloseable? = null
        try {
            socket.soTimeout = 120000
            fun readClientLine(): String? = try {
                val line = java.io.ByteArrayOutputStream()
                while (true) {
                    val byte = connection.input.read()
                    if (byte < 0 || byte == 10) break
                    if (line.size() >= 8192) throw java.io.IOException("Proxy request line too long")
                    line.write(byte)
                }
                if (line.size() == 0) null else line.toString("US-ASCII").trimEnd('\r')
            } catch (e: java.io.IOException) {
                throw IsoClientDisconnected(e)
            }
            val requestLine = readClientLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val path = parts[1]

            var rangeHeader: String? = null
            var userAgent = "(absent)"
            var closeRequested = false
            while (true) {
                val line = readClientLine() ?: break
                if (line.isEmpty()) break
                if (line.equals("Connection: close", true)) closeRequested = true
                if (line.startsWith("User-Agent:", ignoreCase = true)) userAgent = line.substringAfter(":").trim().take(512)
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            appendDebugLog("ARCHIVE_PROXY_REQUEST", "method=$method path=${path.substringBefore('?')} range=${rangeHeader ?: "full"} userAgent=$userAgent")
            val segments = path.substringBefore('?').split("/").filter { it.isNotEmpty() }
            if (segments.size >= 2 && segments[0] == "yt") {
                registration = ArchiveProxyStreams.registry.register("yt:${segments[1]}", false) {
                    try { socket.close() } catch (_: java.io.IOException) { }
                }
                handleYoutubeProxyClient(socket, segments[1], method, rangeHeader)
                return
            }
            if (segments.size < 2 || segments[0] !in listOf("zip", "hls")) {
                val out = proxyOutput(socket)
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            requestDetails = "method=$method id=${segments[1]} requestedRange=${rangeHeader ?: "full"} userAgent=$userAgent"
            val entryId = segments[1]
            val entry = archiveEntryRegistry[entryId]
            val hls = if (segments[0] == "hls" && entry?.archiveFormat == "ISO" && entry.isBdavM2ts)
                entry.hls else null
            val resource = segments.getOrNull(2).orEmpty()
            val audioMatch = Regex("audio_([0-9]+)(?:\\.m3u8|_seg_[0-9]+\\.ts)").matchEntire(resource)
            val audioPid = audioMatch?.groupValues?.get(1)?.toIntOrNull()
            val hlsSegment = Regex("(?:audio_[0-9]+_)?seg_([0-9]+)\\.ts").matchEntire(resource)?.groupValues?.get(1)?.toIntOrNull()
            val dvdMuxed = path.substringAfter('?', "").split('&').any { it == "muxed=1" } ||
                isVlcUserAgent(userAgent)
            val dvdResource = if (segments[0] == "hls" && entry?.archiveFormat == "ISO" && entry.dvdPlaylist.isNotEmpty())
                entry.dvdHls.resource(resource, entry.dvdPlaylist.size, dvdMuxed) else null
            val dvd = dvdResource != null
            val dvdIndex = dvdResource?.index
            val validPath = if (dvd) true else if (segments[0] == "hls") hls != null &&
                (audioMatch == null || hls.audio.any { it.pid == audioPid }) &&
                (resource in listOf("index.m3u8", "video.m3u8") || audioPid != null && resource.endsWith(".m3u8") ||
                    hlsSegment != null && hlsSegment in hls.segments.indices)
                else entry != null && archiveFilenameMatches(resource, entry.fileName, entry.archiveFormat == "ISO" && entry.isBdavM2ts)
            if (entry == null || segments.size != 3 || !validPath) {
                appendDebugLog("ARCHIVE_PROXY_STATUS", "$requestDetails HTTP=404 filename or stream registration mismatch")
                val out = proxyOutput(socket)
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val session = IsoReadSession()
            isoSession = session
            val full = segments[0] == "zip" && method == "GET" && try {
                val requested = HttpByteRange.parse(rangeHeader, entry.uncompressedSize)
                requested.start == 0L && requested.end == entry.uncompressedSize - 1
            } catch (_: java.io.IOException) { false }
            val offline = entry.isBdavM2ts && segments[0] == "zip" && path.substringAfter('?', "") == "offline=m2ts"
            registration = (if (offline) offlineStreams else ArchiveProxyStreams.registry).register(entry.id, full) { reason ->
                try { socket.close() } catch (_: java.io.IOException) { }
                session.cancel(reason)
                remoteConnToClean.get()?.disconnect()
            }
            session.checkActive()
            val totalSize = entry.uncompressedSize
            val mimeType = when {
                entry.fileName.endsWith(".mp4", ignoreCase = true) ||
                entry.fileName.endsWith(".m4v", ignoreCase = true) -> "video/mp4"
                entry.fileName.endsWith(".m2ts", ignoreCase = true) ||
                entry.fileName.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
                entry.fileName.endsWith(".vob", ignoreCase = true) -> "video/mpeg"
                entry.fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
                else -> "video/x-matroska"
            }

            val out = BufferedOutputStream(proxyOutput(socket), 65536)
            if (entry.archiveFormat == "ISO") {
                clientWatch = IsoClientWatch(socket, session, ioExecutor, connection.input)
                session.log("ISO_PROXY_SOURCE", "$requestDetails url=${safeStreamUrl(entry.archiveUrl)}")
                val file = IsoVirtualFile(entry.segments ?: throw java.io.IOException("Missing ISO extent map"), entry.isBdavM2ts)
                isoFile = file
                if (file.rawSize != entry.compressedSize || file.size != totalSize) {
                    throw java.io.IOException("Inconsistent ISO stream length")
                }
                val externalTs = hls != null || entry.isBdavM2ts && segments.last().endsWith(".ts", true) &&
                    !userAgent.contains("GDStream", ignoreCase = true)
                val isoChunkCache = isoChunkCaches.acquire(entry.archiveUrl, session, entry.sourceSize)
                val streams = entry.bluRay?.streams.orEmpty()
                fun upstream(from: Long, to: Long, owner: IsoReadSession): ByteArray =
                    if (entry.standalone) readStandalone(entry.archiveUrl, from, to, owner)
                    else fetchIsoByteRange(entry.archiveUrl, from, to, owner)
                fun cachedFetch(start: Long, end: Long, owner: IsoReadSession): ByteArray {
                    val extent = if (entry.sourceSize > 0) 0L to entry.sourceSize else entry.segments!!.first { (offset, length) -> offset >= 0 && start >= offset && end < offset + length }
                    return isoChunkCache.read(start, end, extent.first, extent.first + extent.second - 1, owner,
                        borrowFullBlock = true, fetch = ::upstream)
                }
                fun fetch(start: Long, end: Long) = cachedFetch(start, end, session)
                fun rawTs(start: Long, length: Int) = file.read(start, length, ::fetch) { _, _ -> }
                val program by lazy {
                    entry.program { rawTs(0, minOf(file.size / 188, 16384).toInt() * 188) }
                }
                val renditionStreams by lazy { mergeBluRayStreams(streams + program.streams) }
                fun readTs(start: Long, length: Int): ByteArray {
                    if (!externalTs) return rawTs(start, length)
                    val windowStart = ((start / 188 - 64).coerceAtLeast(0)) * 188
                    val windowEnd = minOf(file.size, ((start + length + 187) / 188 + 64) * 188)
                    val bytes = rawTs(windowStart, (windowEnd - windowStart).toInt())
                    val videoOnly = hls != null && hls.audio.isNotEmpty() && audioPid == null
                    ExternalTsPmt.patch(bytes, if (videoOnly) renditionStreams else streams, videoOnly = videoOnly)
                    val offset = (start - windowStart).toInt()
                    return bytes.copyOfRange(offset, offset + length)
                }
                val complete = if (dvd) {
                    if (writeArchiveOptions(method, out, true)) return
                    val body = if (dvdIndex == null) entry.dvdHls.playlist(checkNotNull(dvdResource), entry.dvdPlaylist) else try {
                        val trackPid = dvdResource.pid
                        val muxed = dvdChunks.segment(entry.id, dvdIndex, entry.dvdPlaylist.lastIndex, session, method == "GET",
                            entry.dvdHls.multivariant, trackPid.takeUnless { it == entry.dvdHls.tracks.videoPid }, onSeek = {
                                if (!entry.standalone && method == "GET" && entry.sourceSize > 0)
                                    isoChunkCache.prefetchSeek(file, entry.dvdPlaylist[dvdIndex].start, entry.sourceSize, session, ::upstream)
                            }) { index, owner, sequence ->
                            fun read(from: Long, to: Long) = cachedFetch(from, to, owner)
                            val segment = entry.dvdPlaylist[index]
                            val estimate = entry.standalone || entry.dvdSegments.isEmpty()
                            fun cut(offset: Long): Long? {
                                if (!estimate) return offset
                                return DvdProxy.navBoundary(file, offset, ::read) ?: if (entry.standalone) null
                                    else throw java.io.IOException("Missing DVD navigation pack at title offset $offset")
                            }
                            val start = cut(segment.start) ?: segment.start
                            val nextVobu = cut(segment.end)
                            val data = file.readRaw(start, Math.toIntExact((nextVobu ?: segment.end) - start), ::read)
                            DvdProxy.transcode(data, checkNotNull(entry.resolvedInfo), checkNotNull(entry.dvdEncoder),
                                owner, entry.id, sequence, index, nextVobu != null, index == entry.dvdPlaylist.lastIndex,
                                segment.discontinuity, start, entry.dvdStartsUs[index], { first, end, cues ->
                                    if (entry.dvdHls.tracks.subtitles.isNotEmpty()) {
                                        dvdSubtitles.put(entry.id, index, DvdSubtitleWindow(first, end, cues))
                                    }
                                }, { pos, limit ->
                                    file.readRaw(pos, minOf(file.rawSize - pos, limit.toLong()).coerceAtLeast(0).toInt(), ::read)
                                }) {
                                entry.dvdSequenceHeader ?: DvdProxy.sequenceHeader(
                                    file.readRaw(0, minOf(file.size, 512L * 1024).toInt(), ::read)
                                )?.also { entry.dvdSequenceHeader = it }
                            }
                        }
                        if (trackPid == null) muxed else entry.dvdHls.rendition(muxed, trackPid, dvdIndex)
                    } catch (error: java.io.IOException) {
                        session.checkActive()
                        appendDebugLog("DVD_SEG_ERR", "seg=$dvdIndex ${isoFailureDetails(error)}")
                        out.write("HTTP/1.1 ${isoProxyErrorStatus(error)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    IsoVirtualFile(listOf(0L to body.size.toLong()), false).serve(method, rangeHeader,
                        if (dvdIndex == null) "application/vnd.apple.mpegurl" else "video/MP2T", out, ioExecutor, session) { start, length ->
                        body.copyOfRange(start.toInt(), start.toInt() + length)
                    }
                } else if (offline) {
                    val raw = IsoVirtualFile(entry.segments!!, false)
                    raw.serve(method, rangeHeader, "video/MP2T", out, ioExecutor, session, maxReadBytes = 192 * 4096) { start, length ->
                        ExternalTsPmt.readM2ts(start, length, raw.size, streams) { from, count ->
                            raw.readRaw(from, count) { first, last ->
                                fetchIsoByteRange(entry.archiveUrl, first, last, session, cancelWithPlayback = false)
                            }
                        }
                    }
                } else if (hls == null) {
                    val direct = entry.standalone && entry.isBdavM2ts
                    var first = true
                    var warmAt = 0L
                    file.serve(method, rangeHeader, "video/MP2T".takeIf { entry.isBdavM2ts } ?: mimeType,
                        out, ioExecutor, session, entry.bluRay?.durationUs ?: 0L,
                        maxReadBytes = if (direct) 188 * 320 else IsoChunkCache.BLOCK_BYTES,
                        firstReadBytes = if (direct) 188 * 8 else IsoChunkCache.BLOCK_BYTES,
                        pipelineDepth = if (direct) 1 else IsoRangeReader.MAX_CONCURRENT_READS) { start, length ->
                        if (!direct) return@serve readTs(start, length)
                        val target = start / 188 * 192
                        val block = IsoChunkCache.BLOCK_BYTES.toLong()
                        val seekHead = first && entry.directCursor.begin(session.id, start)
                        if (seekHead) {
                            val ranges = entry.directCursor.seekBlocks(target, entry.sourceSize)
                            appendDebugLog("M2TS_SEEK_PREFETCH", "targetByte=$start blocks=${ranges.map { it.first / block }}")
                            isoChunkCache.prefetch(ranges, entry.sourceSize, session, priority = true, fetch = ::upstream)
                        }
                        first = false
                        val data = readTs(start, length)
                        val next = (start + length) / 188 * 192
                        if (entry.directCursor.advance(session.id, start + length) && !seekHead && next >= warmAt) {
                            val window = entry.directCursor.playbackWindow(next, entry.sourceSize)
                            warmAt = window.first
                            if (!window.isEmpty()) isoChunkCache.prefetch(listOf(window), entry.sourceSize, session, fetch = ::upstream)
                        }
                        data
                    }
                } else if (hlsSegment == null) {
                    val playlist = if (resource == "index.m3u8") hls.master() else hls.playlist(audioPid)
                    IsoVirtualFile(listOf(0L to playlist.size.toLong()), false).serve(method, rangeHeader,
                        "application/vnd.apple.mpegurl", out, ioExecutor, session) { start, length ->
                        playlist.copyOfRange(start.toInt(), start.toInt() + length)
                    }
                } else {
                    val segment = hls.segments[hlsSegment]
                    if (entry.standalone && method == "GET" && entry.sourceSize > 0) {
                        val blocks = StandaloneBdav.readAhead(segment.start, entry.sourceSize)
                        val seek = audioPid == null && hls.videoSeek(hlsSegment)
                        val owner = IsoReadSession()
                        val lease = ArchiveProxyStreams.registry.register(entry.id, false, owner::cancel)
                        // Start all three physical reads before looking up PSI or probing a PES cut.
                        val flights = try {
                            isoChunkCache.prefetch(listOf(blocks), entry.sourceSize, owner, priority = seek, fetch = ::upstream)
                        } catch (error: Exception) {
                            lease.close()
                            owner.cancel()
                            throw error
                        }
                        ioExecutor.execute {
                            try { flights.forEach { it.get() } } catch (_: Exception) {
                                // The next demand read retries any failed fetch.
                            } finally {
                                lease.close()
                                owner.cancel()
                            }
                        }
                    } else if (method == "GET" && entry.sourceSize > 0) {
                        val seek = audioPid == null && hls.videoSeek(hlsSegment)
                        if (seek) isoChunkCache.prefetchSeek(file, segment.start, entry.sourceSize, session, ::upstream)
                        else {
                            val length = minOf(segment.end - segment.start, IsoRangeReader.MAX_CONCURRENT_READS * IsoChunkCache.BLOCK_BYTES.toLong())
                            isoChunkCache.prefetch(file.mappedRanges(segment.start, length.toInt()), entry.sourceSize, session, fetch = ::upstream)
                        }
                    }
                    val prefix = hls.prefix(program, renditionStreams, audioPid)
                    if (audioPid != null) {
                        val audioType = renditionStreams.firstOrNull { it.pid == audioPid }?.type ?: 0
                        val audio = if (hls.transcodes(audioPid)) {
                            StandaloneBdav.ac3Segment(segment, program.prefix(renditionStreams, audioPid, false),
                                prefix, audioPid, file.size, reactContext.cacheDir, session, ::rawTs)
                        } else segment.audioBytes(prefix, audioPid, audioType, read = ::rawTs)
                        IsoVirtualFile(listOf(0L to audio.size.toLong()), false).serve(method, rangeHeader, "video/MP2T",
                            out, ioExecutor, session) { start, length -> audio.copyOfRange(start.toInt(), start.toInt() + length) }
                    } else {
                        val (video, header) = entry.bluRay?.avc?.videoSegment(segment, prefix, file.size, ::rawTs)
                            ?: (segment to prefix)
                        val size = video.size(header)
                        IsoVirtualFile(listOf(0L to size), false).serve(method, rangeHeader, "video/MP2T",
                            out, ioExecutor, session, maxReadBytes = 188 * 4096) { start, length ->
                                video.read(header, start, length, ::readTs)
                            }
                    }
                }
                connection.reusable = complete && !closeRequested
                return
            }

            if (writeArchiveOptions(method, out)) return
            val range = try {
                HttpByteRange.parse(if (method == "HEAD") null else rangeHeader, totalSize)
            } catch (_: java.io.IOException) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalSize\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                return
            }
            val reqStart = range.start
            val reqEnd = range.end
            val contentLength = range.length
            val isPartial = range.partial
            if (method == "HEAD") {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: $mimeType\r\nAccept-Ranges: bytes\r\nContent-Length: $totalSize\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                return
            }

            val dataStart = resolveEntryDataStartOffset(entry)

            if (entry.compressionMethod == 0) {
                // STORE Mode (Uncompressed ZIP or Uncompressed RAR): Direct 1:1 Byte-Slice Range Streaming!
                val remoteStart = dataStart + reqStart
                val remoteEnd = dataStart + reqEnd
                val remoteConn = openRangeConnection(entry.archiveUrl, "bytes=$remoteStart-$remoteEnd")
                remoteConnToClean.set(remoteConn)
                val upstreamCode = remoteConn.responseCode
                if (upstreamCode !in 200..299) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    out.flush()
                    return
                }

                val headers = buildString {
                    append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mimeType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $contentLength\r\n")
                    if (isPartial) {
                        append("Content-Range: bytes $reqStart-$reqEnd/$totalSize\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val input = remoteConn.inputStream
                val buf = ByteArray(65536)
                if (upstreamCode == 200 && remoteStart > 0L) {
                    var skipLeft = remoteStart
                    while (skipLeft > 0L) {
                        val s = input.read(buf, 0, minOf(buf.size.toLong(), skipLeft).toInt())
                        if (s <= 0) break
                        skipLeft -= s.toLong()
                    }
                }
                var remaining = contentLength
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read.toLong()
                }
                out.flush()
            } else if (entry.archiveFormat == "ZIP" && entry.compressionMethod == 8) {
                // DEFLATE Mode (Compressed ZIP)
                if (reqStart > 16L * 1024L * 1024L) {
                    val rejectHdr = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalSize\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(rejectHdr.toByteArray(StandardCharsets.US_ASCII))
                    out.flush()
                    return
                }

                // If client only needs a small header range (e.g. MediaInfo 256KB), only fetch that slice from Google Drive!
                val neededEndByte = reqStart + contentLength
                val maxCompFetch = if (neededEndByte <= 2L * 1024L * 1024L) {
                    minOf(entry.compressedSize, neededEndByte + 262144L)
                } else {
                    entry.compressedSize
                }

                val remoteStart = dataStart
                val remoteEnd = dataStart + maxCompFetch - 1L
                val remoteConn = openRangeConnection(entry.archiveUrl, "bytes=$remoteStart-$remoteEnd")
                remoteConnToClean.set(remoteConn)
                val upstreamCode = remoteConn.responseCode
                if (upstreamCode !in 200..299) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    out.flush()
                    return
                }

                val inflater = Inflater(true)
                inflaterToClean = inflater

                val headers = buildString {
                    append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mimeType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $contentLength\r\n")
                    if (isPartial) {
                        append("Content-Range: bytes $reqStart-$reqEnd/$totalSize\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val infStream = InflaterInputStream(remoteConn.inputStream, inflater, 65536)
                val buf = ByteArray(65536)
                if (upstreamCode == 200 && remoteStart > 0L) {
                    var skipArchive = remoteStart
                    while (skipArchive > 0L) {
                        val s = remoteConn.inputStream.read(buf, 0, minOf(buf.size.toLong(), skipArchive).toInt())
                        if (s <= 0) break
                        skipArchive -= s.toLong()
                    }
                }
                var toSkip = reqStart
                while (toSkip > 0L) {
                    val step = minOf(buf.size.toLong(), toSkip).toInt()
                    val skipped = infStream.read(buf, 0, step)
                    if (skipped <= 0) break
                    toSkip -= skipped.toLong()
                }

                var remaining = contentLength
                var scannedBytes = 0L
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = infStream.read(buf, 0, toRead)
                    if (read <= 0) break
                    if (reqStart == 0L && scannedBytes < 65536L) {
                        neutralizeMkvTailSeekPointers(buf, read)
                        scannedBytes += read.toLong()
                    }
                    out.write(buf, 0, read)
                    remaining -= read.toLong()
                }
                out.flush()
            } else {
                val outStream = proxyOutput(socket)
                outStream.write("HTTP/1.1 415 Unsupported Media Type\r\nContent-Length: 0\r\n\r\n".toByteArray())
                outStream.flush()
            }
        } catch (_: IsoClientDisconnected) {
            // Client aborted while sending its request, before an upstream read began.
        } catch (e: Exception) {
            if (isoSession?.isCancelled == true) return
            val mapping = if (e is IsoProxyReadException && e.count > 0) isoFile?.physicalRanges(e.start, e.count) else null
            val upstream = e.isoRangeFailure()
            // HLS can read PSI or build a rendition before IsoVirtualFile.serve commits headers.
            if (upstream != null && e !is IsoProxyReadException) {
                val out = proxyOutput(socket)
                out.write("HTTP/1.1 ${isoProxyErrorStatus(e)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }
            appendDebugLog("ARCHIVE_PROXY_ERR", "$requestDetails mappedPhysical=${mapping ?: "unavailable"} upstreamHTTP=${upstream?.status ?: "unavailable"} ${isoFailureDetails(e)}")
        } finally {
            clientWatch?.close()
            // Finish the local response before upstream cleanup can contend with an HTTP reader.
            if (!connection.reusable) try { socket.close() } catch (_: Throwable) {}
            isoSession?.cancel()
            registration?.close()
            val connRef = remoteConnToClean.get()
            val infRef = inflaterToClean
            if (connRef != null || infRef != null) {
                ioExecutor.submit {
                    try { connRef?.disconnect() } catch (_: Throwable) {}
                    try { infRef?.end() } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun parseRar5ArchiveEntries(archiveUrl: String, totalSize: Long, port: Int): List<JSONObject> {
        val mediaExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts", ".m2ts")
        val items = ArrayList<JSONObject>()
        var offset = 8L // Skip 8-byte RAR5 signature
        var hops = 0

        while (offset + 10L < totalSize && hops < 250) {
            hops++
            val readEnd = minOf(totalSize - 1L, offset + 511L)
            val buf = fetchRemoteByteRange(archiveUrl, offset, readEnd)
            if (buf.size < 8) break

            val (headerSize, hsLen) = readRar5Vint(buf, 4)
            val hdrStart = 4 + hsLen
            if (headerSize <= 0L || headerSize > 65536L) break

            val fullHdrBytes = if (hdrStart + headerSize.toInt() <= buf.size) {
                buf
            } else {
                fetchRemoteByteRange(archiveUrl, offset, minOf(totalSize - 1L, offset + hdrStart + headerSize - 1L))
            }

            var p = hdrStart
            val (headerType, htLen) = readRar5Vint(fullHdrBytes, p); p += htLen
            val (headerFlags, hfLen) = readRar5Vint(fullHdrBytes, p); p += hfLen

            if ((headerFlags and 0x0001L) != 0L) {
                val (_, esLen) = readRar5Vint(fullHdrBytes, p); p += esLen
            }
            var dataSize = 0L
            if ((headerFlags and 0x0002L) != 0L) {
                val (ds, dsLen) = readRar5Vint(fullHdrBytes, p); dataSize = ds; p += dsLen
            }

            if (headerType == 5L) break // End of archive header

            val dataStartOffset = offset + hdrStart.toLong() + headerSize

            if (headerType == 2L) { // File header
                val (fileFlags, ffLen) = readRar5Vint(fullHdrBytes, p); p += ffLen
                val (unpackedSize, usLen) = readRar5Vint(fullHdrBytes, p); p += usLen
                val (_, atLen) = readRar5Vint(fullHdrBytes, p); p += atLen
                if ((fileFlags and 0x0002L) != 0L) p += 4 // mtime
                if ((fileFlags and 0x0004L) != 0L) p += 4 // crc32
                val (compInfo, ciLen) = readRar5Vint(fullHdrBytes, p); p += ciLen
                val (_, hoLen) = readRar5Vint(fullHdrBytes, p); p += hoLen
                val (nameLen, nlLen) = readRar5Vint(fullHdrBytes, p); p += nlLen

                val nLen = nameLen.toInt()
                if (nLen > 0 && p + nLen <= fullHdrBytes.size) {
                    val fullEntryName = String(fullHdrBytes, p, nLen, StandardCharsets.UTF_8).replace('\\', '/')
                    val baseName = fullEntryName.substringAfterLast('/')
                    val lowerName = baseName.lowercase(Locale.ROOT)
                    val isDir = (fileFlags and 0x0001L) != 0L
                    val method = ((compInfo ushr 7) and 0x07L).toInt() // 0 = STORE

                    if (!isDir && unpackedSize > 0L && mediaExtensions.any { lowerName.endsWith(it) }) {
                        val entryId = "ze_${System.nanoTime()}_${archiveEntryCounter.getAndIncrement()}"
                        val target = ArchiveEntryTarget(
                            id = entryId,
                            archiveUrl = archiveUrl,
                            fileName = baseName,
                            archiveFormat = "RAR",
                            compressionMethod = method,
                            compressedSize = dataSize,
                            uncompressedSize = unpackedSize,
                            localHeaderOffset = offset,
                            dataStartOffset = dataStartOffset
                        )
                        archiveEntryRegistry[entryId] = target
                        val safeName = URLEncoder.encode(baseName, "UTF-8").replace("+", "%20")
                        val streamUrl = "http://127.0.0.1:$port/zip/$entryId/$safeName"

                        items.add(JSONObject().apply {
                            put("id", entryId)
                            put("name", baseName)
                            put("size", unpackedSize.toString())
                            put("mimeType", if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")) "video/mp4" else "video/x-matroska")
                            put("link", streamUrl)
                            put("isZipEntry", true)
                            put("zipCompression", if (method == 0) "STORE" else "RAR_COMPRESSED")
                        })
                    }
                }
            }

            val nextOffset = dataStartOffset + dataSize
            if (nextOffset <= offset) break
            offset = nextOffset
        }
        return items
    }

    // Hop across RAR 4.x block headers via tiny Range reads (Works even if .rar was renamed to .zip!)
    private fun parseRar4ArchiveEntries(archiveUrl: String, totalSize: Long, port: Int): List<JSONObject> {
        val mediaExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts", ".m2ts")
        val items = ArrayList<JSONObject>()
        var offset = 7L // Skip 7-byte RAR4 signature
        var hops = 0

        while (offset + 7L < totalSize && hops < 250) {
            hops++
            val readEnd = minOf(totalSize - 1L, offset + 511L)
            val buf = fetchRemoteByteRange(archiveUrl, offset, readEnd)
            if (buf.size < 7) break

            val headType = buf[2].toInt() and 0xFF
            val headFlags = readUInt16LE(buf, 3)
            val headSize = readUInt16LE(buf, 5)
            if (headSize < 7 || headType == 0x7B) break

            var addSize = 0L
            if ((headFlags and 0x8000) != 0 && buf.size >= 11) {
                addSize = readUInt32LE(buf, 7)
            }

            if (headType == 0x74 && buf.size >= 32) { // File Header
                var packSize = readUInt32LE(buf, 7)
                var unpSize = readUInt32LE(buf, 11)
                val method = buf[25].toInt() and 0xFF // 0x30 = STORE
                val nameSize = readUInt16LE(buf, 26)
                var nameOffset = 32

                if ((headFlags and 0x0100) != 0 && buf.size >= 40) {
                    val highPack = readUInt32LE(buf, 32)
                    val highUnp = readUInt32LE(buf, 36)
                    packSize = packSize or (highPack shl 32)
                    unpSize = unpSize or (highUnp shl 32)
                    nameOffset = 40
                }
                addSize = packSize

                if (nameSize > 0 && nameOffset + nameSize <= buf.size) {
                    val rawNameStr = String(buf, nameOffset, nameSize, StandardCharsets.UTF_8)
                        .substringBefore('\u0000')
                        .replace('\\', '/')
                    val baseName = rawNameStr.substringAfterLast('/')
                    val lowerName = baseName.lowercase(Locale.ROOT)
                    val isDir = (headFlags and 0x00E0) == 0x00E0
                    val normMethod = if (method == 0x30) 0 else method
                    val dataStartOffset = offset + headSize.toLong()

                    if (!isDir && unpSize > 0L && mediaExtensions.any { lowerName.endsWith(it) }) {
                        val entryId = "ze_${System.nanoTime()}_${archiveEntryCounter.getAndIncrement()}"
                        val target = ArchiveEntryTarget(
                            id = entryId,
                            archiveUrl = archiveUrl,
                            fileName = baseName,
                            archiveFormat = "RAR",
                            compressionMethod = normMethod,
                            compressedSize = packSize,
                            uncompressedSize = unpSize,
                            localHeaderOffset = offset,
                            dataStartOffset = dataStartOffset
                        )
                        archiveEntryRegistry[entryId] = target
                        val safeName = URLEncoder.encode(baseName, "UTF-8").replace("+", "%20")
                        val streamUrl = "http://127.0.0.1:$port/zip/$entryId/$safeName"

                        items.add(JSONObject().apply {
                            put("id", entryId)
                            put("name", baseName)
                            put("size", unpSize.toString())
                            put("mimeType", if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")) "video/mp4" else "video/x-matroska")
                            put("link", streamUrl)
                            put("isZipEntry", true)
                            put("zipCompression", if (normMethod == 0) "STORE" else "RAR_COMPRESSED")
                        })
                    }
                }
            }

            val nextOffset = offset + headSize.toLong() + addSize
            if (nextOffset <= offset) break
            offset = nextOffset
        }
        return items
    }

    private val isoDiskCache by lazy { IsoIndexDiskCache(java.io.File(reactContext.cacheDir, "iso-index"), java.io.File(reactContext.filesDir, "iso-index")) }

    private val zipDiskCache = ConcurrentHashMap<String, String>()
    private val zipDiskLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
    private val zipDiskWriteLock = Any()
    private val zipInflightMap = ConcurrentHashMap<String, MutableList<Promise>>()

    /** Resolve history aliases from local indexes without opening/scanning the disc or starting playback. */
    @ReactMethod
    fun getArchiveWatchAliases(url: String, size: Double, promise: Promise) {
        ioExecutor.submit {
            try {
                val aliases = linkedSetOf(url, url.substringBefore('#'))
                archiveEntryRegistry.values.filter { it.archiveFormat == "ISO" && it.archiveUrl.substringBefore('#') == url.substringBefore('#') }
                    .forEach { aliases.add(it.id); aliases.add(it.fileName) }
                ensureZipDiskCacheLoaded()
                val blobs = listOfNotNull(isoDiskCache.get(url, size.toLong()), zipDiskCache[extractArchiveCacheKey(url, size)])
                for (blob in blobs) {
                    val root = JSONObject(blob)
                    val entries = JSONArray(root.optString("rawJson", "[]"))
                    for (i in 0 until entries.length()) {
                        val entry = entries.optJSONObject(i) ?: continue
                        for (field in listOf("id", "name", "link")) entry.optString(field).takeIf { it.isNotEmpty() }?.let(aliases::add)
                    }
                    val targets = root.optJSONArray("targets") ?: continue
                    for (i in 0 until targets.length()) {
                        val target = targets.optJSONObject(i) ?: continue
                        for (field in listOf("id", "fileName")) target.optString(field).takeIf { it.isNotEmpty() }?.let(aliases::add)
                    }
                }
                promise.resolve(Arguments.createArray().apply { aliases.forEach(::pushString) })
            } catch (error: Exception) { promise.reject("WATCH_ALIASES", error) }
        }
    }

    private fun extractArchiveCacheKey(rawUrl: String, sizeHint: Double): String {
        val qId = Regex("[?&]id=([a-zA-Z0-9_-]{15,})").find(rawUrl)?.groupValues?.getOrNull(1)
        val pId = Regex("/d/([a-zA-Z0-9_-]{15,})").find(rawUrl)?.groupValues?.getOrNull(1)
        val driveId = qId ?: pId
        return if (!driveId.isNullOrEmpty()) "gd_$driveId" else "${rawUrl.substringBefore("#").substringBefore("?")}|${sizeHint.toLong()}"
    }

    private fun ensureZipDiskCacheLoaded() {
        if (!zipDiskLoaded.compareAndSet(false, true)) return
        try {
            val file = java.io.File(reactContext.filesDir, "gdstream_zip_index_v2.json")
            if (file.exists() && file.length() > 2L) {
                val rootObj = JSONObject(file.readText(Charsets.UTF_8))
                val keys = rootObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = rootObj.optString(k, "")
                    if (v.isNotEmpty()) zipDiskCache[k] = v
                }
            }
        } catch (_: Throwable) {}
    }

    private fun saveZipDiskCacheAsync() {
        ioExecutor.submit {
            synchronized(zipDiskWriteLock) {
                try {
                    val obj = JSONObject()
                    for ((k, v) in zipDiskCache) obj.put(k, v)
                    val tmp = java.io.File(reactContext.filesDir, "gdstream_zip_index_v2.tmp")
                    val dest = java.io.File(reactContext.filesDir, "gdstream_zip_index_v2.json")
                    tmp.writeText(obj.toString(), Charsets.UTF_8)
                    tmp.renameTo(dest)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun restoreArchiveFromDisk(cachedBlob: String, freshUrl: String): String? {
        return try {
            val root = JSONObject(cachedBlob)
            val rawJson = root.optString("rawJson", "")
            val targetsArr = root.optJSONArray("targets") ?: return null
            val hasIso = (0 until targetsArr.length()).any {
                targetsArr.optJSONObject(it)?.optString("archiveFormat") == "ISO"
            }
            if (hasIso && root.optInt("isoIndexVersion", 0) != IsoIndexDiskCache.VERSION) return null
            var hasUnstrippedM2ts = false
            for (ci in 0 until targetsArr.length()) {
                val co = targetsArr.optJSONObject(ci) ?: continue
                if (co.optString("archiveFormat") == "ISO" && co.optString("fileName").endsWith(".m2ts", true) && !co.optBoolean("isBdavM2ts", false)) {
                    hasUnstrippedM2ts = true
                    break
                }
            }
            if (rawJson.isEmpty() || targetsArr.length() == 0 || (!hasIso && rawJson.contains("download.aspx", ignoreCase = true)) || hasUnstrippedM2ts) return null

            if (!hasIso) ensureVaultSessionReady()
            val activePort = ensureArchiveProxyRunning()

            val restoredTargets = HashMap<String, ArchiveEntryTarget>()
            for (i in 0 until targetsArr.length()) {
                val o = targetsArr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                val segsJson = o.optJSONArray("segments")
                val restoredSegs = if (segsJson != null && segsJson.length() > 0) {
                    val list = ArrayList<Pair<Long, Long>>(segsJson.length())
                    for (si in 0 until segsJson.length()) {
                        val pairArr = segsJson.optJSONArray(si) ?: continue
                        if (pairArr.length() >= 2) {
                            list.add(Pair(pairArr.optLong(0, 0L), pairArr.optLong(1, 0L)))
                        }
                    }
                    list
                } else null
                // Re-read the IFO once for DVD indexes written before C_ADT extent selection.
                if (o.optString("archiveFormat") == "ISO" && o.optString("fileName").endsWith(".vob", true) &&
                    o.optInt("dvdIndexVersion") != DvdTitleInfo.INDEX_VERSION) return null
                val dvdSegments = o.optJSONArray("dvdSegments")?.let { array ->
                    (0 until array.length()).map { array.getJSONArray(it).let { row ->
                        DvdProxy.Segment(row.getLong(0), row.getLong(1), row.getLong(2), row.optBoolean(3, true))
                    } }
                }.orEmpty()
                val entry = ArchiveEntryTarget(
                    id = id,
                    dvdSegments = dvdSegments,
                    archiveUrl = freshUrl,
                    sourceSize = root.optLong("sourceSize"),
                    fileName = o.optString("fileName", ""),
                    archiveFormat = o.optString("archiveFormat", "ZIP"),
                    compressionMethod = o.optInt("compressionMethod", 0),
                    compressedSize = o.optLong("compressedSize", 0L),
                    uncompressedSize = o.optLong("uncompressedSize", 0L),
                    localHeaderOffset = o.optLong("localHeaderOffset", 0L),
                    dataStartOffset = o.optLong("dataStartOffset", -1L),
                    segments = restoredSegs,
                    isBdavM2ts = o.optBoolean("isBdavM2ts", false),
                    bluRay = BluRayProfile.fromJson(o.optJSONObject("bluRay"))
                )
                if (entry.archiveFormat == "ISO") {
                    val file = IsoVirtualFile(restoredSegs ?: return null, entry.isBdavM2ts)
                    if (file.rawSize != entry.compressedSize || file.size != entry.uncompressedSize) return null
                    if (entry.bluRay?.points?.any { it.position >= file.size || it.timeUs >= entry.bluRay.durationUs } == true) return null
                }
                restoredTargets[id] = entry
            }
            val items = JSONArray(rawJson)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val target = restoredTargets[item.getString("id")] ?: return null
                val safeName = URLEncoder.encode(target.fileName, "UTF-8").replace("+", "%20")
                if (target.archiveFormat == "ISO") item.optJSONObject("mediaInfo")?.let { info ->
                    restoredTargets[target.id] = target.copy(resolvedInfo = discInfoMap(info))
                }
                item.put("link", "http://127.0.0.1:$activePort/zip/${target.id}/$safeName")
                if (target.archiveFormat == "ISO") item.put("archiveFormat", "ISO")
            }
            archiveEntryRegistry.putAll(restoredTargets)
            if (hasIso) appendDebugLog("ISO_CACHE_RESTORE", "entries=${restoredTargets.size} port=$activePort source=${safeStreamUrl(freshUrl)}; signed redirect locations are not cached")
            items.toString()
        } catch (error: Exception) {
            appendDebugLog("ARCHIVE_CACHE_ERR", isoFailureDetails(error))
            null
        }
    }

    private fun persistArchiveToDisk(cacheKey: String, rawJson: String, sourceUrl: String, sourceSize: Long) {
        try {
            val arr = JSONArray(rawJson)
            if (arr.length() == 0) return
            val targetsArr = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val t = archiveEntryRegistry[id] ?: continue
                targetsArr.put(JSONObject().apply {
                    put("id", t.id)
                    put("fileName", t.fileName)
                    put("archiveFormat", t.archiveFormat)
                    put("compressionMethod", t.compressionMethod)
                    put("compressedSize", t.compressedSize)
                    put("uncompressedSize", t.uncompressedSize)
                    put("localHeaderOffset", t.localHeaderOffset)
                    put("dataStartOffset", t.dataStartOffset)
                    if (!t.segments.isNullOrEmpty()) {
                        val sArr = JSONArray()
                        for ((sOff, sLen) in t.segments) {
                            sArr.put(JSONArray().apply { put(sOff); put(sLen) })
                        }
                        put("segments", sArr)
                    }
                    t.bluRay?.let { put("bluRay", it.toJson()) }
                    if (t.archiveFormat == "ISO" && t.fileName.endsWith(".vob", true)) put("dvdIndexVersion", DvdTitleInfo.INDEX_VERSION)
                    if (t.dvdSegments.isNotEmpty()) put("dvdSegments", JSONArray().apply {
                        t.dvdSegments.forEach { put(JSONArray().put(it.start).put(it.end).put(it.durationUs).put(it.discontinuity)) }
                    })
                    if (t.isBdavM2ts) {
                        put("isBdavM2ts", true)
                    }
                })
            }
            if (targetsArr.length() > 0) {
                val root = JSONObject().apply {
                    put("sourceSize", sourceSize)
                    put("rawJson", rawJson)
                    put("targets", targetsArr)
                    put("isoIndexVersion", IsoIndexDiskCache.VERSION)
                }
                if ((0 until targetsArr.length()).any { targetsArr.getJSONObject(it).optString("archiveFormat") == "ISO" }) {
                    isoDiskCache.put(sourceUrl, sourceSize, root.toString())
                } else {
                    zipDiskCache[cacheKey] = root.toString()
                    saveZipDiskCacheAsync()
                }
            }
        } catch (_: Throwable) {}
    }

    @ReactMethod
    fun listZipMediaEntries(zipUrl: String, fileSizeHint: Double, promise: Promise) {
        ArchiveProxyStreams.registry.resume()
        ioExecutor.submit {
            ensureZipDiskCacheLoaded()
            val hintedName = try { java.net.URLDecoder.decode(zipUrl.substringAfter("#name=", ""), "UTF-8") } catch (_: IllegalArgumentException) { "" }
            val iso = zipUrl.substringBefore('#').substringBefore('?').endsWith(".iso", true) || hintedName.endsWith(".iso", true)
            val sourceSize = try {
                if (iso && fileSizeHint <= 0) isoDiskCache.lastKnownSize(zipUrl) ?: discSize(zipUrl, library = true) else fileSizeHint.toLong()
            } catch (error: Exception) {
                promise.reject("E_ISO_SIZE", error.message, error)
                return@submit
            }
            val cacheKey = if (iso) IsoIndexDiskCache.key(zipUrl, sourceSize) else extractArchiveCacheKey(zipUrl, fileSizeHint)
            val cachedBlob = if (iso) isoDiskCache.get(zipUrl, sourceSize) else zipDiskCache[cacheKey]
            if (!cachedBlob.isNullOrEmpty()) {
                val restoredJson = restoreArchiveFromDisk(cachedBlob, zipUrl)
                if (!restoredJson.isNullOrEmpty()) {
                    promise.resolve(restoredJson)
                    return@submit
                }
            }

            var isLeader = false
            zipInflightMap.compute(cacheKey) { _, existing ->
                if (existing == null) {
                    isLeader = true
                    java.util.Collections.synchronizedList(mutableListOf(promise))
                } else {
                    existing.add(promise)
                    existing
                }
            }
            if (!isLeader) return@submit

            val proxyPromise = object : Promise {
                override fun resolve(value: Any?) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    if (value is String && value.startsWith("[") && value.length > 2) {
                        persistArchiveToDisk(cacheKey, value, zipUrl, sourceSize)
                    }
                    for (p in list) p.resolve(value)
                }
                override fun reject(code: String?, message: String?) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", message)
                }
                override fun reject(code: String?, throwable: Throwable?) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", throwable)
                }
                override fun reject(code: String?, message: String?, throwable: Throwable?) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", message, throwable)
                }
                override fun reject(throwable: Throwable) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(throwable)
                }
                override fun reject(throwable: Throwable, userInfo: com.facebook.react.bridge.WritableMap) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(throwable, userInfo)
                }
                override fun reject(code: String?, userInfo: com.facebook.react.bridge.WritableMap) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", userInfo)
                }
                override fun reject(code: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", throwable, userInfo)
                }
                override fun reject(code: String?, message: String?, userInfo: com.facebook.react.bridge.WritableMap) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", message, userInfo)
                }
                override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap?) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(code ?: "E_ZIP", message, throwable, userInfo)
                }
                @Deprecated("Deprecated in React Native")
                override fun reject(message: String) {
                    val list = zipInflightMap.remove(cacheKey) ?: listOf(promise)
                    for (p in list) p.reject(message)
                }
            }

            scanZipMediaEntriesUncached(zipUrl, sourceSize.toDouble(), proxyPromise)
        }
    }

    private fun scanZipMediaEntriesUncached(zipUrl: String, fileSizeHint: Double, promise: Promise) {
        run {
            try {
                val seed = verifyCertificateAndEnv()
                if (seed != 0x5A3C9E71) {
                    promise.reject("E_SEC", "Security shield active")
                    return@run
                }

                val baseUrl = nSync(1, seed)
                val shieldToken = nSync(5, seed)
                if (shieldToken.isNotEmpty()) cachedShieldToken = shieldToken

                if (!nativeSessionLoggedIn && baseUrl.isNotEmpty() && shieldToken.isNotEmpty()) {
                    try { performNativeLogin(baseUrl, seed, shieldToken) } catch (_: Throwable) {}
                }

                var totalArchiveSize = fileSizeHint.toLong()
                if (totalArchiveSize <= 0L) {
                    totalArchiveSize = detectRemoteFileSize(zipUrl)
                }
                if (totalArchiveSize <= 22L) {
                    promise.reject("E_ZIP", "Unable to determine archive file size")
                    return@run
                }

                val port = ensureArchiveProxyRunning()

                val lowerUrlPath = zipUrl.substringBefore("#").substringBefore("?").lowercase(Locale.ROOT)
                val hintedName = try {
                    java.net.URLDecoder.decode(zipUrl.substringAfter("#name=", ""), "UTF-8")
                } catch (_: IllegalArgumentException) { "" }
                var looksLikeIso = lowerUrlPath.endsWith(".iso") || hintedName.endsWith(".iso", true)
                // 1. Check first 16 bytes at offset 0 to auto-detect RAR4 / RAR5 (even if .rar was renamed to .zip!)
                val headProbe = if (looksLikeIso) ByteArray(0) else fetchRemoteByteRange(zipUrl, 0L, 15L)
                if (headProbe.size >= 7 &&
                    headProbe[0] == 0x52.toByte() && // 'R'
                    headProbe[1] == 0x61.toByte() && // 'a'
                    headProbe[2] == 0x72.toByte() && // 'r'
                    headProbe[3] == 0x21.toByte() && // '!'
                    headProbe[4] == 0x1A.toByte() &&
                    headProbe[5] == 0x07.toByte()
                ) {
                    val rarItems = if (headProbe.size >= 8 && headProbe[6] == 0x01.toByte() && headProbe[7] == 0x00.toByte()) {
                        parseRar5ArchiveEntries(zipUrl, totalArchiveSize, port)
                    } else {
                        parseRar4ArchiveEntries(zipUrl, totalArchiveSize, port)
                    }
                    val sortedRar = rarItems.sortedWith(Comparator { a, b ->
                        a.optString("name", "").compareTo(b.optString("name", ""), ignoreCase = true)
                    })
                    val outRar = JSONArray()
                    for (item in sortedRar) outRar.put(item)
                    promise.resolve(outRar.toString())
                    return@run
                }

                // 1B. Blu-ray / DVD / Data .ISO (UDF 2.50 + ISO-9660 + MPLS + VOB)
                var volDescProbe: ByteArray? = null
                var isoDiagReason = ""
                if (!looksLikeIso && totalArchiveSize >= 36864L) {
                    try {
                        val pvdProbe = fetchRemoteByteRange(zipUrl, 32768L, minOf(totalArchiveSize - 1L, 65535L))
                        volDescProbe = pvdProbe
                        var off = 0
                        while (off + 6 <= pvdProbe.size) {
                            val sig = String(pvdProbe, off + 1, 5, StandardCharsets.US_ASCII)
                            if (sig == "CD001" || sig == "BEA01" || sig == "NSR02" || sig == "NSR03") {
                                looksLikeIso = true
                                break
                            }
                            off += 2048
                        }
                    } catch (probeEx: Throwable) {
                        isoDiagReason = probeEx.message ?: probeEx.javaClass.simpleName
                    }
                }
                if (looksLikeIso) {
                    val isoOutcome = parseIsoDiscEntries(zipUrl, totalArchiveSize, port, volDescProbe)
                    if (isoOutcome.first.isNotEmpty()) {
                        val outIso = JSONArray()
                        for (item in isoOutcome.first) outIso.put(item)
                        promise.resolve(outIso.toString())
                        return@run
                    }
                    if (isoOutcome.second.isNotEmpty()) {
                        isoDiagReason = isoOutcome.second
                    }
                    // Do not fall through to a ZIP tail probe on a known ISO. A server ignoring
                    // Range would otherwise make the legacy ZIP reader discard the whole BD50.
                    promise.reject("E_ISO", "ISO Disc Error: " + isoDiagReason.ifEmpty { "No playable video streams found" })
                    return@run
                }

                // 2. Standard ZIP / ZIP64 Central Directory Tail Reader
                val tailLen = minOf(totalArchiveSize, 131072L).toInt()
                val tailStart = totalArchiveSize - tailLen.toLong()
                val tailBuf = fetchRemoteByteRange(zipUrl, tailStart, totalArchiveSize - 1L)

                var eocdPos = -1
                for (i in (tailBuf.size - 22) downTo 0) {
                    if (tailBuf[i] == 0x50.toByte() &&
                        tailBuf[i + 1] == 0x4b.toByte() &&
                        tailBuf[i + 2] == 0x05.toByte() &&
                        tailBuf[i + 3] == 0x06.toByte()
                    ) {
                        eocdPos = i
                        break
                    }
                }

                if (eocdPos < 0) {
                    val msg = if (looksLikeIso && isoDiagReason.isNotEmpty()) {
                        "ISO Disc Error: " + isoDiagReason
                    } else if (looksLikeIso) {
                        "ISO Disc Error: No playable BDMV/VIDEO_TS video streams found inside ISO"
                    } else {
                        "Archive header not recognized (Not a valid ZIP, RAR, or ISO file)"
                    }
                    promise.reject("E_ZIP", msg)
                    return@run
                }

                var cdSize = readUInt32LE(tailBuf, eocdPos + 12)
                var cdOffset = readUInt32LE(tailBuf, eocdPos + 16)

                if (eocdPos >= 20) {
                    val locPos = eocdPos - 20
                    if (tailBuf[locPos] == 0x50.toByte() &&
                        tailBuf[locPos + 1] == 0x4b.toByte() &&
                        tailBuf[locPos + 2] == 0x06.toByte() &&
                        tailBuf[locPos + 3] == 0x07.toByte()
                    ) {
                        val zip64EocdOffset = readInt64LE(tailBuf, locPos + 8)
                        val z64Buf = if (zip64EocdOffset >= tailStart && (zip64EocdOffset + 56L) <= totalArchiveSize) {
                            val rel = (zip64EocdOffset - tailStart).toInt()
                            tailBuf.copyOfRange(rel, rel + 56)
                        } else {
                            fetchRemoteByteRange(zipUrl, zip64EocdOffset, zip64EocdOffset + 55L)
                        }
                        if (z64Buf.size >= 56 &&
                            z64Buf[0] == 0x50.toByte() &&
                            z64Buf[1] == 0x4b.toByte() &&
                            z64Buf[2] == 0x06.toByte() &&
                            z64Buf[3] == 0x06.toByte()
                        ) {
                            cdSize = readInt64LE(z64Buf, 40)
                            cdOffset = readInt64LE(z64Buf, 48)
                        }
                    }
                }

                if (cdSize <= 0L || cdSize > 16L * 1024L * 1024L) {
                    promise.reject("E_ZIP", "Invalid ZIP Central Directory size")
                    return@run
                }

                val cdBytes = if (cdOffset >= tailStart && (cdOffset + cdSize) <= totalArchiveSize) {
                    val relStart = (cdOffset - tailStart).toInt()
                    val relEnd = (relStart + cdSize.toInt()).coerceAtMost(tailBuf.size)
                    tailBuf.copyOfRange(relStart, relEnd)
                } else {
                    fetchRemoteByteRange(zipUrl, cdOffset, cdOffset + cdSize - 1L)
                }

                val mediaExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts", ".m2ts")
                val parsedItems = ArrayList<JSONObject>()

                var ptr = 0
                while (ptr + 46 <= cdBytes.size) {
                    if (cdBytes[ptr] != 0x50.toByte() ||
                        cdBytes[ptr + 1] != 0x4b.toByte() ||
                        cdBytes[ptr + 2] != 0x01.toByte() ||
                        cdBytes[ptr + 3] != 0x02.toByte()
                    ) {
                        break
                    }

                    val compMethod = readUInt16LE(cdBytes, ptr + 10)
                    var compSize = readUInt32LE(cdBytes, ptr + 20)
                    var uncompSize = readUInt32LE(cdBytes, ptr + 24)
                    val nameLen = readUInt16LE(cdBytes, ptr + 28)
                    val extraLen = readUInt16LE(cdBytes, ptr + 30)
                    val commentLen = readUInt16LE(cdBytes, ptr + 32)
                    var localHdrOff = readUInt32LE(cdBytes, ptr + 42)

                    val nameStart = ptr + 46
                    if (nameStart + nameLen > cdBytes.size) break
                    val fullEntryName = String(cdBytes, nameStart, nameLen, StandardCharsets.UTF_8)

                    val extraStart = nameStart + nameLen
                    val extraEnd = (extraStart + extraLen).coerceAtMost(cdBytes.size)
                    if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localHdrOff == 0xFFFFFFFFL) {
                        var ep = extraStart
                        while (ep + 4 <= extraEnd) {
                            val headerId = readUInt16LE(cdBytes, ep)
                            val dataSize = readUInt16LE(cdBytes, ep + 2)
                            var dp = ep + 4
                            if (headerId == 0x0001) {
                                if (uncompSize == 0xFFFFFFFFL && dp + 8 <= ep + 4 + dataSize) {
                                    uncompSize = readInt64LE(cdBytes, dp)
                                    dp += 8
                                }
                                if (compSize == 0xFFFFFFFFL && dp + 8 <= ep + 4 + dataSize) {
                                    compSize = readInt64LE(cdBytes, dp)
                                    dp += 8
                                }
                                if (localHdrOff == 0xFFFFFFFFL && dp + 8 <= ep + 4 + dataSize) {
                                    localHdrOff = readInt64LE(cdBytes, dp)
                                    dp += 8
                                }
                                break
                            }
                            ep += 4 + dataSize
                        }
                    }

                    ptr = extraStart + extraLen + commentLen

                    val baseName = fullEntryName.substringAfterLast('/')
                    val lowerName = baseName.lowercase(Locale.ROOT)
                    val isJunk = fullEntryName.endsWith("/") ||
                        fullEntryName.contains("__MACOSX") ||
                        baseName.startsWith("._") ||
                        baseName.isEmpty()

                    if (!isJunk && uncompSize > 0L && mediaExtensions.any { lowerName.endsWith(it) }) {
                        val entryId = "ze_${archiveEntryCounter.getAndIncrement()}"
                        val target = ArchiveEntryTarget(
                            id = entryId,
                            archiveUrl = zipUrl,
                            fileName = baseName,
                            archiveFormat = "ZIP",
                            compressionMethod = compMethod,
                            compressedSize = compSize,
                            uncompressedSize = uncompSize,
                            localHeaderOffset = localHdrOff
                        )
                        archiveEntryRegistry[entryId] = target

                        val safeName = URLEncoder.encode(baseName, "UTF-8").replace("+", "%20")
                        val streamUrl = "http://127.0.0.1:$port/zip/$entryId/$safeName"

                        val itemObj = JSONObject().apply {
                            put("id", entryId)
                            put("name", baseName)
                            put("size", uncompSize.toString())
                            put("mimeType", if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")) "video/mp4" else "video/x-matroska")
                            put("link", streamUrl)
                            put("isZipEntry", true)
                            put("zipCompression", if (compMethod == 0) "STORE" else "DEFLATE")
                        }
                        parsedItems.add(itemObj)
                    }
                }

                parsedItems.sortWith(Comparator { a, b ->
                    a.optString("name", "").compareTo(b.optString("name", ""), ignoreCase = true)
                })

                val outArray = JSONArray()
                for (item in parsedItems) {
                    outArray.put(item)
                }
                promise.resolve(outArray.toString())
            } catch (e: Exception) {
                promise.reject("E_ZIP", e.message ?: "Failed to inspect archive")
            }
        }
    }

    // =========================================================================
    // 🎬 4-TIER TRAILER RESOLVER & LANDSCAPE CHROMELESS CINEMA PLAYER
    // =========================================================================

    private fun isReviewOrJunkTitle(rawTitle: String): Boolean {
        val t = rawTitle.lowercase(Locale.ROOT)
        val junkWords = listOf(
            "review", "reaction", "public talk", "public review", "public opinion",
            "fdfs", "explained", "breakdown", "roast", "troll", "spoof", "parody",
            "interview", "press meet", "audio launch", "success meet", "making",
            "behind the scenes", "full movie", "jukebox", "lyrical", "video song",
            "whatsapp status", "shorts", "#shorts", "top cinemas", "galatta", "behindwoods"
        )
        return junkWords.any { t.contains(it) }
    }

    private fun verifyYoutubeOembedClean(videoId: String, allowAnyOfficialTmdb: Boolean = false): Boolean {
        try {
            if (videoId.length != 11) return false
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val oConn = (URL(oembedUrl).openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            val code = oConn.responseCode
            if (code !in 200..299) {
                oConn.disconnect()
                return false
            }
            val oJson = BufferedReader(InputStreamReader(oConn.inputStream, Charsets.UTF_8)).use { it.readText() }
            oConn.disconnect()

            val oObj = JSONObject(oJson)
            val oTitle = oObj.optString("title", "")
            val oAuthor = oObj.optString("author_name", "")
            if (!allowAnyOfficialTmdb && (isReviewOrJunkTitle(oTitle) || isReviewOrJunkTitle(oAuthor))) {
                return false
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun fetchKinocheckTrailerId(tmdbId: String, isSeries: Boolean): String {
        try {
            if (tmdbId.isEmpty()) return ""
            val endpoint = if (isSeries) "shows" else "movies"
            val url = "https://api.kinocheck.de/$endpoint?tmdb_id=$tmdbId"
            val conn = (URL(url).openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GDStream/3.0")
            }
            if (conn.responseCode in 200..299) {
                val txt = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                conn.disconnect()
                val root = JSONObject(txt)
                val trailerObj = root.optJSONObject("trailer")
                val ytId = trailerObj?.optString("youtube_video_id", "") ?: ""
                if (ytId.length == 11 && verifyYoutubeOembedClean(ytId, true)) {
                    return ytId
                }
            }
            conn.disconnect()
        } catch (_: Throwable) {}
        return ""
    }

    private fun fetchAppleItunesTrailerStreams(title: String, year: String): List<DirectTrailerStream> {
        val list = ArrayList<DirectTrailerStream>()
        try {
            val clean = title.trim()
            if (clean.isEmpty()) return emptyList()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            for (country in listOf("IN", "US")) {
                val url = "https://itunes.apple.com/search?term=$encoded&country=$country&media=movie&entity=movie&limit=6"
                val conn = (URL(url).openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode in 200..299) {
                    val json = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    conn.disconnect()
                    val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
                    val normTarget = clean.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val trackName = item.optString("trackName", "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                        val releaseDate = item.optString("releaseDate", "")
                        val relYear = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else ""
                        val previewUrl = item.optString("previewUrl", "")

                        if (previewUrl.isNotEmpty() && (trackName == normTarget || trackName.contains(normTarget))) {
                            if (year.isEmpty() || relYear.isEmpty() || kotlin.math.abs((relYear.toIntOrNull() ?: 0) - (year.toIntOrNull() ?: 0)) <= 1) {
                                list.add(DirectTrailerStream("1080p Apple Cinema", 1080, previewUrl))
                                return list
                            }
                        }
                    }
                } else {
                    conn.disconnect()
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    private fun langCodeToDisplayName(rawCode: String): String {
        return when (rawCode.trim().lowercase(Locale.ROOT).substringBefore("-")) {
            "ta", "tam" -> "Tamil"
            "te", "tel" -> "Telugu"
            "hi", "hin" -> "Hindi"
            "ml", "mal" -> "Malayalam"
            "kn", "kan" -> "Kannada"
            "en", "eng" -> "English"
            "ja", "jpn" -> "Japanese"
            "ko", "kor" -> "Korean"
            "es", "spa" -> "Spanish"
            "fr", "fre", "fra" -> "French"
            else -> ""
        }
    }

    private fun detectLanguageFromTitle(titleText: String, fallbackCode: String): Pair<String, String> {
        val lower = titleText.lowercase(Locale.ROOT)
        val checks = listOf(
            Triple("tamil", "ta", "Tamil"),
            Triple("telugu", "te", "Telugu"),
            Triple("hindi", "hi", "Hindi"),
            Triple("malayalam", "ml", "Malayalam"),
            Triple("kannada", "kn", "Kannada"),
            Triple("english", "en", "English"),
            Triple("japanese", "ja", "Japanese"),
            Triple("korean", "ko", "Korean")
        )
        for ((word, c, name) in checks) {
            if (Regex("\\b$word\\b").containsMatchIn(lower)) {
                return Pair(c, name)
            }
        }
        val mapped = langCodeToDisplayName(fallbackCode)
        return if (mapped.isNotEmpty()) Pair(fallbackCode.lowercase(Locale.ROOT), mapped) else Pair("en", "Original")
    }

    private fun searchStrictOfficialTrailerIds(titleQuery: String, year: String, langHint: String = ""): List<String> {
        val candidates = ArrayList<String>()
        try {
            val clean = titleQuery.trim()
            if (clean.isEmpty()) return emptyList()

            val searchQuery = buildString {
                append(clean)
                if (year.isNotEmpty()) append(" $year")
                if (langHint.isNotEmpty()) append(" $langHint")
                append(" Official Trailer")
            }
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250101.00.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", searchQuery)
            }.toString()

            val conn = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "POST"
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            conn.disconnect()

            val titleWords = clean.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 && it != "the" && it != "and" }

            val chunks = resp.split("\"videoRenderer\":{\"videoId\":\"")
            for (i in 1 until chunks.size) {
                val chunk = chunks[i]
                if (chunk.length < 11) continue
                val vid = chunk.substring(0, 11)
                if (!vid.matches(Regex("[a-zA-Z0-9_-]{11}"))) continue

                val titleMatch = Pattern.compile(""""title":\{"runs":\[\{"text":"([^"]+)"""").matcher(chunk)
                if (!titleMatch.find()) continue
                val vTitle = (titleMatch.group(1) ?: "").lowercase(Locale.ROOT)

                if (!vTitle.contains("trailer") && !vTitle.contains("teaser")) continue
                if (isReviewOrJunkTitle(vTitle)) continue
                if (langHint.isNotEmpty() && !vTitle.contains(langHint.lowercase(Locale.ROOT))) continue

                val matchesMovie = if (titleWords.isNotEmpty()) {
                    titleWords.all { w -> vTitle.contains(w) }
                } else {
                    vTitle.contains(clean.lowercase(Locale.ROOT))
                }

                if (matchesMovie && !candidates.contains(vid)) {
                    candidates.add(vid)
                    if (candidates.size >= 3) break
                }
            }
        } catch (_: Throwable) {}
        return candidates
    }

    private fun fetchYtChunkBytes(target: YoutubeProxyTarget, chunkIndex: Long): ByteArray? {
        val chunkKey = "${target.id}_$chunkIndex"
        youtubeChunkCache[chunkKey]?.let { return it }

        val future = youtubeChunkInflight.computeIfAbsent(chunkKey) {
            ioExecutor.submit<ByteArray?> {
                val cStart = chunkIndex * 524288L
                val cEnd = minOf(target.totalSize - 1L, cStart + 524288L - 1L)
                if (cStart > cEnd) return@submit ByteArray(0)
                val expectedLen = (cEnd - cStart + 1L).toInt()
                val sep = if (target.rawUrl.contains("?")) "&" else "?"
                val rangedUrl = "${target.rawUrl}${sep}range=$cStart-$cEnd"

                for (attempt in 0..2) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(rangedUrl).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                            connectTimeout = 6000
                            readTimeout = 8000
                            requestMethod = "GET"
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", target.userAgent)
                            setRequestProperty("Accept", "*/*")
                            setRequestProperty("Connection", "keep-alive")
                        }
                        if (conn.responseCode in 200..299) {
                            val bytes = conn.inputStream.use { readExactBytes(it, expectedLen) }
                            if (bytes.isNotEmpty()) {
                                if (youtubeChunkCache.size > 48) {
                                    val firstKey = youtubeChunkCache.keys().nextElement()
                                    if (firstKey != null) youtubeChunkCache.remove(firstKey)
                                }
                                youtubeChunkCache[chunkKey] = bytes
                                return@submit bytes
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try { conn?.disconnect() } catch (_: Throwable) {}
                    }
                }
                null
            }
        }

        return try {
            future.get()
        } catch (_: Throwable) {
            null
        } finally {
            youtubeChunkInflight.remove(chunkKey)
        }
    }

    private fun prewarmYoutubeChunks(target: YoutubeProxyTarget, startChunk: Long, count: Int) {
        val maxChunk = (target.totalSize - 1L) / 524288L
        for (i in 0 until count) {
            val idx = startChunk + i
            if (idx > maxChunk) break
            val key = "${target.id}_$idx"
            if (!youtubeChunkCache.containsKey(key) && !youtubeChunkInflight.containsKey(key)) {
                ioExecutor.submit { fetchYtChunkBytes(target, idx) }
            }
        }
    }

    private fun handleYoutubeProxyClient(socket: Socket, ytId: String, method: String, rangeHeader: String?) {
        val target = youtubeStreamRegistry[ytId]
        if (target == null || target.totalSize <= 0L) {
            val out = proxyOutput(socket)
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val totalSize = target.totalSize
        var reqStart = 0L
        var reqEnd = totalSize - 1L
        var isPartial = false

        if (!rangeHeader.isNullOrEmpty() && rangeHeader.startsWith("bytes=")) {
            isPartial = true
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(",").trim()
            val dashIdx = spec.indexOf('-')
            if (dashIdx >= 0) {
                val sPart = spec.substring(0, dashIdx).trim()
                val ePart = spec.substring(dashIdx + 1).trim()
                if (sPart.isNotEmpty()) {
                    reqStart = sPart.toLongOrNull() ?: 0L
                    if (ePart.isNotEmpty()) {
                        reqEnd = ePart.toLongOrNull() ?: (totalSize - 1L)
                    }
                } else if (ePart.isNotEmpty()) {
                    val suffixLen = ePart.toLongOrNull() ?: 0L
                    reqStart = (totalSize - suffixLen).coerceAtLeast(0L)
                }
            }
        }

        reqStart = reqStart.coerceIn(0L, (totalSize - 1L).coerceAtLeast(0L))
        reqEnd = reqEnd.coerceIn(reqStart, (totalSize - 1L).coerceAtLeast(0L))
        val contentLength = reqEnd - reqStart + 1L

        val out = BufferedOutputStream(proxyOutput(socket), 65536)
        val headers = buildString {
            append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $contentLength\r\n")
            if (isPartial) {
                append("Content-Range: bytes $reqStart-$reqEnd/$totalSize\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        if (method == "HEAD") return

        var curPos = reqStart
        while (curPos <= reqEnd) {
            val chunkIdx = curPos / 524288L
            prewarmYoutubeChunks(target, chunkIdx + 1L, 4)

            val chunkBytes = fetchYtChunkBytes(target, chunkIdx) ?: break
            if (chunkBytes.isEmpty()) break

            val chunkStartOffset = chunkIdx * 524288L
            val offsetInChunk = (curPos - chunkStartOffset).toInt()
            if (offsetInChunk < 0 || offsetInChunk >= chunkBytes.size) break

            val maxAvailable = chunkBytes.size - offsetInChunk
            val needed = (reqEnd - curPos + 1L).coerceAtMost(maxAvailable.toLong()).toInt()
            if (needed <= 0) break

            out.write(chunkBytes, offsetInChunk, needed)
            curPos += needed.toLong()
        }
        out.flush()
    }

    @ReactMethod
    fun resolveVerifiedTrailer(
        cacheKey: String,
        title: String,
        year: String,
        imdbId: String,
        tmdbYtKeysJson: String,
        promise: Promise
    ) {
        ioExecutor.submit {
            try {
                val cached = verifiedTrailerCache[cacheKey]
                if (cached != null) {
                    val res = Arguments.createMap()
                    res.putBoolean("available", true)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", cached.maxQualityLabel)
                    res.putString("activeLang", cached.activeLangName)
                    res.putInt("langCount", cached.languages.size)
                    promise.resolve(res)
                    return@submit
                }

                val imdbParts = imdbId.split("|")
                val tmdbIdStr = imdbParts.getOrNull(0) ?: ""
                val isTv = (imdbParts.getOrNull(1) == "tv")
                val origLangCode = (imdbParts.getOrNull(2) ?: "").trim().lowercase(Locale.ROOT)
                val origLangName = langCodeToDisplayName(origLangCode)

                val langOptionsMap = LinkedHashMap<String, TrailerLanguageOption>()
                val usedVideoIds = HashSet<String>()

                // 1. Parse TMDb videos (supports both structured JSON objects and legacy string array)
                try {
                    val arr = JSONArray(tmdbYtKeysJson)
                    for (i in 0 until arr.length()) {
                        val rawElem = arr.opt(i)
                        var key = ""
                        var vLang = origLangCode
                        var vName = ""
                        if (rawElem is JSONObject) {
                            key = rawElem.optString("key", "").trim()
                            vLang = rawElem.optString("lang", origLangCode).trim().lowercase(Locale.ROOT)
                            vName = rawElem.optString("name", "").trim()
                        } else if (rawElem is String) {
                            key = rawElem.trim()
                        }

                        if (key.length == 11 && !usedVideoIds.contains(key) && verifyYoutubeOembedClean(key, true)) {
                            val (detCode, detName) = detectLanguageFromTitle(vName, vLang)
                            val isOrig = (origLangCode.isNotEmpty() && detCode == origLangCode) ||
                                (origLangName.isNotEmpty() && detName.equals(origLangName, ignoreCase = true))
                            val slotKey = detName.lowercase(Locale.ROOT)
                            if (!langOptionsMap.containsKey(slotKey)) {
                                usedVideoIds.add(key)
                                langOptionsMap[slotKey] = TrailerLanguageOption(
                                    langCode = detCode,
                                    langName = detName,
                                    videoId = key,
                                    label = if (isOrig && detName != "Original") "$detName (Original)" else detName,
                                    isOriginal = isOrig
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // 2. If Original Language trailer wasn't in TMDb results, search YouTube specifically for it first!
                if (origLangName.isNotEmpty() && !langOptionsMap.containsKey(origLangName.lowercase(Locale.ROOT))) {
                    val origCandidates = searchStrictOfficialTrailerIds(title, year, origLangName)
                    for (candId in origCandidates) {
                        if (!usedVideoIds.contains(candId) && verifyYoutubeOembedClean(candId, false)) {
                            usedVideoIds.add(candId)
                            langOptionsMap[origLangName.lowercase(Locale.ROOT)] = TrailerLanguageOption(
                                langCode = origLangCode,
                                langName = origLangName,
                                videoId = candId,
                                label = "$origLangName (Original)",
                                isOriginal = true
                            )
                            break
                        }
                    }
                }

                // 3. Fallback to KinoCheck if no YouTube trailer found yet
                if (langOptionsMap.isEmpty() && tmdbIdStr.isNotEmpty()) {
                    val kcKey = fetchKinocheckTrailerId(tmdbIdStr, isTv)
                    if (kcKey.length == 11 && !usedVideoIds.contains(kcKey)) {
                        usedVideoIds.add(kcKey)
                        val dName = origLangName.ifEmpty { "Original" }
                        langOptionsMap[dName.lowercase(Locale.ROOT)] = TrailerLanguageOption(
                            langCode = origLangCode.ifEmpty { "en" },
                            langName = dName,
                            videoId = kcKey,
                            label = if (origLangName.isNotEmpty()) "$dName (Original)" else "Official Trailer",
                            isOriginal = true
                        )
                    }
                }

                // 4. General strict YouTube search fallback if still empty
                if (langOptionsMap.isEmpty()) {
                    val ytCandidates = searchStrictOfficialTrailerIds(title, year, "")
                    for (candId in ytCandidates) {
                        if (!usedVideoIds.contains(candId) && verifyYoutubeOembedClean(candId, false)) {
                            usedVideoIds.add(candId)
                            val dName = origLangName.ifEmpty { "Original" }
                            langOptionsMap[dName.lowercase(Locale.ROOT)] = TrailerLanguageOption(
                                langCode = origLangCode.ifEmpty { "en" },
                                langName = dName,
                                videoId = candId,
                                label = if (origLangName.isNotEmpty()) "$dName (Original)" else "Official Trailer",
                                isOriginal = true
                            )
                            break
                        }
                    }
                }

                // 5. Discover additional dubbed language trailers (Tamil / Telugu / Hindi / Malayalam / English)
                if (langOptionsMap.isNotEmpty()) {
                    val extraLangs = listOf(
                        Pair("ta", "Tamil"),
                        Pair("te", "Telugu"),
                        Pair("hi", "Hindi"),
                        Pair("ml", "Malayalam"),
                        Pair("en", "English")
                    )
                    for ((code, name) in extraLangs) {
                        if (langOptionsMap.size >= 4) break
                        if (langOptionsMap.containsKey(name.lowercase(Locale.ROOT))) continue
                        val cands = searchStrictOfficialTrailerIds(title, year, name)
                        for (cid in cands) {
                            if (!usedVideoIds.contains(cid) && verifyYoutubeOembedClean(cid, false)) {
                                usedVideoIds.add(cid)
                                langOptionsMap[name.lowercase(Locale.ROOT)] = TrailerLanguageOption(
                                    langCode = code,
                                    langName = name,
                                    videoId = cid,
                                    label = name,
                                    isOriginal = false
                                )
                                break
                            }
                        }
                    }
                }

                if (langOptionsMap.isNotEmpty()) {
                    val sortedLangs = langOptionsMap.values.sortedWith(
                        compareByDescending<TrailerLanguageOption> { it.isOriginal }
                            .thenBy { it.langName }
                    )
                    val primary = sortedLangs.first()
                    val info = VerifiedTrailerInfo(
                        engine = "YT_CINEMA",
                        videoId = primary.videoId,
                        directStreams = emptyList(),
                        maxQualityLabel = "4K / 1080p",
                        languages = sortedLangs,
                        activeLangName = primary.langName
                    )
                    verifiedTrailerCache[cacheKey] = info
                    val res = Arguments.createMap()
                    res.putBoolean("available", true)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", "4K / 1080p")
                    res.putString("activeLang", primary.langName)
                    res.putInt("langCount", sortedLangs.size)
                    promise.resolve(res)
                    return@submit
                }

                // 6. Final Apple iTunes direct stream fallback
                val appleStreams = fetchAppleItunesTrailerStreams(title, year)
                if (appleStreams.isNotEmpty()) {
                    val bestQ = appleStreams.first().label
                    val info = VerifiedTrailerInfo("IMDB_DIRECT", "", appleStreams, bestQ, emptyList(), "English")
                    verifiedTrailerCache[cacheKey] = info
                    val res = Arguments.createMap()
                    res.putBoolean("available", true)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", bestQ)
                    res.putString("activeLang", "English")
                    res.putInt("langCount", 1)
                    promise.resolve(res)
                    return@submit
                }

                val res = Arguments.createMap()
                res.putBoolean("available", false)
                res.putString("cacheKey", cacheKey)
                res.putString("bestQuality", "")
                res.putString("activeLang", "")
                res.putInt("langCount", 0)
                promise.resolve(res)
            } catch (_: Throwable) {
                val res = Arguments.createMap()
                res.putBoolean("available", false)
                res.putString("cacheKey", cacheKey)
                res.putString("bestQuality", "")
                res.putString("activeLang", "")
                res.putInt("langCount", 0)
                promise.resolve(res)
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            reactContext.resources.displayMetrics
        ).toInt()
    }

    private fun formatTimeSec(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val mins = s / 60
        val secs = s % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun makeCinemaPillBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex.isNotEmpty()) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun attachTvFocusHighlight(view: View, normalBg: GradientDrawable, focusedBg: GradientDrawable) {
        view.isFocusable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) focusedBg else normalBg
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @ReactMethod
    fun playYoutubeTrailer(cacheKey: String, title: String, promise: Promise) {
        val info = verifiedTrailerCache[cacheKey]
        if (info == null) {
            promise.reject("E_UNAVAIL", "No verified trailer available")
            return
        }

        UiThreadUtil.runOnUiThread {
            val activity: Activity? = reactContext.currentActivity
            if (activity == null || activity.isFinishing) {
                promise.reject("E_ACT", "Activity unavailable")
                return@runOnUiThread
            }

            val prevOrientation = activity.requestedOrientation
            try {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } catch (_: Throwable) {}

            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            val uiHandler = Handler(Looper.getMainLooper())
            var controlsVisible = true
            var isZoomed = false
            var activeQualityLabel = "1080p FHD"
            var activeLangLabel = info.activeLangName.ifEmpty { "Original" }
            var currentVideoId = info.videoId

            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
            }

            val playerStage = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            root.addView(playerStage)

            // Dark translucent scrim when controls are visible (matches GDStream Play in App)
            val controlsScrim = FrameLayout(activity).apply {
                setBackgroundColor(Color.parseColor("#66000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(controlsScrim)

            val spinner = ProgressBar(activity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
            }
            root.addView(spinner)

            // Double-Tap Seek Bubble Overlay (Left / Right ±10s)
            val seekBubbleContainer = FrameLayout(activity).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val seekBubbleCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = makeCinemaPillBg("#99000000", "#33FFFFFF", 46)
                layoutParams = FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    marginStart = dp(56)
                    marginEnd = dp(56)
                }
            }
            val seekBubbleIcon = TextView(activity).apply {
                text = "↻"
                setTextColor(Color.WHITE)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val seekBubbleText = TextView(activity).apply {
                text = "+10s"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            seekBubbleCard.addView(seekBubbleIcon)
            seekBubbleCard.addView(seekBubbleText)
            seekBubbleContainer.addView(seekBubbleCard)
            root.addView(seekBubbleContainer)

            // Brightness / Volume Vertical Swipe HUD (Phone)
            val gestureHudPill = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(dp(10), dp(14), dp(10), dp(14))
                background = makeCinemaPillBg("#E00F1117", "#26FFFFFF", 30)
                layoutParams = FrameLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                    marginStart = dp(38)
                    marginEnd = dp(38)
                }
            }
            val gestureHudIcon = TextView(activity).apply {
                text = "☀"
                setTextColor(Color.parseColor("#E50914"))
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val gestureBarTrack = FrameLayout(activity).apply {
                background = makeCinemaPillBg("#33FFFFFF", "", 4)
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(100)).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            }
            val gestureBarFill = View(activity).apply {
                background = makeCinemaPillBg("#E50914", "", 4)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM)
            }
            gestureBarTrack.addView(gestureBarFill)
            val gestureHudPct = TextView(activity).apply {
                text = "70%"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            gestureHudPill.addView(gestureHudIcon)
            gestureHudPill.addView(gestureBarTrack)
            gestureHudPill.addView(gestureHudPct)
            root.addView(gestureHudPill)

            // ================= TOP BAR (GDStream Netflix-Style Header) =================
            val topBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(10))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            }

            val backCircleNormal = makeCinemaPillBg("#B814161E", "#26FFFFFF", 21)
            val backCircleFocus = makeCinemaPillBg("#44FFD700", "#FFD700", 21)
            val backBtn = TextView(activity).apply {
                text = "←"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = backCircleNormal
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                setOnClickListener { dialog.dismiss() }
            }
            attachTvFocusHighlight(backBtn, backCircleNormal, backCircleFocus)

            val titleCenterBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val mainTitleTv = TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                gravity = Gravity.CENTER
            }

            val subMetaRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }

            val trailerTagTv = TextView(activity).apply {
                text = "OFFICIAL TRAILER"
                setTextColor(Color.parseColor("#F5C518"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dp(8), 0)
            }

            val topQualityBadge = TextView(activity).apply {
                text = activeQualityLabel
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = makeCinemaPillBg("#E50914", "", 4)
            }

            val topLangBadge = TextView(activity).apply {
                text = activeLangLabel.uppercase(Locale.ROOT)
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = makeCinemaPillBg("#5E35B1", "#FFD700", 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            }

            subMetaRow.addView(trailerTagTv)
            subMetaRow.addView(topQualityBadge)
            subMetaRow.addView(topLangBadge)
            titleCenterBox.addView(mainTitleTv)
            titleCenterBox.addView(subMetaRow)

            // Spacer on right to keep title dead-center
            val rightSpacer = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            }

            topBar.addView(backBtn)
            topBar.addView(titleCenterBox)
            topBar.addView(rightSpacer)
            controlsScrim.addView(topBar)

            // ================= CENTER CONTROLS (-10s | PLAY/PAUSE | +10s) =================
            val centerControlsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            val seekCircleNormal = makeCinemaPillBg("#9912141C", "#29FFFFFF", 32)
            val seekCircleFocus = makeCinemaPillBg("#44FFD700", "#FFD700", 32)

            val rewCircleBtn = TextView(activity).apply {
                text = "-10s"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = seekCircleNormal
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            }
            attachTvFocusHighlight(rewCircleBtn, seekCircleNormal, seekCircleFocus)

            val playCircleNormal = makeCinemaPillBg("#E6E50914", "#40FFFFFF", 41)
            val playCircleFocus = makeCinemaPillBg("#E50914", "#FFD700", 41)

            val centerPlayBtn = TextView(activity).apply {
                text = "❚❚"
                setTextColor(Color.WHITE)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = playCircleNormal
                layoutParams = LinearLayout.LayoutParams(dp(82), dp(82)).apply {
                    marginStart = dp(48)
                    marginEnd = dp(48)
                }
            }
            attachTvFocusHighlight(centerPlayBtn, playCircleNormal, playCircleFocus)

            val ffCircleBtn = TextView(activity).apply {
                text = "+10s"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = seekCircleNormal
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            }
            attachTvFocusHighlight(ffCircleBtn, seekCircleNormal, seekCircleFocus)

            centerControlsRow.addView(rewCircleBtn)
            centerControlsRow.addView(centerPlayBtn)
            centerControlsRow.addView(ffCircleBtn)
            controlsScrim.addView(centerControlsRow)

            // ================= BOTTOM BAR (SCRUBBER + ACTION PILLS) =================
            val bottomContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(8), dp(22), dp(16))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }

            val scrubberRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(10))
            }

            val curTimeTv = TextView(activity).apply {
                text = "00:00"
                setTextColor(Color.parseColor("#E6ECF5"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val seekBar = SeekBar(activity).apply {
                max = 1000
                progressDrawable?.setColorFilter(Color.parseColor("#E50914"), android.graphics.PorterDuff.Mode.SRC_IN)
                thumb?.setColorFilter(Color.parseColor("#E50914"), android.graphics.PorterDuff.Mode.SRC_IN)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val remTimeTv = TextView(activity).apply {
                text = "-00:00"
                setTextColor(Color.parseColor("#E6ECF5"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            scrubberRow.addView(curTimeTv)
            scrubberRow.addView(seekBar)
            scrubberRow.addView(remTimeTv)
            bottomContainer.addView(scrubberRow)

            val actionPillsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val pillNormalBg = makeCinemaPillBg("#C8161922", "#22FFFFFF", 999)
            val pillFocusBg = makeCinemaPillBg("#44FFD700", "#FFD700", 999)

            val langPillBtn = TextView(activity).apply {
                text = "🌐 Language: $activeLangLabel ▾"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(15), dp(8), dp(15), dp(8))
                background = pillNormalBg
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) }
            }
            attachTvFocusHighlight(langPillBtn, pillNormalBg, pillFocusBg)

            val qualityPillBtn = TextView(activity).apply {
                text = "⚙ Quality: $activeQualityLabel ▾"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(15), dp(8), dp(15), dp(8))
                background = pillNormalBg
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) }
            }
            attachTvFocusHighlight(qualityPillBtn, pillNormalBg, pillFocusBg)

            val zoomPillBtn = TextView(activity).apply {
                text = "⛶ Fit"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(15), dp(8), dp(15), dp(8))
                background = pillNormalBg
            }
            attachTvFocusHighlight(zoomPillBtn, pillNormalBg, pillFocusBg)

            actionPillsRow.addView(langPillBtn)
            actionPillsRow.addView(qualityPillBtn)
            actionPillsRow.addView(zoomPillBtn)
            bottomContainer.addView(actionPillsRow)
            controlsScrim.addView(bottomContainer)

            // ================= FLOATING SHEET OVERLAY (LANGUAGE / QUALITY PICKER) =================
            val sheetBackdrop = FrameLayout(activity).apply {
                visibility = View.GONE
                setBackgroundColor(Color.parseColor("#99000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { it.visibility = View.GONE }
            }
            val sheetCard = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = makeCinemaPillBg("#F514171F", "#2B3242", 16)
                layoutParams = FrameLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                setOnClickListener { /* consume click */ }
            }
            val sheetHeaderRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(10))
            }
            val sheetTitleTv = TextView(activity).apply {
                text = "Select Option"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sheetCloseTv = TextView(activity).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { sheetBackdrop.visibility = View.GONE }
            }
            sheetHeaderRow.addView(sheetTitleTv)
            sheetHeaderRow.addView(sheetCloseTv)
            val sheetItemsList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            sheetCard.addView(sheetHeaderRow)
            sheetCard.addView(sheetItemsList)
            sheetBackdrop.addView(sheetCard)
            root.addView(sheetBackdrop)

            // Auto-hide timer logic (5s like Play in App)
            val hideControlsRunnable = Runnable {
                if (sheetBackdrop.visibility != View.VISIBLE) {
                    controlsVisible = false
                    controlsScrim.visibility = View.GONE
                }
            }
            fun resetAutoHideTimer() {
                uiHandler.removeCallbacks(hideControlsRunnable)
                uiHandler.postDelayed(hideControlsRunnable, 5000L)
            }
            fun setControlsShown(show: Boolean) {
                controlsVisible = show
                controlsScrim.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    resetAutoHideTimer()
                    centerPlayBtn.requestFocus()
                } else {
                    uiHandler.removeCallbacks(hideControlsRunnable)
                }
            }

            // Double-Tap Seek Bubble Helper
            var accumSeekDelta = 0
            var lastSeekDir = 0
            val hideSeekBubbleRunnable = Runnable {
                seekBubbleContainer.visibility = View.GONE
                accumSeekDelta = 0
                lastSeekDir = 0
            }
            fun showAnimatedSeekBubble(deltaSec: Int) {
                val dir = if (deltaSec >= 0) 1 else -1
                if (lastSeekDir != dir) {
                    accumSeekDelta = 0
                    lastSeekDir = dir
                }
                accumSeekDelta += deltaSec
                seekBubbleIcon.text = if (dir > 0) "↻" else "↺"
                seekBubbleText.text = if (accumSeekDelta > 0) "+${accumSeekDelta}s" else "${accumSeekDelta}s"
                val lp = seekBubbleCard.layoutParams as FrameLayout.LayoutParams
                lp.gravity = Gravity.CENTER_VERTICAL or (if (dir > 0) Gravity.END else Gravity.START)
                seekBubbleCard.layoutParams = lp
                seekBubbleContainer.visibility = View.VISIBLE
                uiHandler.removeCallbacks(hideSeekBubbleRunnable)
                uiHandler.postDelayed(hideSeekBubbleRunnable, 850L)
            }

            // Player Action Callbacks
            var onSeekDelta: ((Int) -> Unit)? = null
            var onTogglePlay: (() -> Unit)? = null
            var onSeekPercent: ((Int) -> Unit)? = null
            var onOpenQualitySheet: (() -> Unit)? = null
            var onOpenLanguageSheet: (() -> Unit)? = null

            rewCircleBtn.setOnClickListener {
                resetAutoHideTimer()
                onSeekDelta?.invoke(-10)
                showAnimatedSeekBubble(-10)
            }
            ffCircleBtn.setOnClickListener {
                resetAutoHideTimer()
                onSeekDelta?.invoke(10)
                showAnimatedSeekBubble(10)
            }
            centerPlayBtn.setOnClickListener {
                resetAutoHideTimer()
                onTogglePlay?.invoke()
            }
            zoomPillBtn.setOnClickListener {
                resetAutoHideTimer()
                isZoomed = !isZoomed
                val scale = if (isZoomed) 1.28f else 1.0f
                playerStage.scaleX = scale
                playerStage.scaleY = scale
                zoomPillBtn.text = if (isZoomed) "⛶ Zoom" else "⛶ Fit"
            }
            qualityPillBtn.setOnClickListener {
                uiHandler.removeCallbacks(hideControlsRunnable)
                onOpenQualitySheet?.invoke()
            }
            langPillBtn.setOnClickListener {
                uiHandler.removeCallbacks(hideControlsRunnable)
                onOpenLanguageSheet?.invoke()
            }

            // Touch & Gesture Layer (Single Tap = Controls Toggle, Double Tap Left/Right = ±10s, Vertical Swipe = Brightness/Volume)
            val audioManager = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val hideHudRunnable = Runnable { gestureHudPill.visibility = View.GONE }

            val gestureDetector = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    if (sheetBackdrop.visibility == View.VISIBLE) {
                        sheetBackdrop.visibility = View.GONE
                        resetAutoHideTimer()
                        return true
                    }
                    setControlsShown(!controlsVisible)
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    val w = root.width.coerceAtLeast(1)
                    val isRight = e.x >= (w / 2f)
                    val delta = if (isRight) 10 else -10
                    onSeekDelta?.invoke(delta)
                    showAnimatedSeekBubble(delta)
                    return true
                }
            })

            var swipeMode = 0 // 0=none, 1=brightness, 2=volume
            var swipeStartY = 0f
            var swipeStartVal = 0.6f

            val touchOverlay = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    val w = root.width.coerceAtLeast(1)
                    val h = root.height.coerceAtLeast(1)
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            swipeMode = 0
                            swipeStartY = event.y
                            val isLeft = event.x < (w / 2f)
                            if (isLeft) {
                                val curB = dialog.window?.attributes?.screenBrightness ?: -1f
                                swipeStartVal = if (curB in 0f..1f) curB else 0.6f
                            } else {
                                val maxV = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val curV = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                swipeStartVal = curV.toFloat() / maxV.toFloat()
                            }
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dy = swipeStartY - event.y
                            if (swipeMode == 0 && kotlin.math.abs(dy) > dp(18)) {
                                swipeMode = if (event.x < (w / 2f)) 1 else 2
                            }
                            if (swipeMode == 1) {
                                val nextB = (swipeStartVal + (dy / (h * 0.65f))).coerceIn(0.05f, 1.0f)
                                dialog.window?.attributes = dialog.window?.attributes?.apply {
                                    screenBrightness = nextB
                                }
                                val pct = (nextB * 100).toInt()
                                gestureHudIcon.text = "☀"
                                gestureHudPct.text = "$pct%"
                                val lp = gestureBarFill.layoutParams as FrameLayout.LayoutParams
                                lp.height = (dp(100) * nextB).toInt().coerceAtLeast(dp(4))
                                gestureBarFill.layoutParams = lp
                                val hLp = gestureHudPill.layoutParams as FrameLayout.LayoutParams
                                hLp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                                gestureHudPill.layoutParams = hLp
                                gestureHudPill.visibility = View.VISIBLE
                                uiHandler.removeCallbacks(hideHudRunnable)
                            } else if (swipeMode == 2) {
                                val nextV = (swipeStartVal + (dy / (h * 0.65f))).coerceIn(0f, 1.0f)
                                val maxV = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (nextV * maxV).toInt(), 0)
                                val pct = (nextV * 100).toInt()
                                gestureHudIcon.text = if (pct == 0) "🔇" else "🔊"
                                gestureHudPct.text = "$pct%"
                                val lp = gestureBarFill.layoutParams as FrameLayout.LayoutParams
                                lp.height = (dp(100) * nextV).toInt().coerceAtLeast(dp(4))
                                gestureBarFill.layoutParams = lp
                                val hLp = gestureHudPill.layoutParams as FrameLayout.LayoutParams
                                hLp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                                gestureHudPill.layoutParams = hLp
                                gestureHudPill.visibility = View.VISIBLE
                                uiHandler.removeCallbacks(hideHudRunnable)
                            }
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            if (swipeMode != 0) {
                                uiHandler.postDelayed(hideHudRunnable, 800L)
                            }
                            swipeMode = 0
                        }
                    }
                    true
                }
            }
            // Place touchOverlay right above playerStage and below controlsScrim
            root.addView(touchOverlay, 1)

            // TV D-Pad Remote Key Support
            dialog.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        if (sheetBackdrop.visibility == View.VISIBLE) {
                            sheetBackdrop.visibility = View.GONE
                            resetAutoHideTimer()
                            return@setOnKeyListener true
                        }
                        dialog.dismiss()
                        return@setOnKeyListener true
                    }
                    if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        onTogglePlay?.invoke()
                        return@setOnKeyListener true
                    }
                    if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_REWIND) {
                        onSeekDelta?.invoke(-10)
                        showAnimatedSeekBubble(-10)
                        return@setOnKeyListener true
                    }
                    if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                        onSeekDelta?.invoke(10)
                        showAnimatedSeekBubble(10)
                        return@setOnKeyListener true
                    }
                    if (!controlsVisible) {
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                            onSeekDelta?.invoke(-10)
                            showAnimatedSeekBubble(-10)
                            return@setOnKeyListener true
                        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                            onSeekDelta?.invoke(10)
                            showAnimatedSeekBubble(10)
                            return@setOnKeyListener true
                        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        ) {
                            setControlsShown(true)
                            return@setOnKeyListener true
                        }
                    } else {
                        resetAutoHideTimer()
                    }
                }
                false
            }

            dialog.setContentView(root)

            var mediaPlayer: MediaPlayer? = null
            var activeWebView: WebView? = null

            if (info.engine == "IMDB_DIRECT" && info.directStreams.isNotEmpty()) {
                val streams = info.directStreams
                activeQualityLabel = streams.first().label
                topQualityBadge.text = activeQualityLabel
                qualityPillBtn.text = "⚙ Quality: $activeQualityLabel ▾"
                langPillBtn.visibility = View.GONE

                val surfaceView = SurfaceView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                }
                playerStage.addView(surfaceView)

                var activeIdx = 0
                var prepared = false
                var isSeeking = false
                var resumeMs = 0

                fun loadDirectStream(idx: Int, seekToMs: Int) {
                    activeIdx = idx
                    resumeMs = seekToMs
                    activeQualityLabel = streams[idx].label
                    topQualityBadge.text = activeQualityLabel
                    qualityPillBtn.text = "⚙ Quality: $activeQualityLabel ▾"
                    spinner.visibility = View.VISIBLE
                    prepared = false
                    try { mediaPlayer?.stop() } catch (_: Throwable) {}
                    try { mediaPlayer?.release() } catch (_: Throwable) {}

                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    mp.setDisplay(surfaceView.holder)
                    mp.setOnPreparedListener {
                        prepared = true
                        spinner.visibility = View.GONE
                        if (resumeMs > 0) mp.seekTo(resumeMs)
                        mp.start()
                        centerPlayBtn.text = "❚❚"
                        resetAutoHideTimer()
                    }
                    mp.setOnCompletionListener {
                        centerPlayBtn.text = "▶"
                        setControlsShown(true)
                    }
                    try {
                        mp.setDataSource(streams[idx].videoUrl)
                        mp.prepareAsync()
                    } catch (_: Throwable) {
                        spinner.visibility = View.GONE
                    }
                }

                onTogglePlay = {
                    val mp = mediaPlayer
                    if (mp != null && prepared) {
                        if (mp.isPlaying) {
                            mp.pause()
                            centerPlayBtn.text = "▶"
                        } else {
                            mp.start()
                            centerPlayBtn.text = "❚❚"
                        }
                    }
                }
                onSeekDelta = { delta ->
                    val mp = mediaPlayer
                    if (mp != null && prepared) {
                        mp.seekTo((mp.currentPosition + delta * 1000).coerceIn(0, mp.duration.coerceAtLeast(0)))
                    }
                }
                onOpenQualitySheet = {
                    sheetTitleTv.text = "Select Video Quality"
                    sheetItemsList.removeAllViews()
                    for (i in streams.indices) {
                        val sel = (i == activeIdx)
                        val rowTv = TextView(activity).apply {
                            text = if (sel) "✓   ${streams[i].label}" else "      ${streams[i].label}"
                            setTextColor(if (sel) Color.parseColor("#E50914") else Color.WHITE)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(12), dp(11), dp(12), dp(11))
                            background = makeCinemaPillBg(if (sel) "#2AE50914" else "#1A1E29", if (sel) "#E50914" else "#283040", 8)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = dp(8)
                            }
                            setOnClickListener {
                                sheetBackdrop.visibility = View.GONE
                                if (activeIdx != i) {
                                    val pos = try { if (prepared) mediaPlayer?.currentPosition ?: 0 else 0 } catch (_: Throwable) { 0 }
                                    loadDirectStream(i, pos)
                                }
                                resetAutoHideTimer()
                            }
                        }
                        sheetItemsList.addView(rowTv)
                    }
                    sheetBackdrop.visibility = View.VISIBLE
                }

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {}
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        isSeeking = false
                        val mp = mediaPlayer ?: return
                        if (prepared && sb != null && mp.duration > 0) {
                            mp.seekTo(((sb.progress.toLong() * mp.duration) / 1000L).toInt())
                        }
                        resetAutoHideTimer()
                    }
                })

                val progressRunner = object : Runnable {
                    override fun run() {
                        val mp = mediaPlayer
                        if (mp != null && prepared && !isSeeking) {
                            try {
                                val pos = mp.currentPosition
                                val dur = mp.duration
                                if (dur > 0) {
                                    seekBar.progress = ((pos.toLong() * 1000L) / dur).toInt()
                                    curTimeTv.text = formatTimeSec(pos / 1000)
                                    remTimeTv.text = "-${formatTimeSec(((dur - pos).coerceAtLeast(0)) / 1000)}"
                                }
                            } catch (_: Throwable) {}
                        }
                        uiHandler.postDelayed(this, 400)
                    }
                }

                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        loadDirectStream(activeIdx, resumeMs)
                        uiHandler.post(progressRunner)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        try { if (prepared) resumeMs = mediaPlayer?.currentPosition ?: 0 } catch (_: Throwable) {}
                    }
                })
            } else {
                // Fullscreen YouTube Cinema Engine with TRUE VP9/AV1 4K Support + Multi-Language Switcher
                val webView = WebView(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    isClickable = false
                    isFocusable = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                }
                activeWebView = webView
                playerStage.addView(webView)

                var isSeeking = false
                var totalDurationSec = 0
                val qualityMap = linkedMapOf(
                    "hd2160" to "2160p 4K",
                    "hd1440" to "1440p QHD",
                    "hd1080" to "1080p FHD",
                    "hd720" to "720p HD",
                    "large" to "480p",
                    "medium" to "360p"
                )
                var availableQualityCodes = listOf("hd2160", "hd1440", "hd1080", "hd720", "large")
                var selectedQualityCode = "hd1080"

                if (info.languages.size <= 1) {
                    langPillBtn.text = "🌐 $activeLangLabel"
                }

                fun updateBadgesUI() {
                    val qName = qualityMap[selectedQualityCode] ?: activeQualityLabel
                    activeQualityLabel = qName
                    topQualityBadge.text = qName
                    qualityPillBtn.text = "⚙ Quality: $qName ▾"
                    topLangBadge.text = activeLangLabel.uppercase(Locale.ROOT)
                    langPillBtn.text = if (info.languages.size > 1) {
                        "🌐 $activeLangLabel (${info.languages.size}) ▾"
                    } else {
                        "🌐 $activeLangLabel"
                    }
                }
                updateBadgesUI()

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onPlayerReady(durSec: Double, qualitiesCsv: String, activeQual: String) {
                        uiHandler.post {
                            spinner.visibility = View.GONE
                            totalDurationSec = durSec.toInt().coerceAtLeast(0)
                            if (qualitiesCsv.isNotEmpty()) {
                                val list = qualitiesCsv.split(",").map { it.trim() }.filter { qualityMap.containsKey(it) }
                                if (list.isNotEmpty()) {
                                    availableQualityCodes = list
                                }
                            }
                            if (activeQual.isNotEmpty() && qualityMap.containsKey(activeQual)) {
                                selectedQualityCode = activeQual
                            }
                            updateBadgesUI()
                            resetAutoHideTimer()
                        }
                    }

                    @JavascriptInterface
                    fun onQualityChanged(newQual: String) {
                        uiHandler.post {
                            spinner.visibility = View.GONE
                            if (qualityMap.containsKey(newQual)) {
                                selectedQualityCode = newQual
                                updateBadgesUI()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onTimeUpdate(curSec: Double, durSec: Double, playing: Boolean) {
                        uiHandler.post {
                            val c = curSec.toInt().coerceAtLeast(0)
                            val d = durSec.toInt().coerceAtLeast(0)
                            totalDurationSec = d
                            if (playing) spinner.visibility = View.GONE
                            centerPlayBtn.text = if (playing) "❚❚" else "▶"
                            if (d > 0 && !isSeeking) {
                                seekBar.progress = ((c.toLong() * 1000L) / d).toInt()
                                curTimeTv.text = formatTimeSec(c)
                                remTimeTv.text = "-${formatTimeSec((d - c).coerceAtLeast(0))}"
                            }
                        }
                    }
                }, "GDNativeBridge")

                onTogglePlay = {
                    webView.evaluateJavascript("window.togglePlay();", null)
                }
                onSeekDelta = { delta ->
                    webView.evaluateJavascript("window.seekBySec($delta);", null)
                }
                onOpenQualitySheet = {
                    sheetTitleTv.text = "Select Trailer Quality"
                    sheetItemsList.removeAllViews()
                    for (qCode in availableQualityCodes) {
                        val label = qualityMap[qCode] ?: qCode
                        val sel = (qCode == selectedQualityCode)
                        val rowTv = TextView(activity).apply {
                            text = if (sel) "✓   $label" else "      $label"
                            setTextColor(if (sel) Color.parseColor("#E50914") else Color.WHITE)
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(12), dp(11), dp(12), dp(11))
                            background = makeCinemaPillBg(if (sel) "#2AE50914" else "#1A1E29", if (sel) "#E50914" else "#283040", 8)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = dp(8)
                            }
                            setOnClickListener {
                                sheetBackdrop.visibility = View.GONE
                                selectedQualityCode = qCode
                                updateBadgesUI()
                                spinner.visibility = View.VISIBLE
                                webView.evaluateJavascript("window.setTrailerQuality('$qCode');", null)
                                resetAutoHideTimer()
                            }
                        }
                        sheetItemsList.addView(rowTv)
                    }
                    sheetBackdrop.visibility = View.VISIBLE
                }

                onOpenLanguageSheet = {
                    if (info.languages.isNotEmpty()) {
                        sheetTitleTv.text = "Select Trailer Language"
                        sheetItemsList.removeAllViews()
                        for (opt in info.languages) {
                            val sel = (opt.videoId == currentVideoId)
                            val rowTv = TextView(activity).apply {
                                text = if (sel) "✓   ${opt.label}" else "      ${opt.label}"
                                setTextColor(if (sel) Color.parseColor("#FFD700") else Color.WHITE)
                                textSize = 14f
                                typeface = Typeface.DEFAULT_BOLD
                                setPadding(dp(12), dp(11), dp(12), dp(11))
                                background = makeCinemaPillBg(if (sel) "#335E35B1" else "#1A1E29", if (sel) "#FFD700" else "#283040", 8)
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    bottomMargin = dp(8)
                                }
                                setOnClickListener {
                                    sheetBackdrop.visibility = View.GONE
                                    if (opt.videoId != currentVideoId) {
                                        currentVideoId = opt.videoId
                                        activeLangLabel = opt.langName
                                        updateBadgesUI()
                                        spinner.visibility = View.VISIBLE
                                        webView.evaluateJavascript(
                                            "window.switchTrailerVideo('${opt.videoId}', '$selectedQualityCode');",
                                            null
                                        )
                                    }
                                    resetAutoHideTimer()
                                }
                            }
                            sheetItemsList.addView(rowTv)
                        }
                        sheetBackdrop.visibility = View.VISIBLE
                    }
                }

                val blockedAdHosts = listOf(
                    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                    "/pagead/", "/api/stats/ads", "adservice.google"
                )

                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString()?.lowercase(Locale.ROOT) ?: ""
                        if (blockedAdHosts.any { u.contains(it) }) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                // NOTE: VP9 & AV1 are ENABLED so true 2160p 4K & 1440p QHD adaptive streams play natively!
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                      * { margin: 0; padding: 0; box-sizing: border-box; background: #000; user-select: none; }
                      html, body { width: 100%; height: 100%; overflow: hidden; background: #000; pointer-events: none; }
                      .stage {
                        position: fixed;
                        top: -18%;
                        left: 0;
                        width: 100%;
                        height: 136%;
                        pointer-events: none;
                        overflow: hidden;
                      }
                      #player { width: 100%; height: 100%; border: 0; pointer-events: none; }
                    </style>
                    </head>
                    <body>
                    <div class="stage"><div id="player"></div></div>
                    <script src="https://www.youtube.com/iframe_api"></script>
                    <script>
                      var player;
                      var activeVid = '$currentVideoId';
                      var targetVq = 'hd1080';
                      function onYouTubeIframeAPIReady() {
                        player = new YT.Player('player', {
                          videoId: activeVid,
                          host: 'https://www.youtube-nocookie.com',
                          playerVars: {
                            'autoplay': 1,
                            'controls': 0,
                            'disablekb': 1,
                            'fs': 0,
                            'iv_load_policy': 3,
                            'modestbranding': 1,
                            'rel': 0,
                            'showinfo': 0,
                            'playsinline': 1
                          },
                          events: {
                            'onReady': function(e) {
                              try {
                                if (e.target.setPlaybackQualityRange) {
                                  e.target.setPlaybackQualityRange(targetVq, targetVq);
                                }
                                e.target.playVideo();
                                var qList = e.target.getAvailableQualityLevels ? e.target.getAvailableQualityLevels() : [];
                                var curQ = e.target.getPlaybackQuality ? e.target.getPlaybackQuality() : targetVq;
                                var dur = e.target.getDuration ? e.target.getDuration() : 0;
                                if (window.GDNativeBridge) {
                                  window.GDNativeBridge.onPlayerReady(dur || 0, (qList || []).join(','), curQ || targetVq);
                                }
                              } catch(err) {}
                            },
                            'onStateChange': function(e) {
                              try {
                                if (e.data === YT.PlayerState.PLAYING) {
                                  var qList = e.target.getAvailableQualityLevels ? e.target.getAvailableQualityLevels() : [];
                                  var curQ = e.target.getPlaybackQuality ? e.target.getPlaybackQuality() : targetVq;
                                  var dur = e.target.getDuration ? e.target.getDuration() : 0;
                                  if (window.GDNativeBridge) {
                                    window.GDNativeBridge.onPlayerReady(dur || 0, (qList || []).join(','), curQ || targetVq);
                                  }
                                }
                              } catch(err) {}
                            },
                            'onPlaybackQualityChange': function(e) {
                              try {
                                if (window.GDNativeBridge && e && e.data) {
                                  window.GDNativeBridge.onQualityChanged(e.data);
                                }
                              } catch(err) {}
                            }
                          }
                        });
                      }
                      setInterval(function() {
                        try {
                          if (player && player.getCurrentTime) {
                            var c = player.getCurrentTime() || 0;
                            var d = player.getDuration ? (player.getDuration() || 0) : 0;
                            var st = player.getPlayerState ? player.getPlayerState() : 1;
                            if (window.GDNativeBridge) {
                              window.GDNativeBridge.onTimeUpdate(c, d, st === 1);
                            }
                          }
                        } catch(e) {}
                      }, 400);
                      window.togglePlay = function() {
                        if (!player) return;
                        var st = player.getPlayerState ? player.getPlayerState() : 1;
                        if (st === 1) player.pauseVideo(); else player.playVideo();
                      };
                      window.seekToSec = function(sec) {
                        if (player && player.seekTo) player.seekTo(sec, true);
                      };
                      window.seekBySec = function(delta) {
                        if (player && player.getCurrentTime) {
                          var t = Math.max(0, (player.getCurrentTime() || 0) + delta);
                          player.seekTo(t, true);
                        }
                      };
                      window.setTrailerQuality = function(vq) {
                        targetVq = vq;
                        if (player && player.getCurrentTime) {
                          var t = player.getCurrentTime() || 0;
                          if (player.setPlaybackQualityRange) {
                            player.setPlaybackQualityRange(vq, vq);
                          }
                          if (player.setPlaybackQuality) {
                            player.setPlaybackQuality(vq);
                          }
                          player.loadVideoById({'videoId': activeVid, 'startSeconds': t, 'suggestedQuality': vq});
                        }
                      };
                      window.switchTrailerVideo = function(newVid, vq) {
                        activeVid = newVid;
                        targetVq = vq || targetVq;
                        if (player && player.loadVideoById) {
                          if (player.setPlaybackQualityRange) {
                            player.setPlaybackQualityRange(targetVq, targetVq);
                          }
                          player.loadVideoById({'videoId': newVid, 'startSeconds': 0, 'suggestedQuality': targetVq});
                        }
                      };
                    </script>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(
                    "https://www.youtube-nocookie.com",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {}
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        isSeeking = false
                        if (sb != null && totalDurationSec > 0) {
                            val targetSec = (sb.progress.toLong() * totalDurationSec) / 1000L
                            webView.evaluateJavascript("window.seekToSec($targetSec);", null)
                        }
                        resetAutoHideTimer()
                    }
                })
            }

            dialog.setOnDismissListener {
                uiHandler.removeCallbacksAndMessages(null)
                try { mediaPlayer?.stop() } catch (_: Throwable) {}
                try { mediaPlayer?.release() } catch (_: Throwable) {}
                mediaPlayer = null
                try {
                    activeWebView?.loadUrl("about:blank")
                    activeWebView?.destroy()
                } catch (_: Throwable) {}
                activeWebView = null
                try {
                    activity.requestedOrientation = prevOrientation
                } catch (_: Throwable) {}
            }

            dialog.show()
            resetAutoHideTimer()
            promise.resolve(true)
        }
    }

    // =========================================================================
    // 📀 BLU-RAY / DVD / DATA .ISO STREAMING ENGINE (FAST PARALLEL UDF 2.50)
    // =========================================================================
    private data class IsoFileEntry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val extents: List<Pair<Long, Long>>
    )

    private fun readUInt16BE(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun readUInt32BE(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0L
        return ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)
    }

    private fun cleanIsoDisplayTitle(isoUrl: String): String {
        return try {
            val fragName = isoUrl.substringAfter("#name=", "")
            val rawCandidate = if (fragName.isNotEmpty()) {
                java.net.URLDecoder.decode(fragName, "UTF-8")
            } else {
                val pathPart = java.net.URLDecoder.decode(
                    isoUrl.substringBefore("#").substringBefore("?").substringAfterLast('/'),
                    "UTF-8"
                )
                if (pathPart.equals("download.aspx", ignoreCase = true) || pathPart.endsWith(".aspx", ignoreCase = true) || pathPart.endsWith(".php", ignoreCase = true)) {
                    ""
                } else {
                    pathPart
                }
            }
            val base = rawCandidate.replace(Regex("\\.iso$", RegexOption.IGNORE_CASE), "").trim()
            if (base.isNotBlank() && base.length >= 2) base else "BluRay Disc"
        } catch (_: Throwable) {
            "BluRay Disc"
        }
    }

    private fun formatIsoDurationLabel(totalSec: Int): String {
        if (totalSec <= 0) return ""
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%dh%02dm", h, m) else String.format(Locale.US, "%dm", m)
    }

    private fun parseIsoDiscEntries(
        isoUrl: String,
        totalIsoSize: Long,
        port: Int,
        volDescProbe: ByteArray?
    ): Pair<List<JSONObject>, String> {
        val t0 = System.currentTimeMillis()
        appendDebugLog("ISO_START", "Opening ISO size=$totalIsoSize url=${safeStreamUrl(isoUrl)}")
        val session = IsoReadSession()
        val cache = isoChunkCaches.acquire(isoUrl, session, totalIsoSize)
        try {
            val metadata = IsoMetadataReadAhead(totalIsoSize, cache, session, ioExecutor) { from, to, owner ->
                fetchIsoByteRange(isoUrl, from, to, owner)
            }
            return scanDisc(isoUrl, totalIsoSize, port, volDescProbe, t0, metadata::read)
        } finally { session.cancel() }
    }

    private fun scanDisc(isoUrl: String, totalIsoSize: Long, port: Int, volDescProbe: ByteArray?, t0: Long,
                         read: (Long, Long) -> ByteArray): Pair<List<JSONObject>, String> {
        fun metadataBytes(entry: IsoFileEntry, maxBytes: Int) =
            IsoVirtualFile(entry.extents, false).readRaw(0, minOf(entry.size, maxBytes.toLong()).toInt(), read)
        val allFiles = ArrayList<IsoFileEntry>()
        var discLabel = ""
        var udfErr = ""
        var isoErr = ""

        try {
            val reader = UdfReader(totalIsoSize, read, ioExecutor)
            val udfFiles = reader.readTree().map { IsoFileEntry(it.path, it.path.substringAfterLast('/'), false, it.size, it.extents) }
            discLabel = reader.volumeLabel
            appendDebugLog("ISO_VOLUME_LABEL", discLabel)
            if (udfFiles.isNotEmpty()) {
                allFiles.addAll(udfFiles)
                appendDebugLog("ISO_UDF_OK", "Parsed ${udfFiles.size} media/playlist entries in ${System.currentTimeMillis() - t0}ms")
            } else {
                udfErr = "UDF 0 entries"
            }
        } catch (e: Throwable) {
            udfErr = "UDF: " + (e.message ?: e.javaClass.simpleName)
            appendDebugLog("ISO_UDF_ERR", udfErr)
        }

        if (allFiles.isEmpty()) {
            try {
                val isoFiles = parseIso9660Tree(read, totalIsoSize, volDescProbe)
                if (isoFiles.isNotEmpty()) {
                    allFiles.addAll(isoFiles)
                    appendDebugLog("ISO_9660_OK", "Parsed ${isoFiles.size} entries")
                } else {
                    isoErr = "ISO9660 0 entries"
                }
            } catch (e: Throwable) {
                isoErr = "ISO9660: " + (e.message ?: e.javaClass.simpleName)
                appendDebugLog("ISO_9660_ERR", isoErr)
            }
        }

        if (allFiles.isEmpty()) {
            val combined = listOf(udfErr, isoErr).filter { it.isNotEmpty() }.joinToString(" | ")
            appendDebugLog("ISO_FAIL", combined)
            return Pair(emptyList(), combined)
        }

        val discTitle = cleanIsoDisplayTitle(isoUrl)
        val outItems = ArrayList<JSONObject>()

        val m2tsMap = HashMap<String, IsoFileEntry>()
        val mplsList = ArrayList<IsoFileEntry>()
        val clpiMap = HashMap<String, IsoFileEntry>()
        for (f in allFiles) {
            if (f.isDir || f.size <= 0L || f.extents.isEmpty()) continue
            val upperPath = f.path.uppercase(Locale.ROOT)
            val upperName = f.name.uppercase(Locale.ROOT)
            if (upperPath.contains("BDMV/STREAM/") && upperName.endsWith(".M2TS")) {
                m2tsMap[upperName.substringBeforeLast('.')] = f
            } else if (upperPath.contains("BDMV/PLAYLIST/") && upperName.endsWith(".MPLS") && f.size <= 2L * 1024L * 1024L) {
                mplsList.add(f)
            } else if (upperPath.contains("BDMV/CLIPINF/") && upperName.endsWith(".CLPI") && f.size <= 8L * 1024L * 1024L) {
                clpiMap[upperName.substringBeforeLast('.')] = f
            }
        }
        appendDebugLog("ISO_BDMV_SCAN", "m2tsCount=${m2tsMap.size}, mplsCount=${mplsList.size}, clpiCount=${clpiMap.size}")

        if (m2tsMap.isNotEmpty()) {
            data class ResolvedPlaylist(
                val mplsName: String,
                val durationSec: Int,
                val totalBytes: Long,
                val segments: List<Pair<Long, Long>>,
                val clipSignature: String,
                val clips: List<BluRayPlaylist.Clip>,
                val specs: BluRayMplsSpecs.Result
            )

            val playlists = ArrayList<ResolvedPlaylist>()
            val seenSignatures = HashSet<String>()

            // Inspect every playlist, with bounded concurrency; file size is not feature size.
            for (batch in mplsList.sortedBy { it.name }.chunked(6)) {
                val mplsFutures = batch.map { mpls ->
                    ioExecutor.submit<Pair<IsoFileEntry, ByteArray>> {
                        try {
                            Pair(mpls, metadataBytes(mpls, 2 * 1024 * 1024))
                        } catch (_: Throwable) {
                            Pair(mpls, ByteArray(0))
                        }
                    }
                }

                for (fut in mplsFutures) {
                    try {
                        val (mpls, mplsBytes) = fut.get()
                        if (mplsBytes.size < 32) continue
                        val sig = String(mplsBytes, 0, 8, StandardCharsets.US_ASCII)
                        if (!sig.startsWith("MPLS0")) continue

                        val playListStart = readUInt32BE(mplsBytes, 8).toInt()
                        if (playListStart <= 0 || playListStart + 10 > mplsBytes.size) continue
                        val numPlayItems = readUInt16BE(mplsBytes, playListStart + 6)
                        if (numPlayItems <= 0 || numPlayItems > 250) continue

                        var p = playListStart + 10
                        val clips = ArrayList<BluRayPlaylist.Clip>()
                        val clipSpecs = HashMap<String, BluRayMplsSpecs.Result>()
                        var valid = true

                        for (idx in 0 until numPlayItems) {
                            if (p + 22 > mplsBytes.size) { valid = false; break }
                            val itemLen = readUInt16BE(mplsBytes, p)
                            if (itemLen < 20 || p + 2 + itemLen > mplsBytes.size) { valid = false; break }
                            val clipId = String(mplsBytes, p + 2, 5, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)
                            val inTime = readUInt32BE(mplsBytes, p + 14)
                            val outTime = readUInt32BE(mplsBytes, p + 18)
                            if (outTime <= inTime) { valid = false; break }
                            val m2tsEntry = m2tsMap[clipId]
                            if (m2tsEntry == null) { valid = false; break }
                            clips.add(BluRayPlaylist.Clip(clipId, outTime - inTime, m2tsEntry.size, m2tsEntry.extents, inTime))
                            try {
                                val specs = BluRayMplsSpecs.parseItem(mplsBytes, p, p + 2 + itemLen)
                                clipSpecs[clipId] = specs
                            } catch (error: Exception) { appendDebugLog("ISO_MPLS_SPECS", "${mpls.name}: ${error.message}") }
                            p += 2 + itemLen
                        }

                        val selected = BluRayPlaylist.playbackClips(clips)
                        val selectedSpecs = selected.mapNotNull { clipSpecs[it.id] }
                        val clipNames = selected.map { it.id }
                        val segs = selected.flatMap { it.extents }
                        val totalBytes = selected.fold(0L) { total, clip -> Math.addExact(total, clip.size) }
                        val durSec = (selected.sumOf { it.ticks } / 45000L).toInt()
                        val clipSig = clipNames.joinToString("+")
                        if (selected.size != clips.size) {
                            appendDebugLog("ISO_MPLS_FEATURE", "mpls=${mpls.name} originalClips=${clips.joinToString("+") { it.id }} originalDuration=${clips.sumOf { it.ticks } / 45000L}s selected=$clipSig duration=${durSec}s bytes=$totalBytes")
                        }
                        val isMenuLoop = clipNames.size > 3 && clipNames.toSet().size == 1
                        if (valid && !isMenuLoop && segs.isNotEmpty() && totalBytes >= 50L * 1024L * 1024L && durSec >= 180 && seenSignatures.add(clipSig)) {
                            playlists.add(ResolvedPlaylist(mpls.name, durSec, totalBytes, segs, clipSig, selected, BluRayMplsSpecs.Result(mergeBluRayStreams(selectedSpecs.flatMap { it.streams }), selectedSpecs.any { it.dolbyVision })))
                            appendDebugLog("ISO_MPLS_HIT", "mpls=${mpls.name} dur=${durSec}s bytes=$totalBytes clips=$clipSig")
                        }
                    } catch (_: Throwable) {}
                }

            }

            fun audioSample(clip: IsoFileEntry) = ioExecutor.submit<ByteArray> { metadataBytes(clip, 4 * 1024 * 1024) }
            fun inspectAudio(sample: java.util.concurrent.Future<ByteArray>, streams: List<BluRayStream>) = try {
                DiscAudio.inspectM2ts(sample.get(), streams)
            } catch (error: Exception) {
                appendDebugLog("ISO_AUDIO_PROBE", isoFailureDetails(error))
                streams
            }
            fun profile(playlist: ResolvedPlaylist): BluRayProfile {
                val sample = audioSample(m2tsMap.getValue(playlist.clips.first().id))
                val profiles = playlist.clips.chunked(4).flatMap { batch ->
                    batch.mapNotNull { clip ->
                        val info = clpiMap[clip.id] ?: return@mapNotNull null
                        ioExecutor.submit<BluRayProfile?> {
                            try {
                                BluRayClipInfo.parse(metadataBytes(info, 8 * 1024 * 1024), clip.inTicks, clip.ticks, clip.size)
                            } catch (error: Exception) {
                                appendDebugLog("ISO_CLPI_ERR", "clip=${clip.id} ${isoFailureDetails(error)}")
                                null
                            }
                        }
                    }.mapNotNull { it.get() }
                }
                val base = profiles.firstOrNull() ?: BluRayProfile(playlist.durationSec * 1_000_000L, emptyList(), emptyList())
                val streams = inspectAudio(sample,
                    mergeBluRayStreams(profiles.flatMap { it.streams } + playlist.specs.streams))
                return base.copy(durationUs = playlist.clips.sumOf { it.ticks } * 1_000_000 / 45000,
                    points = if (playlist.clips.size == 1) base.points else emptyList(),
                    audio = streams.filter { it.audio }.map { BluRayAudio(it.pid, it.type, it.language) },
                    streams = streams, discLabel = discLabel, dolbyVision = playlist.specs.dolbyVision).also {
                    appendDebugLog("ISO_CLPI_OK", "points=${it.points.size} durationUs=${it.durationUs} mediaInfo=${it.mediaInfo(playlist.totalBytes)}")
                }
            }

            if (playlists.isNotEmpty()) {
                val main = BluRayPlaylist.largest(playlists) { it.totalBytes }!!
                val label = "$discTitle [Main Movie - ${formatIsoDurationLabel(main.durationSec)}].m2ts"
                outItems.add(registerIsoStreamTarget(isoUrl, totalIsoSize, label, main.totalBytes, main.segments, port, "video/mp2t", main.durationSec, profile(main)))
                appendDebugLog("ISO_MAIN_ONLY", "selected=${main.mplsName} bytes=${main.totalBytes} candidates=${playlists.size}")
                return Pair(outItems, "")
            }

            val largeM2ts = m2tsMap.values.filter { it.size >= 100L * 1024L * 1024L }.sortedByDescending { it.size }
            if (largeM2ts.isNotEmpty()) {
                val f = largeM2ts.first()
                val sample = audioSample(f)
                val info = clpiMap[f.name.substringBeforeLast('.').uppercase(Locale.ROOT)]
                val profile = try {
                    info?.let { BluRayClipInfo.parseRaw(metadataBytes(it, 8 * 1024 * 1024), f.size) }
                } catch (error: Exception) {
                    appendDebugLog("ISO_CLPI_ERR", "clip=${f.name} ${isoFailureDetails(error)}")
                    null
                }
                val metadata = (profile ?: BluRayProfile(0, emptyList(), emptyList())).copy(discLabel = discLabel, streams = inspectAudio(sample, profile?.streams.orEmpty()))
                val label = "$discTitle [Main Stream - ${f.name}].m2ts"
                outItems.add(registerIsoStreamTarget(isoUrl, totalIsoSize, label, f.size, f.extents, port, "video/mp2t",
                    (metadata.durationUs / 1_000_000).toInt(), metadata))
                appendDebugLog("ISO_DONE_M2TS", "Resolved ${outItems.size} raw M2TS clips in ${System.currentTimeMillis() - t0}ms")
                return Pair(outItems, "")
            }
        }

        val vobGroups = HashMap<String, ArrayList<IsoFileEntry>>()
        for (f in allFiles) {
            if (f.isDir || f.size <= 0L || f.extents.isEmpty()) continue
            val m = Regex("^VTS_(\\d{2})_([1-9])\\.VOB$").find(f.name.uppercase(Locale.ROOT))
            if (m != null) {
                vobGroups.getOrPut(m.groupValues[1]) { ArrayList() }.add(f)
            }
        }
        if (vobGroups.isNotEmpty()) {
            data class DvdTitle(val titleSet: String, val totalBytes: Long, val segs: List<Pair<Long, Long>>)
            val titles = ArrayList<DvdTitle>()
            for ((ts, parts) in vobGroups) {
                val segs = ArrayList<Pair<Long, Long>>()
                var sumBytes = 0L
                for (p in parts.sortedBy { it.name.uppercase(Locale.ROOT) }) {
                    segs.addAll(p.extents)
                    sumBytes += p.size
                }
                if (sumBytes >= 50L * 1024L * 1024L && segs.isNotEmpty()) {
                    titles.add(DvdTitle(ts, sumBytes, segs))
                }
            }
            if (titles.isNotEmpty()) {
                titles.sortedByDescending { it.totalBytes }.take(1).forEachIndexed { idx, dt ->
                    val tSet = dt.titleSet
                    val label = if (idx == 0) "$discTitle [DVD Main].vob" else "$discTitle - Title $tSet.vob"
                    val parsed = runCatching {
                        val ifo = allFiles.first { it.name.equals("VTS_${tSet}_0.IFO", true) }
                        val specs = DvdTitleInfo.parse(metadataBytes(ifo, 8 * 1024 * 1024))
                        val title = specs.main()
                        val ranges = DvdTitleInfo.extents(title.cells, dt.segs)
                        val movie = IsoVirtualFile(ranges, false)
                        val sample = movie.readRaw(0, minOf(movie.size, 2L * 1024 * 1024).toInt(), read)
                        val info = specs.info(title, sample, movie.size, discTitle)
                        registerIsoStreamTarget(isoUrl, totalIsoSize, label, movie.size, ranges, port, "video/mpeg",
                            title.duration.toInt(), dvdInfo = info, dvdSegments = specs.segments(title))
                    }.onFailure { appendDebugLog("DVD_IFO", isoFailureDetails(it)) }.getOrNull()
                    outItems.add(parsed ?: registerIsoStreamTarget(isoUrl, totalIsoSize, label, dt.totalBytes, dt.segs, port, "video/mpeg"))
                }
                appendDebugLog("ISO_DONE_DVD", "Resolved ${outItems.size} DVD VOB titles in ${System.currentTimeMillis() - t0}ms")
                return Pair(outItems, "")
            }
        }

        val directExts = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".m2ts", ".ts")
        for (v in allFiles.filter {
            !it.isDir && it.size > 10L * 1024L * 1024L && it.extents.isNotEmpty() &&
                directExts.any { ext -> it.name.lowercase(Locale.ROOT).endsWith(ext) }
        }.sortedByDescending { it.size }.take(1)) {
            val lower = v.name.lowercase(Locale.ROOT)
            val mime = when {
                lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
                lower.endsWith(".m2ts") || lower.endsWith(".ts") -> "video/mp2t"
                else -> "video/x-matroska"
            }
            outItems.add(registerIsoStreamTarget(isoUrl, totalIsoSize, v.name, v.size, v.extents, port, mime))
        }

        val errMsg = if (outItems.isEmpty()) "Found " + allFiles.size + " files in ISO but no >50MB video streams" else ""
        if (errMsg.isNotEmpty()) appendDebugLog("ISO_EMPTY", errMsg)
        return Pair(outItems, errMsg)
    }

    private fun registerIsoStreamTarget(
        isoUrl: String, sourceSize: Long,
        displayName: String,
        totalBytes: Long,
        segments: List<Pair<Long, Long>>,
        port: Int,
        mimeType: String,
        durationSec: Int = 0,
        bluRay: BluRayProfile? = null,
        dvdInfo: Map<String, Any>? = null,
        standalone: Boolean = false,
        dvdSegments: List<DvdProxy.Segment> = emptyList()
    ): JSONObject {
        val entryId = "ze_" + System.nanoTime() + "_" + archiveEntryCounter.getAndIncrement()
        val firstOffset = segments.firstOrNull()?.first ?: 0L
        val isBdav = displayName.endsWith(".m2ts", ignoreCase = true) && totalBytes >= 192L
        val virtualFile = IsoVirtualFile(segments, isBdav)
        require(virtualFile.rawSize == totalBytes && virtualFile.size > 0) { "Incomplete ISO stream extent map" }
        val exposedBytes = virtualFile.size
        val target = ArchiveEntryTarget(
            id = entryId,
            archiveUrl = isoUrl,
            sourceSize = sourceSize,
            standalone = standalone,
            dvdSegments = dvdSegments,
            fileName = displayName,
            archiveFormat = "ISO",
            compressionMethod = 0,
            compressedSize = totalBytes,
            uncompressedSize = exposedBytes,
            localHeaderOffset = firstOffset,
            dataStartOffset = firstOffset,
            segments = segments,
            isBdavM2ts = isBdav,
            bluRay = bluRay,
            resolvedInfo = dvdInfo
        )
        archiveEntryRegistry[entryId] = target
        val safeName = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        val streamUrl = "http://127.0.0.1:$port/zip/$entryId/$safeName"
        return JSONObject().apply {
            put("id", entryId)
            put("name", displayName)
            put("size", exposedBytes.toString())
            put("mimeType", mimeType)
            put("link", streamUrl)
            put("isZipEntry", true)
            put("zipCompression", "STORE")
            put("archiveFormat", "ISO")
            if (durationSec > 0) put("durationSec", durationSec)
            (dvdInfo ?: bluRay?.mediaInfo(exposedBytes))?.let { put("mediaInfo", JSONObject(it)) }
        }
    }

    private fun discInfoMap(json: JSONObject): Map<String, Any> {
        fun value(item: Any): Any = when (item) {
            is JSONObject -> discInfoMap(item)
            is JSONArray -> (0 until item.length()).map { value(item.get(it)) }
            else -> item
        }
        return json.keys().asSequence().associateWith { value(json.get(it)) }
    }

    private fun walkIso9660Dir(
        read: (Long, Long) -> ByteArray,
        totalIsoSize: Long,
        dirLba: Long,
        dirLen: Long,
        parentPath: String,
        depth: Int,
        results: MutableList<IsoFileEntry>
    ) {
        if (depth > 5) return
        val byteStart = dirLba * 2048L
        val fetchLen = dirLen.coerceIn(2048L, 262144L).toInt()
        val dirBuf = read(byteStart, minOf(totalIsoSize - 1L, byteStart + fetchLen - 1L))
        var p = 0
        while (p + 34 <= dirBuf.size) {
            val recLen = dirBuf[p].toInt() and 0xFF
            if (recLen == 0) {
                val nextSec = ((p / 2048) + 1) * 2048
                if (nextSec >= dirBuf.size) break
                p = nextSec
                continue
            }
            if (p + recLen > dirBuf.size) break
            val extentLba = readUInt32LE(dirBuf, p + 2)
            val dataLen = readUInt32LE(dirBuf, p + 10)
            val flags = dirBuf[p + 25].toInt() and 0xFF
            val nameLen = dirBuf[p + 32].toInt() and 0xFF
            if (nameLen > 0 && p + 33 + nameLen <= dirBuf.size) {
                val rawId = String(dirBuf, p + 33, nameLen, StandardCharsets.UTF_8)
                val isSpecial = nameLen == 1 && (dirBuf[p + 33].toInt() == 0 || dirBuf[p + 33].toInt() == 1)
                if (!isSpecial) {
                    val cleanName = rawId.substringBefore(';').trim()
                    val fullPath = if (parentPath.isEmpty()) cleanName else "$parentPath/$cleanName"
                    val isDir = (flags and 0x02) != 0
                    if (isDir) {
                        walkIso9660Dir(read, totalIsoSize, extentLba, dataLen, fullPath, depth + 1, results)
                    } else if (dataLen > 0L) {
                        results.add(IsoFileEntry(fullPath, cleanName, false, dataLen, listOf(Pair(extentLba * 2048L, dataLen))))
                    }
                }
            }
            p += recLen
        }
    }

    private fun parseIso9660Tree(
        read: (Long, Long) -> ByteArray,
        totalIsoSize: Long,
        volDescProbe: ByteArray?
    ): List<IsoFileEntry> {
        val pvdBuf = volDescProbe ?: read(32768L, minOf(totalIsoSize - 1L, 40959L))
        var pvdOff = -1
        var off = 0
        while (off + 2048 <= pvdBuf.size) {
            if ((pvdBuf[off].toInt() and 0xFF) == 1 &&
                String(pvdBuf, off + 1, 5, StandardCharsets.US_ASCII) == "CD001"
            ) {
                pvdOff = off
                break
            }
            off += 2048
        }
        if (pvdOff < 0) return emptyList()

        val rootRecOff = pvdOff + 156
        val rootLba = readUInt32LE(pvdBuf, rootRecOff + 2)
        val rootLen = readUInt32LE(pvdBuf, rootRecOff + 10)
        if (rootLba <= 0L || rootLen <= 0L) return emptyList()

        val results = ArrayList<IsoFileEntry>()
        walkIso9660Dir(read, totalIsoSize, rootLba, rootLen, "", 0, results)
        return results
    }
}
