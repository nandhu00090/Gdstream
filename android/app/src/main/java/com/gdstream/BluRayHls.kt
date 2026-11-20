package com.gdstream

import androidx.media3.extractor.Ac3Util
import java.io.ByteArrayOutputStream
import java.io.IOException

/** CLPI positions already address the continuous 188-byte virtual TS, not physical M2TS bytes. */
internal class BluRayHls private constructor(val segments: List<Segment>, val audio: List<BluRayStream>) {
    data class Segment(val start: Long, val end: Long, val durationUs: Long) {
        fun audioBytes(
            prefix: ByteArray,
            pid: Int,
            streamType: Int = 0,
            read: (Long, Int) -> ByteArray
        ): ByteArray {
            if (streamType == 0x83) {
                return extractAc3CoreSegment(prefix, pid, read)
            }
            val out = ByteArrayOutputStream()
            out.write(prefix)
            var position = start
            var lastCc = 0
            while (position < end) {
                val chunk = read(position, minOf(end - position, 188L * 4096).toInt())
                require(chunk.isNotEmpty() && chunk.size % 188 == 0)
                for (p in chunk.indices step 188) {
                    val packetPid = ((chunk[p + 1].toInt() and 31) shl 8) or (chunk[p + 2].toInt() and 255)
                    if (packetPid == pid) {
                        val pusi = (chunk[p + 1].toInt() and 0x40) != 0
                        if (pusi) {
                            val pts = parsePtsFromTsPacket(chunk, p)
                            if (pts >= 0L) {
                                // Emit a spec-compliant adaptation-only PCR packet on audioPid
                                // (CC does not increment for adaptation-only packets).
                                out.write(pcrPacket(pid, lastCc, pts))
                            }
                        }
                        lastCc = chunk[p + 3].toInt() and 15
                        out.write(chunk, p, 188)
                    }
                }
                position += chunk.size
            }
            return out.toByteArray()
        }

        /** Extract only embedded AC-3 core frames (0x0B77) from Blu-ray TrueHD (0x83) PID and
         * re-packetize as clean stream_id=0xBD PES with valid PCR on audioPid. */
        private fun extractAc3CoreSegment(
            prefix: ByteArray,
            pid: Int,
            read: (Long, Int) -> ByteArray
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(prefix)
            var position = start
            var cc = 0
            val pesBuf = ByteArrayOutputStream()
            var pesPts = -1L

            fun flushPes() {
                if (pesBuf.size() < 9) { pesBuf.reset(); return }
                val raw = pesBuf.toByteArray()
                pesBuf.reset()
                if (raw[0] != 0.toByte() || raw[1] != 0.toByte() || raw[2] != 1.toByte()) return
                val headerLen = raw[8].toInt() and 255
                val payloadStart = 9 + headerLen
                if (payloadStart >= raw.size) return
                // Scan payload for AC-3 syncword (0x0B77) and emit only valid AC-3 frames
                val ac3Frames = ByteArrayOutputStream()
                var i = payloadStart
                while (i + 7 <= raw.size) {
                    if (raw[i] == 0x0b.toByte() && raw[i + 1] == 0x77.toByte()) {
                        val bsid = (raw[i + 5].toInt() and 255) ushr 3
                        if (bsid <= 10) {
                            val slice = raw.copyOfRange(i, minOf(raw.size, i + 8))
                            val frameSize = Ac3Util.parseAc3SyncframeSize(slice)
                            if (frameSize > 0 && i + frameSize <= raw.size) {
                                ac3Frames.write(raw, i, frameSize)
                                i += frameSize
                                continue
                            }
                        }
                    }
                    i++
                }
                val ac3 = ac3Frames.toByteArray()
                if (ac3.isEmpty()) return
                val pts = if (pesPts >= 0L) pesPts else 0L
                val pesSize = ac3.size + 8
                val header = byteArrayOf(
                    0, 0, 1, 0xbd.toByte(),
                    (pesSize ushr 8).toByte(), pesSize.toByte(),
                    0x80.toByte(), 0x80.toByte(), 5,
                    (0x21 or ((pts ushr 29).toInt() and 0x0e)).toByte(),
                    (pts ushr 22).toByte(),
                    (((pts ushr 14) and 0xfe) or 1).toByte(),
                    (pts ushr 7).toByte(),
                    (((pts shl 1) and 0xfe) or 1).toByte()
                )
                val fullPes = header + ac3
                var pos = 0
                while (pos < fullPes.size) {
                    val pkt = ByteArray(188) { 0xff.toByte() }
                    pkt[0] = 0x47
                    pkt[1] = ((pid ushr 8) or if (pos == 0) 0x40 else 0).toByte()
                    pkt[2] = pid.toByte()
                    val writePcr = pos == 0
                    val count = minOf(fullPes.size - pos, if (writePcr) 176 else 184)
                    val pad = 184 - count
                    pkt[3] = ((if (pad > 0) 0x30 else 0x10) or (cc++ and 15)).toByte()
                    if (pad > 0) pkt[4] = (pad - 1).toByte()
                    if (pad > 1) pkt[5] = if (writePcr) 0x10 else 0
                    if (writePcr) {
                        for (b in 0..3) pkt[6 + b] = (pts ushr (25 - b * 8)).toByte()
                        pkt[10] = (((pts and 1) shl 7) or 0x7e).toByte()
                        pkt[11] = 0
                    }
                    fullPes.copyInto(pkt, 188 - count, pos, pos + count)
                    out.write(pkt)
                    pos += count
                }
            }

            while (position < end) {
                val chunk = read(position, minOf(end - position, 188L * 4096).toInt())
                require(chunk.isNotEmpty() && chunk.size % 188 == 0)
                for (p in chunk.indices step 188) {
                    val packetPid = ((chunk[p + 1].toInt() and 31) shl 8) or (chunk[p + 2].toInt() and 255)
                    if (packetPid != pid) continue
                    if ((chunk[p + 3].toInt() and 0x10) == 0) continue
                    val pusi = (chunk[p + 1].toInt() and 0x40) != 0
                    if (pusi) {
                        flushPes()
                        pesPts = parsePtsFromTsPacket(chunk, p)
                    }
                    val payStart = p + 4 + if ((chunk[p + 3].toInt() and 0x20) != 0) 1 + (chunk[p + 4].toInt() and 255) else 0
                    if (payStart < p + 188) pesBuf.write(chunk, payStart, p + 188 - payStart)
                }
                position += chunk.size
            }
            flushPes()
            return out.toByteArray()
        }

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

