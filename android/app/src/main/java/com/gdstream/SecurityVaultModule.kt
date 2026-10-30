package com.gdstream

import android.app.Activity
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

    data class TrailerStreamOption(
        val label: String,
        val height: Int,
        val videoUrl: String,
        val audioUrl: String,
        val isMuxed: Boolean
    )

    private val verifiedTrailerCache = ConcurrentHashMap<String, List<TrailerStreamOption>>()

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

    // --- STAGE 1: IMDb Official Trailer CDN Extractor (Direct Amazon CloudFront MP4 / HLS, Zero Country Blocks, Zero Reviews!) ---
    private fun fetchImdbOfficialTrailerStreams(imdbIdArg: String, title: String, year: String): List<TrailerStreamOption> {
        try {
            var imdbId = imdbIdArg.trim()
            val cleanTitle = title.trim()

            // If TMDb didn't have imdb_id, look up IMDb's official suggestion API by title + year
            if (!imdbId.startsWith("tt") && cleanTitle.isNotEmpty()) {
                val firstChar = cleanTitle.lowercase(Locale.ROOT).firstOrNull { it.isLetterOrDigit() } ?: 'a'
                val encodedQ = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
                val sugUrl = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encodedQ.json"
                val sugConn = (URL(sugUrl).openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                }
                if (sugConn.responseCode in 200..299) {
                    val sugJson = BufferedReader(InputStreamReader(sugConn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val dArr = JSONObject(sugJson).optJSONArray("d") ?: JSONArray()
                    val normTarget = cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                    for (i in 0 until dArr.length()) {
                        val item = dArr.optJSONObject(i) ?: continue
                        val idCand = item.optString("id", "")
                        val lCand = item.optString("l", "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                        val yCand = item.optInt("y", 0).toString()
                        if (idCand.startsWith("tt") && (lCand == normTarget || lCand.contains(normTarget))) {
                            if (year.isEmpty() || yCand == year || kotlin.math.abs((yCand.toIntOrNull() ?: 0) - (year.toIntOrNull() ?: 0)) <= 1) {
                                imdbId = idCand
                                break
                            }
                        }
                    }
                }
                sugConn.disconnect()
            }

            if (!imdbId.startsWith("tt")) return emptyList()

            // Fetch IMDb Title page to find the primary official trailer videoId (vi...)
            val titleConn = (URL("https://www.imdb.com/title/$imdbId/").openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val titleHtml = BufferedReader(InputStreamReader(titleConn.inputStream, Charsets.UTF_8)).use { it.readText() }
            titleConn.disconnect()

            val viMatcher = Pattern.compile("/video/(vi\\d+)").matcher(titleHtml)
            if (!viMatcher.find()) return emptyList()
            val videoId = viMatcher.group(1) ?: return emptyList()

            // Fetch IMDb Video page and parse __NEXT_DATA__ playbackURLs (1080p, 720p, 480p, SD, AUTO)
            val vidConn = (URL("https://www.imdb.com/video/$videoId/").openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            }
            val vidHtml = BufferedReader(InputStreamReader(vidConn.inputStream, Charsets.UTF_8)).use { it.readText() }
            vidConn.disconnect()

            val nextMatcher = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>").matcher(vidHtml)
            if (!nextMatcher.find()) return emptyList()
            val nextJsonStr = nextMatcher.group(1) ?: return emptyList()

            val playbackMatcher = Pattern.compile("\"playbackURLs\":(\\[.*?\\])").matcher(nextJsonStr)
            if (!playbackMatcher.find()) return emptyList()
            val pbArray = JSONArray(playbackMatcher.group(1))

            val byHeight = LinkedHashMap<Int, TrailerStreamOption>()
            var hlsOption: TrailerStreamOption? = null

            for (i in 0 until pbArray.length()) {
                val obj = pbArray.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").replace("\\u0026", "&")
                val mime = obj.optString("videoMimeType", "").uppercase(Locale.ROOT)
                val defObj = obj.optJSONObject("displayName")
                val valStr = (defObj?.optString("value", "") ?: obj.optString("videoDefinition", "")).uppercase(Locale.ROOT)
                if (url.isEmpty()) continue

                if (mime.contains("M3U8") || valStr.contains("AUTO")) {
                    if (hlsOption == null) {
                        hlsOption = TrailerStreamOption("Auto (HLS)", 1, url, "", true)
                    }
                    continue
                }

                val height = when {
                    valStr.contains("1080") -> 1080
                    valStr.contains("720") -> 720
                    valStr.contains("480") -> 480
                    valStr.contains("360") || valStr.contains("SD") -> 360
                    else -> 480
                }
                val label = when (height) {
                    1080 -> "1080p FHD"
                    720 -> "720p HD"
                    480 -> "480p"
                    else -> "360p"
                }
                if (!byHeight.containsKey(height)) {
                    byHeight[height] = TrailerStreamOption(label, height, url, "", true)
                }
            }

            val results = byHeight.values.sortedByDescending { it.height }.toMutableList()
            if (hlsOption != null) results.add(hlsOption)
            return results
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    // --- STAGE 2: Strict YouTube Official Trailer Search (Blocks Reviews, Teasers, Reactions, Clips, Shorts) ---
    private fun findStrictYoutubeOfficialTrailerCandidates(titleQuery: String, year: String): List<String> {
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
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "POST"
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            conn.disconnect()

            // Significant words from the movie title
            val titleWords = clean.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 && it != "the" && it != "and" }

            // Strictly forbidden words (Never allow reviews, teasers, reactions, interviews, etc.)
            val forbiddenWords = listOf(
                "review", "reaction", "teaser", "glimpse", "promo", "explained",
                "breakdown", "public", "talk", "opinion", "fdfs", "interview",
                "making", "behind", "scene", "clip", "sneak", "spoof", "troll",
                "roast", "parody", "fan", "concept", "short", "status", "whatsapp",
                "full movie", "jukebox", "lyrical", "video song", "audio launch", "press"
            )

            val pattern = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"")
            val matcher = pattern.matcher(resp)
            while (matcher.find()) {
                val vid = matcher.group(1) ?: continue
                val vTitle = (matcher.group(2) ?: "").lowercase(Locale.ROOT)

                // 1. Must explicitly contain "trailer"
                if (!vTitle.contains("trailer")) continue

                // 2. Must NOT contain any forbidden word (review, teaser, reaction, etc.)
                if (forbiddenWords.any { vTitle.contains(it) }) continue

                // 3. Must match the movie title words
                val matchesTitle = if (titleWords.isNotEmpty()) {
                    titleWords.all { w -> vTitle.contains(w) }
                } else {
                    vTitle.contains(clean.lowercase(Locale.ROOT))
                }

                if (matchesTitle && !candidates.contains(vid)) {
                    candidates.add(vid)
                    if (candidates.size >= 3) break
                }
            }
        } catch (_: Throwable) {}
        return candidates
    }

    // --- STAGE 3: Extract & Verify Playable Direct Streams in India (gl: "IN") ---
    private fun extractVerifiedYoutubeStreamsInIndia(videoId: String): List<TrailerStreamOption> {
        val clients = listOf(
            Triple("IOS", "19.45.4", "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"),
            Triple("ANDROID_VR", "1.60.19", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1; Cronet/132.0.6808.3)"),
            Triple("ANDROID_TESTSUITE", "1.9", "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US)")
        )

        for ((clientName, clientVer, ua) in clients) {
            try {
                val clientObj = JSONObject().apply {
                    put("clientName", clientName)
                    put("clientVersion", clientVer)
                    put("hl", "en")
                    put("gl", "IN") // Strict India region check so country-blocked trailers are caught!
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
                    connectTimeout = 7000
                    readTimeout = 7000
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

                // Check Playability Status in India (Reject country-blocked, private, or age-gated videos!)
                val playability = root.optJSONObject("playabilityStatus")
                val status = playability?.optString("status", "") ?: ""
                if (status != "OK") {
                    continue
                }

                // Check videoDetails to reject Reviews/Teasers/Shorts even if TMDb linked them
                val videoDetails = root.optJSONObject("videoDetails")
                val lengthSec = videoDetails?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0
                val ytTitle = (videoDetails?.optString("title", "") ?: "").lowercase(Locale.ROOT)
                if (lengthSec in 1..40 || lengthSec > 320) {
                    // Trailer must be between 40s and 5m20s
                    continue
                }
                if (ytTitle.contains("review") || ytTitle.contains("reaction") || ytTitle.contains("public talk")) {
                    continue
                }

                val streamingData = root.optJSONObject("streamingData") ?: continue
                val formats = streamingData.optJSONArray("formats") ?: JSONArray()
                val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()

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

                val byHeight = LinkedHashMap<Int, TrailerStreamOption>()
                val byHeightScore = HashMap<Int, Int>()

                if (bestAudioUrl.isNotEmpty()) {
                    for (i in 0 until adaptive.length()) {
                        val item = adaptive.optJSONObject(i) ?: continue
                        val mime = item.optString("mimeType", "")
                        val url = item.optString("url", "")
                        val height = item.optInt("height", 0)
                        if (url.isEmpty() || !mime.startsWith("video/") || height < 240) continue
                        // Avoid AV1 (av01) on devices without hardware AV1 decoder; prefer AVC1 (H.264) & VP9
                        if (mime.contains("av01")) continue
                        val codecScore = when {
                            mime.contains("avc1") -> 30000000
                            mime.contains("mp4") -> 20000000
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
                val hlsUrl = streamingData.optString("hlsManifestUrl", "")
                if (hlsUrl.isNotEmpty()) {
                    sorted.add(TrailerStreamOption("Auto (HLS)", 1, hlsUrl, "", true))
                }

                if (sorted.isNotEmpty()) {
                    return sorted
                }
            } catch (_: Throwable) {}
        }
        return emptyList()
    }

    // Pre-validates and caches playable direct streams BEFORE enabling the Trailer button in UI!
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
                if (!cached.isNullOrEmpty()) {
                    val res = Arguments.createMap()
                    res.putBoolean("available", true)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", cached.first().label)
                    promise.resolve(res)
                    return@submit
                }

                var verifiedStreams: List<TrailerStreamOption> = emptyList()

                // 1. Try TMDb Official Trailer YouTube Keys first (with strict India gl:IN + Direct Stream verification)
                try {
                    val arr = JSONArray(tmdbYtKeysJson)
                    for (i in 0 until arr.length()) {
                        val key = arr.optString(i, "").trim()
                        if (key.length == 11) {
                            val s = extractVerifiedYoutubeStreamsInIndia(key)
                            if (s.isNotEmpty()) {
                                verifiedStreams = s
                                break
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // 2. Try IMDb Official Trailer CDN (Direct Amazon CloudFront 1080p/720p/480p MP4 — Zero Country Blocks!)
                if (verifiedStreams.isEmpty()) {
                    val imdbStreams = fetchImdbOfficialTrailerStreams(imdbId, title, year)
                    if (imdbStreams.isNotEmpty()) {
                        verifiedStreams = imdbStreams
                    }
                }

                // 3. Try Strict YouTube Official Trailer Search (Blocks all Reviews, Teasers, Reactions, Clips)
                if (verifiedStreams.isEmpty()) {
                    val strictCandidates = findStrictYoutubeOfficialTrailerCandidates(title, year)
                    for (candId in strictCandidates) {
                        val s = extractVerifiedYoutubeStreamsInIndia(candId)
                        if (s.isNotEmpty()) {
                            verifiedStreams = s
                            break
                        }
                    }
                }

                val res = Arguments.createMap()
                if (verifiedStreams.isNotEmpty()) {
                    verifiedTrailerCache[cacheKey] = verifiedStreams
                    res.putBoolean("available", true)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", verifiedStreams.first().label)
                } else {
                    res.putBoolean("available", false)
                    res.putString("cacheKey", cacheKey)
                    res.putString("bestQuality", "")
                }
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

    private fun formatTimeMs(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    // Plays ONLY in our 100% Native In-App Video Player (Play in App engine) — NO YouTube WebView!
    @ReactMethod
    fun playYoutubeTrailer(cacheKey: String, title: String, promise: Promise) {
        val streams = verifiedTrailerCache[cacheKey]
        if (streams.isNullOrEmpty()) {
            promise.reject("E_UNAVAIL", "No verified trailer streams available")
            return
        }

        UiThreadUtil.runOnUiThread {
            val activity: Activity? = reactContext.currentActivity
            if (activity == null || activity.isFinishing) {
                promise.reject("E_ACT", "Activity unavailable")
                return@runOnUiThread
            }

            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.parseColor("#050508"))
            }

            val playerStage = FrameLayout(activity).apply {
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
            playerStage.addView(surfaceView)

            val spinner = ProgressBar(activity).apply {
                layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
            }
            playerStage.addView(spinner)
            root.addView(playerStage)

            // Top Overlay Bar (Title + Real Available Quality Chips + Close Button)
            val topOverlay = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#D90D0D14"))
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
                text = "🎬 $title • Official Trailer"
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
                    setColor(Color.parseColor("#E50914"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { dialog.dismiss() }
            }

            headerRow.addView(titleView)
            headerRow.addView(closeBtn)
            topOverlay.addView(headerRow)

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

            // Bottom Native Controls Bar
            val bottomOverlay = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#D90D0D14"))
                setPadding(dp(16), dp(10), dp(16), dp(18))
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

            // Toggle controls visibility when tapping video stage
            playerStage.setOnClickListener {
                val nextVis = if (topOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                topOverlay.visibility = nextVis
                bottomOverlay.visibility = nextVis
            }

            dialog.setContentView(root)

            var videoPlayer: MediaPlayer? = null
            var audioPlayer: MediaPlayer? = null
            var videoPrepared = false
            var audioPrepared = false
            var isSeeking = false
            var resumePosMs = 0
            val uiHandler = Handler(Looper.getMainLooper())

            var activeIndex = streams.indexOfFirst { it.height == 1080 }.takeIf { it >= 0 } ?: 0
            val qualityButtons = ArrayList<TextView>()

            fun updateQualityStyles() {
                for (i in qualityButtons.indices) {
                    val btn = qualityButtons[i]
                    val isSel = (i == activeIndex)
                    btn.background = GradientDrawable().apply {
                        setColor(if (isSel) Color.parseColor("#E50914") else Color.parseColor("#1F2029"))
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), if (isSel) Color.parseColor("#FF4D4D") else Color.parseColor("#374151"))
                    }
                    btn.setTextColor(if (isSel) Color.WHITE else Color.parseColor("#D1D5DB"))
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
                updateQualityStyles()
                spinner.visibility = View.VISIBLE
                releasePlayers()

                val opt = streams[index]
                val vp = MediaPlayer()
                videoPlayer = vp
                vp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                vp.setDisplay(surfaceView.holder)
                vp.setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        val rootW = playerStage.width.takeIf { it > 0 } ?: reactContext.resources.displayMetrics.widthPixels
                        val rootH = playerStage.height.takeIf { it > 0 } ?: reactContext.resources.displayMetrics.heightPixels
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
                vp.setOnErrorListener { _, _, _ ->
                    // Automatically fallback to next available stream quality if one fails
                    if (activeIndex + 1 < streams.size) {
                        loadStreamOption(activeIndex + 1, resumePosMs)
                    } else {
                        spinner.visibility = View.GONE
                    }
                    true
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

            // Show ONLY the actual available qualities for this verified trailer!
            for (i in streams.indices) {
                val opt = streams[i]
                val chip = TextView(activity).apply {
                    text = opt.label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, dp(8), 0) }
                    setOnClickListener {
                        if (activeIndex != i) {
                            val pos = try { if (videoPrepared) videoPlayer?.currentPosition ?: 0 else 0 } catch (_: Throwable) { 0 }
                            loadStreamOption(i, pos)
                        }
                    }
                }
                qualityButtons.add(chip)
                qualityRow.addView(chip)
            }
            updateQualityStyles()

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
                val t = (vp.currentPosition - 10000).coerceAtLeast(0)
                vp.seekTo(t)
                try { audioPlayer?.seekTo(t) } catch (_: Throwable) {}
            }

            ffBtn.setOnClickListener {
                val vp = videoPlayer ?: return@setOnClickListener
                if (!videoPrepared) return@setOnClickListener
                val t = (vp.currentPosition + 10000).coerceAtMost(vp.duration.coerceAtLeast(0))
                vp.seekTo(t)
                try { audioPlayer?.seekTo(t) } catch (_: Throwable) {}
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
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

            val syncTask = object : Runnable {
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
                            val ap = audioPlayer
                            if (ap != null && audioPrepared && vp.isPlaying) {
                                if (!ap.isPlaying) ap.start()
                                if (kotlin.math.abs(pos - ap.currentPosition) > 90) ap.seekTo(pos)
                            }
                        } catch (_: Throwable) {}
                    }
                    uiHandler.postDelayed(this, 400)
                }
            }

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    loadStreamOption(activeIndex, resumePosMs)
                    uiHandler.post(syncTask)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    try { if (videoPrepared) resumePosMs = videoPlayer?.currentPosition ?: 0 } catch (_: Throwable) {}
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
