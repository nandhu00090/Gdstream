package com.gdstream

import java.util.Locale

/** DVD VTS part-of-title table -> program chain -> program map and playback cells. */
internal class DvdTitleInfo private constructor(private val ifo: ByteArray) {
    private fun u(p: Int) = ifo[p].toInt() and 255

    private fun word(p: Int) = (u(p) shl 8) or u(p + 1)

    private fun long(p: Int) = (word(p).toLong() shl 16) or word(p + 2).toLong()

    private fun table(pointer: Int) = Math.toIntExact(long(pointer) * 2048)

    private fun seconds(p: Int): Double {
        fun bcd(value: Int): Int {
            require(value and 15 <= 9 && value ushr 4 <= 9)
            return (value ushr 4) * 10 + (value and 15)
        }
        val fps = if (u(p + 3) ushr 6 == 1) 25.0 else 30000.0 / 1001
        return bcd(u(p)) * 3600 + bcd(u(p + 1)) * 60 + bcd(u(p + 2)) + bcd(u(p + 3) and 63) / fps
    }

    data class Title(val duration: Double, val cells: List<LongRange>, val pgc: Int)

    fun main(): Title {
        val parts = table(0xc8)
        val chains = table(0xcc)
        return (0 until word(parts))
            .map { title ->
                val first = parts + Math.toIntExact(long(parts + 8 + title * 4))
                val end =
                    parts +
                        Math.toIntExact(
                            if (title + 1 < word(parts)) long(parts + 12 + title * 4)
                            else long(parts + 4) + 1
                        )
                require(end <= ifo.size && first < end && (end - first) % 4 == 0)
                val chapters =
                    (first until end step 4).map { p ->
                        val id = word(p)
                        require(id in 1..word(chains))
                        val pgc = chains + Math.toIntExact(long(chains + 8 + (id - 1) * 8 + 4))
                        val program = word(p + 2)
                        require(program in 1..u(pgc + 2))
                        pgc to program
                    }
                val cells = ArrayList<LongRange>()
                var duration = 0.0
                chapters.forEachIndexed { index, (pgc, program) ->
                    val map = pgc + word(pgc + 230)
                    val playback = pgc + word(pgc + 232)
                    val next = chapters.getOrNull(index + 1)
                    val stop =
                        if (next != null && next.first == pgc && next.second > program)
                            u(map + next.second - 1)
                        else u(pgc + 3) + 1
                    for (cell in u(map + program - 1) until stop) {
                        require(cell in 1..u(pgc + 3))
                        val c = playback + (cell - 1) * 24
                        if ((u(c) ushr 4 and 3) == 1 && u(c) ushr 6 != 1)
                            continue // first angle only
                        val start = long(c + 8) * 2048
                        val until = (long(c + 20) + 1) * 2048
                        require(until > start)
                        cells.add(start until until)
                        duration += seconds(c + 4)
                    }
                }
                Title(duration, cells, chapters.first().first)
            }
            .maxByOrNull { it.duration } ?: error("Empty DVD title table")
    }

