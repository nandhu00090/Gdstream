package com.gdstream

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class BluRayStream(val pid: Int, val type: Int, val format: Int = 0, val rate: Int = 0,
                        val dynamicRange: Int = 0, val language: String = "") {
    val height: Int get() = when (format) { 1, 3 -> 480; 2, 7 -> 576; 4, 6 -> 1080; 5 -> 720; 8 -> 2160; else -> 0 }
    val video: Boolean get() = type in listOf(1, 2, 0x1b, 0x20, 0x24, 0xea)
    val audio: Boolean get() = type in listOf(3, 4, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0xa1, 0xa2)
    val codec: String get() = when (type) {
        0x24 -> "HEVC"; 0x1b -> "AVC"; 1, 2 -> "MPEG-2"; 0xea -> "VC-1"
        0x80 -> "LPCM"; 0x81 -> "AC-3"; 0x82 -> "DTS"; 0x83 -> "TrueHD"
        0x84, 0xa1 -> "E-AC-3"; 0x85 -> "DTS-HD"; 0x86 -> "DTS-HD MA"; 0xa2 -> "DTS Express"
        0x90 -> "PGS"; else -> "Unknown"
    }
    fun json() = JSONArray().put(pid).put(type).put(format).put(rate).put(dynamicRange).put(language)
    companion object {
        fun fromJson(a: JSONArray) = BluRayStream(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4), a.getString(5))
        fun descriptor(pid: Int, data: ByteArray, clpi: Boolean): BluRayStream {
            require(data.isNotEmpty())
            fun byte(i: Int) = data.getOrNull(i)?.toInt()?.and(255) ?: 0
            val type = byte(0)
            val langOffset = when (type) { 0x90, 0x91, 0xa0 -> 1; else -> 2 }
            val language = if (langOffset + 3 <= data.size) String(data, langOffset, 3, Charsets.US_ASCII).trim() else ""
            return BluRayStream(pid, type, byte(1) ushr 4, byte(1) and 15,
                if (type == 0x24) byte(if (clpi) 3 else 2) ushr 4 else 0, language)
        }
    }
}

internal fun mergeBluRayStreams(streams: List<BluRayStream>): List<BluRayStream> =
    streams.groupBy { it.pid }.values.map { entries ->
        entries.reduce { a, b -> a.copy(format = a.format.takeIf { it > 0 } ?: b.format,
            rate = a.rate.takeIf { it > 0 } ?: b.rate, dynamicRange = maxOf(a.dynamicRange, b.dynamicRange),
            language = a.language.takeIf { it.matches(Regex("[a-z]{3}")) && it != "und" } ?: b.language) }
    }

private val iso639Locales by lazy { Locale.getISOLanguages().map { Locale(it) }.associateBy { it.isO3Language } }

internal fun bluRayLanguage(code: String): String {
    val canonical = when (code.lowercase(Locale.ROOT)) { "fre" -> "fra"; "ger" -> "deu"; "chi" -> "zho"; "dut" -> "nld"; else -> code.lowercase(Locale.ROOT) }
    if (canonical.isBlank() || canonical == "und") return "Unknown"
    val name = (iso639Locales[canonical] ?: Locale.forLanguageTag(canonical)).getDisplayLanguage(Locale.ENGLISH)
    return name.ifBlank { canonical }
}