    fun master(): ByteArray = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        audio.forEachIndexed { i, track ->
            val language = track.language.takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } && it != "und" } ?: "und"
            val langName = bluRayLanguage(language)
            val codecLabel = if (track.type == 0x83) "AC-3" else track.codec
            val displayName = if (langName == "Unknown") {
                "Audio ${i + 1} • $codecLabel"
            } else {
                "$langName ($codecLabel, ${i + 1})"
            }
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"$displayName\",")
            append("LANGUAGE=\"$language\",DEFAULT=${if (i == 0) "YES" else "NO"},AUTOSELECT=YES,URI=\"audio_${track.pid}.m3u8\"\n")
        }
        val peak = segments.maxOf { ((it.end - it.start) * 8.0e6 / it.durationUs).toLong() }.coerceAtLeast(1)
        append("#EXT-X-STREAM-INF:BANDWIDTH=$peak")
        if (audio.isNotEmpty()) append(",AUDIO=\"audio\"")
        append("\nvideo.m3u8\n")
    }.toByteArray()

    fun playlist(audioPid: Int? = null): ByteArray = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${(segments.maxOf { it.durationUs } + 999999) / 1000000}\n")
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        segments.forEachIndexed { index, segment ->
            append("#EXTINF:${java.math.BigDecimal.valueOf(segment.durationUs, 6).toPlainString()},\n${if (audioPid == null) "" else "audio_${audioPid}_"}seg_$index.ts\n")
        }
        append("#EXT-X-ENDLIST\n")
    }.toByteArray(Charsets.UTF_8)

    companion object {
        // Never expose HDMV LPCM (0x80) as an HLS EXT-X-MEDIA TS track: Media3 DefaultTsPayloadReaderFactory
        // cannot parse 0x80 in TS and would emit 0 tracks (crashing Just Player).
        private val HLS_SUPPORTED_AUDIO_TYPES = setOf(0x03, 0x04, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0xa1, 0xa2)

        private fun parsePtsFromTsPacket(chunk: ByteArray, p: Int): Long {
            fun u(i: Int) = chunk[i].toInt() and 255
            if ((u(p + 3) and 0x10) == 0) return -1L
            val pes = p + 4 + if ((u(p + 3) and 0x20) != 0) 1 + u(p + 4) else 0
            if (pes + 14 > p + 188 || u(pes) != 0 || u(pes + 1) != 0 || u(pes + 2) != 1) return -1L
            if ((u(pes + 7) and 0x80) == 0 || u(pes + 8) < 5) return -1L
            val t = pes + 9
            return ((u(t).toLong() and 0x0e) shl 29) or
                (u(t + 1).toLong() shl 22) or
                ((u(t + 2).toLong() and 0xfe) shl 14) or
                (u(t + 3).toLong() shl 7) or
                (u(t + 4) ushr 1).toLong()
        }

        private fun pcrPacket(pid: Int, cc: Int, pcr90k: Long): ByteArray {
            val pkt = ByteArray(188) { 0xff.toByte() }
            pkt[0] = 0x47
            pkt[1] = ((pid ushr 8) and 0x1f).toByte()
            pkt[2] = pid.toByte()
            pkt[3] = (0x20 or (cc and 15)).toByte() // adaptation-only
            pkt[4] = 183.toByte()
            pkt[5] = 0x10 // PCR_flag
            for (b in 0..3) pkt[6 + b] = (pcr90k ushr (25 - b * 8)).toByte()
            pkt[10] = (((pcr90k and 1) shl 7) or 0x7e).toByte()
            pkt[11] = 0
            return pkt
        }

        fun create(profile: BluRayProfile?, size: Long, standalone: Boolean = false): BluRayHls? {
            if (profile == null || profile.durationUs <= 0 || size <= 0 || size % 188 != 0L) return null
            val supportedAudio = profile.streams.filter { it.audio && it.type in HLS_SUPPORTED_AUDIO_TYPES }.distinctBy { it.pid }
            if (standalone && profile.points.isEmpty()) {
                val packets = size / 188
                val count = minOf(packets, (profile.durationUs + 3_999_999) / 4_000_000).toInt()
                val segments = (0 until count).map { i ->
                    val start = (packets / count * i + packets % count * i / count) * 188
                    val end = (packets / count * (i + 1) + packets % count * (i + 1) / count) * 188
                    Segment(start, end, profile.durationUs * (i + 1) / count - profile.durationUs * i / count)
                }
                return BluRayHls(segments, supportedAudio)
            }
            if (profile.points.size < 2) return null
            val points = profile.points
            if (points.any { it.timeUs < 0 || it.timeUs >= profile.durationUs || it.position < 0 || it.position >= size || it.position % 188 != 0L } ||
                points.zipWithNext().any { (a, b) -> b.timeUs <= a.timeUs || b.position <= a.position }) return null
            val boundaries = arrayListOf(BluRayPoint(0, 0))
            for (point in points) {
                if (point.timeUs - boundaries.last().timeUs >= 4_000_000 && point.position > boundaries.last().position) boundaries.add(point)
            }
            boundaries.add(BluRayPoint(profile.durationUs, size))
            return BluRayHls(boundaries.zipWithNext { a, b -> Segment(a.position, b.position, b.timeUs - a.timeUs) }, supportedAudio)
        }
    }
}

