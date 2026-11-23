package com.gdstream

import org.json.JSONArray
import org.json.JSONObject

/** Positions are in the proxy's 188-byte TS address space, never ISO sectors or 192-byte SPNs. */
data class BluRayPoint(val timeUs: Long, val position: Long)
data class BluRayAudio(val pid: Int, val type: Int, val language: String) {
    val codecName: String get() = when (type) {
        0x80 -> "LPCM"
        0x81 -> "AC-3"
        0x83 -> "TrueHD"
        0x84, 0xa1 -> "E-AC-3"
        0x82 -> "DTS"
        0x85 -> "DTS-HD"
        0x86 -> "DTS-HD MA"
        else -> "Audio"
    }
    fun displayLabel(trackIndex: Int, channels: String = ""): String {
        val name = bluRayLanguage(language)
        val title = if (name == "Unknown") "Audio ${trackIndex + 1}" else name
        return listOf(title, codecName, channels).filter { it.isNotEmpty() }.joinToString(" • ")
    }
    val label: String get() = displayLabel(0)
}
data class BluRayProfile(val durationUs: Long, val points: List<BluRayPoint>, val audio: List<BluRayAudio>,
    val streams: List<BluRayStream> = emptyList(), val discLabel: String = "", val dolbyVision: Boolean = false,
    val pcrIndex: StandalonePcrIndex? = null, val primaryLanguageHint: String = "") {
    fun toJson() = JSONObject().put("durationUs", durationUs)
        .put("streams", JSONArray().apply { streams.forEach { put(it.json()) } })
        .put("discLabel", discLabel).put("dolbyVision", dolbyVision)
        .put("points", JSONArray().apply { points.forEach { put(JSONArray().put(it.timeUs).put(it.position)) } })
        .put("audio", JSONArray().apply { audio.forEach { put(JSONArray().put(it.pid).put(it.type).put(it.language)) } })
    companion object {
        fun fromJson(json: JSONObject?): BluRayProfile? {
            if (json == null) return null
            val p = json.getJSONArray("points")
            val a = json.getJSONArray("audio")
            require(p.length() <= 250_000 && a.length() <= 256)
            val points = (0 until p.length()).map { p.getJSONArray(it).let { BluRayPoint(it.getLong(0), it.getLong(1)) } }
            require(points.all { it.timeUs >= 0 && it.position >= 0 && it.position % 188 == 0L })
            require(points.zipWithNext().all { (a, b) -> b.timeUs > a.timeUs && b.position > a.position })
            return BluRayProfile(json.getLong("durationUs"), points, (0 until a.length()).map {
                a.getJSONArray(it).let { BluRayAudio(it.getInt(0), it.getInt(1), it.getString(2)) }
            }, json.optJSONArray("streams")?.let { arr -> (0 until arr.length()).map { BluRayStream.fromJson(arr.getJSONArray(it)) } }.orEmpty(),
                json.optString("discLabel", ""), json.optBoolean("dolbyVision", false))
        }
    }
}

/** Bounds-checked CLPI ProgramInfo and CPI EP_map parser (Blu-ray timestamps use 45 kHz). */
internal object BluRayClipInfo {
    private class Bits(val data: ByteArray, var bit: Int, val end: Int) {
        fun read(n: Int): Long {
            require(n in 0..32 && bit.toLong() + n <= end.toLong() * 8) { "Truncated CLPI bit field" }
            var value = 0L
            repeat(n) { value = (value shl 1) or ((data[bit / 8].toInt() ushr (7 - bit++ % 8)) and 1).toLong() }
            return value
        }
    }
    /** Raw-stream fallback: take the presentation interval from the CLPI STC sequence. */
    fun parseRaw(data: ByteArray, rawSize: Long): BluRayProfile {
        fun u32(p: Int): Long {
            require(p >= 0 && p.toLong() + 4 <= data.size) { "Truncated CLPI sequence" }
            return (0..3).fold(0L) { n, i -> (n shl 8) or (data[p + i].toLong() and 255) }
        }
        val start = u32(8)
        if (start == 0L) return parse(data, 0, 1, rawSize).copy(durationUs = 0, points = emptyList())
        require(start <= data.size - 26)
        val p = start.toInt()
        require(u32(p) >= 22 && start + 4 + u32(p) <= data.size)
        val inTicks = u32(p + 18)
        val outTicks = u32(p + 22)
        require(outTicks > inTicks)
        val profile = parse(data, inTicks, outTicks - inTicks, rawSize)
        return if (data[p + 5].toInt() == 1 && data[p + 10].toInt() == 1) profile
            else profile.copy(durationUs = 0, points = emptyList())
    }

