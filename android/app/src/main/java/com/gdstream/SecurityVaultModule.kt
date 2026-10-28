package com.gdstream

import android.app.Dialog
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
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern
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

    data class TrailerStreamOption(
        val label: String,
        val height: Int,
        val videoUrl: String,
        val audioUrl: String,
        val isMuxed: Boolean
    )

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

    // Fallback Native YouTube InnerTube Trailer Finder when TMDb /videos is empty
    @ReactMethod
    fun findYoutubeTrailerId(titleQuery: String, promise: Promise) {
        ioExecutor.submit {
            try {
                val clean = titleQuery.trim()
                if (clean.isEmpty()) {
                    promise.resolve("")
                    return@submit
                }
                val searchQuery = "$clean Official Trailer"
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
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                }

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                conn.disconnect()

                // Match videoRenderer blocks with videoId and verify title relevance
                val primaryWord = clean.split(Regex("\\s+")).firstOrNull { it.length >= 3 }?.lowercase(Locale.ROOT)
                    ?: clean.lowercase(Locale.ROOT)
                val pattern = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"")
                val matcher = pattern.matcher(resp)
                var foundId = ""
                while (matcher.find()) {
                    val vid = matcher.group(1) ?: continue
                    val vTitle = (matcher.group(2) ?: "").lowercase(Locale.ROOT)
                    if (vTitle.contains(primaryWord) &&
                        (vTitle.contains("trailer") || vTitle.contains("teaser") || vTitle.contains("glimpse") || vTitle.contains("promo"))
                    ) {
                        foundId = vid
                        break
                    }
                }
                promise.resolve(foundId)
            } catch (_: Throwable) {
                promise.resolve("")
            }
        }
    }

    private fun fetchInnerTubeStreams(videoId: String): List<TrailerStreamOption> {
        val clients = listOf(
            Triple("ANDROID_VR", "1.60.19", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1; Cronet/132.0.6808.3)"),
            Triple("IOS", "19.45.4", "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"),
            Triple("ANDROID_TESTSUITE", "1.9", "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US)")
        )

        for ((clientName, clientVer, ua) in clients) {
            try {
                val clientObj = JSONObject().apply {
                    put("clientName", clientName)
                    put("clientVersion", clientVer)
                    put("hl", "en")
                    put("gl", "US")
                    if (clientName == "IOS") {
                        put("deviceMake", "Apple")
                        put("deviceModel", "iPhone16,2")
                        put("osName", "iPhone")
                        put("osVersion", "18.1.0.22B83")
                    } else {
                        put("androidSdkVersion", 34)
                        put("osName", "Android")
                        put("osVersion", "14")
                    }
                }
                val body = JSONObject().apply {
                    put("context", JSONObject().put("client", clientObj))
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }.toString()

                val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 12000
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", ua)
                }

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                conn.disconnect()

                val root = JSONObject(text)
                val streamingData = root.optJSONObject("streamingData") ?: continue

                val formats = streamingData.optJSONArray("formats") ?: JSONArray()
                val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()

                // 1. Find best audio-only stream (prefer audio/mp4 m4a for universal hardware playback)
                var bestAudioUrl = ""
                var bestAudioScore = -1
                for (i in 0 until adaptive.length()) {
                    val item = adaptive.optJSONObject(i) ?: continue
                    val mime = item.optString("mimeType", "")
                    val url = item.optString("url", "")
                    if (url.isEmpty() || !mime.startsWith("audio/")) continue
                    val br = item.optInt("bitrate", 0)
                    val score = (if (mime.contains("mp4")) 1000000 else 0) + br
                    if (score > bestAudioScore) {
                        bestAudioScore = score
                        bestAudioUrl = url
                    }
                }

                // 2. Collect video resolutions (2160p 4K, 1440p, 1080p, 720p, 480p, 360p)
                val byHeight = LinkedHashMap<Int, TrailerStreamOption>()
                val byHeightScore = HashMap<Int, Int>()

                // Check adaptiveFormats first for high-res 4K / 1440p / 1080p / 720p / 480p / 360p
                if (bestAudioUrl.isNotEmpty()) {
                    for (i in 0 until adaptive.length()) {
                        val item = adaptive.optJSONObject(i) ?: continue
                        val mime = item.optString("mimeType", "")
                        val url = item.optString("url", "")
                        val height = item.optInt("height", 0)
                        if (url.isEmpty() || !mime.startsWith("video/") || height < 240) continue
                        // Prefer avc1 / mp4 or vp9 for hardware compatibility, skip av01 if alternatives exist
                        val codecScore = when {
                            mime.contains("avc1") -> 30000000
                            mime.contains("mp4") && !mime.contains("av01") -> 20000000
                            mime.contains("vp9") -> 15000000
                            else -> 5000000
                        } + item.optInt("bitrate", 0)

                        val prevScore = byHeightScore[height] ?: -1
                        if (codecScore > prevScore) {
                            byHeightScore[height] = codecScore
                            val label = when {
                                height >= 2160 -> "2160p 4K"
                                height >= 1440 -> "1440p QHD"
                                height >= 1080 -> "1080p FHD"
                                height >= 720 -> "720p HD"
                                height >= 480 -> "480p"
                                else -> "${height}p"
                            }
                            byHeight[height] = TrailerStreamOption(
                                label = label,
                                height = height,
                                videoUrl = url,
                                audioUrl = bestAudioUrl,
                                isMuxed = false
                            )
                        }
                    }
                }

                // Also check muxed formats (with integrated audio)
                for (i in 0 until formats.length()) {
                    val item = formats.optJSONObject(i) ?: continue
                    val url = item.optString("url", "")
                    val height = item.optInt("height", 0)
                    if (url.isEmpty() || height < 240) continue
                    if (!byHeight.containsKey(height)) {
                        val label = when {
                            height >= 1080 -> "1080p FHD"
                            height >= 720 -> "720p HD"
                            height >= 480 -> "480p"
                            else -> "${height}p"
                        }
                        byHeight[height] = TrailerStreamOption(
                            label = label,
                            height = height,
                            videoUrl = url,
                            audioUrl = "",
                            isMuxed = true
                        )
                    }
                }

                val sorted = byHeight.values.sortedByDescending { it.height }.toMutableList()

                // Also append HLS Auto option if IOS client provided hlsManifestUrl
                val hlsUrl = streamingData.optString("hlsManifestUrl", "")
                if (hlsUrl.isNotEmpty()) {
                    sorted.add(
                        TrailerStreamOption(
                            label = "Auto (HLS)",
                            height = 1,
                            videoUrl = hlsUrl,
                            audioUrl = "",
                            isMuxed = true
                        )
                    )
                }

                if (sorted.isNotEmpty()) {
                    return sorted
                }
            } catch (_: Throwable) {}
        }
        return emptyList()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            reactContext.resources.displayMetrics
        ).toInt()
    }

    private fun formatTimeMs(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    @ReactMethod
    fun playYoutubeTrailer(videoId: String, title: String, promise: Promise) {
        ioExecutor.submit {
            val streams = fetchInnerTubeStreams(videoId)
            if (streams.isEmpty()) {
                promise.reject("E_TRAILER", "Trailer streams unavailable")
                return@submit
            }

            UiThreadUtil.runOnUiThread {
                val activity: android.app.Activity? = reactContext.currentActivity
                if (activity == null || activity.isFinishing) {
                    promise.reject("E_ACT", "Activity unavailable")
                    return@runOnUiThread
                }

                val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val root = FrameLayout(activity).apply {
                    setBackgroundColor(Color.parseColor("#08080C"))
                }

                // Video Surface container centered
                val surfaceContainer = FrameLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                }

                val surfaceView = SurfaceView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                }
                surfaceContainer.addView(surfaceView)

                val spinner = ProgressBar(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
                }
                surfaceContainer.addView(spinner)
                root.addView(surfaceContainer)

                // Top Overlay Bar (Title + Quality Selector Chips + Close Button)
                val topOverlay = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#CC0D0D14"))
                    setPadding(dp(16), dp(14), dp(16), dp(12))
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

                val titleView = TextView(activity).apply {
                    text = "🎬 $title • Official Trailer (Ad-Free)"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val closeBtn = TextView(activity).apply {
                    text = "✕ Close"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#262632"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#444455"))
                    }
                    setOnClickListener { dialog.dismiss() }
                }

                headerRow.addView(titleView)
                headerRow.addView(closeBtn)
                topOverlay.addView(headerRow)

                // Quality Selector Row
                val qualityScroll = HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(0, dp(10), 0, 0)
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

                // Bottom Controls Overlay
                val bottomOverlay = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#CC0D0D14"))
                    setPadding(dp(16), dp(10), dp(16), dp(16))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                }

                val seekBar = SeekBar(activity).apply {
                    max = 1000
                }
                bottomOverlay.addView(seekBar)

                val controlsRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }

                val playPauseBtn = TextView(activity).apply {
                    text = "⏸ Pause"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E50914"))
                        cornerRadius = dp(8).toFloat()
                    }
                }

                val rewBtn = TextView(activity).apply {
                    text = "⏪ -10s"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                }

                val ffBtn = TextView(activity).apply {
                    text = "+10s ⏩"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                }

                val timeLabel = TextView(activity).apply {
                    text = "00:00 / 00:00"
                    setTextColor(Color.parseColor("#D1D5DB"))
                    textSize = 12f
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                controlsRow.addView(playPauseBtn)
                controlsRow.addView(rewBtn)
                controlsRow.addView(ffBtn)
                controlsRow.addView(timeLabel)
                bottomOverlay.addView(controlsRow)
                root.addView(bottomOverlay)

                dialog.setContentView(root)

                // MediaPlayers (Video + Synced Audio for 4K / 1440p / 1080p DASH streams)
                var videoPlayer: MediaPlayer? = null
                var audioPlayer: MediaPlayer? = null
                var videoPrepared = false
                var audioPrepared = false
                var isSeeking = false
                var resumePosMs = 0
                val uiHandler = Handler(Looper.getMainLooper())

                // Default to 1080p FHD if available, otherwise highest quality
                var activeIndex = streams.indexOfFirst { it.height == 1080 }
                    .takeIf { it >= 0 } ?: 0

                val qualityButtons = ArrayList<TextView>()

                fun updateQualityButtonStyles() {
                    for (i in qualityButtons.indices) {
                        val btn = qualityButtons[i]
                        val isSelected = (i == activeIndex)
                        btn.background = GradientDrawable().apply {
                            setColor(if (isSelected) Color.parseColor("#E50914") else Color.parseColor("#1F2029"))
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), if (isSelected) Color.parseColor("#FF4D4D") else Color.parseColor("#374151"))
                        }
                        btn.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#D1D5DB"))
                    }
                }

                fun releasePlayers() {
                    videoPrepared = false
                    audioPrepared = false
                    try { videoPlayer?.stop() } catch (_: Throwable) {}
                    try { videoPlayer?.release() } catch (_: Throwable) {}
                    videoPlayer = null
                    try { audioPlayer?.stop() } catch (_: Throwable) {}
                    try { audioPlayer?.release() } catch (_: Throwable) {}
                    audioPlayer = null
                }

                fun tryStartPlayback() {
                    val opt = streams[activeIndex]
                    if (!videoPrepared) return
                    if (!opt.isMuxed && opt.audioUrl.isNotEmpty() && !audioPrepared) return

                    spinner.visibility = View.GONE
                    try {
                        if (resumePosMs > 0) {
                            videoPlayer?.seekTo(resumePosMs)
                            audioPlayer?.seekTo(resumePosMs)
                        }
                        videoPlayer?.start()
                        audioPlayer?.start()
                        playPauseBtn.text = "⏸ Pause"
                    } catch (_: Throwable) {}
                }

                fun loadStreamOption(index: Int, seekMs: Int) {
                    activeIndex = index
                    resumePosMs = seekMs
                    updateQualityButtonStyles()
                    spinner.visibility = View.VISIBLE
                    releasePlayers()

                    val opt = streams[index]
                    val holder = surfaceView.holder

                    val vp = MediaPlayer()
                    videoPlayer = vp
                    vp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    vp.setDisplay(holder)
                    vp.setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) {
                            val rootW = surfaceContainer.width.takeIf { it > 0 } ?: reactContext.resources.displayMetrics.widthPixels
                            val rootH = surfaceContainer.height.takeIf { it > 0 } ?: reactContext.resources.displayMetrics.heightPixels
                            val videoAspect = w.toFloat() / h.toFloat()
                            val screenAspect = rootW.toFloat() / rootH.toFloat()
                            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
                            if (videoAspect > screenAspect) {
                                lp.width = rootW
                                lp.height = (rootW / videoAspect).toInt()
                            } else {
                                lp.height = rootH
                                lp.width = (rootH * videoAspect).toInt()
                            }
                            lp.gravity = Gravity.CENTER
                            surfaceView.layoutParams = lp
                        }
                    }
                    vp.setOnPreparedListener {
                        videoPrepared = true
                        tryStartPlayback()
                    }
                    vp.setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                            spinner.visibility = View.VISIBLE
                            try { audioPlayer?.pause() } catch (_: Throwable) {}
                        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                            spinner.visibility = View.GONE
                            if (vp.isPlaying) {
                                try {
                                    audioPlayer?.seekTo(vp.currentPosition)
                                    audioPlayer?.start()
                                } catch (_: Throwable) {}
                            }
                        }
                        false
                    }
                    vp.setOnCompletionListener {
                        playPauseBtn.text = "▶ Replay"
                        try { audioPlayer?.pause() } catch (_: Throwable) {}
                    }

                    if (!opt.isMuxed && opt.audioUrl.isNotEmpty()) {
                        val ap = MediaPlayer()
                        audioPlayer = ap
                        ap.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        ap.setOnPreparedListener {
                            audioPrepared = true
                            tryStartPlayback()
                        }
                        try {
                            ap.setDataSource(opt.audioUrl)
                            ap.prepareAsync()
                        } catch (_: Throwable) {
                            audioPrepared = true
                        }
                    }

                    try {
                        vp.setDataSource(opt.videoUrl)
                        vp.prepareAsync()
                    } catch (_: Throwable) {
                        spinner.visibility = View.GONE
                    }
                }

                // Populate Quality Chips (e.g., 2160p 4K, 1440p QHD, 1080p FHD, 720p HD, 480p, 360p)
                for (i in streams.indices) {
                    val opt = streams[i]
                    val chip = TextView(activity).apply {
                        text = opt.label
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(12), dp(5), dp(12), dp(5))
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.setMargins(0, 0, dp(8), 0)
                        layoutParams = lp
                        setOnClickListener {
                            if (activeIndex != i) {
                                val currentPos = try {
                                    if (videoPrepared) videoPlayer?.currentPosition ?: 0 else 0
                                } catch (_: Throwable) { 0 }
                                loadStreamOption(i, currentPos)
                            }
                        }
                    }
                    qualityButtons.add(chip)
                    qualityRow.addView(chip)
                }
                updateQualityButtonStyles()

                playPauseBtn.setOnClickListener {
                    val vp = videoPlayer ?: return@setOnClickListener
                    if (!videoPrepared) return@setOnClickListener
                    if (vp.isPlaying) {
                        vp.pause()
                        try { audioPlayer?.pause() } catch (_: Throwable) {}
                        playPauseBtn.text = "▶ Play"
                    } else {
                        try {
                            audioPlayer?.seekTo(vp.currentPosition)
                            vp.start()
                            audioPlayer?.start()
                            playPauseBtn.text = "⏸ Pause"
                        } catch (_: Throwable) {}
                    }
                }

                rewBtn.setOnClickListener {
                    val vp = videoPlayer ?: return@setOnClickListener
                    if (!videoPrepared) return@setOnClickListener
                    val target = (vp.currentPosition - 10000).coerceAtLeast(0)
                    vp.seekTo(target)
                    try { audioPlayer?.seekTo(target) } catch (_: Throwable) {}
                }

                ffBtn.setOnClickListener {
                    val vp = videoPlayer ?: return@setOnClickListener
                    if (!videoPrepared) return@setOnClickListener
                    val dur = vp.duration.coerceAtLeast(0)
                    val target = (vp.currentPosition + 10000).coerceAtMost(dur)
                    vp.seekTo(target)
                    try { audioPlayer?.seekTo(target) } catch (_: Throwable) {}
                }

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        isSeeking = false
                        val vp = videoPlayer ?: return
                        if (!videoPrepared || sb == null) return
                        val dur = vp.duration
                        if (dur > 0) {
                            val target = ((sb.progress.toLong() * dur) / 1000L).toInt()
                            vp.seekTo(target)
                            try { audioPlayer?.seekTo(target) } catch (_: Throwable) {}
                        }
                    }
                })

                val syncRunnable = object : Runnable {
                    override fun run() {
                        val vp = videoPlayer
                        if (vp != null && videoPrepared) {
                            try {
                                val pos = vp.currentPosition
                                val dur = vp.duration
                                if (dur > 0 && !isSeeking) {
                                    seekBar.progress = ((pos.toLong() * 1000L) / dur).toInt()
                                    timeLabel.text = "${formatTimeMs(pos)} / ${formatTimeMs(dur)}"
                                }
                                // Keep DASH audio in tight sync (<90ms drift)
                                val ap = audioPlayer
                                if (ap != null && audioPrepared && vp.isPlaying) {
                                    if (!ap.isPlaying) ap.start()
                                    val drift = kotlin.math.abs(pos - ap.currentPosition)
                                    if (drift > 90) {
                                        ap.seekTo(pos)
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                        uiHandler.postDelayed(this, 400)
                    }
                }

                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        loadStreamOption(activeIndex, resumePosMs)
                        uiHandler.post(syncRunnable)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        try {
                            if (videoPrepared) resumePosMs = videoPlayer?.currentPosition ?: 0
                        } catch (_: Throwable) {}
                        releasePlayers()
                    }
                })

                dialog.setOnDismissListener {
                    uiHandler.removeCallbacksAndMessages(null)
                    releasePlayers()
                }

                dialog.show()
                promise.resolve(true)
            }
        }
    }
}
