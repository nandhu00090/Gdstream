package com.gdstream

/** Endpoints are aligned to the same BDAV packet grid before inspecting PES timestamps. */
internal object StandaloneBdav {
    const val HLS_TARGET_PACKETS = 4 * 1024 * 1024 / 188

    fun matches(head: ByteArray) = head.size >= 3 * 192 &&
        (0..2).all { head[it * 192 + 4] == 0x47.toByte() }

    fun profile(head: ByteArray, tail: ByteArray, label: String, rawSize: Long = 0, originalLanguage: String = ""): BluRayProfile {
        require(matches(head))
        val ts = ByteArray(head.size / 192 * 188)
        for (packet in 0 until ts.size / 188) {
            head.copyInto(ts, packet * 188, packet * 192 + 4, (packet + 1) * 192)
        }
        val inferredLang = inferLanguageCodeFromName(label).ifBlank {
            runCatching { java.util.Locale.forLanguageTag(originalLanguage).isO3Language }.getOrDefault("")
        }
        var firstAudio = true
        val program = BluRayPsi.program(ts)
        val streams = DiscAudio.inspectM2ts(head, program?.streams.orEmpty()).map { stream ->
            val primary = stream.audio && firstAudio
            if (stream.audio) firstAudio = false
            if (primary && (stream.language.isBlank() || stream.language == "und") && inferredLang.isNotEmpty()) {
                stream.copy(language = inferredLang)
            } else {
                stream
            }
        }
        val first = timestamps(head)
        val last = timestamps(tail)
        // Use one elementary clock at both ends. Prefer the main video when it spans both samples.
        val pid = (streams.filter { it.video }.map { it.pid } + first.keys)
            .firstOrNull { it in first && it in last }
        val ticks = if (pid == null) 0L else (last.getValue(pid).last - first.getValue(pid).first) and 0x1ffffffffL
        val ptsDuration = ticks * 1_000_000 / 90_000
        val index = if (rawSize > 0) StandalonePcrIndex.fromEndpoints(head, tail, rawSize, program?.pcrPid)
            ?.takeIf { ptsDuration == 0L || kotlin.math.abs(ptsDuration - it.spanUs) <= maxOf(2_000_000L, ptsDuration / 100) }
            else null
        val duration = if (ptsDuration > 0) ptsDuration else index?.spanUs ?: 0
        return BluRayProfile(duration, emptyList(),
            streams.filter { it.audio }.map { BluRayAudio(it.pid, it.type, it.language) }, streams, label, pcrIndex = index, primaryLanguageHint = inferredLang)
    }

    private fun timestamps(data: ByteArray): Map<Int, LongRange> {
        fun u(i: Int) = data[i].toInt() and 255
        val result = linkedMapOf<Int, LongRange>()
        for (packet in 0 until data.size - 191 step 192) {
            val p = packet + 4
            if (u(p) != 0x47 || u(p + 1) and 0xc0 != 0x40 || u(p + 3) and 0xd0 != 0x10) continue
            val pes = p + 4 + if (u(p + 3) and 32 != 0) 1 + u(p + 4) else 0
            if (pes + 14 > p + 188 || u(pes) != 0 || u(pes + 1) != 0 || u(pes + 2) != 1 ||
                u(pes + 7) and 128 == 0 || u(pes + 8) < 5) continue
            val pts = pes + 9
            if (u(pts) and 1 == 0 || u(pts + 2) and 1 == 0 || u(pts + 4) and 1 == 0) continue
            val value = ((u(pts).toLong() and 14) shl 29) or (u(pts + 1).toLong() shl 22) or
                ((u(pts + 2).toLong() and 254) shl 14) or (u(pts + 3).toLong() shl 7) or (u(pts + 4) ushr 1).toLong()
            val pid = ((u(p + 1) and 31) shl 8) or u(p + 2)
            result[pid] = (result[pid]?.first ?: value)..value
        }
        return result
    }
}

/** PCR anchors use the proxy's 188-byte coordinates. Interpolate only between samples on the same clock. */
class StandalonePcrIndex private constructor(private val pid: Int, private val origin: Long,
                                             val spanUs: Long, endPosition: Long) {
    private val anchors = java.util.TreeMap<Long, Long>().apply {
        put(0L, 0L)
        put(spanUs, endPosition)
    }

    internal val pointCount: Int @Synchronized get() = anchors.size

    companion object {
        private const val MASK = 0x1ffffffffL
        private fun packetPid(bytes: ByteArray, p: Int) =
            ((bytes[p + 1].toInt() and 31) shl 8) or (bytes[p + 2].toInt() and 255)

        private fun ticks(bytes: ByteArray, p: Int): Long? {
            fun u(i: Int) = bytes[p + i].toInt() and 255
            if (u(0) != 0x47 || u(1) and 0x80 != 0 || u(3) and 0x20 == 0 ||
                u(4) !in 7..183 || u(5) and 0x90 != 0x10) return null
            return (u(6).toLong() shl 25) or (u(7).toLong() shl 17) or (u(8).toLong() shl 9) or
                (u(9).toLong() shl 1) or (u(10) ushr 7).toLong()
        }

        internal fun fromEndpoints(head: ByteArray, tail: ByteArray, size: Long, preferredPid: Int?): StandalonePcrIndex? {
            val starts = linkedMapOf<Int, Long>()
            for (p in 4..head.size - 188 step 192) {
                val clock = ticks(head, p) ?: continue
                val pid = packetPid(head, p)
                if (preferredPid == null || preferredPid == pid) starts.putIfAbsent(pid, clock)
            }
            for (packet in tail.size / 192 - 1 downTo 0) {
                val p = packet * 192 + 4
                val pid = packetPid(tail, p)
                val origin = starts[pid] ?: continue
                val last = ticks(tail, p) ?: continue
                val spanUs = ((last - origin) and MASK) * 1_000_000 / 90_000
                val end = (size / 192 - tail.size / 192 + packet) * 188
                if (spanUs > 0 && end > 0) return StandalonePcrIndex(pid, origin, spanUs, end)
            }
            return null
        }
    }

    /** Inspect either a raw BDAV probe or a peek into the stripped TS after a seek. */
    @Synchronized internal fun sample(bytes: ByteArray, packetBytes: Int, position: Long, length: Int = bytes.size): BluRayPoint? {
        for (packet in 0 until length / packetBytes) {
            val p = packet * packetBytes + packetBytes - 188
            if (packetPid(bytes, p) != pid) continue
            val clock = ticks(bytes, p) ?: continue
            val us = ((clock - origin) and MASK) * 1_000_000 / 90_000
            val pos = position + packet * 188L
            val before = anchors.lowerEntry(us) ?: continue
            val after = anchors.higherEntry(us) ?: continue
            if (pos <= before.value || pos >= after.value) continue
            if (anchors.size < 1024) anchors[us] = pos
            return BluRayPoint(us, pos)
        }
        return null
    }

    @Synchronized internal fun estimate(timeUs: Long): BluRayPoint {
        // Allow the elementary reader to synchronize at a nearby video random-access unit.
        val t = (timeUs - 1_000_000).coerceIn(0, spanUs)
        val a = anchors.floorEntry(t)!!
        val b = anchors.higherEntry(t) ?: return BluRayPoint(t, a.value)
        val fraction = (t - a.key).toDouble() / (b.key - a.key)
        return BluRayPoint(t, (a.value + ((b.value - a.value) * fraction).toLong()) / 188 * 188)
    }
}
