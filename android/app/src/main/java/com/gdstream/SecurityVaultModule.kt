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

    private val ioExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var nativeSessionLoggedIn = false

    @Volatile
    private var sessionCookieHeader: String = ""

    @Volatile
    private var cachedShieldToken: String = ""

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

    data class ArchiveEntryTarget(
        val id: String,
        val archiveUrl: String,
        val fileName: String,
        val archiveFormat: String, // "ZIP", "RAR", or "ISO"
        val compressionMethod: Int, // 0 = STORE (Uncompressed), 8 = DEFLATE (ZIP), >0 = RAR Compressed
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        @Volatile var dataStartOffset: Long = -1L,
        val segments: List<Pair<Long, Long>>? = null
    )

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
    private val archiveEntryCounter = AtomicInteger(1)

    @Volatile
    private var archiveProxyPort: Int = 0

    private val archiveProxyLock = Any()

    companion object {
        init {
            try {
                System.loadLibrary("gdcore")
                // Keep cookies isolated in sessionCookieHeader so React Native never clears them
            } catch (_: Throwable) {}
        }
    }

    override fun getName(): String = "SecurityVault"

    private external fun nSync(slot: Int, sigSeed: Int): String

    private fun buildPinnedSystemSslFactory(): SSLSocketFactory {
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
        return sslContext.socketFactory
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
        shieldToken: String
    ): String {
        val url = URL(urlStr)
        val conn = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
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
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return ""
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun performNativeLogin(baseUrl: String, seed: Int, shieldToken: String) {
        val user = nSync(2, seed)
        val pass = nSync(3, seed)
        if (user.isEmpty() || pass.isEmpty()) throw SecurityException("Sync check failed")
        cachedShieldToken = shieldToken
        val formBody = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
        executePinnedPost(
            "$baseUrl/login",
            formBody.toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded",
            shieldToken
        )
        nativeSessionLoggedIn = true
    }

    @ReactMethod
    fun securePostDirectory(path: String, pageIndex: Int, promise: Promise) {
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
                    performNativeLogin(baseUrl, seed, shieldToken)
                }

                val jsonPayload = "{\"page_index\":$pageIndex}".toByteArray(Charsets.UTF_8)
                var responseText = executePinnedPost(
                    "$baseUrl$path",
                    jsonPayload,
                    "application/json",
                    shieldToken
                )

                if (!responseText.contains("\"files\"")) {
                    performNativeLogin(baseUrl, seed, shieldToken)
                    responseText = executePinnedPost(
                        "$baseUrl$path",
                        jsonPayload,
                        "application/json",
                        shieldToken
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
                            shieldToken
                        )
                        if (!nextRespText.contains("\"files\"")) break
                        val nextRoot = JSONObject(nextRespText)
                        val nextData = nextRoot.optJSONObject("data")
                        val nextFiles = nextData?.optJSONArray("files") ?: break
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
                } catch (_: Throwable) {}

                promise.resolve(responseText)
            } catch (e: Exception) {
                promise.reject("E_NET", e.message ?: "Network error")
            }
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

    private fun openRangeConnectionInternal(urlStr: String, rangeHeader: String?): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 25000
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "GDStream-Native-Engine/3.0")
                setRequestProperty("Accept-Encoding", "identity")
                if (cachedShieldToken.isNotEmpty()) {
                    setRequestProperty("X-GD-Shield", cachedShieldToken)
                }
                if (sessionCookieHeader.isNotEmpty()) {
                setRequestProperty("Cookie", sessionCookieHeader)
            }
                if (!rangeHeader.isNullOrEmpty()) {
                    setRequestProperty("Range", rangeHeader)
                }
                if (this is HttpsURLConnection) {
                    sslSocketFactory = buildPinnedSystemSslFactory()
                    hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                }
            }
            val code = conn.responseCode
            val setCookies = conn.headerFields["Set-Cookie"] ?: conn.headerFields["set-cookie"]
            if (!setCookies.isNullOrEmpty()) {
                val cookieParts = setCookies.mapNotNull { it.substringBefore(";").trim().takeIf { s -> s.isNotEmpty() } }
                if (cookieParts.isNotEmpty()) {
                    sessionCookieHeader = cookieParts.joinToString("; ")
                }
            }
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) break
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects for archive stream")
    }

    private fun openRangeConnection(urlStr: String, rangeHeader: String?): HttpURLConnection {
        ensureVaultSessionReady()
        var conn = openRangeConnectionInternal(urlStr, rangeHeader)
        val cType = (conn.contentType ?: "").lowercase(Locale.ROOT)
        val code = conn.responseCode
        // If Cloudflare Worker returned an HTML login page or 401/403 instead of binary bytes, re-login immediately and retry!
        if (code == 401 || code == 403 || cType.contains("text/html")) {
            conn.disconnect()
            nativeSessionLoggedIn = false
            ensureVaultSessionReady()
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
        val conn = openRangeConnection(archiveUrl, "bytes=$start-$endInclusive")
        try {
            return conn.inputStream.use { readExactBytes(it, len) }
        } finally {
            conn.disconnect()
        }
    }

    private fun detectRemoteFileSize(archiveUrl: String): Long {
        val conn = openRangeConnection(archiveUrl, "bytes=0-1")
        try {
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
        if (archiveProxyPort > 0) return archiveProxyPort
        synchronized(archiveProxyLock) {
            if (archiveProxyPort > 0) return archiveProxyPort
            val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            archiveProxyPort = server.localPort
            Thread {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        ioExecutor.submit { handleArchiveProxyClient(client) }
                    } catch (_: Throwable) {}
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

    private fun handleArchiveProxyClient(socket: Socket) {
        var remoteConnToClean: HttpURLConnection? = null
        var inflaterToClean: Inflater? = null
        try {
            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val path = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 2 && segments[0] == "yt") {
                handleYoutubeProxyClient(socket, segments[1], method, rangeHeader)
                return
            }
            if (segments.size < 2 || segments[0] != "zip") {
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val entryId = segments[1]
            val entry = archiveEntryRegistry[entryId]
            if (entry == null) {
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val totalSize = entry.uncompressedSize
            val mimeType = when {
                entry.fileName.endsWith(".mp4", ignoreCase = true) ||
                entry.fileName.endsWith(".m4v", ignoreCase = true) -> "video/mp4"
                entry.fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
                else -> "video/x-matroska"
            }

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

            val out = BufferedOutputStream(socket.getOutputStream(), 65536)

            if (method == "HEAD") {
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
                return
            }

            if (entry.compressionMethod == 0 && !entry.segments.isNullOrEmpty() && entry.segments.size > 1) {
                // Multi-segment virtual stream (Blu-ray .mpls seamless branching or DVD multi-VOB)
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

                val buf = ByteArray(65536)
                var virtualCursor = 0L
                for ((segOffset, segLen) in entry.segments) {
                    val segVirtStart = virtualCursor
                    val segVirtEnd = virtualCursor + segLen - 1L
                    virtualCursor += segLen
                    if (segVirtEnd < reqStart) continue
                    if (segVirtStart > reqEnd) break

                    val overlapStart = maxOf(reqStart, segVirtStart)
                    val overlapEnd = minOf(reqEnd, segVirtEnd)
                    if (overlapStart > overlapEnd) continue

                    val remoteStart = segOffset + (overlapStart - segVirtStart)
                    val remoteEnd = segOffset + (overlapEnd - segVirtStart)
                    var segRemaining = overlapEnd - overlapStart + 1L

                    val segConn = openRangeConnection(entry.archiveUrl, "bytes=$remoteStart-$remoteEnd")
                    remoteConnToClean = segConn
                    val uCode = segConn.responseCode
                    if (uCode !in 200..299) {
                        try { segConn.disconnect() } catch (_: Throwable) {}
                        break
                    }
                    val segIn = segConn.inputStream
                    if (uCode == 200 && remoteStart > 0L) {
                        var skipLeft = remoteStart
                        while (skipLeft > 0L) {
                            val s = segIn.read(buf, 0, minOf(buf.size.toLong(), skipLeft).toInt())
                            if (s <= 0) break
                            skipLeft -= s.toLong()
                        }
                    }
                    while (segRemaining > 0L) {
                        val toRead = minOf(buf.size.toLong(), segRemaining).toInt()
                        val read = segIn.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        segRemaining -= read.toLong()
                    }
                    try { segConn.disconnect() } catch (_: Throwable) {}
                    remoteConnToClean = null
                }
                out.flush()
                return
            }

            val dataStart = resolveEntryDataStartOffset(entry)

            if (entry.compressionMethod == 0) {
                // STORE Mode (Uncompressed ZIP, Uncompressed RAR, or Single-Clip ISO): Direct 1:1 Byte-Slice Range Streaming!
                val remoteStart = dataStart + reqStart
                val remoteEnd = dataStart + reqEnd
                val remoteConn = openRangeConnection(entry.archiveUrl, "bytes=$remoteStart-$remoteEnd")
                remoteConnToClean = remoteConn
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
                remoteConnToClean = remoteConn
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
                val outStream = socket.getOutputStream()
                outStream.write("HTTP/1.1 415 Unsupported Media Type\r\nContent-Length: 0\r\n\r\n".toByteArray())
                outStream.flush()
            }
        } catch (_: Throwable) {
        } finally {
            // Close local client socket FIRST so MediaInfo & ExoPlayer never wait on upstream teardown!
            try { socket.close() } catch (_: Throwable) {}
            val connRef = remoteConnToClean
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

    private val zipDiskCache = ConcurrentHashMap<String, String>()
    private val zipDiskLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
    private val zipDiskWriteLock = Any()
    private val zipInflightMap = ConcurrentHashMap<String, MutableList<Promise>>()

    private fun extractArchiveCacheKey(rawUrl: String, sizeHint: Double): String {
        val qId = Regex("[?&]id=([a-zA-Z0-9_-]{15,})").find(rawUrl)?.groupValues?.getOrNull(1)
        val pId = Regex("/d/([a-zA-Z0-9_-]{15,})").find(rawUrl)?.groupValues?.getOrNull(1)
        val driveId = qId ?: pId
        return if (!driveId.isNullOrEmpty()) "gd_$driveId" else "${rawUrl.substringBefore("?")}|${sizeHint.toLong()}"
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
            if (rawJson.isEmpty() || targetsArr.length() == 0) return null

            val seed = verifyCertificateAndEnv()
            if (seed == 0x5A3C9E71) {
                val baseUrl = nSync(1, seed)
                val shieldToken = nSync(5, seed)
                if (shieldToken.isNotEmpty()) cachedShieldToken = shieldToken
                if (!nativeSessionLoggedIn && baseUrl.isNotEmpty() && shieldToken.isNotEmpty()) {
                    try { performNativeLogin(baseUrl, seed, shieldToken) } catch (_: Throwable) {}
                }
            }
            val activePort = ensureArchiveProxyRunning()

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
                val entry = ArchiveEntryTarget(
                    id = id,
                    archiveUrl = freshUrl,
                    fileName = o.optString("fileName", ""),
                    archiveFormat = o.optString("archiveFormat", "ZIP"),
                    compressionMethod = o.optInt("compressionMethod", 0),
                    compressedSize = o.optLong("compressedSize", 0L),
                    uncompressedSize = o.optLong("uncompressedSize", 0L),
                    localHeaderOffset = o.optLong("localHeaderOffset", 0L),
                    dataStartOffset = o.optLong("dataStartOffset", -1L),
                    segments = restoredSegs
                )
                archiveEntryRegistry[id] = entry
            }
            rawJson.replace(Regex("http://127\\.0\\.0\\.1:\\d+"), "http://127.0.0.1:$activePort")
        } catch (_: Throwable) { null }
    }

    private fun persistArchiveToDisk(cacheKey: String, rawJson: String) {
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
                })
            }
            if (targetsArr.length() > 0) {
                val root = JSONObject().apply {
                    put("rawJson", rawJson)
                    put("targets", targetsArr)
                }
                zipDiskCache[cacheKey] = root.toString()
                saveZipDiskCacheAsync()
            }
        } catch (_: Throwable) {}
    }

    @ReactMethod
    fun listZipMediaEntries(zipUrl: String, fileSizeHint: Double, promise: Promise) {
        ioExecutor.submit {
            ensureZipDiskCacheLoaded()
            val cacheKey = extractArchiveCacheKey(zipUrl, fileSizeHint)
            val cachedBlob = zipDiskCache[cacheKey]
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
                        persistArchiveToDisk(cacheKey, value)
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

            scanZipMediaEntriesUncached(zipUrl, fileSizeHint, proxyPromise)
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

                // 1. Check first 16 bytes at offset 0 to auto-detect RAR4 / RAR5 (even if .rar was renamed to .zip!)
                val headProbe = fetchRemoteByteRange(zipUrl, 0L, 15L)
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

                // 1B. Auto-detect Blu-ray / DVD / Data .ISO (UDF / ISO-9660)
                val lowerUrlPath = zipUrl.substringBefore("?").lowercase(Locale.ROOT)
                var looksLikeIso = lowerUrlPath.endsWith(".iso")
                var volDescProbe: ByteArray? = null
                if (totalArchiveSize >= 36864L) {
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
                }
                if (looksLikeIso) {
                    val isoItems = parseIsoDiscEntries(zipUrl, totalArchiveSize, port, volDescProbe)
                    if (isoItems.isNotEmpty()) {
                        val outIso = JSONArray()
                        for (item in isoItems) outIso.put(item)
                        promise.resolve(outIso.toString())
                        return@run
                    }
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
                    promise.reject("E_ZIP", "Archive header not recognized (Not a valid ZIP or RAR file)")
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
            val out = socket.getOutputStream()
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

        val out = BufferedOutputStream(socket.getOutputStream(), 65536)
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
    // BLU-RAY / DVD / DATA .ISO STREAMING ENGINE (UDF + ISO-9660 + MPLS + VOB)
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

    private fun decodeUdfDString(b: ByteArray, off: Int, len: Int): String {
        if (len <= 1 || off + len > b.size) return ""
        val compId = b[off].toInt() and 0xFF
        return try {
            when (compId) {
                16 -> String(b, off + 1, len - 1, StandardCharsets.UTF_16BE).trimEnd('\u0000')
                else -> String(b, off + 1, len - 1, StandardCharsets.UTF_8).trimEnd('\u0000')
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun cleanIsoDisplayTitle(isoUrl: String): String {
        return try {
            val rawName = java.net.URLDecoder.decode(
                isoUrl.substringBefore("?").substringAfterLast('/'),
                "UTF-8"
            )
            val base = rawName.replace(Regex("\\.iso$", RegexOption.IGNORE_CASE), "")
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
    ): List<JSONObject> {
        val allFiles = ArrayList<IsoFileEntry>()

        try {
            val udfFiles = parseUdfTree(isoUrl, totalIsoSize)
            if (udfFiles.isNotEmpty()) {
                allFiles.addAll(udfFiles)
            }
        } catch (_: Throwable) {}

        if (allFiles.isEmpty()) {
            try {
                val iso9660Files = parseIso9660Tree(isoUrl, totalIsoSize, volDescProbe)
                allFiles.addAll(iso9660Files)
            } catch (_: Throwable) {}
        }

        if (allFiles.isEmpty()) return emptyList()

        val discTitle = cleanIsoDisplayTitle(isoUrl)
        val outItems = ArrayList<JSONObject>()

        val m2tsMap = HashMap<String, IsoFileEntry>()
        val mplsList = ArrayList<IsoFileEntry>()
        for (f in allFiles) {
            if (f.isDir || f.size <= 0L || f.extents.isEmpty()) continue
            val upperPath = f.path.uppercase(Locale.ROOT)
            val upperName = f.name.uppercase(Locale.ROOT)
            if (upperPath.contains("BDMV/STREAM/") && upperName.endsWith(".M2TS")) {
                val clipKey = upperName.substringBeforeLast('.')
                m2tsMap[clipKey] = f
            } else if (upperPath.contains("BDMV/PLAYLIST/") && upperName.endsWith(".MPLS") && f.size <= 2L * 1024L * 1024L) {
                mplsList.add(f)
            }
        }

        if (m2tsMap.isNotEmpty()) {
            data class ResolvedPlaylist(
                val mplsName: String,
                val durationSec: Int,
                val totalBytes: Long,
                val segments: List<Pair<Long, Long>>,
                val clipSignature: String
            )

            val playlists = ArrayList<ResolvedPlaylist>()
            val seenSignatures = HashSet<String>()

            for (mpls in mplsList.sortedByDescending { it.size }.take(40)) {
                try {
                    val mplsBytes = readIsoFileBytes(isoUrl, mpls, 524288)
                    if (mplsBytes.size < 32) continue
                    val sig = String(mplsBytes, 0, 8, StandardCharsets.US_ASCII)
                    if (!sig.startsWith("MPLS0")) continue

                    val playListStart = readUInt32BE(mplsBytes, 8).toInt()
                    if (playListStart <= 0 || playListStart + 10 > mplsBytes.size) continue
                    val numPlayItems = readUInt16BE(mplsBytes, playListStart + 6)
                    if (numPlayItems <= 0 || numPlayItems > 250) continue

                    var p = playListStart + 10
                    val segs = ArrayList<Pair<Long, Long>>()
                    val clipNames = ArrayList<String>()
                    var totalTicks = 0L
                    var totalBytes = 0L
                    var valid = true

                    for (idx in 0 until numPlayItems) {
                        if (p + 22 > mplsBytes.size) { valid = false; break }
                        val itemLen = readUInt16BE(mplsBytes, p)
                        if (itemLen < 20 || p + 2 + itemLen > mplsBytes.size) { valid = false; break }
                        val clipId = String(mplsBytes, p + 2, 5, StandardCharsets.US_ASCII).uppercase(Locale.ROOT)
                        val inTime = readUInt32BE(mplsBytes, p + 14)
                        val outTime = readUInt32BE(mplsBytes, p + 18)
                        if (outTime > inTime) {
                            totalTicks += (outTime - inTime)
                        }
                        val m2tsEntry = m2tsMap[clipId]
                        if (m2tsEntry == null) {
                            valid = false
                            break
                        }
                        segs.addAll(m2tsEntry.extents)
                        clipNames.add(clipId)
                        totalBytes += m2tsEntry.size
                        p += 2 + itemLen
                    }

                    val durSec = (totalTicks / 45000L).toInt()
                    val clipSig = clipNames.joinToString("+")
                    if (valid && segs.isNotEmpty() && totalBytes >= 50L * 1024L * 1024L && durSec >= 180 && seenSignatures.add(clipSig)) {
                        playlists.add(
                            ResolvedPlaylist(
                                mplsName = mpls.name,
                                durationSec = durSec,
                                totalBytes = totalBytes,
                                segments = segs,
                                clipSignature = clipSig
                            )
                        )
                    }
                } catch (_: Throwable) {}
            }

            if (playlists.isNotEmpty()) {
                val epCandidates = playlists.filter { it.durationSec in 1080..4800 }
                val longFeatures = playlists.filter { it.durationSec > 4800 }
                val isSeriesDisc = epCandidates.size >= 2 && longFeatures.isEmpty()

                if (isSeriesDisc) {
                    val orderedEps = epCandidates.sortedBy { it.mplsName }
                    val seasonMatch = Regex("S(\\d{1,2})", RegexOption.IGNORE_CASE).find(discTitle)
                    val sNum = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val cleanShow = discTitle
                        .replace(Regex("\\bS\\d{1,2}(?:E\\d{1,2}|D\\d{1,2}|\\s*Disc\\s*\\d+)?\\b", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\b(BluRay|BD50|BD25|UHD|REMUX|1080p|2160p|4K|AVC|HEVC).*$", RegexOption.IGNORE_CASE), "")
                        .trim()
                        .ifEmpty { discTitle }

                    orderedEps.forEachIndexed { idx, ep ->
                        val epCode = String.format(Locale.US, "S%02dE%02d", sNum, idx + 1)
                        val durTag = formatIsoDurationLabel(ep.durationSec)
                        val displayName = if (durTag.isNotEmpty()) "$cleanShow $epCode [$durTag].m2ts" else "$cleanShow $epCode.m2ts"
                        outItems.add(registerIsoStreamTarget(isoUrl, displayName, ep.totalBytes, ep.segments, port, "video/mp2t"))
                    }
                    return outItems
                } else {
                    val sortedMovies = playlists.sortedByDescending { it.durationSec }
                    sortedMovies.forEachIndexed { idx, pl ->
                        val durTag = formatIsoDurationLabel(pl.durationSec)
                        val featureNum = idx + 1
                        val label = when {
                            idx == 0 && durTag.isNotEmpty() -> "$discTitle [Main Movie - $durTag].m2ts"
                            idx == 0 -> "$discTitle [Main Movie].m2ts"
                            durTag.isNotEmpty() -> "$discTitle - Feature $featureNum [$durTag].m2ts"
                            else -> "$discTitle - Feature $featureNum.m2ts"
                        }
                        outItems.add(registerIsoStreamTarget(isoUrl, label, pl.totalBytes, pl.segments, port, "video/mp2t"))
                    }
                    return outItems
                }
            }

            val largeM2ts = m2tsMap.values.filter { it.size >= 100L * 1024L * 1024L }.sortedByDescending { it.size }
            if (largeM2ts.isNotEmpty()) {
                largeM2ts.forEachIndexed { idx, f ->
                    val clipFileName = f.name
                    val label = if (idx == 0) "$discTitle [Main Stream - $clipFileName].m2ts" else "$discTitle - $clipFileName"
                    outItems.add(registerIsoStreamTarget(isoUrl, label, f.size, f.extents, port, "video/mp2t"))
                }
                return outItems
            }
        }

        val vobGroups = HashMap<String, ArrayList<IsoFileEntry>>()
        for (f in allFiles) {
            if (f.isDir || f.size <= 0L || f.extents.isEmpty()) continue
            val upperName = f.name.uppercase(Locale.ROOT)
            val m = Regex("^VTS_(\\d{2})_([1-9])\\.VOB$").find(upperName)
            if (m != null) {
                val titleSet = m.groupValues[1]
                vobGroups.getOrPut(titleSet) { ArrayList() }.add(f)
            }
        }
        if (vobGroups.isNotEmpty()) {
            data class DvdTitle(val titleSet: String, val totalBytes: Long, val segs: List<Pair<Long, Long>>)
            val titles = ArrayList<DvdTitle>()
            for ((ts, parts) in vobGroups) {
                val sortedParts = parts.sortedBy { it.name.uppercase(Locale.ROOT) }
                val segs = ArrayList<Pair<Long, Long>>()
                var sumBytes = 0L
                for (p in sortedParts) {
                    segs.addAll(p.extents)
                    sumBytes += p.size
                }
                if (sumBytes >= 50L * 1024L * 1024L && segs.isNotEmpty()) {
                    titles.add(DvdTitle(ts, sumBytes, segs))
                }
            }
            if (titles.isNotEmpty()) {
                val sortedTitles = titles.sortedByDescending { it.totalBytes }
                sortedTitles.forEachIndexed { idx, dt ->
                    val tSet = dt.titleSet
                    val label = if (idx == 0) "$discTitle [DVD Main].vob" else "$discTitle - Title $tSet.vob"
                    outItems.add(registerIsoStreamTarget(isoUrl, label, dt.totalBytes, dt.segs, port, "video/mpeg"))
                }
                return outItems
            }
        }

        val directExts = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".m2ts", ".ts")
        val directVideos = allFiles.filter {
            !it.isDir && it.size > 10L * 1024L * 1024L && it.extents.isNotEmpty() &&
                directExts.any { ext -> it.name.lowercase(Locale.ROOT).endsWith(ext) }
        }.sortedBy { it.name }

        for (v in directVideos) {
            val lower = v.name.lowercase(Locale.ROOT)
            val mime = when {
                lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
                lower.endsWith(".m2ts") || lower.endsWith(".ts") -> "video/mp2t"
                else -> "video/x-matroska"
            }
            outItems.add(registerIsoStreamTarget(isoUrl, v.name, v.size, v.extents, port, mime))
        }

        return outItems
    }

    private fun registerIsoStreamTarget(
        isoUrl: String,
        displayName: String,
        totalBytes: Long,
        segments: List<Pair<Long, Long>>,
        port: Int,
        mimeType: String
    ): JSONObject {
        val entryId = "ze_" + System.nanoTime() + "_" + archiveEntryCounter.getAndIncrement()
        val firstOffset = segments.firstOrNull()?.first ?: 0L
        val target = ArchiveEntryTarget(
            id = entryId,
            archiveUrl = isoUrl,
            fileName = displayName,
            archiveFormat = "ISO",
            compressionMethod = 0,
            compressedSize = totalBytes,
            uncompressedSize = totalBytes,
            localHeaderOffset = firstOffset,
            dataStartOffset = firstOffset,
            segments = segments
        )
        archiveEntryRegistry[entryId] = target
        val safeName = URLEncoder.encode(displayName, "UTF-8").replace("+", "%20")
        val streamUrl = "http://127.0.0.1:$port/zip/$entryId/$safeName"
        return JSONObject().apply {
            put("id", entryId)
            put("name", displayName)
            put("size", totalBytes.toString())
            put("mimeType", mimeType)
            put("link", streamUrl)
            put("isZipEntry", true)
            put("zipCompression", "STORE")
        }
    }

    private fun readIsoFileBytes(isoUrl: String, entry: IsoFileEntry, maxBytes: Int): ByteArray {
        val toFetch = minOf(entry.size, maxBytes.toLong()).toInt()
        if (toFetch <= 0 || entry.extents.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(toFetch)
        var rem = toFetch
        for ((extOff, extLen) in entry.extents) {
            if (rem <= 0) break
            val chunk = minOf(extLen, rem.toLong()).toInt()
            val bytes = fetchRemoteByteRange(isoUrl, extOff, extOff + chunk - 1L)
            out.write(bytes)
            rem -= bytes.size
        }
        return out.toByteArray()
    }

    private fun resolveUdfBlockByte(
        blockNum: Long,
        partRef: Int,
        blockSize: Long,
        defaultPhysBase: Long,
        metaBaseByte: Long,
        metaPartRef: Int,
        physPartitions: Map<Int, Long>
    ): Long {
        return if (partRef == metaPartRef && metaBaseByte >= 0L) {
            metaBaseByte + blockNum * blockSize
        } else {
            val pBase = physPartitions[partRef] ?: defaultPhysBase
            pBase + blockNum * blockSize
        }
    }

    private fun walkUdfDir(
        isoUrl: String,
        blockSize: Long,
        defaultPhysBase: Long,
        metaBaseByte: Long,
        metaPartRef: Int,
        physPartitions: Map<Int, Long>,
        icbBlock: Long,
        icbPartRef: Int,
        parentPath: String,
        depth: Int,
        results: MutableList<IsoFileEntry>
    ) {
        if (depth > 6) return
        val feByteOff = resolveUdfBlockByte(icbBlock, icbPartRef, blockSize, defaultPhysBase, metaBaseByte, metaPartRef, physPartitions)
        val feBuf = fetchRemoteByteRange(isoUrl, feByteOff, feByteOff + blockSize - 1L)
        val dirExts = parseUdfFileEntryExtents(feBuf, 0, blockSize, defaultPhysBase, metaBaseByte, metaPartRef)
        if (dirExts.isEmpty()) return

        val dirStream = java.io.ByteArrayOutputStream()
        var fetched = 0
        for ((dOff, dLen) in dirExts) {
            if (fetched >= 524288) break
            val take = minOf(dLen, (524288 - fetched).toLong()).toInt()
            if (take <= 0) break
            val chunk = if (dOff >= feByteOff && dOff + take <= feByteOff + feBuf.size) {
                feBuf.copyOfRange((dOff - feByteOff).toInt(), (dOff - feByteOff).toInt() + take)
            } else {
                fetchRemoteByteRange(isoUrl, dOff, dOff + take - 1L)
            }
            dirStream.write(chunk)
            fetched += chunk.size
        }
        val dirBuf = dirStream.toByteArray()

        var p = 0
        while (p + 38 <= dirBuf.size) {
            val tid = readUInt16LE(dirBuf, p)
            if (tid != 257) break
            val chars = dirBuf[p + 18].toInt() and 0xFF
            val lFi = dirBuf[p + 19].toInt() and 0xFF
            val childBlock = readUInt32LE(dirBuf, p + 24)
            val childPartRef = readUInt16LE(dirBuf, p + 28)
            val lIu = readUInt16LE(dirBuf, p + 36)
            val fiStart = p + 38 + lIu
            val nextP = p + ((38 + lIu + lFi + 3) and 3.inv())
            if (fiStart + lFi > dirBuf.size) break
            p = nextP

            val isDeleted = (chars and 0x04) != 0
            val isParent = (chars and 0x08) != 0
            if (isDeleted || isParent || lFi <= 1) continue

            val name = decodeUdfDString(dirBuf, fiStart, lFi)
            if (name.isEmpty() || name == "." || name == "..") continue
            val isChildDir = (chars and 0x02) != 0
            val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"

            if (isChildDir) {
                val upper = fullPath.uppercase(Locale.ROOT)
                if (depth <= 2 || upper.startsWith("BDMV") || upper.startsWith("VIDEO_TS")) {
                    walkUdfDir(isoUrl, blockSize, defaultPhysBase, metaBaseByte, metaPartRef, physPartitions, childBlock, childPartRef, fullPath, depth + 1, results)
                }
            } else {
                val childFeOff = resolveUdfBlockByte(childBlock, childPartRef, blockSize, defaultPhysBase, metaBaseByte, metaPartRef, physPartitions)
                val childFeBuf = fetchRemoteByteRange(isoUrl, childFeOff, childFeOff + blockSize - 1L)
                val infoLen = if (childFeBuf.size >= 64) readInt64LE(childFeBuf, 56) else 0L
                val fileExts = parseUdfFileEntryExtents(childFeBuf, 0, blockSize, defaultPhysBase, metaBaseByte, metaPartRef)
                if (infoLen > 0L && fileExts.isNotEmpty()) {
                    results.add(
                        IsoFileEntry(
                            path = fullPath,
                            name = name,
                            isDir = false,
                            size = infoLen,
                            extents = fileExts
                        )
                    )
                }
            }
        }
    }

    private fun parseUdfTree(isoUrl: String, totalIsoSize: Long): List<IsoFileEntry> {
        if (totalIsoSize < 600L * 1024L) return emptyList()
        var avdp = fetchRemoteByteRange(isoUrl, 524288L, 524799L)
        if (avdp.size < 32 || readUInt16LE(avdp, 0) != 2) {
            val lastSec = ((totalIsoSize / 2048L) - 1L) * 2048L
            if (lastSec > 524288L) {
                avdp = fetchRemoteByteRange(isoUrl, lastSec, lastSec + 511L)
            }
        }
        if (avdp.size < 32 || readUInt16LE(avdp, 0) != 2) return emptyList()

        val mvdsLen = readUInt32LE(avdp, 16).coerceIn(2048L, 65536L).toInt()
        val mvdsLoc = readUInt32LE(avdp, 20)
        if (mvdsLoc <= 0L) return emptyList()

        val mvdsStart = mvdsLoc * 2048L
        val mvdsBuf = fetchRemoteByteRange(isoUrl, mvdsStart, minOf(totalIsoSize - 1L, mvdsStart + mvdsLen - 1L))

        var blockSize = 2048L
        val physPartitions = HashMap<Int, Long>()
        var fsdLen = 0L
        var fsdBlock = 0L
        var fsdPartRef = 0
        var metaPhysPartNum = -1
        var metaFileBlock = -1L
        var metaPartRef = -1

        var off = 0
        while (off + 512 <= mvdsBuf.size) {
            val tagId = readUInt16LE(mvdsBuf, off)
            if (tagId == 8 || tagId == 0) break
            if (tagId == 5) {
                val partNum = readUInt16LE(mvdsBuf, off + 22)
                val partStartSec = readUInt32LE(mvdsBuf, off + 188)
                physPartitions[partNum] = partStartSec * 2048L
            } else if (tagId == 6) {
                val bs = readUInt32LE(mvdsBuf, off + 212)
                if (bs >= 512L) blockSize = bs
                fsdLen = readUInt32LE(mvdsBuf, off + 248)
                fsdBlock = readUInt32LE(mvdsBuf, off + 252)
                fsdPartRef = readUInt16LE(mvdsBuf, off + 256)

                val numPartMaps = readUInt32LE(mvdsBuf, off + 268).toInt()
                var mp = off + 440
                for (mIdx in 0 until numPartMaps) {
                    if (mp + 6 > off + 2048 || mp + 6 > mvdsBuf.size) break
                    val mType = mvdsBuf[mp].toInt() and 0xFF
                    val mLen = mvdsBuf[mp + 1].toInt() and 0xFF
                    if (mLen <= 0 || mp + mLen > mvdsBuf.size) break
                    if (mType == 2 && mLen >= 48) {
                        val partIdStr = String(mvdsBuf, mp + 5, 23, StandardCharsets.US_ASCII)
                        if (partIdStr.contains("Metadata")) {
                            metaPartRef = mIdx
                            metaPhysPartNum = readUInt16LE(mvdsBuf, mp + 38)
                            metaFileBlock = readUInt32LE(mvdsBuf, mp + 40)
                        }
                    }
                    mp += mLen
                }
            }
            off += 2048
        }

        if (physPartitions.isEmpty() || fsdLen <= 0L) return emptyList()
        val defaultPhysBase = physPartitions[0] ?: physPartitions.values.first()

        var metaBaseByte = -1L
        if (metaPartRef >= 0 && metaFileBlock >= 0L) {
            val mPhysBase = physPartitions[metaPhysPartNum] ?: defaultPhysBase
            val mFeOffset = mPhysBase + metaFileBlock * blockSize
            val mFeBuf = fetchRemoteByteRange(isoUrl, mFeOffset, mFeOffset + blockSize - 1L)
            val mExts = parseUdfFileEntryExtents(mFeBuf, 0, blockSize, mPhysBase, -1L)
            if (mExts.isNotEmpty()) {
                metaBaseByte = mExts[0].first
            }
        }

        val fsdByteOff = resolveUdfBlockByte(fsdBlock, fsdPartRef, blockSize, defaultPhysBase, metaBaseByte, metaPartRef, physPartitions)
        val fsdBuf = fetchRemoteByteRange(isoUrl, fsdByteOff, fsdByteOff + blockSize - 1L)
        if (fsdBuf.size < 416 || readUInt16LE(fsdBuf, 0) != 256) return emptyList()

        val rootIcbBlock = readUInt32LE(fsdBuf, 404)
        val rootIcbPartRef = readUInt16LE(fsdBuf, 408)

        val results = ArrayList<IsoFileEntry>()
        walkUdfDir(isoUrl, blockSize, defaultPhysBase, metaBaseByte, metaPartRef, physPartitions, rootIcbBlock, rootIcbPartRef, "", 0, results)
        return results
    }

    private fun parseUdfFileEntryExtents(
        buf: ByteArray,
        secOff: Int,
        blockSize: Long,
        defaultPhysBase: Long,
        metaBaseByte: Long,
        metaPartRef: Int = -1
    ): List<Pair<Long, Long>> {
        if (secOff + 180 > buf.size) return emptyList()
        val tagId = readUInt16LE(buf, secOff)
        if (tagId != 256 && tagId != 261) return emptyList()

        val icbFlags = readUInt16LE(buf, secOff + 18)
        val allocType = icbFlags and 0x07
        val infoLen = readInt64LE(buf, secOff + 56)

        val lEa: Int
        val lAd: Int
        val adBase: Int
        if (tagId == 261) {
            if (secOff + 216 > buf.size) return emptyList()
            lEa = readUInt32LE(buf, secOff + 208).toInt()
            lAd = readUInt32LE(buf, secOff + 212).toInt()
            adBase = secOff + 216 + lEa
        } else {
            lEa = readUInt32LE(buf, secOff + 168).toInt()
            lAd = readUInt32LE(buf, secOff + 172).toInt()
            adBase = secOff + 176 + lEa
        }
        if (adBase < 0 || adBase > buf.size) return emptyList()
        val adEnd = (adBase + lAd).coerceAtMost(buf.size)

        val out = ArrayList<Pair<Long, Long>>()
        var remainingInfo = infoLen

        if (allocType == 0) {
            var ap = adBase
            while (ap + 8 <= adEnd && remainingInfo > 0L) {
                val rawLen = readUInt32LE(buf, ap)
                val extType = (rawLen ushr 30).toInt()
                val extBytes = rawLen and 0x3FFFFFFFL
                val extPos = readUInt32LE(buf, ap + 4)
                ap += 8
                if (extBytes == 0L) break
                if (extType == 0) {
                    val take = minOf(extBytes, remainingInfo)
                    out.add(Pair(defaultPhysBase + extPos * blockSize, take))
                    remainingInfo -= take
                }
            }
        } else if (allocType == 1) {
            var ap = adBase
            while (ap + 16 <= adEnd && remainingInfo > 0L) {
                val rawLen = readUInt32LE(buf, ap)
                val extType = (rawLen ushr 30).toInt()
                val extBytes = rawLen and 0x3FFFFFFFL
                val extBlock = readUInt32LE(buf, ap + 4)
                val pRef = readUInt16LE(buf, ap + 8)
                ap += 16
                if (extBytes == 0L) break
                if (extType == 0) {
                    val base = if (pRef == metaPartRef && metaBaseByte >= 0L) metaBaseByte else defaultPhysBase
                    val take = minOf(extBytes, remainingInfo)
                    out.add(Pair(base + extBlock * blockSize, take))
                    remainingInfo -= take
                }
            }
        }
        return out
    }

    private fun walkIso9660Dir(
        isoUrl: String,
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
        val dirBuf = fetchRemoteByteRange(isoUrl, byteStart, minOf(totalIsoSize - 1L, byteStart + fetchLen - 1L))
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
                        walkIso9660Dir(isoUrl, totalIsoSize, extentLba, dataLen, fullPath, depth + 1, results)
                    } else if (dataLen > 0L) {
                        results.add(
                            IsoFileEntry(
                                path = fullPath,
                                name = cleanName,
                                isDir = false,
                                size = dataLen,
                                extents = listOf(Pair(extentLba * 2048L, dataLen))
                            )
                        )
                    }
                }
            }
            p += recLen
        }
    }

    private fun parseIso9660Tree(
        isoUrl: String,
        totalIsoSize: Long,
        volDescProbe: ByteArray?
    ): List<IsoFileEntry> {
        val pvdBuf = volDescProbe ?: fetchRemoteByteRange(isoUrl, 32768L, minOf(totalIsoSize - 1L, 40959L))
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
        walkIso9660Dir(isoUrl, totalIsoSize, rootLba, rootLen, "", 0, results)
        return results
    }

}