    fun info(title: Title, sample: ByteArray, bytes: Long): Map<String, Any> {
        fun language(p: Int): String {
            val code = String(ifo, p, 2, Charsets.US_ASCII)
            return if (code.all { it in 'a'..'z' }) Locale(code).getDisplayLanguage(Locale.ENGLISH)
            else "Unknown"
        }
        fun s(i: Int) = sample[i].toInt() and 255
        val frames = HashMap<Int, ByteArray>()
        val mpegRates = HashMap<Int, Int>()
        var videoRate = 0
        for (i in 0 until sample.size - 16) {
            if (s(i) != 0 || s(i + 1) != 0 || s(i + 2) != 1) continue
            if (s(i + 3) == 0xb3)
                videoRate = (s(i + 8) shl 10) or (s(i + 9) shl 2) or (s(i + 10) ushr 6)
            val streamId = s(i + 3)
            if (streamId in 0xc0..0xdf && streamId !in mpegRates) {
                val start = i + 9 + s(i + 8)
                val end = minOf(sample.size - 3, i + 6 + (s(i + 4) shl 8) + s(i + 5) - 3)
                // DVD MPEG audio is Layer II. The version selects the bitrate table.
                for (p in start until end) {
                    if (s(p) != 255 || s(p + 1) and 0xe6 != 0xe4) continue
                    val rates = if (s(p + 1) and 8 != 0) MPEG1_LAYER2 else MPEG2_LAYER2
                    val kbps = rates.getOrNull(s(p + 2) ushr 4) ?: continue
                    if (kbps > 0) mpegRates[streamId] = kbps
                    break
                }
            }
            if (streamId != 0xbd) continue
            val at = i + 9 + s(i + 8)
            val end = minOf(sample.size, i + 6 + (s(i + 4) shl 8) + s(i + 5))
            if (at + 4 < end && s(at) !in frames) frames[s(at)] = sample.copyOfRange(at + 4, end)
        }
        val audio =
            (0 until word(0x202).coerceAtMost(8)).mapNotNull { index ->
                val control = word(title.pgc + 12 + index * 2)
                if (control and 0x8000 == 0) return@mapNotNull null
                val p = 0x204 + 8 * index
                val track = (control ushr 8) and 7
                val type = u(p) ushr 5
                val codec =
                    when (type) {
                        0 -> "AC-3"
                        2,
                        3 -> "MPEG Audio"
                        4 -> "LPCM"
                        6 -> "DTS"
                        else -> "Unknown"
                    }
                val format =
                    when (type) {
                        0 -> frames[0x80 + track]?.let { DiscAudio.probe(it, 0x81) }
                        6 -> frames[0x88 + track]?.let { DiscAudio.probe(it, 0x82) }
                        else -> null
                    }
                val count = (u(p + 1) and 7) + 1
                val layout =
                    format?.layout?.takeIf { it.isNotEmpty() }
                        ?: when (count) {
                            1,
                            2 -> "$count.0 ch"
                            else -> "$count ch"
                        }
                val rate =
                    format?.kbps
                        ?: if (type == 4)
                            (intArrayOf(16, 20, 24, 0)[u(p + 1) ushr 6] *
                                (if (u(p + 1) and 16 != 0) 96 else 48) *
                                count)
                        else mpegRates[0xc0 + track] ?: 0
                val lang = language(p + 2)
                mapOf(
                    "language" to lang,
                    "channels" to layout,
                    "codec" to codec,
                    "bitrateKbps" to rate,
                    "summary" to
                        listOfNotNull(lang, codec, layout, "${rate} kbps".takeIf { rate > 0 })
                            .joinToString(" • "),
                )
            }
        val subs =
            (0 until word(0x254).coerceAtMost(32))
                .filter { u(title.pgc + 28 + it * 4) and 128 != 0 }
                .map { mapOf("language" to language(0x258 + it * 6), "format" to "DVD Subpicture") }
        val pal = u(0x200) and 0x10 != 0
        val picture = (u(0x201) ushr 2) and 3
        val height = (if (pal) 576 else 480) / if (picture == 3) 2 else 1
        val average =
            if (title.duration > 0)
                bytes * 8.0 / title.duration / 1e6 -
                    audio.sumOf { it["bitrateKbps"] as Int } / 1000.0
            else 0.0
        return mapOf(
            "durationSec" to title.duration,
            "totalBytes" to bytes.toDouble(),
            "videoCodec" to if (u(0x200) ushr 6 == 0) "MPEG-1" else "MPEG-2",
            "videoBitrateMbps" to
                if (average > 0) average
                else if (videoRate in 1 until 0x3ffff) videoRate * 0.0004 else 0.0,
            "width" to intArrayOf(720, 704, 352, 352)[picture],
            "height" to height,
            "resolution" to "${height}i",
            "frameRate" to if (pal) 25.0 else 30000.0 / 1001,
            "audioTracks" to audio,
            "subtitleTracks" to subs,
            "languages" to audio.map { it.getValue("language") }.distinct(),
            "hasHDR" to false,
            "hasDV" to false,
            "hasAtmos" to false,
            "dvProfile" to -1,
        )
    }

    companion object {
        private val MPEG1_LAYER2 =
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val MPEG2_LAYER2 =
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

        fun parse(bytes: ByteArray): DvdTitleInfo {
            require(bytes.size >= 2048 && String(bytes, 0, 12, Charsets.US_ASCII) == "DVDVIDEO-VTS")
            return DvdTitleInfo(bytes)
        }

        fun extents(
            cells: List<LongRange>,
            extents: List<Pair<Long, Long>>,
        ): List<Pair<Long, Long>> =
            cells.flatMap { cell ->
                var pos = 0L
                val ranges =
                    extents.mapNotNull { (offset, length) ->
                        val start = maxOf(pos, cell.first)
                        val end = minOf(pos + length, cell.last + 1)
                        val part =
                            if (start < end)
                                (if (offset < 0) -1L else offset + start - pos) to end - start
                            else null
                        pos += length
                        part
                    }
                require(ranges.sumOf { it.second } == cell.last - cell.first + 1)
                ranges
            }
    }
}
