package com.gdstream

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

class MediaInfoModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val inspectorPool = Executors.newCachedThreadPool()

    override fun getName(): String = "MediaInfoModule"

    private data class MkvTrack(
        var trackType: Int = 0, // 1 = Video, 2 = Audio, 17 = Subtitle
        var codecId: String = "",
        var name: String = "",
        var language: String = "",
        var languageIetf: String = "",
        var pixelWidth: Int = 0,
        var pixelHeight: Int = 0,
        var defaultDurationNs: Long = 0L,
        var channels: Int = 0,
        var bitDepth: Int = 0,
        var colorTransfer: Int = 0,
        var colorPrimaries: Int = 0,
        var hasDoviConfig: Boolean = false,
        var dvProfile: Int = -1,
        var dvHasHdr10Base: Boolean = false
    )

    private data class HeaderParseResult(
        val totalBytes: Long,
        val durationSec: Double,
        val tracks: List<MkvTrack>,
        val hasDoviRecord: Boolean,
        val dvProfile: Int,
        val dvHasHdr10Base: Boolean,
        val hasAtmosHint: Boolean
    )

    private fun formatLanguageName(rawCode: String?, trackTitle: String? = null): String {
        val code = (rawCode ?: "")
            .trim()
            .lowercase(Locale.ROOT)
            .substringBefore("-")
            .substringBefore("_")

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

        if (code.isNotEmpty() && code != "und" && code != "unk") {
            if (map.containsKey(code)) return map[code]!!
            try {
                val loc = Locale(code)
                val disp = loc.getDisplayLanguage(Locale.ENGLISH)
                if (disp.isNotBlank() && disp.lowercase(Locale.ROOT) != code) {
                    return disp
                }
            } catch (_: Exception) {}
        }

        // Check track title if language tag was 'und' or missing
        if (!trackTitle.isNullOrBlank()) {
            val lowerTitle = trackTitle.lowercase(Locale.ROOT)
            for ((_, langName) in map) {
                if (lowerTitle.contains(langName.lowercase(Locale.ROOT))) {
                    return langName
                }
            }
        }

        return if (code.isNotEmpty() && code != "und" && code != "unk") {
            code.uppercase(Locale.ROOT)
        } else {
            "Unknown"
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

    private fun formatMkvVideoCodec(codecId: String): String {
        val c = codecId.uppercase(Locale.ROOT)
        return when {
            c.contains("HEVC") || c.contains("H265") || c.contains("MPEGH") -> "HEVC (H.265)"
            c.contains("AVC") || c.contains("H264") || c.contains("MPEG4/ISO") -> "AVC (H.264)"
            c.contains("AV1") -> "AV1"
            c.contains("VP9") -> "VP9"
            c.contains("MPEG2") -> "MPEG-2"
            else -> c.removePrefix("V_").ifEmpty { "Video" }
        }
    }

    private fun formatMkvAudioCodec(codecId: String, title: String, hasAtmosHint: Boolean): String {
        val c = codecId.uppercase(Locale.ROOT)
        val t = title.lowercase(Locale.ROOT)
        val isAtmos = t.contains("atmos") || (hasAtmosHint && (c.contains("EAC3") || c.contains("TRUEHD")))
        return when {
            c.contains("TRUEHD") -> if (isAtmos) "Dolby TrueHD Atmos" else "Dolby TrueHD"
            c.contains("EAC3") -> if (isAtmos) "Dolby Atmos (DD+)" else "Dolby Digital Plus (DD+)"
            c.contains("AC3") -> "Dolby Digital (AC3)"
            c.contains("DTS") -> when {
                t.contains("hd") || t.contains("ma") || c.contains("HD") -> "DTS-HD MA"
                t.contains("x") -> "DTS:X"
                else -> "DTS"
            }
            c.contains("OPUS") -> "Opus"
            c.contains("AAC") -> "AAC"
            c.contains("FLAC") -> "FLAC"
            c.contains("VORBIS") -> "Vorbis"
            c.contains("MPEG/L3") || c.contains("MP3") -> "MP3"
            c.contains("PCM") -> "PCM"
            else -> c.removePrefix("A_").ifEmpty { "Audio" }
        }
    }

    private fun formatMkvSubtitleCodec(codecId: String): String {
        val c = codecId.uppercase(Locale.ROOT)
        return when {
            c.contains("UTF8") || c.contains("SUBRIP") || c.contains("ASCII") -> "SRT"
            c.contains("ASS") || c.contains("SSA") -> "ASS"
            c.contains("PGS") -> "PGS"
            c.contains("VOBSUB") -> "VOBSUB"
            c.contains("WEBVTT") || c.contains("VTT") -> "VTT"
            c.contains("DVBSUB") -> "DVB"
            else -> "SUB"
        }
    }

    private fun extractBitrateHintFromTitle(title: String): String? {
        val m = Regex("(\\d{2,4}\\s*k(?:bps)?)", RegexOption.IGNORE_CASE).find(title)
        return m?.value
    }

    // EBML VINT Readers
    private fun readEbmlId(buf: ByteArray, pos: Int, limit: Int): Pair<Long, Int>? {
        if (pos >= limit) return null
        val b0 = buf[pos].toInt() and 0xFF
        if (b0 == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 4 && (b0 and mask) == 0) {
            mask = mask shr 1
            len++
        }
        if (len > 4 || pos + len > limit) return null
        var id = b0.toLong()
        for (i in 1 until len) {
            id = (id shl 8) or ((buf[pos + i].toInt() and 0xFF).toLong())
        }
        return Pair(id, len)
    }

    private fun readEbmlSize(buf: ByteArray, pos: Int, limit: Int): Pair<Long, Int>? {
        if (pos >= limit) return null
        val b0 = buf[pos].toInt() and 0xFF
        if (b0 == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 8 && (b0 and mask) == 0) {
            mask = mask shr 1
            len++
        }
        if (len > 8 || pos + len > limit) return null
        var size = (b0 and (mask - 1)).toLong()
        var allOnes = (b0 and (mask - 1)) == (mask - 1)
        for (i in 1 until len) {
            val bi = buf[pos + i].toInt() and 0xFF
            if (bi != 0xFF) allOnes = false
            size = (size shl 8) or bi.toLong()
        }
        if (allOnes) {
            return Pair(-1L, len) // Unknown / streaming size
        }
        return Pair(size, len)
    }

    private fun readEbmlUint(buf: ByteArray, pos: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) {
            v = (v shl 8) or ((buf[pos + i].toInt() and 0xFF).toLong())
        }
        return v
    }

    private fun readEbmlFloat(buf: ByteArray, pos: Int, len: Int): Double {
        return try {
            if (len == 4) {
                ByteBuffer.wrap(buf, pos, 4).float.toDouble()
            } else if (len == 8) {
                ByteBuffer.wrap(buf, pos, 8).double
            } else {
                0.0
            }
        } catch (_: Exception) {
            0.0
        }
    }

    private fun readEbmlString(buf: ByteArray, pos: Int, len: Int): String {
        if (len <= 0) return ""
        var actualLen = len
        while (actualLen > 0 && buf[pos + actualLen - 1].toInt() == 0) {
            actualLen--
        }
        return String(buf, pos, actualLen, Charsets.UTF_8).trim()
    }

    private fun inspectDoviRecordBytes(
        buf: ByteArray,
        start: Int,
        len: Int
    ): Triple<Boolean, Int, Boolean> {
        val end = minOf(buf.size, start + len)
        for (offset in start..(end - 5)) {
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
                    val hasHdr10Base =
                        (profile == 7 || profile == 8) &&
                            blPresent == 1 &&
                            (blCompatId == 1 || blCompatId == 2 || blCompatId == 4)
                    return Triple(true, profile, hasHdr10Base)
                }
            }
        }
        return Triple(false, -1, false)
    }

    private fun parseMkvTracksAndInfo(buf: ByteArray, totalBytes: Long): HeaderParseResult {
        val tracks = mutableListOf<MkvTrack>()
        var timestampScale = 1_000_000L
        var rawDuration = 0.0

        val ascii = String(buf, Charsets.ISO_8859_1)
        val hasAtmosHint =
            ascii.contains("Atmos", ignoreCase = true) ||
                ascii.contains("E-AC-3 JOC", ignoreCase = true)

        var globalHasDovi = false
        var globalDvProfile = -1
        var globalDvHasHdr10Base = false

        val markers = listOf("dvcC", "dvvC", "DOVI")
        for (marker in markers) {
            val idx = ascii.indexOf(marker)
            if (idx >= 0 && idx + 8 < buf.size) {
                globalHasDovi = true
                val (ok, prof, hdrBase) = inspectDoviRecordBytes(
                    buf,
                    idx + 4,
                    minOf(24, buf.size - (idx + 4))
                )
                if (ok) {
                    globalDvProfile = prof
                    globalDvHasHdr10Base = hdrBase
                    break
                }
            }
        }

        fun parseColourBox(start: Int, limit: Int, track: MkvTrack) {
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                when (idPair.first) {
                    0x55BAL -> track.colorTransfer = readEbmlUint(buf, p, sz).toInt()
                    0x55BBL -> track.colorPrimaries = readEbmlUint(buf, p, sz).toInt()
                }
                p += sz
            }
        }

        fun parseVideoBox(start: Int, limit: Int, track: MkvTrack) {
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                when (idPair.first) {
                    0xB0L -> track.pixelWidth = readEbmlUint(buf, p, sz).toInt()
                    0xBAL -> track.pixelHeight = readEbmlUint(buf, p, sz).toInt()
                    0x55B0L -> parseColourBox(p, p + sz, track)
                }
                p += sz
            }
        }

        fun parseAudioBox(start: Int, limit: Int, track: MkvTrack) {
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                when (idPair.first) {
                    0x9FL -> track.channels = readEbmlUint(buf, p, sz).toInt()
                    0x6264L -> track.bitDepth = readEbmlUint(buf, p, sz).toInt()
                }
                p += sz
            }
        }

        fun parseBlockAdditionMapping(start: Int, limit: Int, track: MkvTrack) {
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                if (idPair.first == 0x41EDL && sz >= 5) {
                    val (ok, prof, hdrBase) = inspectDoviRecordBytes(buf, p, sz)
                    if (ok) {
                        track.hasDoviConfig = true
                        track.dvProfile = prof
                        track.dvHasHdr10Base = hdrBase
                    }
                }
                p += sz
            }
        }

        fun parseTrackEntry(start: Int, limit: Int): MkvTrack {
            val track = MkvTrack()
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                when (idPair.first) {
                    0x83L -> track.trackType = readEbmlUint(buf, p, sz).toInt()
                    0x86L -> track.codecId = readEbmlString(buf, p, sz)
                    0x536EL -> track.name = readEbmlString(buf, p, sz)
                    0x22B59CL -> track.language = readEbmlString(buf, p, sz)
                    0x22B59DL -> track.languageIetf = readEbmlString(buf, p, sz)
                    0x23E383L -> track.defaultDurationNs = readEbmlUint(buf, p, sz)
                    0xE0L -> parseVideoBox(p, p + sz, track)
                    0xE1L -> parseAudioBox(p, p + sz, track)
                    0x41E4L -> parseBlockAdditionMapping(p, p + sz, track)
                }
                p += sz
            }
            return track
        }

        fun parseTracksMaster(start: Int, limit: Int) {
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                if (idPair.first == 0xAEL) {
                    tracks.add(parseTrackEntry(p, p + sz))
                }
                p += sz
            }
        }

        fun parseInfoMaster(start: Int, limit: Int) {
            var p = start
            while (p < limit) {
                val idPair = readEbmlId(buf, p, limit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, limit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > limit) break
                when (idPair.first) {
                    0x2AD7B1L -> timestampScale = readEbmlUint(buf, p, sz)
                    0x4489L -> rawDuration = readEbmlFloat(buf, p, sz)
                }
                p += sz
            }
        }

        // Top-level EBML walk
        var pos = 0
        val limit = buf.size
        while (pos < limit) {
            val idPair = readEbmlId(buf, pos, limit) ?: break
            pos += idPair.second
            val sizePair = readEbmlSize(buf, pos, limit) ?: break
            pos += sizePair.second
            val sz = sizePair.first

            if (idPair.first == 0x18538067L) {
                // Step inside Segment
                continue
            }
            if (idPair.first == 0x1F43B675L) {
                // Reached media Cluster; header metadata is complete
                break
            }
            if (sz < 0) break
            val elemEnd = minOf(limit.toLong(), pos.toLong() + sz).toInt()
            when (idPair.first) {
                0x1549A966L -> parseInfoMaster(pos, elemEnd)
                0x1654AE6BL -> parseTracksMaster(pos, elemEnd)
            }
            if (pos.toLong() + sz > limit) break
            pos += sz.toInt()
        }

        val durationSec = if (rawDuration > 0.0) {
            (rawDuration * timestampScale.toDouble()) / 1_000_000_000.0
        } else {
            0.0
        }

        for (t in tracks) {
            if (t.hasDoviConfig) {
                globalHasDovi = true
                globalDvProfile = t.dvProfile
                globalDvHasHdr10Base = t.dvHasHdr10Base
            }
        }

        return HeaderParseResult(
            totalBytes = totalBytes,
            durationSec = durationSec,
            tracks = tracks,
            hasDoviRecord = globalHasDovi,
            dvProfile = globalDvProfile,
            dvHasHdr10Base = globalDvHasHdr10Base,
            hasAtmosHint = hasAtmosHint
        )
    }

    private fun fetchAndParseHeader(urlStr: String): HeaderParseResult {
        val maxHeaderBytes = 640 * 1024 // 640 KB covers MKV Info + Tracks + Fonts/Cues

        if (urlStr.startsWith("file://") || urlStr.startsWith("/")) {
            val f = File(urlStr.removePrefix("file://"))
            val len = if (f.exists()) f.length() else 0L
            val buf = ByteArray(minOf(len, maxHeaderBytes.toLong()).toInt())
            try {
                FileInputStream(f).use { it.read(buf) }
            } catch (_: Exception) {}
            return parseMkvTracksAndInfo(buf, len)
        }

        var conn: HttpURLConnection? = null
        try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)")
                    setRequestProperty("Range", "bytes=0-${maxHeaderBytes - 1}")
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

            if (conn == null) {
                return HeaderParseResult(0L, 0.0, emptyList(), false, -1, false, false)
            }

            var totalBytes = 0L
            val contentRange = conn.getHeaderField("Content-Range")
            if (!contentRange.isNullOrEmpty() && contentRange.contains("/")) {
                totalBytes = contentRange.substringAfter("/").trim().toLongOrNull() ?: 0L
            }
            if (totalBytes <= 0L) {
                totalBytes = conn.contentLengthLong.coerceAtLeast(0L)
            }

            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val tmp = ByteArray(16 * 1024)
                var read = 0
                while (out.size() < maxHeaderBytes && input.read(tmp).also { read = it } != -1) {
                    out.write(tmp, 0, read)
                }
            }
            return parseMkvTracksAndInfo(out.toByteArray(), totalBytes)
        } catch (_: Exception) {
            return HeaderParseResult(0L, 0.0, emptyList(), false, -1, false, false)
        } finally {
            conn?.disconnect()
        }
    }

    @ReactMethod
    fun inspectMedia(url: String, fileName: String, promise: Promise) {
        inspectorPool.submit {
            try {
                val parsed = fetchAndParseHeader(url)

                var width = 0
                var height = 0
                var videoCodec = ""
                var frameRate = 0.0
                var durationSec = parsed.durationSec
                var hasDV = parsed.hasDoviRecord
                var hasHDR10 = parsed.dvHasHdr10Base
                var dvProfile = parsed.dvProfile
                var hasAtmos = parsed.hasAtmosHint

                val audioList = Arguments.createArray()
                val subtitleList = Arguments.createArray()
                val audioLanguages = linkedSetOf<String>()

                if (parsed.tracks.isNotEmpty()) {
                    // 1. Primary path: 100% accurate EBML TrackEntry data
                    for (t in parsed.tracks) {
                        when (t.trackType) {
                            1 -> { // Video Track
                                if (width == 0 && t.pixelWidth > 0) width = t.pixelWidth
                                if (height == 0 && t.pixelHeight > 0) height = t.pixelHeight
                                if (videoCodec.isEmpty() && t.codecId.isNotEmpty()) {
                                    videoCodec = formatMkvVideoCodec(t.codecId)
                                }
                                if (frameRate == 0.0 && t.defaultDurationNs > 0L) {
                                    frameRate = 1_000_000_000.0 / t.defaultDurationNs.toDouble()
                                }
                                // TransferCharacteristics 16 = PQ (HDR10), 18 = HLG
                                if (t.colorTransfer == 16 || t.colorTransfer == 18) {
                                    if (dvProfile != 5) {
                                        hasHDR10 = true
                                    }
                                }
                            }
                            2 -> { // Audio Track
                                val rawLang = t.languageIetf.ifBlank { t.language }
                                val langName = formatLanguageName(rawLang, t.name)
                                if (langName != "Unknown") {
                                    audioLanguages.add(langName)
                                }
                                val codecName = formatMkvAudioCodec(t.codecId, t.name, parsed.hasAtmosHint)
                                if (codecName.contains("Atmos", ignoreCase = true)) {
                                    hasAtmos = true
                                }
                                val channelStr = formatChannelLayout(t.channels)
                                val bitrateHint = extractBitrateHintFromTitle(t.name)
                                val isOriginal = t.name.contains("Original", ignoreCase = true)

                                val summaryParts = mutableListOf<String>()
                                summaryParts.add(if (isOriginal) "$langName (Original)" else langName)
                                summaryParts.add(codecName)
                                if (channelStr.isNotEmpty()) summaryParts.add(channelStr)
                                if (!bitrateHint.isNullOrEmpty()) summaryParts.add(bitrateHint)

                                val trackMap = Arguments.createMap().apply {
                                    putString("language", langName)
                                    putString("codec", codecName)
                                    putString("channels", channelStr)
                                    putInt("bitrateKbps", 0)
                                    putString("summary", summaryParts.joinToString(" • "))
                                }
                                audioList.pushMap(trackMap)
                            }
                            17 -> { // Subtitle Track
                                val rawLang = t.languageIetf.ifBlank { t.language }
                                val langName = formatLanguageName(rawLang, t.name)
                                val subFormat = formatMkvSubtitleCodec(t.codecId)
                                val subMap = Arguments.createMap().apply {
                                    putString("language", langName)
                                    putString("format", subFormat)
                                }
                                subtitleList.pushMap(subMap)
                            }
                        }
                    }
                } else {
                    // 2. Fallback path for .mp4 / non-EBML containers via MediaExtractor
                    val extractor = MediaExtractor()
                    try {
                        if (url.startsWith("file://") || url.startsWith("/")) {
                            extractor.setDataSource(url.removePrefix("file://"))
                        } else {
                            val headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)"
                            )
                            extractor.setDataSource(url, headers)
                        }
                        for (i in 0 until extractor.trackCount) {
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
                                if (videoCodec.isEmpty()) {
                                    videoCodec = when {
                                        mime.contains("hevc") -> "HEVC (H.265)"
                                        mime.contains("avc") -> "AVC (H.264)"
                                        else -> "Video"
                                    }
                                }
                            } else if (mime.startsWith("audio/")) {
                                val rawLang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                                    format.getString(MediaFormat.KEY_LANGUAGE)
                                } else null
                                val langName = formatLanguageName(rawLang)
                                if (langName != "Unknown") audioLanguages.add(langName)
                                val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                } else 0
                                val chStr = formatChannelLayout(ch)
                                val codecName = formatMkvAudioCodec(mime, "", parsed.hasAtmosHint)
                                val trackMap = Arguments.createMap().apply {
                                    putString("language", langName)
                                    putString("codec", codecName)
                                    putString("channels", chStr)
                                    putInt("bitrateKbps", 0)
                                    putString(
                                        "summary",
                                        listOfNotNull(langName, codecName, chStr.ifEmpty { null })
                                            .joinToString(" • ")
                                    )
                                }
                                audioList.pushMap(trackMap)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            extractor.release()
                        } catch (_: Exception) {}
                    }
                }

                // Cross-check DV & HDR10 flags
                val lowerName = fileName.lowercase(Locale.ROOT)
                val nameHasDv = Regex("\\b(dv|dovi|dolby[ ._-]*vision)\\b").containsMatchIn(lowerName)
                val nameHasHdr = Regex("\\b(hdr|hdr10|hdr10\\+)\\b").containsMatchIn(lowerName)

                if (dvProfile == 5) {
                    hasDV = true
                    hasHDR10 = false
                } else if (dvProfile == 7 || dvProfile == 8) {
                    hasDV = true
                    if (nameHasHdr || parsed.dvHasHdr10Base) {
                        hasHDR10 = true
                    }
                } else {
                    if (nameHasDv) hasDV = true
                    if (hasDV && !nameHasHdr && !parsed.dvHasHdr10Base) {
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
                    putDouble("frameRate", frameRate)
                    putDouble("durationSec", durationSec)
                    putDouble("totalBytes", parsed.totalBytes.toDouble())
                    putBoolean("hasDV", hasDV)
                    putBoolean("hasHDR", hasHDR10)
                    putInt("dvProfile", dvProfile)
                    putBoolean("hasAtmos", hasAtmos)
                    putArray("audioTracks", audioList)
                    putArray("subtitleTracks", subtitleList)
                    putArray("languages", langArray)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("MEDIAINFO_ERROR", e.message)
            }
        }
    }
}