    fun parse(data: ByteArray, inTicks: Long, durationTicks: Long, rawSize: Long): BluRayProfile {
        require(inTicks >= 0 && durationTicks > 0 && rawSize >= 192)
        require(data.size >= 32 && String(data, 0, 4, Charsets.US_ASCII) == "HDMV") { "Invalid CLPI header" }
        fun uint(at: Int, bytes: Int): Long {
            require(at >= 0 && at.toLong() + bytes <= data.size) { "Truncated CLPI" }
            var value = 0L
            repeat(bytes) { value = (value shl 8) or (data[at + it].toLong() and 255) }
            return value
        }
        fun section(pointer: Int): Pair<Int, Int>? {
            val start = uint(pointer, 4)
            if (start == 0L) return null
            require(start <= data.size - 4)
            val end = start + 4 + uint(start.toInt(), 4)
            require(end <= data.size && end >= start + 4)
            return start.toInt() to end.toInt()
        }
        val audio = ArrayList<BluRayAudio>()
        val streams = ArrayList<BluRayStream>()
        val videoPids = HashSet<Int>()
        section(12)?.let { (start, end) ->
            require(start + 6 <= end)
            var p = start + 6
            repeat(uint(start + 5, 1).toInt()) {
                require(p + 8 <= end)
                val streamCount = uint(p + 6, 1).toInt()
                p += 8
                repeat(streamCount) {
                    require(p + 4 <= end)
                    val pid = uint(p, 2).toInt()
                    val length = uint(p + 2, 1).toInt()
                    val coding = p + 3
                    require(length > 0 && coding + length <= end)
                    val type = uint(coding, 1).toInt()
                    streams.add(BluRayStream.descriptor(pid, data.copyOfRange(coding, coding + length), true))
                    if (type in listOf(0x01, 0x02, 0x1b, 0x24, 0xea)) videoPids.add(pid)
                    if (type in listOf(0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0xa1, 0xa2)) {
                        require(length >= 5)
                        audio.add(BluRayAudio(pid, type, String(data, coding + 2, 3, Charsets.US_ASCII)))
                    }
                    p = coding + length
                }
            }
        }
        val points = ArrayList<BluRayPoint>()
        val durationUs = Math.multiplyExact(durationTicks, 1_000_000L) / 45000L
        section(8)?.let { (start, end) ->
            require(start + 6 <= end)
            if (uint(start + 5, 1) != 1L) return BluRayProfile(durationUs, points, audio, streams)
            val atc = start + 6
            require(atc + 6 <= end)
            if (uint(atc + 4, 1) != 1L) return BluRayProfile(durationUs, points, audio, streams)
            val stc = atc + 6
            require(stc + 14 <= end)
            if (kotlin.math.abs(uint(stc + 6, 4) - inTicks) > 256) return BluRayProfile(durationUs, points, audio, streams)
        }
        val cpi = section(16) ?: return BluRayProfile(durationUs, points, audio, streams)
        val (start, end) = cpi
        if (end == start + 4) return BluRayProfile(durationUs, points, audio, streams)
        require(start + 8 <= end)
        val epBase = start + 6
        val table = Bits(data, (start + 8) * 8, end)
        data class Entry(val pid: Int, val type: Int, val coarse: Int, val fine: Int, val at: Int)
        val entries = (0 until uint(start + 7, 1).toInt()).map {
            val pid = table.read(16).toInt(); table.read(10)
            val type = table.read(4).toInt()
            val coarse = table.read(16).toInt()
            val fine = table.read(18).toInt()
            val at = epBase.toLong() + table.read(32)
            require(at >= start + 8 && at + 4 <= end && coarse <= fine && fine <= 250_000)
            Entry(pid, type, coarse, fine, at.toInt())
        }
        val entry = entries.firstOrNull { it.pid in videoPids } ?: entries.firstOrNull { it.type == 1 }
            ?: return BluRayProfile(durationUs, points, audio, streams)
        val fineStart = entry.at.toLong() + uint(entry.at, 4)
        require(entry.at.toLong() + 4 + entry.coarse * 8L <= fineStart && fineStart + entry.fine * 4L <= end)
        val coarseBits = Bits(data, (entry.at + 4) * 8, end)
        data class Coarse(val ref: Int, val pts: Long, val spn: Long)
        val coarse = (0 until entry.coarse).map { Coarse(coarseBits.read(18).toInt(), coarseBits.read(14), coarseBits.read(32)) }
        require(coarse.all { it.ref < entry.fine } && coarse.zipWithNext().all { (a, b) -> a.ref < b.ref })
        coarse.forEachIndexed { index, c ->
            val until = coarse.getOrNull(index + 1)?.ref ?: entry.fine
            val fine = Bits(data, (fineStart.toInt() + c.ref * 4) * 8, end)
            for (i in c.ref until until) {
                fine.read(4)
                val ticks = ((c.pts and -2L) shl 18) + (fine.read(11) shl 8)
                val packet = (c.spn and -131072L) + fine.read(17)
                val relative = ticks - inTicks
                if (relative >= -256 && relative < durationTicks && packet < rawSize / 192) {
                    val point = BluRayPoint(maxOf(0, relative) * 1_000_000 / 45000, packet * 188)
                    val last = points.lastOrNull()
                    if (last == null || point.timeUs > last.timeUs && point.position > last.position) points.add(point)
                }
            }
        }
        if (points.isNotEmpty() && points.first().timeUs > 0 && points.first().position > 0) points.add(0, BluRayPoint(0, 0))
        return BluRayProfile(durationUs, points, audio.distinctBy { it.pid }, streams.distinctBy { it.pid })
    }
}
