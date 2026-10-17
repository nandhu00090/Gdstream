package com.gdstream

import android.media.MediaExtractor
import android.media.MediaFormat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MediaInfoModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val executor = Executors.newFixedThreadPool(3)

    override fun getName(): String = "MediaInfoModule"

    private fun normalizeLanguage(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val clean = code.trim().lowercase(Locale.US).split("-", "_")[0]
        if (clean == "und" || clean == "unk" || clean == "mul" || clean == "zxx") return ""
        val map = mapOf(
            "tam" to "Tamil", "ta" to "Tamil",
            "tel" to "Telugu", "te" to "Telugu",
            "hin" to "Hindi", "hi" to "Hindi",
            "mal" to "Malayalam", "ml" to "Malayalam",
            "kan" to "Kannada", "kn" to "Kannada",
            "eng" to "English", "en" to "English",
            "kor" to "Korean", "ko" to "Korean",
            "jpn" to "Japanese", "ja" to "Japanese",
            "fre" to "French", "fra" to "French", "fr" to "French",
            "spa" to "Spanish", "es" to "Spanish",
            "ger" to "German", "deu" to "German", "de" to "German",
            "ita" to "Italian", "it" to "Italian",
            "chi" to "Chinese", "zho" to "Chinese", "zh" to "Chinese",
            "rus" to "Russian", "ru" to "Russian",
            "por" to "Portuguese", "pt" to "Portuguese",
            "ara" to "Arabic", "ar" to "Arabic",
            "tha" to "Thai", "th" to "Thai",
            "ind" to "Indonesian", "id" to "Indonesian"
        )
        map[clean]?.let { return it }
        return try {
            val loc = Locale.forLanguageTag(clean)
            val disp = loc.getDisplayLanguage(Locale.ENGLISH)
            if (disp.isNotBlank() && !disp.equals(clean, ignoreCase = true)) disp else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun inferLanguageFromFileName(fileName: String): String {
        val u = fileName.uppercase(Locale.US)
        return when {
            Regex("\\b(TAMIL|TAM)\\b").containsMatchIn(u) -> "Tamil"
            Regex("\\b(MALAYALAM|MAL)\\b").containsMatchIn(u) -> "Malayalam"
            Regex("\\b(TELUGU|TEL)\\b").containsMatchIn(u) -> "Telugu"
            Regex("\\b(HINDI|HIN)\\b").containsMatchIn(u) -> "Hindi"
            Regex("\\b(KANNADA|KAN)\\b").containsMatchIn(u) -> "Kannada"
            Regex("\\b(KOREAN|KOR)\\b").containsMatchIn(u) -> "Korean"
            Regex("\\b(JAPANESE|JPN|JAP)\\b").containsMatchIn(u) -> "Japanese"
            Regex("\\b(ENGLISH|ENG)\\b").containsMatchIn(u) -> "English"
            else -> ""
        }
    }

    private fun formatBitrateMbps(kbps: Int): String {
        if (kbps <= 0) return ""
        return if (kbps >= 1000) {
            String.format(Locale.US, "%.1f Mb/s", kbps / 1000.0)
        } else {
            "$kbps kb/s"
        }
    }

    private fun openRangeConnection(urlStr: String, rangeHeader: String?): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeHeader != null) {
                    setRequestProperty("Range", rangeHeader)
                }
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) break
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw Exception("Redirect limit exceeded")
    }

    private fun readHeaderBytesAndSize(urlOrPath: String, maxBytes: Int = 262144): Pair<ByteArray, Long> {
        if (urlOrPath.startsWith("/") || urlOrPath.startsWith("file://")) {
            val f = File(urlOrPath.removePrefix("file://"))
            if (!f.exists()) return Pair(ByteArray(0), 0L)
            val total = f.length()
            val toRead = minOf(total, maxBytes.toLong()).toInt()
            val buf = ByteArray(toRead)
            f.inputStream().use { inp ->
                var off = 0
                while (off < toRead) {
                    val r = inp.read(buf, off, toRead - off)
                    if (r <= 0) break
                    off += r
                }
                return Pair(if (off == toRead) buf else buf.copyOf(off), total)
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = openRangeConnection(urlOrPath, "bytes=0-${maxBytes - 1}")
            var totalSize = 0L
            val contentRange = conn.getHeaderField("Content-Range")
            if (!contentRange.isNullOrEmpty() && contentRange.contains("/")) {
                totalSize = contentRange.substringAfter("/").trim().toLongOrNull() ?: 0L
            }
            if (totalSize <= 0L) {
                val cl = conn.contentLengthLong
                if (cl > maxBytes) totalSize = cl
            }
            val buf = ByteArray(maxBytes)
            var off = 0
            conn.inputStream.use { inp: InputStream ->
                while (off < maxBytes) {
                    val r = inp.read(buf, off, maxBytes - off)
                    if (r <= 0) break
                    off += r
                }
            }
            return Pair(if (off == maxBytes) buf else buf.copyOf(off), totalSize)
        } catch (_: Exception) {
            return Pair(ByteArray(0), 0L)
        } finally {
            conn?.disconnect()
        }
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val nb = needle.toByteArray(Charsets.US_ASCII)
        if (nb.isEmpty() || haystack.size < nb.size) return false
        outer@ for (i in 0..(haystack.size - nb.size)) {
            for (j in nb.indices) {
                if (haystack[i + j] != nb[j]) continue@outer
            }
            return true
        }
        return false
    }

    @ReactMethod
    fun inspectMedia(urlOrPath: String, fileName: String, promise: Promise) {
        executor.submit {
            val extractor = MediaExtractor()
            try {
                val (headerBytes, probedTotalBytes) = readHeaderBytesAndSize(urlOrPath)
                val totalBytes = probedTotalBytes

                if (urlOrPath.startsWith("/") || urlOrPath.startsWith("file://")) {
                    val cleanPath = urlOrPath.removePrefix("file://")
                    extractor.setDataSource(cleanPath)
                } else {
                    val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)")
                    extractor.setDataSource(urlOrPath, headers)
                }

                var width = 0
                var height = 0
                var videoCodec = ""
                var videoBitrateKbps = 0
                var frameRate = 0.0
                var durationUs = 0L
                var hasDV = false
                var hasHDR = false
                var dvProfile = -1
                var hasAtmos = false
                var colorTransfer = 0

                val fileUpper = fileName.uppercase(Locale.US)
                val fileHasAtmos = Regex("\\bATMOS\\b").containsMatchIn(fileUpper)
                val fileHasDV = Regex("\\b(DV|DOVI|DOLBY\\s*VISION)\\b").containsMatchIn(fileUpper)
                val fileHasHDR = Regex("\\b(HDR10[+]?|HDR)\\b").containsMatchIn(fileUpper)

                val headerHasDolbyVision = containsAscii(headerBytes, "dvhe") ||
                    containsAscii(headerBytes, "dvh1") ||
                    containsAscii(headerBytes, "DOVI") ||
                    containsAscii(headerBytes, "Dolby Vision")
                val headerHasAtmos = containsAscii(headerBytes, "Atmos") ||
                    containsAscii(headerBytes, "JOC") ||
                    fileHasAtmos

                data class RawAudioTrack(
                    val language: String,
                    val commercialCodec: String,
                    val channelsCount: Int,
                    val channelLabel: String,
                    val bitrateKbps: Int,
                    val sampleRateKhz: String,
                    val isAtmos: Boolean
                )

                val rawAudioList = mutableListOf<RawAudioTrack>()
                val subtitleList = Arguments.createArray()
                val languagesSet = linkedSetOf<String>()

                val trackCount = extractor.trackCount
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        val d = format.getLong(MediaFormat.KEY_DURATION)
                        if (d > durationUs) durationUs = d
                    }

                    if (mime.startsWith("video/")) {
                        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                            val w = format.getInteger(MediaFormat.KEY_WIDTH)
                            if (w > width) width = w
                        }
                        if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                            if (h > height) height = h
                        }
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = try {
                                format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                            } catch (_: Exception) {
                                format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                            }
                        }
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            videoBitrateKbps = (format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000.0).roundToInt()
                        }
                        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                            colorTransfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            if (colorTransfer == 6 || colorTransfer == 7) {
                                hasHDR = true
                            }
                        }

                        if (mime.equals("video/dolby-vision", ignoreCase = true)) {
                            hasDV = true
                            if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                                val p = format.getInteger(MediaFormat.KEY_PROFILE)
                                dvProfile = when (p) {
                                    0x0100 -> 8
                                    0x0080 -> 7
                                    0x0020 -> 5
                                    else -> 8
                                }
                                if (dvProfile == 7 || dvProfile == 8) hasHDR = true
                            }
                        }

                        if (videoCodec.isEmpty() || mime.equals("video/dolby-vision", ignoreCase = true)) {
                            videoCodec = when {
                                mime.contains("hevc", true) || mime.contains("dolby-vision", true) -> "HEVC (H.265)"
                                mime.contains("avc", true) -> "AVC (H.264)"
                                mime.contains("av01", true) || mime.contains("av1", true) -> "AV1"
                                mime.contains("vp9", true) -> "VP9"
                                else -> mime.substringAfter("/").uppercase(Locale.US)
                            }
                        }
                    } else if (mime.startsWith("audio/")) {
                        val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else null
                        var lang = normalizeLanguage(rawLang)
                        if (lang.isEmpty()) {
                            lang = inferLanguageFromFileName(fileName).ifEmpty { "Unknown" }
                        }
                        if (lang != "Unknown") languagesSet.add(lang)

                        val chCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else 6

                        val chLabel = when (chCount) {
                            8 -> "7.1 ch"
                            6 -> "5.1 ch"
                            2 -> "2.0 ch"
                            1 -> "1.0 ch"
                            else -> "$chCount ch"
                        }

                        val srKhz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            String.format(Locale.US, "%.1f kHz", format.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000.0)
                        } else "48.0 kHz"

                        var brKbps = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            (format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000.0).roundToInt()
                        } else 0

                        val isEac3 = mime.contains("eac3", true) || mime.contains("ec-3", true)
                        val isTrueHd = mime.contains("true-hd", true) || mime.contains("truehd", true) || mime.contains("mlp", true)
                        val isAc3 = mime.equals("audio/ac3", true)
                        val isDtsHd = mime.contains("vnd.dts.hd", true) || mime.contains("dtshd", true)
                        val isDts = mime.contains("dts", true)

                        val trackIsAtmos = mime.contains("joc", true) ||
                            (isEac3 && headerHasAtmos && rawAudioList.isEmpty()) ||
                            (isTrueHd && headerHasAtmos && rawAudioList.isEmpty())

                        if (trackIsAtmos) hasAtmos = true

                        if (brKbps <= 0) {
                            brKbps = when {
                                isEac3 && trackIsAtmos -> if (Regex("640\\s*KBPS", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) 640 else 768
                                isEac3 && chCount >= 6 -> 640
                                isAc3 && chCount >= 6 -> 448
                                else -> 0
                            }
                        }

                        val commercialName = when {
                            isEac3 && trackIsAtmos -> "Dolby Digital Plus with Dolby Atmos"
                            isEac3 -> "Dolby Digital Plus (DD+)"
                            isTrueHd && trackIsAtmos -> "Dolby TrueHD with Dolby Atmos"
                            isTrueHd -> "Dolby TrueHD"
                            isAc3 -> "Dolby Digital (AC3)"
                            isDtsHd -> "DTS-HD Master Audio"
                            isDts -> "DTS"
                            mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
                            mime.contains("opus", true) -> "Opus"
                            mime.contains("flac", true) -> "FLAC"
                            else -> mime.substringAfter("/").uppercase(Locale.US)
                        }

                        rawAudioList.add(
                            RawAudioTrack(
                                language = lang,
                                commercialCodec = commercialName,
                                channelsCount = chCount,
                                channelLabel = chLabel,
                                bitrateKbps = brKbps,
                                sampleRateKhz = srKhz,
                                isAtmos = trackIsAtmos
                            )
                        )
                    } else if (mime.startsWith("text/") || mime.startsWith("application/")) {
                        val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else null
                        val lang = normalizeLanguage(rawLang).ifEmpty { "Subtitle" }
                        val subFmt = when {
                            mime.contains("subrip", true) || mime.contains("utf8", true) || mime.contains("x-subrip", true) -> "UTF-8 (SRT)"
                            mime.contains("ssa", true) || mime.contains("ass", true) -> "ASS"
                            mime.contains("pgs", true) -> "PGS"
                            mime.contains("vtt", true) -> "VTT"
                            else -> "SRT"
                        }
                        val subMap = Arguments.createMap().apply {
                            putString("language", lang)
                            putString("format", subFmt)
                        }
                        subtitleList.pushMap(subMap)
                    }
                }

                if (headerHasDolbyVision || fileHasDV) hasDV = true
                if (fileHasHDR) hasHDR = true
                if (headerHasAtmos) hasAtmos = true

                if (rawAudioList.isEmpty()) {
                    val fallbackLang = inferLanguageFromFileName(fileName).ifEmpty { "Unknown" }
                    if (fallbackLang != "Unknown") languagesSet.add(fallbackLang)
                    val isEac3 = Regex("\\b(DD[+]?|DDP|EAC3|E-AC-3)\\b").containsMatchIn(fileUpper)
                    val isTrueHd = Regex("\\bTRUEHD\\b").containsMatchIn(fileUpper)
                    val isDtsHd = Regex("\\b(DTS-HD|DTS\\s*HD|DTS\\s*HDMA)\\b").containsMatchIn(fileUpper)
                    val isAc3 = Regex("\\b(DD5|AC3)\\b").containsMatchIn(fileUpper)
                    val chLbl = if (Regex("7\\.1").containsMatchIn(fileUpper)) "7.1 ch" else if (Regex("2\\.0").containsMatchIn(fileUpper)) "2.0 ch" else "5.1 ch"
                    val cName = when {
                        isEac3 && hasAtmos -> "Dolby Digital Plus with Dolby Atmos"
                        isEac3 -> "Dolby Digital Plus (DD+)"
                        isTrueHd && hasAtmos -> "Dolby TrueHD with Dolby Atmos"
                        isTrueHd -> "Dolby TrueHD"
                        isDtsHd -> "DTS-HD Master Audio"
                        isAc3 -> "Dolby Digital (AC3)"
                        else -> "Dolby Audio"
                    }
                    val br = if (isEac3 && hasAtmos) 768 else if (isEac3) 640 else 0
                    rawAudioList.add(RawAudioTrack(fallbackLang, cName, 6, chLbl, br, "48.0 kHz", hasAtmos))
                }

                val durationSec = if (durationUs > 0) (durationUs / 1_000_000L).toInt() else 0
                val overallBitrateKbps = if (totalBytes > 0 && durationSec > 0) {
                    ((totalBytes * 8.0) / durationSec / 1000.0).roundToInt()
                } else 0

                val totalAudioBitrateKbps = rawAudioList.sumOf { if (it.bitrateKbps > 0) it.bitrateKbps else 640 }
                if (videoBitrateKbps <= 0 && overallBitrateKbps > totalAudioBitrateKbps + 200) {
                    videoBitrateKbps = overallBitrateKbps - totalAudioBitrateKbps
                }

                val resolution = when {
                    width >= 3800 || height >= 2000 || Regex("\\b(2160P|4K|UHD)\\b").containsMatchIn(fileUpper) -> "4K"
                    width >= 1900 || height >= 1000 || Regex("\\b1080P\\b").containsMatchIn(fileUpper) -> "1080p"
                    width >= 1200 || height >= 700 || Regex("\\b720P\\b").containsMatchIn(fileUpper) -> "720p"
                    width > 0 -> "${height}p"
                    else -> ""
                }

                if (videoCodec.isEmpty()) {
                    videoCodec = if (Regex("\\b(H\\.?265|X265|HEVC)\\b").containsMatchIn(fileUpper)) "HEVC (H.265)" else "AVC (H.264)"
                }

                val videoBitrateFormatted = formatBitrateMbps(videoBitrateKbps)
                val overallBitrateFormatted = formatBitrateMbps(overallBitrateKbps)

                val audioTracksArray = Arguments.createArray()
                for (at in rawAudioList) {
                    val summaryParts = mutableListOf<String>()
                    if (at.language.isNotEmpty() && at.language != "Unknown") summaryParts.add(at.language)
                    summaryParts.add(at.commercialCodec)
                    summaryParts.add(at.channelLabel)
                    if (at.bitrateKbps > 0) summaryParts.add("${at.bitrateKbps} kb/s")

                    val aMap = Arguments.createMap().apply {
                        putString("language", at.language)
                        putString("codec", at.commercialCodec)
                        putString("channels", at.channelLabel)
                        putInt("bitrateKbps", at.bitrateKbps)
                        putString("summary", summaryParts.joinToString(" • "))
                    }
                    audioTracksArray.pushMap(aMap)
                }

                val langsArray = Arguments.createArray()
                for (l in languagesSet) langsArray.pushString(l)

                val result = Arguments.createMap().apply {
                    putInt("width", width)
                    putInt("height", height)
                    putString("resolution", resolution)
                    putString("videoCodec", videoCodec)
                    putString("videoBitrate", videoBitrateFormatted)
                    putString("overallBitrate", overallBitrateFormatted)
                    putDouble("frameRate", frameRate)
                    putInt("durationSec", durationSec)
                    putDouble("totalBytes", totalBytes.toDouble())
                    putBoolean("hasDV", hasDV)
                    putBoolean("hasHDR", hasHDR)
                    putInt("dvProfile", dvProfile)
                    putBoolean("hasAtmos", hasAtmos)
                    putArray("audioTracks", audioTracksArray)
                    putArray("subtitleTracks", subtitleList)
                    putArray("languages", langsArray)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("MEDIAINFO_ERR", e.message)
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
        }
    }
}
