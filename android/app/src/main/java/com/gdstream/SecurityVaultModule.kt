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
import java.util.concurrent.ConcurrentHashMap
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

    data class DirectTrailerStream(
        val label: String,
        val height: Int,
        val videoUrl: String
    )

    data class VerifiedTrailerInfo(
        val engine: String, // "IMDB_DIRECT" or "YT_VERIFIED"
        val videoId: String,
        val directStreams: List<DirectTrailerStream>,
        val maxQualityLabel: String
    )

    private val verifiedTrailerCache = ConcurrentHashMap<String, VerifiedTrailerInfo>()

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

    // Strict filter to reject Movie Reviews, Public Talk, Reactions, Shorts, Songs, etc.
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

    // Fast & reliable YouTube oEmbed verifier (Never triggers LOGIN_REQUIRED bot blocks!)
    // Returns true ONLY if the video exists, is public, allows embedding, and is NOT a review/reaction
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

    // Tier 2: KinoCheck Dedicated Trailer API (Curated Official Trailers by TMDb ID)
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

    // Tier 3: Apple iTunes Official Movie Trailers API (Direct Apple CDN 1080p/720p MP4 — Zero YouTube, Zero Ads, Zero Geo-blocks!)
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

    // Tier 4: Strict YouTube Official Trailer Search (Blocks all Reviews/Reactions/Teasers)
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

                // 1. TIER 1: TMDb Multi-Language Official Trailer Keys (en, ta, ml, hi, te, kn)
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

                // 2. TIER 2: KinoCheck Dedicated Trailer Database (imdbId param also carries "tmdbId|isSeries")
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

                // 3. TIER 3: Apple iTunes Official Movie Trailer Server (Direct 1080p MP4 — Zero YouTube!)
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

                // 4. TIER 4: Strict YouTube Official Trailer Search + oEmbed Verification (Rejects all Reviews!)
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

                // If no official trailer exists in any of the 4 sources, disable button cleanly
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

            // Force Landscape Cinema Mode just like "Play in App"!
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

            // Top Cinema Overlay Bar (Back Button + Title + Dynamic Real Quality Chips)
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

            // Bottom Native Cinema Controls Bar (Play/Pause, -10s, +10s, SeekBar, Time)
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

            // Touching anywhere on the video stage toggles our custom cinema controls
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
            root.addView(touchInterceptor, 1) // Right above playerStage, below topOverlay & bottomOverlay

            dialog.setContentView(root)

            val uiHandler = Handler(Looper.getMainLooper())
            var mediaPlayer: MediaPlayer? = null
            var activeWebView: WebView? = null

            if (info.engine == "IMDB_DIRECT" && info.directStreams.isNotEmpty()) {
                // Native SurfaceView Player for IMDb Direct MP4/HLS streams
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
                // Chromeless Locked Cinema Engine for Verified YouTube Trailers:
                // - ZERO YouTube controls/logos/channel headers (controls: 0 + pointer-events: none + cropped overscan)
                // - Controlled 100% by our Native Android Play/Pause, SeekBar, -10s/+10s, and Dynamic Quality Selector!
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

                var isPlayingState = true
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

                // Start with default chips; JS Bridge immediately updates to exact available qualities onReady!
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
                            isPlayingState = playing
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
