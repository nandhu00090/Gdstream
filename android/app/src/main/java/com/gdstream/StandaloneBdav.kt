package com.gdstream

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Endpoints are aligned to the same BDAV packet grid before inspecting PES timestamps. */
internal object StandaloneBdav {
    const val HLS_TARGET_PACKETS = 8 * 1024 * 1024 / 188

    fun matches(head: ByteArray) = head.size >= 3 * 192 &&
        (0..2).all { head[it * 192 + 4] == 0x47.toByte() }

    fun transportPackets(head: ByteArray): ByteArray {
        val ts = ByteArray(head.size / 192 * 188)
        for (packet in 0 until ts.size / 188) {
            head.copyInto(ts, packet * 188, packet * 192 + 4, (packet + 1) * 192)
        }
        return ts
    }

    private fun payload(ts: ByteArray, p: Int, pid: Int): Int {
        fun u(i: Int) = ts[p + i].toInt() and 255
        if (u(0) != 0x47 || ((u(1) and 31) shl 8) + u(2) != pid ||
            u(1) and 0x80 != 0 || u(3) and 0xd0 != 0x10) return -1
        val at = 4 + if (u(3) and 32 != 0) 1 + u(4) else 0
        return if (at < 188) p + at else -1
    }

    /** Reassemble Annex B across TS and PES boundaries. The last NAL in a probe
     * may be incomplete; seeing the IDR header closes the preceding parameter set. */
    fun avcConfig(ts: ByteArray, pid: Int): Avc? {
        val es = ByteArrayOutputStream()
        val pes = ByteArrayOutputStream()
        var cc = -1
        var firstCc = -1
        fun flush() {
            val bytes = pes.toByteArray()
            pes.reset()
            if (bytes.size < 9 || bytes[0] != 0.toByte() || bytes[1] != 0.toByte() ||
                bytes[2] != 1.toByte() || bytes[3].toInt() and 0xf0 != 0xe0) return
            val start = 9 + (bytes[8].toInt() and 255)
            val size = ((bytes[4].toInt() and 255) shl 8) + (bytes[5].toInt() and 255)
            val end = if (size == 0) bytes.size else minOf(bytes.size, 6 + size)
            if (end > start) es.write(bytes, start, end - start)
        }
        for (p in 0..ts.size - 188 step 188) {
            val at = payload(ts, p, pid)
            if (at < 0) continue
            val counter = ts[p + 3].toInt() and 15
            if (firstCc < 0) firstCc = counter
            val discontinuity = ts[p + 3].toInt() and 32 != 0 && ts[p + 4] != 0.toByte() && ts[p + 5].toInt() and 128 != 0
            if (cc == counter && !discontinuity) continue
            if (cc >= 0 && (discontinuity || counter != ((cc + 1) and 15))) {
                flush()
                parameterSets(es.toByteArray(), pid, firstCc)?.let { return it }
                es.reset()
            }
            cc = counter
            if (ts[p + 1].toInt() and 64 != 0) flush() else if (pes.size() == 0) continue
            pes.write(ts, at, p + 188 - at)
        }
        flush()
        return parameterSets(es.toByteArray(), pid, firstCc)
    }

