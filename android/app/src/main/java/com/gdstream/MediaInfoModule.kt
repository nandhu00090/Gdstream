package com.gdstream

import android.media.MediaExtractor
import android.media.MediaFormat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MediaInfoModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val inspectorPool = Executors.newCachedThreadPool()

    override fun getName(): String = "MediaInfoModule"

    private data class MkvTrack(
        var trackNumber: Int = 0,
        var trackUid: Long = 0L,
        var trackType: Int = 0, // 1 = Video, 2 = Audio, 17 = Subtitle
        var codecId: String = "",
        var name: String = "",
        // Per Matroska spec, default Language (0x22B59C) when omitted is "eng"
        var language: String = "eng",
        var languageIetf: String = "",
        var pixelWidth: Int = 0,
        var pixelHeight: Int = 0,
        var defaultDurationNs: Long = 0L,
        var channels: Int = 0,
        var sampleRate: Double = 0.0,
        var bitDepth: Int = 0,
        var colorTransfer: Int = 0,
        var colorPrimaries: Int = 0,
        var hasDoviConfig: Boolean = false,
        var dvProfile: Int = -1,
        var dvHasHdr10Base: Boolean = false,
        // Populated from real bitstream frames & MKV statistics tags:
        var realBitrateBps: Long = 0L,
        var bitstreamChannels: Int = 0,
        var bitstreamHasAtmos: Boolean = false,
        var bitstreamSubCodec: String = "",
        var frameInspected: Boolean = false
    )

    private data class HeaderParseResult(
        val totalBytes: Long,
        val durationSec: Double,
        val tracks: List<MkvTrack>,
        val hasDoviRecord: Boolean,
        val dvProfile: Int,
        val dvHasHdr10Base: Boolean
    )

    private class BitReader(private val data: ByteArray, startByte: Int, private val limitByte: Int) {
        var bytePos = startByte
        var bitPos = 0

        fun bitsRemaining(): Int = ((limitByte - bytePos) * 8) - bitPos

        fun readBit(): Int {
            if (bytePos >= limitByte) return 0
            val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                bytePos++
            }
            return bit
        }

        fun readBits(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                v = (v shl 1) or readBit()
            }
            return v
        }

        fun skipBits(n: Int) {
            val total = bitPos + n
            bytePos += total shr 3
            bitPos = total and 7
        }
    }

    // Standard AC-3 (bsid <= 8) bitrate table in kbps indexed by (frmsizecod / 2)
    private val ac3BitrateTableKbps = intArrayOf(
        32, 40, 48, 56, 64, 80, 96, 112, 128, 160,
        192, 224, 256, 320, 384, 448, 512, 576, 640
    )

    // Channel count by acmod (0..7)
    private val acmodChannels = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

    /**
     * Parses an actual AC-3 / E-AC-3 syncframe (0x0B77) per ATSC A/52B & ETSI TS 103 420
     * to extract the 100% real bitrate, real channel count, and real Dolby Atmos (JOC) flag.
     */
    private fun inspectAc3OrEac3Frame(buf: ByteArray, start: Int, len: Int, track: MkvTrack) {
        val end = minOf(buf.size, start + len)
        var syncOffset = -1
        for (i in start..minOf(end - 16, start + 64)) {
            if ((buf[i].toInt() and 0xFF) == 0x0B && (buf[i + 1].toInt() and 0xFF) == 0x77) {
                syncOffset = i
                break
            }
        }
        if (syncOffset < 0) return

        val bsid = (buf[syncOffset + 5].toInt() and 0xFF) shr 3
        if (bsid in 11..16) {
            // E-AC-3 (Dolby Digital Plus) Bitstream
            val br = BitReader(buf, syncOffset + 2, end)
            val strmtyp = br.readBits(2)
            br.skipBits(3) // substreamid
            val frmsiz = br.readBits(11)
            val frameSizeBytes = (frmsiz + 1) * 2
            val fscod = br.readBits(2)
            val sampleRate: Int
            val numblkscod: Int
            if (fscod == 3) {
                val fscod2 = br.readBits(2)
                sampleRate = when (fscod2) {
                    0 -> 24000
                    1 -> 22050
                    2 -> 16000
                    else -> 48000
                }
                numblkscod = 3
            } else {
                sampleRate = when (fscod) {
                    0 -> 48000
                    1 -> 44100
                    2 -> 32000
                    else -> 48000
                }
                numblkscod = br.readBits(2)
            }
            val numBlocks = when (numblkscod) {
                0 -> 1
                1 -> 2
                2 -> 3
                else -> 6
            }
            val acmod = br.readBits(3)
            val lfeon = br.readBits(1)
            br.skipBits(5) // bsid

            val chCount = acmodChannels[acmod] + lfeon
            if (chCount > 0) track.bitstreamChannels = chCount

            val exactBps = ((frameSizeBytes.toLong() * 8L * sampleRate.toLong()) / (numBlocks.toLong() * 256L))
            if (exactBps in 32_000L..6_144_000L) {
                track.realBitrateBps = exactBps
            }

            // Step through bsi() to reach addbsie (ETSI TS 103 420 Atmos JOC flag)
            br.skipBits(5) // dialnorm
            if (br.readBit() == 1) br.skipBits(8) // compre -> compr
            if (acmod == 0) {
                br.skipBits(5) // dialnorm2
                if (br.readBit() == 1) br.skipBits(8) // compr2e -> compr2
            }
            if (strmtyp == 1) {
                if (br.readBit() == 1) br.skipBits(16) // chanmape -> chanmap
            }
            if (br.readBit() == 1) { // mixmdate
                if (acmod > 2) br.skipBits(2) // dmixmod
                if ((acmod and 1) != 0 && acmod > 2) br.skipBits(6) // ltrtcmixlev + lorocmixlev
                if ((acmod and 4) != 0) br.skipBits(6) // ltrtsurmixlev + lorosurmixlev
                if (lfeon == 1) {
                    if (br.readBit() == 1) br.skipBits(5) // lfemixlevcode
                }
                if (strmtyp == 0) {
                    if (br.readBit() == 1) br.skipBits(6) // pgmscle
                    if (acmod == 0) {
                        if (br.readBit() == 1) br.skipBits(6) // pgmscl2e
                    }
                    if (br.readBit() == 1) br.skipBits(6) // extpgmscle
                    when (br.readBits(2)) { // mixdef
                        1 -> br.skipBits(5)
                        2 -> br.skipBits(12)
                        3 -> {
                            val mixdeflen = br.readBits(5)
                            br.skipBits((mixdeflen + 2) * 8)
                        }
                    }
                    if (acmod < 2) {
                        if (br.readBit() == 1) br.skipBits(14) // paninfoe
                        if (acmod == 0) {
                            if (br.readBit() == 1) br.skipBits(14) // paninfo2e
                        }
                    }
                    if (br.readBit() == 1) { // frmmixcfginfoe
                        if (numblkscod == 0) {
                            br.skipBits(5)
                        } else {
                            for (b in 0 until numBlocks) {
                                if (br.readBit() == 1) br.skipBits(5)
                            }
                        }
                    }
                }
            }
            if (br.readBit() == 1) { // infomdate
                br.skipBits(5) // bsmod (3) + copyrightb (1) + origbs (1)
                if (acmod == 2) br.skipBits(4) // dsurmod + dheadphonmod
                if (acmod >= 6) br.skipBits(2) // dsurexmod
                if (br.readBit() == 1) br.skipBits(8) // audprodie
                if (acmod == 0) {
                    if (br.readBit() == 1) br.skipBits(8) // audprodi2e
                }
                if (fscod < 3) br.skipBits(1) // sourcefscod
            }
            if (strmtyp == 0 && numblkscod != 3) {
                br.skipBits(1) // convsync
            }
            if (strmtyp == 2) {
                if (br.readBit() == 1) br.skipBits(6) // blkid -> frmsizecod
            }
            if (br.bitsRemaining() >= 7) {
                val addbsie = br.readBit()
                if (addbsie == 1) {
                    val addbsil = br.readBits(6)
                    val addbsiLen = addbsil + 1
                    if (addbsiLen >= 2 && br.bitsRemaining() >= 16) {
                        val b0 = br.readBits(8)
                        val b1 = br.readBits(8)
                        // ETSI TS 103 420: flag_ec3_extension_type_a (bit 0 of b0) == 1
                        // and complexity_index_type_a (b1) in 1..16 indicates Dolby Atmos JOC!
                        if ((b0 and 0x01) == 1 && b1 in 1..16) {
                            track.bitstreamHasAtmos = true
                        }
                    }
                }
            }
            track.frameInspected = true
        } else if (bsid in 1..8) {
            // Standard AC-3 (Dolby Digital) Bitstream
            val b4 = buf[syncOffset + 4].toInt() and 0xFF
            val frmsizecod = b4 and 0x3F
            val rateIdx = frmsizecod shr 1
            if (rateIdx in ac3BitrateTableKbps.indices) {
                track.realBitrateBps = ac3BitrateTableKbps[rateIdx].toLong() * 1000L
            }
            val br = BitReader(buf, syncOffset + 5, end)
            br.skipBits(8) // bsid (5) + bsmod (3)
            val acmod = br.readBits(3)
            if ((acmod and 1) != 0 && acmod != 1) br.skipBits(2)
            if ((acmod and 4) != 0) br.skipBits(2)
            if (acmod == 2) br.skipBits(2)
            val lfeon = br.readBit()
            val chCount = acmodChannels[acmod] + lfeon
            if (chCount > 0) track.bitstreamChannels = chCount
            track.frameInspected = true
        }
    }

    private fun inspectTrueHdOrDtsFrame(buf: ByteArray, start: Int, len: Int, track: MkvTrack) {
        val end = minOf(buf.size, start + len)
        val c = track.codecId.uppercase(Locale.ROOT)
        if (c.contains("TRUEHD")) {
            // Look for Dolby TrueHD Major Sync word 0xF8726FBB
            for (i in start..(end - 16)) {
                if ((buf[i].toInt() and 0xFF) == 0xF8 &&
                    (buf[i + 1].toInt() and 0xFF) == 0x72 &&
                    (buf[i + 2].toInt() and 0xFF) == 0x6F &&
                    (buf[i + 3].toInt() and 0xFF) == 0xBB
                ) {
                    // In TrueHD major sync: byte + 11 bit 7 or fourth substream indicates Atmos
                    val flags = buf[i + 11].toInt() and 0xFF
                    if ((flags and 0x80) != 0 || (buf[i + 10].toInt() and 0x0F) >= 3) {
                        track.bitstreamHasAtmos = true
                    }
                    track.frameInspected = true
                    break
                }
            }
        } else if (c.contains("DTS")) {
            for (i in start..(end - 16)) {
                // DTS Core syncword 0x7FFE8001
                if ((buf[i].toInt() and 0xFF) == 0x7F &&
                    (buf[i + 1].toInt() and 0xFF) == 0xFE &&
                    (buf[i + 2].toInt() and 0xFF) == 0x80 &&
                    (buf[i + 3].toInt() and 0xFF) == 0x01
                ) {
                    track.frameInspected = true
                }
                // DTS-HD Substream syncword 0x64582025
                if ((buf[i].toInt() and 0xFF) == 0x64 &&
                    (buf[i + 1].toInt() and 0xFF) == 0x58 &&
                    (buf[i + 2].toInt() and 0xFF) == 0x20 &&
                    (buf[i + 3].toInt() and 0xFF) == 0x25
                ) {
                    track.bitstreamSubCodec = "DTS-HD MA"
                    track.frameInspected = true
                    break
                }
            }
        } else {
            track.frameInspected = true
        }
    }

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

        if (!trackTitle.isNullOrBlank()) {
            val lowerTitle = trackTitle.lowercase(Locale.ROOT)
            if (lowerTitle.contains("british") || lowerTitle.contains("uk") || lowerTitle.contains("sdh")) {
                return "English"
            }
            for ((_, langName) in map) {
                if (lowerTitle.contains(langName.lowercase(Locale.ROOT))) {
                    return langName
                }
            }
        }

        return if (code.isNotEmpty() && code != "und" && code != "unk") {
            code.uppercase(Locale.ROOT)
        } else {
            "English"
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

    private fun formatBitrate(bps: Long): String {
        if (bps <= 0L) return ""
        val kbps = (bps / 1000.0).roundToInt()
        return if (kbps >= 10000) {
            String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        } else {
            "$kbps kbps"
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

    private fun formatRealAudioCodec(track: MkvTrack): String {
        if (track.bitstreamSubCodec.isNotEmpty()) return track.bitstreamSubCodec
        val c = track.codecId.uppercase(Locale.ROOT)
        return when {
            c.contains("TRUEHD") ->
                if (track.bitstreamHasAtmos) "Dolby TrueHD Atmos" else "Dolby TrueHD"
            c.contains("EAC3") ->
                if (track.bitstreamHasAtmos) "Dolby Atmos (DD+)" else "Dolby Digital Plus (DD+)"
            c.contains("AC3") -> "Dolby Digital (AC3)"
            c.contains("DTS") -> when {
                c.contains("HD") || track.realBitrateBps > 1_550_000L -> "DTS-HD MA"
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
            return Pair(-1L, len)
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

    /**
     * Parses Matroska Tags (0x1254C367) to extract mkvmerge's exact per-track BPS (bitrate).
     */
    private fun parseMkvTagsIntoMap(buf: ByteArray, start: Int, limit: Int, bpsByUid: MutableMap<Long, Long>) {
        fun parseSimpleTag(sStart: Int, sLimit: Int): Pair<String, String> {
            var p = sStart
            var k = ""
            var v = ""
            while (p < sLimit) {
                val idPair = readEbmlId(buf, p, sLimit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, sLimit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > sLimit) break
                when (idPair.first) {
                    0x45A3L -> k = readEbmlString(buf, p, sz)
                    0x4487L -> v = readEbmlString(buf, p, sz)
                }
                p += sz
            }
            return Pair(k, v)
        }

        fun parseSingleTag(tStart: Int, tLimit: Int) {
            var p = tStart
            var targetUid = 0L
            var bpsVal = 0L
            while (p < tLimit) {
                val idPair = readEbmlId(buf, p, tLimit) ?: break
                p += idPair.second
                val sizePair = readEbmlSize(buf, p, tLimit) ?: break
                p += sizePair.second
                val sz = sizePair.first.toInt()
                if (sz < 0 || p + sz > tLimit) break
                when (idPair.first) {
                    0x63C0L -> { // Targets
                        var tp = p
                        val tEnd = p + sz
                        while (tp < tEnd) {
                            val tid = readEbmlId(buf, tp, tEnd) ?: break
                            tp += tid.second
                            val tszPair = readEbmlSize(buf, tp, tEnd) ?: break
                            tp += tszPair.second
                            val tsz = tszPair.first.toInt()
                            if (tsz < 0 || tp + tsz > tEnd) break
                            if (tid.first == 0x63C5L) { // TargetTrackUID
                                targetUid = readEbmlUint(buf, tp, tsz)
                            }
                            tp += tsz
                        }
                    }
                    0x67C8L -> { // SimpleTag
                        val (k, v) = parseSimpleTag(p, p + sz)
                        if (k.equals("BPS", ignoreCase = true) || k.startsWith("BPS-", ignoreCase = true)) {
                            bpsVal = v.toLongOrNull() ?: 0L
                        }
                    }
                }
                p += sz
            }
            if (targetUid != 0L && bpsVal > 0L) {
                bpsByUid[targetUid] = bpsVal
            }
        }

        var p = start
        while (p < limit) {
            val idPair = readEbmlId(buf, p, limit) ?: break
            p += idPair.second
            val sizePair = readEbmlSize(buf, p, limit) ?: break
            p += sizePair.second
            val sz = sizePair.first.toInt()
            if (sz < 0 || p + sz > limit) break
            if (idPair.first == 0x7373L) { // Tag
                parseSingleTag(p, p + sz)
            }
            p += sz
        }
    }

    /**
     * Scans a buffer (e.g., tail 64 KB of an MKV file) for the Tags master element (0x1254C367).
     */
    private fun scanBufferForMkvTags(buf: ByteArray, bpsByUid: MutableMap<Long, Long>) {
        var i = 0
        val end = buf.size - 8
        while (i < end) {
            if ((buf[i].toInt() and 0xFF) == 0x12 &&
                (buf[i + 1].toInt() and 0xFF) == 0x54 &&
                (buf[i + 2].toInt() and 0xFF) == 0xC3 &&
                (buf[i + 3].toInt() and 0xFF) == 0x67
            ) {
                val sizePair = readEbmlSize(buf, i + 4, buf.size)
                if (sizePair != null && sizePair.first > 0) {
                    val contentStart = i + 4 + sizePair.second
                    val contentEnd = minOf(buf.size.toLong(), contentStart.toLong() + sizePair.first).toInt()
                    parseMkvTagsIntoMap(buf, contentStart, contentEnd, bpsByUid)
                    break
                }
            }
            i++
        }
    }

    /**
     * Inspects actual audio packets inside an MKV Cluster (0x1F43B675) to extract
     * real bitstream codecs, Atmos JOC flags, channels, and bitrates per track.
     */
    private fun parseClusterAudioFrames(
        buf: ByteArray,
        start: Int,
        limit: Int,
        trackByNum: Map<Int, MkvTrack>
    ) {
        var p = start
        while (p < limit) {
            if (trackByNum.values.all { it.trackType != 2 || it.frameInspected }) break

            val idPair = readEbmlId(buf, p, limit) ?: break
            p += idPair.second
            val sizePair = readEbmlSize(buf, p, limit) ?: break
            p += sizePair.second
            val sz = sizePair.first.toInt()
            if (sz < 0) break
            val elemEnd = minOf(limit, p + sz)

            if (idPair.first == 0xA3L) { // SimpleBlock
                val trkVint = readEbmlSize(buf, p, elemEnd)
                if (trkVint != null) {
                    val trackNum = trkVint.first.toInt()
                    val track = trackByNum[trackNum]
                    if (track != null && track.trackType == 2 && !track.frameInspected) {
                        val headerLen = trkVint.second + 3 // trackVint + timecode(2) + flags(1)
                        val payloadPos = p + headerLen
                        val payloadLen = elemEnd - payloadPos
                        if (payloadLen > 16) {
                            val c = track.codecId.uppercase(Locale.ROOT)
                            if (c.contains("EAC3") || c.contains("AC3")) {
                                inspectAc3OrEac3Frame(buf, payloadPos, payloadLen, track)
                            } else {
                                inspectTrueHdOrDtsFrame(buf, payloadPos, payloadLen, track)
                            }
                        }
                    }
                }
            } else if (idPair.first == 0xA0L) { // BlockGroup
                var bp = p
                while (bp < elemEnd) {
                    val bid = readEbmlId(buf, bp, elemEnd) ?: break
                    bp += bid.second
                    val bszPair = readEbmlSize(buf, bp, elemEnd) ?: break
                    bp += bszPair.second
                    val bsz = bszPair.first.toInt()
                    if (bsz < 0 || bp + bsz > elemEnd) break
                    if (bid.first == 0xA1L) { // Block
                        val trkVint = readEbmlSize(buf, bp, bp + bsz)
                        if (trkVint != null) {
                            val trackNum = trkVint.first.toInt()
                            val track = trackByNum[trackNum]
                            if (track != null && track.trackType == 2 && !track.frameInspected) {
                                val payloadPos = bp + trkVint.second + 3
                                val payloadLen = (bp + bsz) - payloadPos
                                if (payloadLen > 16) {
                                    val c = track.codecId.uppercase(Locale.ROOT)
                                    if (c.contains("EAC3") || c.contains("AC3")) {
                                        inspectAc3OrEac3Frame(buf, payloadPos, payloadLen, track)
                                    } else {
                                        inspectTrueHdOrDtsFrame(buf, payloadPos, payloadLen, track)
                                    }
                                }
                            }
                        }
                    }
                    bp += bsz
                }
            }
            if (p + sz > limit) break
            p += sz
        }
    }

    private fun parseMkvHeaderAndClusters(
        buf: ByteArray,
        tailBuf: ByteArray?,
        totalBytes: Long
    ): HeaderParseResult {
        val tracks = mutableListOf<MkvTrack>()
        val bpsByUid = mutableMapOf<Long, Long>()
        var timestampScale = 1_000_000L
        var rawDuration = 0.0

        val ascii = String(buf, Charsets.ISO_8859_1)
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
                    0xB5L -> track.sampleRate = readEbmlFloat(buf, p, sz)
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
                    0xD7L -> track.trackNumber = readEbmlUint(buf, p, sz).toInt()
                    0x73C5L -> track.trackUid = readEbmlUint(buf, p, sz)
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

        val trackByNum = mutableMapOf<Int, MkvTrack>()

        // Walk EBML top-level & Segment children (including first Cluster for real audio frames)
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
            val elemEnd = if (sz < 0) limit else minOf(limit.toLong(), pos.toLong() + sz).toInt()
            when (idPair.first) {
                0x1549A966L -> parseInfoMaster(pos, elemEnd)
                0x1654AE6BL -> {
                    parseTracksMaster(pos, elemEnd)
                    for (t in tracks) {
                        if (t.trackNumber > 0) trackByNum[t.trackNumber] = t
                    }
                }
                0x1254C367L -> parseMkvTagsIntoMap(buf, pos, elemEnd, bpsByUid)
                0x1F43B675L -> {
                    // Inspect real audio frames inside the Cluster!
                    parseClusterAudioFrames(buf, pos, elemEnd, trackByNum)
                    if (trackByNum.values.all { it.trackType != 2 || it.frameInspected }) {
                        break
                    }
                }
            }
            if (sz < 0 || pos.toLong() + sz > limit) break
            pos += sz.toInt()
        }

        if (tailBuf != null) {
            scanBufferForMkvTags(tailBuf, bpsByUid)
        }

        for (t in tracks) {
            if (t.realBitrateBps <= 0L && t.trackUid != 0L) {
                val tagBps = bpsByUid[t.trackUid] ?: 0L
                if (tagBps > 0L) t.realBitrateBps = tagBps
            }
            if (t.bitstreamChannels > 0) {
                t.channels = t.bitstreamChannels
            }
            if (t.hasDoviConfig) {
                globalHasDovi = true
                globalDvProfile = t.dvProfile
                globalDvHasHdr10Base = t.dvHasHdr10Base
            }
        }

        val durationSec = if (rawDuration > 0.0) {
            (rawDuration * timestampScale.toDouble()) / 1_000_000_000.0
        } else {
            0.0
        }

        return HeaderParseResult(
            totalBytes = totalBytes,
            durationSec = durationSec,
            tracks = tracks,
            hasDoviRecord = globalHasDovi,
            dvProfile = globalDvProfile,
            dvHasHdr10Base = globalDvHasHdr10Base
        )
    }

    private fun openRangeConnection(urlStr: String, rangeHeader: String): HttpURLConnection? {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)")
                setRequestProperty("Range", rangeHeader)
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) return null
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                return conn
            }
        }
        return null
    }

    private fun fetchAndParseHeader(urlStr: String): HeaderParseResult {
        val maxHeaderBytes = 1024 * 1024 // 1 MB covers EBML Header + Tracks + First Cluster packets
        val tailBytesSize = 64 * 1024    // 64 KB at end of file covers mkvmerge Tags (BPS)

        if (urlStr.startsWith("file://") || urlStr.startsWith("/")) {
            val f = File(urlStr.removePrefix("file://"))
            val len = if (f.exists()) f.length() else 0L
            val headBuf = ByteArray(minOf(len, maxHeaderBytes.toLong()).toInt())
            var tailBuf: ByteArray? = null
            try {
                FileInputStream(f).use { it.read(headBuf) }
                if (len > maxHeaderBytes + tailBytesSize) {
                    tailBuf = ByteArray(tailBytesSize)
                    RandomAccessFile(f, "r").use { raf ->
                        raf.seek(len - tailBytesSize)
                        raf.readFully(tailBuf)
                    }
                }
            } catch (_: Exception) {}
            return parseMkvHeaderAndClusters(headBuf, tailBuf, len)
        }

        var conn: HttpURLConnection? = null
        try {
            conn = openRangeConnection(urlStr, "bytes=0-${maxHeaderBytes - 1}")
            if (conn == null) {
                return HeaderParseResult(0L, 0.0, emptyList(), false, -1, false)
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
            val headBytes = out.toByteArray()

            // Also fetch last 64 KB for MKV Tags (BPS table) if needed
            var tailBytes: ByteArray? = null
            val acceptRanges = conn.getHeaderField("Accept-Ranges") ?: ""
            if (totalBytes > maxHeaderBytes + tailBytesSize && !acceptRanges.equals("none", ignoreCase = true)) {
                var tailConn: HttpURLConnection? = null
                try {
                    val tailStart = totalBytes - tailBytesSize
                    tailConn = openRangeConnection(urlStr, "bytes=$tailStart-${totalBytes - 1}")
                    if (tailConn != null && tailConn.responseCode in 200..299) {
                        val tailOut = ByteArrayOutputStream()
                        tailConn.inputStream.use { input ->
                            val tmp = ByteArray(16 * 1024)
                            var read = 0
                            while (tailOut.size() < tailBytesSize && input.read(tmp).also { read = it } != -1) {
                                tailOut.write(tmp, 0, read)
                            }
                        }
                        tailBytes = tailOut.toByteArray()
                    }
                } catch (_: Exception) {
                } finally {
                    tailConn?.disconnect()
                }
            }

            return parseMkvHeaderAndClusters(headBytes, tailBytes, totalBytes)
        } catch (_: Exception) {
            return HeaderParseResult(0L, 0.0, emptyList(), false, -1, false)
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
                var videoBitrateStr = ""
                var frameRate = 0.0
                var durationSec = parsed.durationSec
                var hasDV = parsed.hasDoviRecord
                var hasHDR10 = parsed.dvHasHdr10Base
                var dvProfile = parsed.dvProfile
                var hasAtmos = false

                val audioList = Arguments.createArray()
                val subtitleList = Arguments.createArray()
                val audioLanguages = linkedSetOf<String>()

                if (parsed.tracks.isNotEmpty()) {
                    for (t in parsed.tracks) {
                        when (t.trackType) {
                            1 -> { // Video Track
                                if (width == 0 && t.pixelWidth > 0) width = t.pixelWidth
                                if (height == 0 && t.pixelHeight > 0) height = t.pixelHeight
                                if (videoCodec.isEmpty() && t.codecId.isNotEmpty()) {
                                    videoCodec = formatMkvVideoCodec(t.codecId)
                                }
                                if (videoBitrateStr.isEmpty() && t.realBitrateBps > 0L) {
                                    videoBitrateStr = formatBitrate(t.realBitrateBps)
                                }
                                if (frameRate == 0.0 && t.defaultDurationNs > 0L) {
                                    frameRate = 1_000_000_000.0 / t.defaultDurationNs.toDouble()
                                }
                                if (t.colorTransfer == 16 || t.colorTransfer == 18) {
                                    if (dvProfile != 5) {
                                        hasHDR10 = true
                                    }
                                }
                            }
                            2 -> { // Audio Track (Pure Bitstream & Container Specs Only!)
                                val rawLang = t.languageIetf.ifBlank { t.language }
                                val langName = formatLanguageName(rawLang, t.name)
                                audioLanguages.add(langName)

                                val codecName = formatRealAudioCodec(t)
                                if (t.bitstreamHasAtmos) {
                                    hasAtmos = true
                                }
                                val channelStr = formatChannelLayout(t.channels)
                                val bitrateStr = formatBitrate(t.realBitrateBps)
                                val bitrateKbps = (t.realBitrateBps / 1000L).toInt()

                                val summaryParts = mutableListOf<String>()
                                summaryParts.add(langName)
                                summaryParts.add(codecName)
                                if (channelStr.isNotEmpty()) summaryParts.add(channelStr)
                                if (bitrateStr.isNotEmpty()) summaryParts.add(bitrateStr)

                                val trackMap = Arguments.createMap().apply {
                                    putString("language", langName)
                                    putString("codec", codecName)
                                    putString("channels", channelStr)
                                    putInt("bitrateKbps", bitrateKbps)
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
                    // Fallback for MP4 containers via MediaExtractor
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
                                } else "eng"
                                val langName = formatLanguageName(rawLang)
                                audioLanguages.add(langName)
                                val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                } else 0
                                val chStr = formatChannelLayout(ch)
                                val brBps = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                    format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                                } else 0L
                                val brStr = formatBitrate(brBps)
                                val codecName = when {
                                    mime.contains("eac3") && mime.contains("joc") -> {
                                        hasAtmos = true
                                        "Dolby Atmos (DD+)"
                                    }
                                    mime.contains("eac3") -> "Dolby Digital Plus (DD+)"
                                    mime.contains("ac3") -> "Dolby Digital (AC3)"
                                    mime.contains("mp4a") || mime.contains("aac") -> "AAC"
                                    else -> "Audio"
                                }
                                val trackMap = Arguments.createMap().apply {
                                    putString("language", langName)
                                    putString("codec", codecName)
                                    putString("channels", chStr)
                                    putInt("bitrateKbps", (brBps / 1000L).toInt())
                                    putString(
                                        "summary",
                                        listOfNotNull(
                                            langName,
                                            codecName,
                                            chStr.ifEmpty { null },
                                            brStr.ifEmpty { null }
                                        ).joinToString(" • ")
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

                if (dvProfile == 5) {
                    hasDV = true
                    hasHDR10 = false
                } else if (dvProfile == 7 || dvProfile == 8) {
                    hasDV = true
                    if (parsed.dvHasHdr10Base) {
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

                val finalVideoCodecLabel = if (videoBitrateStr.isNotEmpty()) {
                    "$videoCodec @ $videoBitrateStr"
                } else {
                    videoCodec
                }

                val langArray = Arguments.createArray()
                for (l in audioLanguages) {
                    langArray.pushString(l)
                }

                val result = Arguments.createMap().apply {
                    putInt("width", width)
                    putInt("height", height)
                    putString("resolution", resolutionLabel)
                    putString("videoCodec", finalVideoCodecLabel)
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