internal fun BluRayProfile.mediaInfo(totalBytes: Long): Map<String, Any> {
    val videos = streams.filter { it.video }
    val main = videos.maxByOrNull { it.height }
    val label = discLabel.replace('_', ' ').replace('.', ' ')
    val labelDv = Regex("\\b(DV|DOVI|Dolby\\s*Vision)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)
    val labelHdr = Regex("\\bHDR(?:10)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)
    val dual = videos.any { it.type == 0x24 && it.height == 2160 } &&
        videos.any { it.type == 0x24 && it.height == 1080 && it.pid == 0x1015 }
    val dv = dolbyVision || labelDv || dual || videos.any { it.dynamicRange == 3 }
    
    val height = main?.height?.takeIf { it > 0 } ?: when {
        Regex("\\b(2160P|UHD|4K)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) -> 2160
        Regex("\\b1080[PI]\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) -> 1080
        else -> 0
    }
    val hdr = labelHdr || videos.any { it.dynamicRange == 2 } || (dv && height == 2160)
    val audioStreams = streams.filter { it.audio }.ifEmpty { audio.map { BluRayStream(it.pid, it.type, language = it.language) } }
    val audioInfo = audioStreams.distinctBy { it.pid }.map { stream ->
        val channels = when (stream.format) { 1 -> "Mono"; 3 -> "Stereo"; 6 -> "Multichannel"; else -> "" }
        val language = bluRayLanguage(stream.language)
        mapOf("language" to language, "codec" to stream.codec, "channels" to channels, "bitrateKbps" to 0,
            "summary" to listOf(language, stream.codec, channels).filter { it.isNotEmpty() }.joinToString(" • "))
    }
    return mapOf("width" to when (height) { 2160 -> 3840; 1080 -> 1920; 720 -> 1280; 480, 576 -> 720; else -> 0 },
        "height" to height, "resolution" to if (height == 0) "Unknown" else "${height}${if (main?.format in listOf(1, 2, 4)) "i" else "p"}", "videoCodec" to (main?.codec ?: "Unknown"),
        "frameRate" to when (main?.rate) { 1 -> 23.976; 2 -> 24.0; 3 -> 25.0; 4 -> 29.97; 6 -> 50.0; 7 -> 59.94; else -> 0.0 },
        "durationSec" to durationUs / 1_000_000.0, "totalBytes" to totalBytes.toDouble(), "hasDV" to dv, "hasHDR" to hdr,
        "dvProfile" to if (dv && hdr) 7 else -1, "hasAtmos" to false, "audioTracks" to audioInfo,
        "subtitleTracks" to streams.filter { it.type == 0x90 }.distinctBy { it.pid }.map { mapOf("language" to bluRayLanguage(it.language), "format" to "PGS") },
        "languages" to audioStreams.map { bluRayLanguage(it.language) }.distinct(), "discLabel" to discLabel)
}

/** MPLS primary STN descriptors supplement CLPI; num_dv identifies an enhancement stream. */
internal object BluRayMplsSpecs {
    data class Result(val streams: List<BluRayStream>, val dolbyVision: Boolean)
    fun parseItem(data: ByteArray, start: Int, end: Int): Result {
        fun u8(p: Int): Int { require(p in start until end); return data[p].toInt() and 255 }
        fun u16(p: Int) = (u8(p) shl 8) or u8(p + 1)
        var p = start + 34
        if ((u16(start + 11) and 0x10) != 0) p += 2 + (u8(p).coerceAtLeast(1) - 1) * 10
        val limit = p + 2 + u16(p)
        require(limit <= end && p + 16 <= limit)
        val count = u8(p + 4) + u8(p + 5) + u8(p + 6) + u8(p + 7)
        val dv = u8(p + 11) > 0
        p += 16
        val streams = ArrayList<BluRayStream>()
        repeat(count) {
            val entrySize = u8(p++)
            require(entrySize >= 3 && p + entrySize < limit)
            val pid = when (u8(p)) { 1 -> u16(p + 1); 2 -> { require(entrySize >= 5); u16(p + 3) }; 3, 4 -> { require(entrySize >= 4); u16(p + 2) }; else -> -1 }
            p += entrySize
            val attrSize = u8(p++)
            require(attrSize > 0 && p + attrSize <= limit)
            if (pid >= 0) streams.add(BluRayStream.descriptor(pid, data.copyOfRange(p, p + attrSize), false))
            p += attrSize
        }
        return Result(streams, dv)
    }
}
