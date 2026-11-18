package com.gdstream

import java.io.ByteArrayOutputStream
import java.io.IOException

/** CLPI positions already address the continuous 188-byte virtual TS, not physical M2TS bytes. */
internal class BluRayHls private constructor(val segments: List<Segment>) {
    data class Segment(val start: Long, val end: Long, val durationUs: Long) {
        fun size(prefix: ByteArray) = prefix.size + end - start
        fun read(prefix: ByteArray, offset: Long, length: Int, readTs: (Long, Int) -> ByteArray): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= size(prefix))
            val result = ByteArray(length)
            val headerBytes = if (offset < prefix.size) minOf(length, prefix.size - offset.toInt()) else 0
            if (headerBytes > 0) prefix.copyInto(result, 0, offset.toInt(), offset.toInt() + headerBytes)
            if (headerBytes < length) readTs(start + maxOf(0, offset - prefix.size), length - headerBytes).copyInto(result, headerBytes)
            return result
        }
    }
    fun playlist(): ByteArray = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${(segments.maxOf { it.durationUs } + 999999) / 1000000}\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        segments.forEachIndexed { index, segment ->
            append("#EXTINF:${java.math.BigDecimal.valueOf(segment.durationUs, 6).toPlainString()},\nseg_$index.ts\n")
        }
        append("#EXT-X-ENDLIST\n")
    }.toByteArray(Charsets.UTF_8)

    companion object {
        fun create(profile: BluRayProfile?, size: Long): BluRayHls? {
            if (profile == null || profile.durationUs <= 0 || profile.points.size < 2 || size <= 0 || size % 188 != 0L) return null
            val points = profile.points
            if (points.any { it.timeUs < 0 || it.timeUs >= profile.durationUs || it.position < 0 || it.position >= size || it.position % 188 != 0L } ||
                points.zipWithNext().any { (a, b) -> b.timeUs <= a.timeUs || b.position <= a.position }) return null
            val boundaries = arrayListOf(BluRayPoint(0, 0))
            for (point in points) {
                if (point.timeUs - boundaries.last().timeUs >= 9_000_000 && point.position > boundaries.last().position) boundaries.add(point)
            }
            boundaries.add(BluRayPoint(profile.durationUs, size))
            return BluRayHls(boundaries.zipWithNext { a, b -> Segment(a.position, b.position, b.timeUs - a.timeUs) })
        }
    }
}

/** Collect complete PSI sections, including PMTs split over several packets, then packetize them.
 * A fresh segment gets a PAT and external PMT even if its keyframe starts between PSI repeats.
 */
internal object BluRayPsi {
    private fun section(data: ByteArray, pid: Int, table: Int): ByteArray? {
        val collected = ByteArrayOutputStream()
        var expected = -1
        var continuity = -1
        for (p in 0 until data.size - 187 step 188) {
            fun u(i: Int) = data[i].toInt() and 255
            if (u(p) != 0x47 || ((u(p + 1) and 31) shl 8) + u(p + 2) != pid) continue
            if (u(p + 1) and 0x80 != 0 || u(p + 3) and 0xc0 != 0) { collected.reset(); continue }
            if (u(p + 3) and 0x10 == 0) continue
            var at = p + 4 + if (u(p + 3) and 0x20 != 0) 1 + u(p + 4) else 0
            if (at >= p + 188) continue
            val cc = u(p + 3) and 15
            if (u(p + 1) and 0x40 != 0) {
                at += 1 + u(at)
                collected.reset(); expected = -1
                if (at + 3 > p + 188 || u(at) != table) continue
                expected = 3 + ((u(at + 1) and 15) shl 8) + u(at + 2)
                if (expected !in 12..1024) { expected = -1; continue }
            } else if (collected.size() == 0 || cc != ((continuity + 1) and 15)) {
                collected.reset(); expected = -1
                continue
            }
            continuity = cc
            if (expected < 0) continue
            collected.write(data, at, minOf(p + 188 - at, expected - collected.size()))
            if (collected.size() == expected) {
                val result = collected.toByteArray()
                if (ExternalTsPmt.crc(result) == 0) return result
                collected.reset(); expected = -1
            }
        }
        return null
    }
    private fun packets(pid: Int, section: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        var cc = 0
        while (offset < section.size) {
            val packet = ByteArray(188) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = ((pid ushr 8) or if (offset == 0) 0x40 else 0).toByte()
            packet[2] = pid.toByte()
            packet[3] = (0x30 or (cc++ and 15)).toByte()
            packet[4] = 1; packet[5] = if (offset == 0) 0x80.toByte() else 0 // discontinuity
            var start = 6
            if (offset == 0) packet[start++] = 0 // pointer_field
            val count = minOf(188 - start, section.size - offset)
            section.copyInto(packet, start, offset, offset + count)
            offset += count
            out.write(packet)
        }
        return out.toByteArray()
    }
    fun prefix(data: ByteArray): ByteArray? {
        val pat = section(data, 0, 0) ?: return null
        var at = 8
        while (at + 4 <= pat.size - 4) {
            val program = ((pat[at].toInt() and 255) shl 8) + (pat[at + 1].toInt() and 255)
            val pid = ((pat[at + 2].toInt() and 31) shl 8) + (pat[at + 3].toInt() and 255)
            if (program != 0) {
                val pmt = section(data, pid, 2) ?: return null
                val external = ExternalTsPmt.rewrite(pmt) ?: throw IOException("Invalid external PMT")
                return packets(0, pat) + packets(pid, external)
            }
            at += 4
        }
        return null
    }
}