    private fun parameterSets(bytes: ByteArray, pid: Int, firstCc: Int): Avc? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var nal = -1
        var zeros = 0
        for (i in bytes.indices) {
            if (bytes[i] == 0.toByte()) { zeros++; continue }
            if (bytes[i] == 1.toByte() && zeros >= 2 && i + 1 < bytes.size) {
                val end = i - zeros
                if (nal >= 0 && end > nal) when (bytes[nal].toInt() and 31) {
                    7 -> sps = bytes.copyOfRange(nal, end)
                    8 -> pps = bytes.copyOfRange(nal, end)
                }
                nal = i + 1
                if (bytes[nal].toInt() and 31 == 5 && sps != null && pps != null &&
                    sps.size >= 4 && pps.size >= 2 && sps.size + pps.size <= 65524)
                    return Avc(pid, sps, pps, firstCc)
            }
            zeros = 0
        }
        return null
    }

    class Avc(private val pid: Int, sps: ByteArray, pps: ByteArray, firstCc: Int) {
        val codec = String.format(Locale.ROOT, "avc1.%02x%02x%02x", sps[1], sps[2], sps[3])
        private val pes = run {
            val length = 3 + 8 + sps.size + pps.size
            val startCode = byteArrayOf(0, 0, 0, 1)
            byteArrayOf(0, 0, 1, 0xe0.toByte(), (length ushr 8).toByte(), length.toByte(),
                0x80.toByte(), 0, 0) + startCode + sps + startCode + pps
        }

        private data class Boundary(val offset: Long, val cc: Int)
        private val boundaries = ConcurrentHashMap<Long, Boundary>().apply { put(0, Boundary(0, firstCc)) }

        // Inserting another video PES in a split source PES would truncate a picture
        // on sequential playback. Share the same nearby PES cut on both sides.
        private fun boundary(offset: Long, size: Long, read: (Long, Int) -> ByteArray): Boundary {
            if (offset == size) return Boundary(size, 0)
            boundaries[offset]?.let { return it }
            var position = offset
            var cut = offset
            // Sparse video or an audio-only tail must not turn a seek into a scan to EOF.
            val limit = offset + minOf(size - offset, HLS_TARGET_PACKETS * 188L)
            var nextCc = 0
            while (position < limit) {
                val length = minOf(188L * 256, limit - position).toInt()
                val bytes = read(position, length)
                require(bytes.size == length)
                for (p in bytes.indices step 188) {
                    if (payload(bytes, p, pid) < 0) continue
                    if (bytes[p + 1].toInt() and 64 != 0) {
                        return Boundary(cut, bytes[p + 3].toInt() and 15).also { boundaries[offset] = it }
                    }
                    nextCc = (bytes[p + 3] + 1) and 15
                    cut = position + p + 188
                }
                position += length
            }
            return Boundary(cut, nextCc).also { boundaries[offset] = it }
        }

        // A bounded, timestamp-free video PES carries only decoder configuration.
        // End one counter before the first source payload and stuff via adaptation fields.
        private fun packets(nextCc: Int): ByteArray {
            val count = (pes.size + 2 + 183) / 184
            val ts = ByteArray(count * 188) { 0xff.toByte() }
            var at = 0
            for (i in 0 until count) {
                val p = i * 188
                val n = minOf(pes.size - at, if (i == 0) 182 else 184)
                val pad = 184 - n
                ts[p] = 0x47
                ts[p + 1] = ((pid ushr 8) or if (i == 0) 64 else 0).toByte()
                ts[p + 2] = pid.toByte()
                ts[p + 3] = ((if (pad > 0) 0x30 else 0x10) or ((nextCc - count + i) and 15)).toByte()
                if (pad > 0) ts[p + 4] = (pad - 1).toByte()
                if (pad > 1) ts[p + 5] = if (i == 0) 0x80.toByte() else 0
                pes.copyInto(ts, p + 188 - n, at, at + n)
                at += n
            }
            return ts
        }

        private val prefixes = Array(16) { packets(it) }
        fun videoSegment(segment: BluRayHls.Segment, psi: ByteArray, size: Long, read: (Long, Int) -> ByteArray):
            Pair<BluRayHls.Segment, ByteArray> {
            val first = boundary(segment.start, size, read)
            val end = boundary(segment.end, size, read)
            return segment.copy(start = first.offset, end = end.offset) to (psi + prefixes[first.cc])
        }
    }

    /** Physical ranges for the block containing this segment and the next two blocks. */
    fun readAhead(position: Long, rawSize: Long): LongRange {
        val block = IsoChunkCache.BLOCK_BYTES.toLong()
        val start = position / 188 * 192 / block * block
        return start until minOf(rawSize, start + 3 * block)
    }

    fun ac3Segment(segment: BluRayHls.Segment, dtsPrefix: ByteArray, ac3Prefix: ByteArray,
                   pid: Int, size: Long, dir: java.io.File, session: IsoReadSession,
                   read: (Long, Int) -> ByteArray): ByteArray {
        val dts = segment.audioBytes(dtsPrefix, pid, completePesUntil = size, read = read)
        if (dts.size == dtsPrefix.size) return ac3Prefix
        return FfmpegBridge.ac3(dts, ac3Prefix, pid, dir, session)
    }

    fun profile(head: ByteArray, tail: ByteArray, label: String, rawSize: Long = 0, originalLanguage: String = ""): BluRayProfile {
        require(matches(head))
        val ts = transportPackets(head)
        val inferredLang = inferLanguageCodeFromName(label).ifBlank {
            runCatching { java.util.Locale.forLanguageTag(originalLanguage).isO3Language }.getOrDefault("")
        }
        var firstAudio = true
        val program = BluRayPsi.program(ts)
        val probed = DiscAudio.inspectM2ts(head, program?.streams.orEmpty())
        val missing = probed.filter { it.audio && it.layout.isEmpty() }
        val tailAudio = if (missing.isEmpty()) emptyMap() else DiscAudio.inspectM2ts(tail, missing)
            .filter { it.layout.isNotEmpty() }.associateBy { it.pid }
        val streams = probed.map { fromHead ->
            val stream = tailAudio[fromHead.pid] ?: fromHead
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
            streams.filter { it.audio }.map { BluRayAudio(it.pid, it.type, it.language) }, streams, label, pcrIndex = index, primaryLanguageHint = inferredLang).also { profile ->
            profile.program = program
            profile.avc = profile.mainAvcPid?.let { avcConfig(ts, it) }
        }
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

    /** Freeze the PCR samples when constructing a VOD playlist. Both renditions use this map. */
    @Synchronized internal fun playlistClock(size: Long): (Long) -> Long {
        val times = java.util.TreeMap<Long, Long>()
        anchors.forEach { (us, pos) -> if (us < spanUs) times[pos] = us }
        times[size] = spanUs
        return { position ->
            val a = checkNotNull(times.floorEntry(position))
            val b = times.higherEntry(position)
            if (b == null) a.value else a.value +
                ((position - a.key).toDouble() / (b.key - a.key) * (b.value - a.value)).toLong()
        }
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
