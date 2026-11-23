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

    data class Cell(val ranges: List<LongRange>, val durationUs: Long, val discontinuity: Boolean)
    data class Title(val duration: Double, val playback: List<Cell>, val pgc: Int) {
        val cells get() = playback.flatMap { it.ranges }
    }

    // The table length includes the last byte, relative to its own header.
    private fun entries(pointer: Int, header: Int, stride: Int): IntProgression? {
        val base = long(pointer) * 2048
        if (base == 0L || base + header > ifo.size) return null
        val end = base + long(base.toInt() + header - 4) + 1
        if (end > ifo.size || end <= base + header || (end - base - header) % stride != 0L) return null
        return (base.toInt() + header until end.toInt() step stride)
    }

    // VTS_C_ADT can give one (VOB ID, cell ID) several physically separated extents.
    private val cellAddresses by lazy {
        entries(0xe0, 8, 12)?.groupBy(
            { word(it) to u(it + 2) },
            { long(it + 4) * 2048 until (long(it + 8) + 1) * 2048 }
        ).orEmpty()
    }

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
                val cells = ArrayList<Cell>()
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
                        val positions = word(pgc + 234)
                        val position = pgc + positions + (cell - 1) * 4
                        val addresses = if (positions == 0) null else cellAddresses[word(position) to u(position + 3)]
                        val ranges = addresses?.mapNotNull {
                            (maxOf(it.first, start) until minOf(it.last + 1, until)).takeUnless(LongRange::isEmpty)
                        }?.distinct()?.sortedBy { it.first } ?: listOf(start until until)
                        require(ranges.isNotEmpty() && ranges.zipWithNext().all { (a, b) -> a.last < b.first })
                        val seconds = seconds(c + 4)
                        val jump = cells.lastOrNull()?.let { it.ranges.last().last + 1 != ranges.first().first } ?: false
                        val discontinuity = u(c) and 2 != 0 || jump && u(c) and 8 == 0
                        cells.add(Cell(ranges, (seconds * 1_000_000).toLong(), discontinuity))
                        duration += seconds
                    }
                }
                Title(duration, cells, chapters.first().first)
            }
            .maxByOrNull { it.duration } ?: error("Empty DVD title table")
    }

    /** Address-map sectors are relative to VTS_XX_1.VOB. Translate through exactly
     * the same selected cell extents that back the virtual movie. */
    fun segments(title: Title): List<DvdProxy.Segment> {
        val entries = entries(0xe4, 4, 4) ?: return emptyList()
        val sectors = entries.map { long(it) * 2048 }
        if (sectors.zipWithNext().any { (a, b) -> b <= a }) return emptyList()
        val result = mutableListOf<DvdProxy.Segment>()
        var virtual = 0L
        for (cell in title.playback) {
            val starts = ArrayList<Long>()
            for (range in cell.ranges) {
                var index = sectors.binarySearch(range.first)
                if (index < 0) return emptyList()
                while (index < sectors.size && sectors[index] <= range.last) {
                    starts.add(virtual + (sectors[index++] - range.first))
                }
                virtual += range.last - range.first + 1
            }
            // The IFO times the cell, not individual VOBUs. Use their count to
            // estimate EXTINF on VBR titles, with every cut on an actual NAV sector.
            val count = starts.size
            if (count == 0 || cell.durationUs <= 0) return emptyList()
            starts.add(virtual) // exclusive last sector of the last selected extent
            var first = 0
            for (i in 1..count) {
                val duration = cell.durationUs * i / count - cell.durationUs * first / count
                val bytes = starts[i] - starts[first]
                if (i < count && duration < 4_000_000 && bytes < 6L * 1024 * 1024) continue
                if (bytes > 12L * 1024 * 1024 || duration <= 0) return emptyList()
                result.add(DvdProxy.Segment(starts[first], starts[i], duration, first == 0 && cell.discontinuity))
                first = i
            }
        }
        return result
    }

    fun info(title: Title, sample: ByteArray, bytes: Long, fallbackHint: String = ""): Map<String, Any> {
        val inferredCode = inferLanguageCodeFromName(fallbackHint)
        val inferredName = if (inferredCode.isNotEmpty()) bluRayLanguage(inferredCode) else ""
        fun language(p: Int, useFallback: Boolean = false): Pair<String, String> {
            val code = String(ifo, p, 2, Charsets.US_ASCII).lowercase(Locale.ROOT)
            if (code.all { it in 'a'..'z' } && code != "xx" && code != "un") {
                val loc = Locale(code)
                val name = loc.getDisplayLanguage(Locale.ENGLISH)
                val iso3 = runCatching { loc.isO3Language }.getOrDefault("")
                if (name.isNotBlank() && !name.equals(code, true)) return name to iso3
            }
            if (useFallback && inferredName.isNotEmpty()) return inferredName to inferredCode
            return "Unknown" to ""
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
        var ac3Substream = -1
        var primaryAudioCode = ""
        val audio =
            (0 until word(0x202).coerceAtMost(8)).mapNotNull { index ->
                val control = word(title.pgc + 12 + index * 2)
                if (control and 0x8000 == 0) return@mapNotNull null
                val p = 0x204 + 8 * index
                val track = (control ushr 8) and 7
                val type = u(p) ushr 5
                if (type == 0 && ac3Substream < 0) ac3Substream = 0x80 + track
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
                val (lang, iso3) = language(p + 2, useFallback = index == 0)
                if (primaryAudioCode.isEmpty() && iso3.isNotEmpty()) primaryAudioCode = iso3
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
                .map { mapOf("language" to language(0x258 + it * 6).first, "format" to "DVD Subpicture") }
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
            "ac3Substream" to ac3Substream,
            "audioLanguageCode" to primaryAudioCode,
            "subtitleTracks" to subs,
            "languages" to audio.map { it.getValue("language") }.distinct(),
            "hasHDR" to false,
            "hasDV" to false,
            "hasAtmos" to false,
            "dvProfile" to -1,
        )
    }

    companion object {
        const val INDEX_VERSION = 1
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
