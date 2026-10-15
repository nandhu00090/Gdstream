package com.gdstream

import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaInfoModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val inspectorPool = Executors.newCachedThreadPool()

    override fun getName(): String = "MediaInfoModule"

    private fun formatLanguageName(code: String?): String {
        if (code.isNullOrBlank() || code == "und" || code == "unk") return "Unknown"
        val clean = code.trim().lowercase(Locale.ROOT)
        val map = mapOf(
            "tam" to "Tamil", "ta" to "Tamil",
            "tel" to "Telugu", "te" to "Telugu",
            "hin" to "Hindi", "hi" to "Hindi",
            "mal" to "Malayalam", "ml" to "Malayalam",
            "kan" to "Kannada", "kn" to "Kannada",
            "eng" to "English", "en" to "English",
            "jpn" to "Japanese", "ja" to "Japanese",
            "kor" to "Korean", "ko" to "Korean",
            "zho" to "Chinese", "zh" to "Chinese", "chi" to "Chinese",
            "spa" to "Spanish", "es" to "Spanish",
            "fra" to "French", "fre" to "French", "fr" to "French",
            "deu" to "German", "ger" to "German", "de" to "German",
            "ita" to "Italian", "it" to "Italian",
            "rus" to "Russian", "ru" to "Russian",
            "por" to "Portuguese", "pt" to "Portuguese",
            "ara" to "Arabic", "ar" to "Arabic",
            "tha" to "Thai", "th" to "Thai",
            "ind" to "Indonesian", "id" to "Indonesian",
            "ben" to "Bengali", "bn" to "Bengali",
            "mar" to "Marathi", "mr" to "Marathi",
            "pan" to "Punjabi", "pa" to "Punjabi",
            "urd" to "Urdu", "ur" to "Urdu"
        )
        if (map.containsKey(clean)) return map[clean]!!
        return try {
            val loc = Locale(clean)
            val disp = loc.getDisplayLanguage(Locale.ENGLISH)
            if (disp.isNotBlank() && disp.lowercase(Locale.ROOT) != clean) disp
            else clean.uppercase(Locale.ROOT)
        } catch (_: Exception) {
            clean.uppercase(Locale.ROOT)
        }
    }

    private fun formatChannelLayout(channels: Int): String {
        return when (channels) {
            1 -> "1.0 Mono"
            2 -> "2.0 Stereo"
            6 -> "5.1 ch"
            8 -> "7.1 ch"
            else -> if (channels > 0) "$channels ch" else ""
        }
    }

    private fun formatAudioCodec(mime: String?, title: String?, headerHasAtmos: Boolean): String {
        val m = (mime ?: "").lowercase(Locale.ROOT)
        val t = (title ?: "").lowercase(Locale.ROOT)
        val isAtmos = m.contains("joc") || t.contains("atmos") || (headerHasAtmos && m.contains("eac3"))
        return when {
            isAtmos -> "Dolby Atmos (DD+)"
            m.contains("eac3") -> "Dolby Digital Plus (DD+)"
            m.contains("ac3") -> "Dolby Digital (AC3)"
            m.contains("true-hd") || m.contains("truehd") ->
                if (t.contains("atmos")) "Dolby TrueHD Atmos" else "Dolby TrueHD"
            m.contains("dts.hd") || m.contains("dtshd") -> "DTS-HD MA"
            m.contains("dts") -> "DTS"
            m.contains("mp4a") || m.contains("aac") -> "AAC"
            m.contains("flac") -> "FLAC"
            m.contains("opus") -> "Opus"
            m.contains("vorbis") -> "Vorbis"
            m.contains("mpeg") -> "MP3"
            else -> m.substringAfter("/").uppercase(Locale.ROOT).ifEmpty { "Audio" }
        }
    }

    private fun formatVideoCodec(mime: String?): String {
        val m = (mime ?: "").lowercase(Locale.ROOT)
        return when {
            m.contains("dolby-vision") -> "HEVC (Dolby Vision)"
            m.contains("hevc") || m.contains("h265") -> "HEVC (H.265)"
            m.contains("avc") || m.contains("h264") -> "AVC (H.264)"
            m.contains("av01") || m.contains("av1") -> "AV1"
            m.contains("vp9") -> "VP9"
            else -> m.substringAfter("/").uppercase(Locale.ROOT).ifEmpty { "Video" }
        }
    }

    private fun formatSubCodec(mime: String?): String {
        val m = (mime ?: "").lowercase(Locale.ROOT)
        return when {
            m.contains("subrip") || m.contains("srt") -> "SRT"
            m.contains("ssa") || m.contains("ass") -> "ASS"
            m.contains("vtt") -> "VTT"
            m.contains("pgs") -> "PGS"
            m.contains("dvd") || m.contains("vobsub") -> "VOBSUB"
            else -> "SUB"
        }
    }

    private data class HeaderProbeResult(
        val totalBytes: Long,
        val hasDoviRecord: Boolean,
        val dvProfile: Int,
        val dvHasHdr10Base: Boolean,
        val hasAtmosHint: Boolean
    )

    private fun probeHeaderBytes(urlStr: String): HeaderProbeResult {
        if (urlStr.startsWith("file://") || urlStr.startsWith("/")) {
            val f = java.io.File(urlStr.removePrefix("file://"))
            val len = if (f.exists()) f.length() else 0L
            val buf = ByteArray(minOf(len, 256 * 1024L).toInt())
            try {
                java.io.FileInputStream(f).use { it.read(buf) }
            } catch (_: Exception) {}
            return analyzeHeaderBuffer(buf, len)
        }

        var conn: HttpURLConnection? = null
        try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)")
                    setRequestProperty("Range", "bytes=0-262143")
                }
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty()) break
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirects++
                } else {
                    break
                }
            }

            if (conn == null) return HeaderProbeResult(0L, false, -1, false, false)

            var totalBytes = 0L
            val contentRange = conn.getHeaderField("Content-Range")
            if (!contentRange.isNullOrEmpty() && contentRange.contains("/")) {
                totalBytes = contentRange.substringAfter("/").trim().toLongOrNull() ?: 0L
            }
            if (totalBytes <= 0L) {
                totalBytes = conn.contentLengthLong.coerceAtLeast(0L)
            }

            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val tmp = ByteArray(16 * 1024)
                var read = 0
                while (out.size() < 256 * 1024 && input.read(tmp).also { read = it } != -1) {
                    out.write(tmp, 0, read)
                }
            }
            return analyzeHeaderBuffer(out.toByteArray(), totalBytes)
        } catch (_: Exception) {
            return HeaderProbeResult(0L, false, -1, false, false)
        } finally {
            conn?.disconnect()
        }
    }

    private fun analyzeHeaderBuffer(buf: ByteArray, totalBytes: Long): HeaderProbeResult {
        val ascii = String(buf, Charsets.ISO_8859_1)
        val hasAtmos = ascii.contains("Atmos", ignoreCase = true) || ascii.contains("E-AC-3 JOC", ignoreCase = true)

        var hasDovi = false
        var dvProfile = -1
        var dvHasHdr10Base = false

        // Search for DOVI configuration box ('dvcC', 'dvvC', or MKV 'DOVI' block addition)
        val markers = listOf("dvcC", "dvvC", "DOVI")
        for (marker in markers) {
            val idx = ascii.indexOf(marker)
            if (idx >= 0 && idx + 8 < buf.size) {
                hasDovi = true
                // Look for 0x01 0x00 (dv_version_major=1, dv_version_minor=0) right after marker
                for (offset in idx + 4..minOf(idx + 20, buf.size - 5)) {
                    val b0 = buf[offset].toInt() and 0xFF
                    val b1 = buf[offset + 1].toInt() and 0xFF
                    if (b0 == 1 && b1 == 0) {
                        val b2 = buf[offset + 2].toInt() and 0xFF
                        val b3 = buf[offset + 3].toInt() and 0xFF
                        val b4 = buf[offset + 4].toInt() and 0xFF
                        val profile = (b2 shr 1) and 0x7F
                        val blPresent = b3 and 0x01
                        val blCompatId = (b4 shr 4) and 0x0F
                        if (profile in 4..9) {
                            dvProfile = profile
                            // Profile 7 or Profile 8 with compatibility ID 1 or 4 has HDR10 base layer.
                            // Profile 5 has proprietary IPTPQc2 base layer (DV-only, NO HDR10 base layer).
                            dvHasHdr10Base = (profile == 7 || profile == 8) && blPresent == 1 && (blCompatId == 1 || blCompatId == 2 || blCompatId == 4)
                            break
                        }
                    }
                }
            }
        }

        return HeaderProbeResult(totalBytes, hasDovi, dvProfile, dvHasHdr10Base, hasAtmos)
    }

    @ReactMethod
    fun inspectMedia(url: String, fileName: String, promise: Promise) {
        inspectorPool.submit {
            val extractor = MediaExtractor()
            try {
                val future = inspectorPool.submit<HeaderProbeResult> {
                    probeHeaderBytes(url)
                }

                if (url.startsWith("file://") || url.startsWith("/")) {
                    extractor.setDataSource(url.removePrefix("file://"))
                } else {
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)"
                    )
                    extractor.setDataSource(url, headers)
                }

                val probe = try {
                    future.get(4, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    HeaderProbeResult(0L, false, -1, false, false)
                }

                var width = 0
                var height = 0
                var videoCodec = ""
                var durationSec = 0.0
                var frameRate = 0f
                var hasDV = probe.hasDoviRecord
                var hasHDR10 = probe.dvHasHdr10Base
                var dvProfile = probe.dvProfile

                val audioList = Arguments.createArray()
                val subtitleList = Arguments.createArray()
                val audioLanguages = mutableSetOf<String>()

                val trackCount = extractor.trackCount
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                    if (durationSec <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationSec = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                    }

                    if (mime.startsWith("video/")) {
                        if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = format.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        if (frameRate == 0f && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            try {
                                frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            } catch (_: Exception) {
                                try {
                                    frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                                } catch (_: Exception) {}
                            }
                        }

                        if (videoCodec.isEmpty()) {
                            videoCodec = formatVideoCodec(mime)
                        }

                        if (mime.equals("video/dolby-vision", ignoreCase = true)) {
                            hasDV = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                                format.containsKey(MediaFormat.KEY_PROFILE)
                            ) {
                                val prof = format.getInteger(MediaFormat.KEY_PROFILE)
                                if (prof == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn) {
                                    if (dvProfile == -1) dvProfile = 5
                                } else if (prof == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt ||
                                    prof == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb
                                ) {
                                    if (dvProfile == -1) dvProfile = 8
                                    hasHDR10 = true
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
                        ) {
                            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            // COLOR_TRANSFER_ST2084 (6 = PQ HDR10) or COLOR_TRANSFER_HLG (7)
                            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                                transfer == MediaFormat.COLOR_TRANSFER_HLG
                            ) {
                                // Only mark HDR10 if it's not a pure Profile 5 DV-only stream
                                if (dvProfile != 5) {
                                    hasHDR10 = true
                                }
                            }
                        }
                    } else if (mime.startsWith("audio/")) {
                        val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else null
                        val title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            format.containsKey("title")
                        ) {
                            format.getString("title")
                        } else null

                        val langName = formatLanguageName(rawLang)
                        if (langName != "Unknown") audioLanguages.add(langName)

                        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else 0
                        val channelStr = formatChannelLayout(channels)
                        val codecName = formatAudioCodec(mime, title, probe.hasAtmosHint)
                        val bitrateKbps = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                        } else 0

                        val trackMap = Arguments.createMap().apply {
                            putString("language", langName)
                            putString("codec", codecName)
                            putString("channels", channelStr)
                            putInt("bitrateKbps", bitrateKbps)
                            putString(
                                "summary",
                                listOfNotNull(
                                    langName,
                                    codecName,
                                    channelStr.ifEmpty { null }
                                ).joinToString(" • ")
                            )
                        }
                        audioList.pushMap(trackMap)
                    } else if (mime.startsWith("text/") ||
                        mime.contains("subrip") ||
                        mime.contains("ssa") ||
                        mime.contains("vtt") ||
                        mime.contains("pgs") ||
                        mime.contains("dvd")
                    ) {
                        val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else null
                        val langName = formatLanguageName(rawLang)
                        val subMap = Arguments.createMap().apply {
                            putString("language", langName)
                            putString("format", formatSubCodec(mime))
                        }
                        subtitleList.pushMap(subMap)
                    }
                }

                // Fallback cross-check with filename if container omitted DV/HDR box flags
                val lowerName = fileName.lowercase(Locale.ROOT)
                val nameHasDv = Regex("\\b(dv|dovi|dolby[ ._-]*vision)\\b").containsMatchIn(lowerName)
                val nameHasHdr = Regex("\\b(hdr|hdr10|hdr10\\+)\\b").containsMatchIn(lowerName)

                if (dvProfile == 5) {
                    hasDV = true
                    hasHDR10 = false
                } else if (dvProfile == 7 || dvProfile == 8) {
                    hasDV = true
                    if (nameHasHdr || probe.dvHasHdr10Base) {
                        hasHDR10 = true
                    }
                } else {
                    if (nameHasDv) hasDV = true
                    // Only mark hasHDR10 alongside DV if filename or container explicitly has HDR10
                    if (hasDV && !nameHasHdr && !probe.dvHasHdr10Base) {
                        hasHDR10 = false
                    } else if (nameHasHdr) {
                        hasHDR10 = true
                    }
                }

                val resolutionLabel = when {
                    width >= 3200 || height >= 1800 -> "4K"
                    width >= 1800 || height >= 950 -> "1080p"
                    width >= 1200 || height >= 650 -> "720p"
                    width > 0 -> "SD"
                    else -> ""
                }

                val langArray = Arguments.createArray()
                for (l in audioLanguages) {
                    langArray.pushString(l)
                }

                val result = Arguments.createMap().apply {
                    putInt("width", width)
                    putInt("height", height)
                    putString("resolution", resolutionLabel)
                    putString("videoCodec", videoCodec)
                    putDouble("frameRate", frameRate.toDouble())
                    putDouble("durationSec", durationSec)
                    putDouble("totalBytes", probe.totalBytes.toDouble())
                    putBoolean("hasDV", hasDV)
                    putBoolean("hasHDR", hasHDR10)
                    putInt("dvProfile", dvProfile)
                    putBoolean("hasAtmos", probe.hasAtmosHint)
                    putArray("audioTracks", audioList)
                    putArray("subtitleTracks", subtitleList)
                    putArray("languages", langArray)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("MEDIAINFO_ERROR", e.message)
            } finally {
                try {
                    extractor.release()
                } catch (_: Exception) {}
            }
        }
    }
}