/** Collect complete PSI sections, including PMTs split over several packets, then packetize them. */
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
            packet[4] = 1; packet[5] = if (offset == 0) 0x80.toByte() else 0
            var start = 6
            if (offset == 0) packet[start++] = 0
            val count = minOf(188 - start, section.size - offset)
            section.copyInto(packet, start, offset, offset + count)
            offset += count
            out.write(packet)
        }
        return out.toByteArray()
    }
    class Program(private val pat: ByteArray, private val pid: Int, private val pmt: ByteArray) {
        val streams by lazy { elementaryStreams(pmt) }
        fun prefix(streams: List<BluRayStream>, audioPid: Int?, videoOnly: Boolean): ByteArray {
            val external = ExternalTsPmt.rewrite(pmt, streams, audioPid, videoOnly = videoOnly)
                ?: throw IOException("Invalid external PMT")
            return packets(0, pat) + packets(pid, external)
        }
    }
    fun program(data: ByteArray): Program? {
        val pat = section(data, 0, 0) ?: return null
        var at = 8
        while (at + 4 <= pat.size - 4) {
            val program = ((pat[at].toInt() and 255) shl 8) + (pat[at + 1].toInt() and 255)
            val pid = ((pat[at + 2].toInt() and 31) shl 8) + (pat[at + 3].toInt() and 255)
            if (program != 0) {
                val pmt = section(data, pid, 2) ?: return null
                return Program(pat, pid, pmt)
            }
            at += 4
        }
        return null
    }

    fun streams(data: ByteArray) = program(data)?.streams.orEmpty()

    private fun elementaryStreams(pmt: ByteArray): List<BluRayStream> {
        fun u(p: Int) = pmt[p].toInt() and 255
        val result = mutableListOf<BluRayStream>()
        var p = 12 + ((u(10) and 15) shl 8) + u(11)
        while (p + 5 <= pmt.size - 4) {
            val end = p + 5 + ((u(p + 3) and 15) shl 8) + u(p + 4)
            if (end > pmt.size - 4) break
            var language = ""
            var at = p + 5
            while (at + 2 <= end) {
                val length = u(at + 1)
                if (at + 2 + length > end) break
                if (u(at) == 10 && length >= 4) language = String(pmt, at + 2, 3, Charsets.US_ASCII)
                at += 2 + length
            }
            result.add(BluRayStream(((u(p + 1) and 31) shl 8) or u(p + 2), u(p), language = language))
            p = end
        }
        return result
    }

    fun prefix(data: ByteArray, streams: List<BluRayStream> = emptyList(), audioPid: Int? = null,
               videoOnly: Boolean = false): ByteArray? = program(data)?.prefix(streams, audioPid, videoOnly)
}
