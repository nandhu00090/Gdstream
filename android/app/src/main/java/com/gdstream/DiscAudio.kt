package com.gdstream

import androidx.media3.extractor.DtsUtil
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal data class DiscAudio(val layout: String = "", val kbps: Int = 0) {
    companion object {
        private val speakers = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
        private val ac3Rates =
            intArrayOf(
                32,
                40,
                48,
                56,
                64,
                80,
                96,
                112,
                128,
                160,
                192,
                224,
                256,
                320,
                384,
                448,
                512,
                576,
                640,
            )
        private val hz = intArrayOf(48000, 44100, 32000)

        fun probe(data: ByteArray, type: Int): DiscAudio {
            fun u(i: Int) = data[i].toInt() and 255
            if (type == 0x80 && data.size >= 4) {
                return runCatching {
                        val pcm = HdmvPcm.layout(data)
                        val sub = if (u(2) ushr 4 in listOf(9, 11)) 1 else 0
                        DiscAudio(
                            "${pcm.channels.size - sub}.$sub ch",
                            pcm.rate * pcm.frameBytes * 8 / 1000,
                        )
                    }
                    .getOrDefault(DiscAudio())
            }
            var core = DiscAudio()
            var hdLayout = ""
            for (i in 0 until data.size - 10) {
                if (
                    (type == 0x81 || type == 0x83 || type == 0x84 || type == 0xa1) &&
                        u(i) == 11 &&
                        u(i + 1) == 119
                ) {
                    val bsid = u(i + 5) ushr 3
                    if ((bsid > 10) != (type == 0x84 || type == 0xa1)) continue
                    val mode: Int
                    val lfe: Int
                    val bitrate: Int
                    if (bsid in 11..16) {
                        val size = (((u(i + 2) and 7) shl 8) + u(i + 3) + 1) * 2
                        val rate = u(i + 4) ushr 6
                        val blocks = (u(i + 4) ushr 4) and 3
                        val samples = if (rate == 3) 1536 else intArrayOf(1, 2, 3, 6)[blocks] * 256
                        val frequency =
                            if (rate == 3) (hz.getOrNull(blocks) ?: continue) / 2 else hz[rate]
                        bitrate = (size * 8.0 * frequency / samples / 1000).roundToInt()
                        mode = (u(i + 4) ushr 1) and 7
                        lfe = u(i + 4) and 1
                    } else {
                        if (bsid > 10 || u(i + 4) ushr 6 == 3) continue
                        bitrate =
                            (ac3Rates.getOrNull((u(i + 4) and 63) / 2) ?: continue) shr
                                (bsid - 8).coerceAtLeast(0)
                        mode = u(i + 6) ushr 5
                        var bit = 3
                        if (mode and 1 != 0 && mode != 1) bit += 2
                        if (mode and 4 != 0) bit += 2
                        if (mode == 2) bit += 2
                        lfe = (u(i + 6 + bit / 8) ushr (7 - bit % 8)) and 1
                    }
                    core = DiscAudio("${speakers[mode]}.${lfe} ch", bitrate)
                    if (type != 0x83) return core
                }
                if (
                    type == 0x83 &&
                        u(i) == 0xf8 &&
                        u(i + 1) == 0x72 &&
                        u(i + 2) == 0x6f &&
                        u(i + 3) == 0xba
                ) {
                    val map =
                        (((u(i + 6) and 31) shl 8) or u(i + 7)).takeIf { it != 0 }
                            ?: (((u(i + 5) and 15) shl 1) or (u(i + 6) ushr 7))
                    val weights = intArrayOf(2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1)
                    val count =
                        weights.indices.sumOf { if (map and (1 shl it) != 0) weights[it] else 0 }
                    val sub = Integer.bitCount(map and 0x1004)
                    if (count > 0) hdLayout = "${count-sub}.$sub ch"
                }
                if (
                    (type == 0x82 || type == 0x85 || type == 0x86) &&
                        u(i) == 0x7f &&
                        u(i + 1) == 0xfe &&
                        u(i + 2) == 0x80 &&
                        u(i + 3) == 1 &&
                        i + 16 <= data.size
                ) {
                    val header = data.copyOfRange(i, i + 16)
                    val format = DtsUtil.parseDtsFormat(header, null, null, 0, "video/mp2t", null)
                    val lfe = if (u(i + 10) and 6 != 0) 1 else 0
                    if (format.channelCount > 0)
                        core =
                            DiscAudio(
                                "${format.channelCount-lfe}.$lfe ch",
                                (format.averageBitrate / 1000.0).roundToInt().coerceAtLeast(0),
                            )
                    if (type == 0x82) return core
                }
                if (
                    type in 0x85..0x86 &&
                        u(i) == 0x64 &&
                        u(i + 1) == 0x58 &&
                        u(i + 2) == 0x20 &&
                        u(i + 3) == 0x25
                ) {
                    val count =
                        runCatching {
                                val size = DtsUtil.parseDtsHdHeaderSize(data.copyOfRange(i, i + 10))
                                require(size > 0 && size <= data.size - i)
                                DtsUtil.parseDtsHdHeader(data.copyOfRange(i, i + size)).channelCount
                            }
                            .getOrDefault(0)
                    if (count == 8 || count == 6) hdLayout = "${count-1}.1 ch"
                }
            }
            return core.copy(layout = hdLayout.ifEmpty { core.layout })
        }

        // Lossless extensions have variable frame sizes. Count all elementary bytes between
        // two PES timestamps to include both the extension and its interleaved core.
        private class Sample {
            val head = ByteArrayOutputStream()
            var bytes = 0L
            var firstPts = -1L
            var firstBytes = 0L
            var kbps = 0

            fun timestamp(pts: Long) {
                if (firstPts < 0) {
                    firstPts = pts
                    firstBytes = bytes
                } else {
                    val ticks = (pts - firstPts) and 0x1ffffffffL
                    if (ticks in 1..900000)
                        kbps = ((bytes - firstBytes) * 720.0 / ticks).roundToInt()
                }
            }
        }

        /**
         * Reassemble elementary payload across TS packet boundaries, bounded independently per PID.
         */
        fun inspectM2ts(data: ByteArray, tracks: List<BluRayStream>): List<BluRayStream> {
            val payloads = tracks.filter { it.audio }.associate { it.pid to Sample() }
            for (packet in 0 until data.size - 191 step 192) {
                val p = packet + 4
                fun u(i: Int) = data[i].toInt() and 255
                val out = payloads[((u(p + 1) and 31) shl 8) or u(p + 2)] ?: continue
                if (u(p) != 0x47 || u(p + 3) and 16 == 0) continue
                var start = p + 4 + if (u(p + 3) and 32 != 0) 1 + u(p + 4) else 0
                if (u(p + 1) and 64 != 0) {
                    if (start + 9 > p + 188) continue
                    if (start + 14 <= p + 188 && u(start + 7) and 128 != 0 && u(start + 8) >= 5) {
                        val t = start + 9
                        val pts =
                            ((u(t).toLong() and 14) shl 29) or
                                (u(t + 1).toLong() shl 22) or
                                ((u(t + 2).toLong() and 254) shl 14) or
                                (u(t + 3).toLong() shl 7) or
                                (u(t + 4) ushr 1).toLong()
                        out.timestamp(pts)
                    }
                    start += 9 + u(start + 8)
                }
                if (start < p + 188) {
                    val count = p + 188 - start
                    out.bytes += count
                    out.head.write(data, start, minOf(65536 - out.head.size(), count))
                }
            }
            return tracks.map { track ->
                payloads[track.pid]?.let {
                    val audio = probe(it.head.toByteArray(), track.type)
                    track.copy(
                        layout = audio.layout,
                        kbps = audio.kbps,
                        sampledKbps =
                            if (track.type in setOf(0x83, 0x85, 0x86) || audio.kbps == 0) it.kbps
                            else 0,
                    )
                } ?: track
            }
        }
    }
}
