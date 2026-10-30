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

    data class VerifiedTrailerInfo(
        val engine: String,
        val videoId: String,
        val directStreams: List<DirectTrailerStream>,
        val maxQualityLabel: String
    )

    data class ArchiveEntryTarget(
        val id: String,
        val archiveUrl: String,
        val fileName: String,
        val archiveFormat: String, // "ZIP" or "RAR"
        val compressionMethod: Int, // 0 = STORE (Uncompressed), 8 = DEFLATE (ZIP), >0 = RAR Compressed
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        @Volatile var dataStartOffset: Long = -1L
    )

    private val verifiedTrailerCache = ConcurrentHashMap<String, VerifiedTrailerInfo>()
    private val archiveEntryRegistry = ConcurrentHashMap<String, ArchiveEntryTarget>()
    private val archiveEntryCounter = AtomicInteger(1)

    @Volatile
    private var archiveProxyPort: Int = 0

    private val archiveProxyLock = Any()

    companion object {
        init {
            try {
                System.loadLibrary("gdcore")
                val cm = CookieManager(null, CookiePolicy.ACCEPT_ALL)
                CookieHandler.setDefault(cm)
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

    private fun openRangeConnection(urlStr: String, rangeHeader: String?): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "GDStream-Native-Engine/3.0")
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
        throw IllegalStateException("Invalid ZIP Local File Header at offset ${entry.localHeaderOffset}")
    }

    private fun ensureArchiveProxyRunning(): Int {
        if (archiveProxyPort > 0) return archiveProxyPort
        synchronized(archiveProxyLock) {
            if (archiveProxyPort > 0) return archiveProxyPort
            val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            archiveProxyPort = server.localPort
            ioExecutor.submit {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        ioExecutor.submit { handleArchiveProxyClient(client) }
                    } catch (_: Throwable) {}
                }
            }
            return archiveProxyPort
        }
    }

    private fun handleArchiveProxyClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII))
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
            if (segments.size < 2 || segments[0] != "zip") {
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val entryId = segments[1]
            val entry = archiveEntryRegistry[entryId]
            if (entry == null) {
                val out = sock.getOutputStream()
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

            val out = BufferedOutputStream(sock.getOutputStream(), 65536)

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

            val dataStart = resolveEntryDataStartOffset(entry)

            if (entry.compressionMethod == 0) {
                // STORE Mode (Uncompressed ZIP or Uncompressed RAR): Direct 1:1 Byte-Slice Range Streaming!
                val remoteStart = dataStart + reqStart
                val remoteEnd = dataStart + reqEnd
                val remoteConn = openRangeConnection(entry.archiveUrl, "bytes=$remoteStart-$remoteEnd")
                try {
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

                    remoteConn.inputStream.use { input ->
                        val buf = ByteArray(65536)
                        var remaining = contentLength
                        while (remaining > 0L) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val read = input.read(buf, 0, toRead)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            remaining -= read.toLong()
                        }
                        out.flush()
                    }
                } finally {
                    remoteConn.disconnect()
                }
            } else if (entry.archiveFormat == "ZIP" && entry.compressionMethod == 8) {
                // DEFLATE Mode (Compressed ZIP): On-the-fly Inflater stream
                val remoteStart = dataStart
                val remoteEnd = dataStart + entry.compressedSize - 1L
                val remoteConn = openRangeConnection(entry.archiveUrl, "bytes=$remoteStart-$remoteEnd")
                val inflater = Inflater(true)
                try {
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

                    InflaterInputStream(remoteConn.inputStream, inflater, 65536).use { infStream ->
                        val buf = ByteArray(65536)
                        var toSkip = reqStart
                        while (toSkip > 0L) {
                            val step = minOf(buf.size.toLong(), toSkip).toInt()
                            val skipped = infStream.read(buf, 0, step)
                            if (skipped <= 0) break
                            toSkip -= skipped.toLong()
                        }

                        var remaining = contentLength
                        while (remaining > 0L) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val read = infStream.read(buf, 0, toRead)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            remaining -= read.toLong()
                        }
                        out.flush()
                    }
                } finally {
                    inflater.end()
                    remoteConn.disconnect()
                }
            } else {
                val outStream = sock.getOutputStream()
                outStream.write("HTTP/1.1 415 Unsupported Media Type\r\nContent-Length: 0\r\n\r\n".toByteArray())
                outStream.flush()
            }
        }
    }

    // Hop across RAR 5.0 block headers via tiny Range reads (Works even if .rar was renamed to .zip!)
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
                        val entryId = "ze_${archiveEntryCounter.getAndIncrement()}"
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
                        val entryId = "ze_${archiveEntryCounter.getAndIncrement()}"
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

    @ReactMethod
    fun listZipMediaEntries(zipUrl: String, fileSizeHint: Double, promise: Promise) {
        ioExecutor.submit {
            try {
                val seed = verifyCertificateAndEnv()
                if (seed != 0x5A3C9E71) {
                    promise.reject("E_SEC", "Security shield active")
                    return@submit
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
                    return@submit
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
                    return@submit
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
                    return@submit
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
                    return@submit
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

    private fun searchStrictOfficialTrailerIds(titleQuery: String, year: String): List<String> {
        val candidates = ArrayList<String>()
        try {
            val clean = titleQuery.trim()
            if (clean.isEmpty()) return emptyList()

            val searchQuery = if (year.isNotEmpty()) "$clean $year Official Trailer" else "$clean Official Trailer"
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
                connectTimeout = 7000
                readTimeout = 7000
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

                val titleMatch = Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"").matcher(chunk)
                if (!titleMatch.find()) continue
                val vTitle = (titleMatch.group(1) ?: "").lowercase(Locale.ROOT)

                if (!vTitle.contains("trailer")) continue
                if (isReviewOrJunkTitle(vTitle)) continue

                val matchesMovie = if (titleWords.isNotEmpty()) {
                    titleWords.all { w -> vTitle.contains(w) }
                } else {
                    vTitle.contains(clean.lowercase(Locale.ROOT))
                }

                if (matchesMovie && !candidates.contains(vid)) {
                    candidates.add(vid)
                    if (candidates.size >= 4) break
                }
            }
        } catch (_: Throwable) {}
        return candidates
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
                    promise.resolve(res)
                    return@submit
                }

                try {
                    val arr = JSONArray(tmdbYtKeysJson)
                    for (i in 0 until arr.length()) {
                        val key = arr.optString(i, "").trim()
                        if (key.length == 11 && verifyYoutubeOembedClean(key, true)) {
                            val info = VerifiedTrailerInfo("YT_VERIFIED", key, emptyList(), "4K / 1080p")
                            verifiedTrailerCache[cacheKey] = info
                            val res = Arguments.createMap()
                            res.putBoolean("available", true)
                            res.putString("cacheKey", cacheKey)
                            res.putString("bestQuality", "4K / 1080p")
                            promise.resolve(res)
                            return@submit
                        }
                    }
                } catch (_: Throwable) {}

                if (imdbId.contains("|")) {
                    val parts = imdbId.split("|")
                    val tmdbIdStr = parts.getOrNull(0) ?: ""
                    val isTv = (parts.getOrNull(1) == "tv")
                    val kcKey = fetchKinocheckTrailerId(tmdbIdStr, isTv)
                    if (kcKey.length == 11) {
                        val info = VerifiedTrailerInfo("YT_VERIFIED", kcKey, emptyList(), "4K / 1080p")
                        verifiedTrailerCache[cacheKey] = info
                        val res = Arguments.createMap()
                        res.putBoolean("available", true)
                        res.putString("cacheKey", cacheKey)
                        res.putString("bestQuality", "4K / 1080p")
                        promise.resolve(res)
                        return@submit
                    }
                }

                val appleStreams = fetchAppleItunesTrailerStreams(title, year)
                if (appleStreams.isNotEmpty()) {
                    val bestQ = appleStreams.first().label
                    val info = VerifiedTrailerInfo("IMDB_DIRECT", "", appleStreams, bestQ)
                    verifiedTrailerCache[cacheKey] = info
                    val res = Arguments.createMap()
                    res.putBoolean("available", true)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", bestQ)
                    promise.resolve(res)
                    return@submit
                }

                val ytCandidates = searchStrictOfficialTrailerIds(title, year)
                for (candId in ytCandidates) {
                    if (verifyYoutubeOembedClean(candId, false)) {
                        val info = VerifiedTrailerInfo("YT_VERIFIED", candId, emptyList(), "1080p FHD")
                        verifiedTrailerCache[cacheKey] = info
                        val res = Arguments.createMap()
                        res.putBoolean("available", true)
                        res.putString("cacheKey", cacheKey)
                        res.putString("bestQuality", "1080p FHD")
                        promise.resolve(res)
                        return@submit
                    }
                }

                val res = Arguments.createMap()
                res.putBoolean("available", false)
                res.putString("cacheKey", cacheKey)
                res.putString("bestQuality", "")
                promise.resolve(res)
            } catch (_: Throwable) {
                val res = Arguments.createMap()
                res.putBoolean("available", false)
                res.putString("cacheKey", cacheKey)
                res.putString("bestQuality", "")
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

    @SuppressLint("SetJavaScriptEnabled")
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
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

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

            val spinner = ProgressBar(activity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
            }
            root.addView(playerStage)
            root.addView(spinner)

            val topOverlay = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC0A0A0F"))
                setPadding(dp(18), dp(12), dp(18), dp(10))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            }

            val headerRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val closeBtn = TextView(activity).apply {
                text = "← Back"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E50914"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { dialog.dismiss() }
            }

            val titleView = TextView(activity).apply {
                text = "  🎬 $title • Official Trailer"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            headerRow.addView(closeBtn)
            headerRow.addView(titleView)
            topOverlay.addView(headerRow)

            val qualityScroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(8), 0, 0)
            }
            val qualityRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val qualityTagLabel = TextView(activity).apply {
                text = "QUALITY: "
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dp(8), 0)
            }
            qualityRow.addView(qualityTagLabel)
            qualityScroll.addView(qualityRow)
            topOverlay.addView(qualityScroll)
            root.addView(topOverlay)

            val bottomOverlay = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#CC0A0A0F"))
                setPadding(dp(18), dp(8), dp(18), dp(14))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }

            val seekBar = SeekBar(activity).apply { max = 1000 }
            bottomOverlay.addView(seekBar)

            val controlsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }

            val playPauseBtn = TextView(activity).apply {
                text = "⏸ Pause"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E50914"))
                    cornerRadius = dp(8).toFloat()
                }
            }

            val rewBtn = TextView(activity).apply {
                text = "⏪ -10s"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }

            val ffBtn = TextView(activity).apply {
                text = "+10s ⏩"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(6), dp(14), dp(6))
            }

            val timeLabel = TextView(activity).apply {
                text = "00:00 / 00:00"
                setTextColor(Color.parseColor("#E5E7EB"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            controlsRow.addView(playPauseBtn)
            controlsRow.addView(rewBtn)
            controlsRow.addView(ffBtn)
            controlsRow.addView(timeLabel)
            bottomOverlay.addView(controlsRow)
            root.addView(bottomOverlay)

            val touchInterceptor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    val vis = if (topOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    topOverlay.visibility = vis
                    bottomOverlay.visibility = vis
                }
            }
            root.addView(touchInterceptor, 1)

            dialog.setContentView(root)

            val uiHandler = Handler(Looper.getMainLooper())
            var mediaPlayer: MediaPlayer? = null
            var activeWebView: WebView? = null

            if (info.engine == "IMDB_DIRECT" && info.directStreams.isNotEmpty()) {
                val streams = info.directStreams
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
                val qButtons = ArrayList<TextView>()

                fun styleButtons() {
                    for (i in qButtons.indices) {
                        val b = qButtons[i]
                        val sel = (i == activeIdx)
                        b.background = GradientDrawable().apply {
                            setColor(if (sel) Color.parseColor("#E50914") else Color.parseColor("#1F2029"))
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), if (sel) Color.parseColor("#FF4D4D") else Color.parseColor("#374151"))
                        }
                        b.setTextColor(if (sel) Color.WHITE else Color.parseColor("#D1D5DB"))
                    }
                }

                fun loadDirectStream(idx: Int, seekToMs: Int) {
                    activeIdx = idx
                    resumeMs = seekToMs
                    styleButtons()
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
                        playPauseBtn.text = "⏸ Pause"
                    }
                    mp.setOnCompletionListener {
                        playPauseBtn.text = "▶ Replay"
                    }
                    try {
                        mp.setDataSource(streams[idx].videoUrl)
                        mp.prepareAsync()
                    } catch (_: Throwable) {
                        spinner.visibility = View.GONE
                    }
                }

                for (i in streams.indices) {
                    val chip = TextView(activity).apply {
                        text = streams[i].label
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(12), dp(5), dp(12), dp(5))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, dp(8), 0) }
                        setOnClickListener {
                            if (activeIdx != i) {
                                val pos = try { if (prepared) mediaPlayer?.currentPosition ?: 0 else 0 } catch (_: Throwable) { 0 }
                                loadDirectStream(i, pos)
                            }
                        }
                    }
                    qButtons.add(chip)
                    qualityRow.addView(chip)
                }
                styleButtons()

                playPauseBtn.setOnClickListener {
                    val mp = mediaPlayer ?: return@setOnClickListener
                    if (!prepared) return@setOnClickListener
                    if (mp.isPlaying) {
                        mp.pause()
                        playPauseBtn.text = "▶ Play"
                    } else {
                        mp.start()
                        playPauseBtn.text = "⏸ Pause"
                    }
                }
                rewBtn.setOnClickListener {
                    val mp = mediaPlayer ?: return@setOnClickListener
                    if (prepared) mp.seekTo((mp.currentPosition - 10000).coerceAtLeast(0))
                }
                ffBtn.setOnClickListener {
                    val mp = mediaPlayer ?: return@setOnClickListener
                    if (prepared) mp.seekTo((mp.currentPosition + 10000).coerceAtMost(mp.duration.coerceAtLeast(0)))
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
                                    timeLabel.text = "${formatTimeSec(pos / 1000)} / ${formatTimeSec(dur / 1000)}"
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
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
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
                var activeVq = "hd1080"
                val qChips = LinkedHashMap<String, TextView>()

                fun styleYtQualityChips() {
                    for ((code, btn) in qChips) {
                        val sel = (code == activeVq)
                        btn.background = GradientDrawable().apply {
                            setColor(if (sel) Color.parseColor("#E50914") else Color.parseColor("#1F2029"))
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), if (sel) Color.parseColor("#FF4D4D") else Color.parseColor("#374151"))
                        }
                        btn.setTextColor(if (sel) Color.WHITE else Color.parseColor("#D1D5DB"))
                    }
                }

                fun populateQualityChips(codes: List<String>) {
                    qualityRow.removeAllViews()
                    qualityRow.addView(qualityTagLabel)
                    qChips.clear()

                    val validCodes = codes.filter { qualityMap.containsKey(it) }
                        .ifEmpty { listOf("hd1080", "hd720", "large") }

                    if (!validCodes.contains(activeVq)) {
                        activeVq = validCodes.first()
                    }

                    for (code in validCodes) {
                        val label = qualityMap[code] ?: code
                        val chip = TextView(activity).apply {
                            text = label
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(12), dp(5), dp(12), dp(5))
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 0, dp(8), 0) }
                            setOnClickListener {
                                activeVq = code
                                styleYtQualityChips()
                                spinner.visibility = View.VISIBLE
                                webView.evaluateJavascript("window.setTrailerQuality('$code');", null)
                            }
                        }
                        qChips[code] = chip
                        qualityRow.addView(chip)
                    }
                    styleYtQualityChips()
                }

                populateQualityChips(listOf("hd1080", "hd720", "large"))

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onPlayerReady(durSec: Double, qualitiesCsv: String) {
                        uiHandler.post {
                            spinner.visibility = View.GONE
                            totalDurationSec = durSec.toInt().coerceAtLeast(0)
                            if (qualitiesCsv.isNotEmpty()) {
                                val list = qualitiesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                if (list.isNotEmpty()) populateQualityChips(list)
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
                            playPauseBtn.text = if (playing) "⏸ Pause" else "▶ Play"
                            if (d > 0 && !isSeeking) {
                                seekBar.progress = ((c.toLong() * 1000L) / d).toInt()
                                timeLabel.text = "${formatTimeSec(c)} / ${formatTimeSec(d)}"
                            }
                        }
                    }
                }, "GDNativeBridge")

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

                val vid = info.videoId
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
                      var targetVq = 'hd1080';
                      function onYouTubeIframeAPIReady() {
                        player = new YT.Player('player', {
                          videoId: '$vid',
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
                                e.target.playVideo();
                                var qList = e.target.getAvailableQualityLevels ? e.target.getAvailableQualityLevels() : [];
                                var dur = e.target.getDuration ? e.target.getDuration() : 0;
                                if (window.GDNativeBridge) {
                                  window.GDNativeBridge.onPlayerReady(dur || 0, (qList || []).join(','));
                                }
                              } catch(err) {}
                            },
                            'onStateChange': function(e) {
                              try {
                                if (e.data === YT.PlayerState.PLAYING) {
                                  var qList = e.target.getAvailableQualityLevels ? e.target.getAvailableQualityLevels() : [];
                                  var dur = e.target.getDuration ? e.target.getDuration() : 0;
                                  if (window.GDNativeBridge && qList && qList.length > 0) {
                                    window.GDNativeBridge.onPlayerReady(dur || 0, qList.join(','));
                                  }
                                  if (targetVq && e.target.setPlaybackQualityRange) {
                                    e.target.setPlaybackQualityRange(targetVq, targetVq);
                                  }
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
                          player.loadVideoById({'videoId': '$vid', 'startSeconds': t, 'suggestedQuality': vq});
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

                playPauseBtn.setOnClickListener {
                    webView.evaluateJavascript("window.togglePlay();", null)
                }
                rewBtn.setOnClickListener {
                    webView.evaluateJavascript("window.seekBySec(-10);", null)
                }
                ffBtn.setOnClickListener {
                    webView.evaluateJavascript("window.seekBySec(10);", null)
                }
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {}
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        isSeeking = false
                        if (sb != null && totalDurationSec > 0) {
                            val targetSec = (sb.progress.toLong() * totalDurationSec) / 1000L
                            webView.evaluateJavascript("window.seekToSec($targetSec);", null)
                        }
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
            promise.resolve(true)
        }
    }
}
