package com.gdstream

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.*
import java.io.ByteArrayOutputStream
import java.io.IOException

internal object DvdProxy {
    private external fun hasDecoder(): Boolean
    private external fun open(encoder: String, color: Int, width: Int, height: Int, fps: Double): Long
    private external fun encode(handle: Long, bytes: ByteArray?, ptsUs: Long, mux: DvdTsMux)
    private external fun close(handle: Long)

    fun canDecodeMpeg2(info: Map<String, Any>?): Boolean {
        val width = (info?.get("width") as? Number)?.toInt() ?: 720
        val height = (info?.get("height") as? Number)?.toInt() ?: 480
        val format = android.media.MediaFormat.createVideoFormat("video/mpeg2", width, height)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
    }

    data class Encoder(val name: String, val color: Int)
    fun encoder(width: Int, height: Int): Encoder? {
        if (!runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion()?.startsWith("Lavc60.") == true && hasDecoder() }.getOrDefault(false)) return null
        for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!codec.isEncoder || !codec.supportedTypes.contains("video/avc")) continue
            if (if (Build.VERSION.SDK_INT >= 29) !codec.isHardwareAccelerated else
                codec.name.startsWith("OMX.google.") || codec.name.startsWith("c2.android.")) continue
            val caps = codec.getCapabilitiesForType("video/avc")
            if (caps.videoCapabilities?.isSizeSupported(width, height) != true) continue
            val color = listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar).firstOrNull { it in caps.colorFormats }
            if (color != null) return Encoder(codec.name, color)
        }
        return null
    }

    data class Segment(val start: Long, val end: Long, val durationUs: Long)
    fun playlist(segments: List<Segment>) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${(segments.maxOf { it.durationUs } + 999999) / 1000000}\n")
        segments.forEachIndexed { index, segment ->
            if (index > 0) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:${java.math.BigDecimal.valueOf(segment.durationUs, 6)},\ndvd_$index.ts\n")
        }
        append("#EXT-X-ENDLIST\n")
    }.toByteArray()

    fun transcode(data: ByteArray, info: Map<String, Any>, encoder: Encoder, session: IsoReadSession): ByteArray {
        val width = (info.getValue("width") as Number).toInt()
        val height = (info.getValue("height") as Number).toInt()
        val handle = open(encoder.name, encoder.color, width, height, (info.getValue("frameRate") as Number).toDouble())
        if (handle == 0L) throw IOException("DVD AVC encoder initialization failed")
        val mux = DvdTsMux()
        try {
            demux(data, (info["ac3Substream"] as? Number)?.toInt() ?: -1) { video, pts, sample ->
                session.checkActive()
                if (video) encode(handle, sample, pts, mux) else mux.audio(pts, sample)
            }
            encode(handle, null, 0, mux)
            return mux.finish()
        } finally { close(handle) }
    }

    /** DVD packs are sector aligned. Deliver only elementary payload to the frame readers.
     * A private-stream PES can start with the tail of the preceding AC-3 frame. */
    fun demux(data: ByteArray, audioId: Int, sample: (Boolean, Long, ByteArray) -> Unit) {
        val output = object : ExtractorOutput {
            override fun endTracks() {}
            override fun seekMap(map: SeekMap) {}
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                val buffer = ByteArrayOutputStream()
                override fun format(format: Format) {}
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                    throw IOException("Unexpected streaming sample")
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    buffer.write(data.data, data.position, length)
                    data.skipBytes(length)
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    val bytes = buffer.toByteArray()
                    val end = bytes.size - offset
                    sample(type == C.TRACK_TYPE_VIDEO, timeUs, bytes.copyOfRange(end - size, end))
                    buffer.reset()
                    buffer.write(bytes, end, offset)
                }
            }
        }
        val video = H262Reader("video/mpeg")
        val audio = Ac3Reader(null, 0, "video/mpeg")
        video.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
        audio.createTracks(output, TsPayloadReader.TrackIdGenerator(1, 1))
        var selectedAudio = audioId
        forEachPes(data) { id, start, end, pts ->
            var payload = start
            if (id == 0xbd && payload + 4 <= end && selectedAudio < 0 && (data[payload].toInt() and 255) in 0x80..0x87)
                selectedAudio = data[payload].toInt() and 255
            if (id == 0xe0 || payload + 4 <= end && (data[payload].toInt() and 255) == selectedAudio) {
                val reader: ElementaryStreamReader = if (id == 0xe0) video else audio
                if (id == 0xbd) payload += 4
                reader.packetStarted(pts, 0)
                reader.consume(ParsableByteArray(data, end).apply { position = payload })
                reader.packetFinished(false)
            }
        }
        video.packetFinished(true)
        audio.packetFinished(true)
    }

    private fun forEachPes(data: ByteArray, visit: (Int, Int, Int, Long) -> Unit) {
        fun u(p: Int) = data[p].toInt() and 255
        for (sector in 0 until data.size - 13 step 2048) {
            val limit = minOf(data.size, sector + 2048)
            var p = sector
            while (p + 6 <= limit && u(p) == 0 && u(p + 1) == 0 && u(p + 2) == 1) {
                val id = u(p + 3)
                if (id == 0xba && p + 14 <= limit) { p += 14 + (u(p + 13) and 7); continue }
                val end = p + 6 + (u(p + 4) shl 8) + u(p + 5)
                if (end > limit || end <= p) break
                if ((id == 0xe0 || id == 0xbd) && p + 9 <= end) {
                    val payload = p + 9 + u(p + 8)
                    val pts = if (u(p + 7) and 128 != 0 && p + 14 <= payload && payload <= end) {
                        val t = p + 9
                        val ticks = ((u(t).toLong() and 14) shl 29) or (u(t + 1).toLong() shl 22) or
                            ((u(t + 2).toLong() and 254) shl 14) or (u(t + 3).toLong() shl 7) or (u(t + 4) ushr 1).toLong()
                        ticks * 1000000 / 90000
                    } else C.TIME_UNSET
                    if (payload < end) visit(id, payload, end, pts)
                }
                p = end
            }
        }
    }

    data class Standalone(val info: Map<String, Any>, val segments: List<Segment>)

    fun standalone(head: ByteArray, tail: ByteArray, size: Long): Standalone? {
        var first = C.TIME_UNSET
        var last = C.TIME_UNSET
        var width = 0
        var height = 0
        var audioId = -1
        var audioFormat = DiscAudio()
        forEachPes(head) { id, start, end, pts ->
            if (id == 0xe0) {
                if (first == C.TIME_UNSET && pts != C.TIME_UNSET) first = pts
                if (width == 0) for (p in start until end - 7) {
                    if (head[p] != 0.toByte() || head[p+1] != 0.toByte() || head[p+2] != 1.toByte() || head[p+3] != 0xb3.toByte()) continue
                    width = ((head[p+4].toInt() and 255) shl 4) or ((head[p+5].toInt() and 255) ushr 4)
                    height = ((head[p+5].toInt() and 15) shl 8) or (head[p+6].toInt() and 255)
                    break
                }
            } else if (start + 4 < end && audioId < 0 && (head[start].toInt() and 255) in 0x80..0x87) {
                audioId = head[start].toInt() and 255
                audioFormat = DiscAudio.probe(head.copyOfRange(start + 4, end), 0x81)
            }
        }
        forEachPes(tail) { id, _, _, pts -> if (id == 0xe0 && pts != C.TIME_UNSET) last = pts }
        if (first == C.TIME_UNSET || last == C.TIME_UNSET || height !in listOf(480, 576) || width !in listOf(352, 704, 720)) return null
        val span = last - first
        val duration = if (span < 0) span + (1L shl 33) * 1000000 / 90000 else span
        if (duration <= 0) return null
        val streams = mutableListOf(BluRayStream(0xe0, 2, format = if (height == 576) 2 else 1, rate = if (height == 576) 3 else 4))
        if (audioId >= 0) streams.add(BluRayStream(audioId, 0x81, layout = audioFormat.layout, kbps = audioFormat.kbps))
        val info = BluRayProfile(duration, emptyList(), emptyList(), streams).mediaInfo(size) +
            mapOf("ac3Substream" to audioId, "width" to width)
        return Standalone(info, estimatedSegments(size, duration))
    }

    fun estimatedSegments(size: Long, duration: Long): List<Segment> {
        val sectors = size / 2048
        if (sectors == 0L || duration <= 0) return emptyList()
        val count = maxOf((duration + 3999999) / 4000000, (size + 6291455) / 6291456).coerceIn(1L, sectors)
        if (count > 100000) return emptyList()
        return (0 until count).map { i ->
            Segment(sectors * i / count * 2048, sectors * (i + 1) / count * 2048, duration * (i + 1) / count - duration * i / count)
        }
    }

    /** Standalone files lack the IFO VOBU map. Resolve the same neighboring boundary
     * on every seek by locating the next navigation pack in a bounded sector-aligned read. */
    fun navBoundary(file: IsoVirtualFile, offset: Long, fetch: (Long, Long) -> ByteArray): Long {
        if (offset == 0L || offset >= file.size / 2048 * 2048) return offset
        val data = file.readRaw(offset, minOf(file.size - offset, 2L * 1024 * 1024).toInt(), fetch)
        for (p in 0 until data.size - 2047 step 2048) {
            if (data[p+3] == 0xba.toByte() && data[p+0x29] == 0xbf.toByte() &&
                data[p+0x403] == 0xbf.toByte()) return offset + p
        }
        return offset
    }

}

/** Each segment starts with PSI and an IDR. PCR uses the video timeline. */
internal class DvdTsMux {
    private data class UnitData(val video: Boolean, val pts: Long, val data: ByteArray)
    private val samples = ArrayList<UnitData>()
    fun video(pts: Long, bytes: ByteArray) { samples.add(UnitData(true, pts, bytes)) }
    fun audio(pts: Long, bytes: ByteArray) { samples.add(UnitData(false, pts, bytes)) }

    fun finish(): ByteArray {
        require(samples.any { it.video }) { "DVD segment produced no AVC frames" }
        val out = ByteArrayOutputStream()
        val cc = IntArray(8192)
        fun packets(pid: Int, data: ByteArray, clock: Long? = null) {
            var pos = 0
            while (pos < data.size) {
                val packet = ByteArray(188) { 0xff.toByte() }
                packet[0] = 0x47
                packet[1] = ((pid shr 8) or if (pos == 0) 64 else 0).toByte()
                packet[2] = pid.toByte()
                val pcr = clock?.takeIf { pos == 0 }
                val count = minOf(data.size - pos, if (pcr != null) 176 else 184)
                val pad = 184 - count
                packet[3] = ((if (pad > 0) 0x30 else 0x10) or (cc[pid]++ and 15)).toByte()
                if (pad > 0) packet[4] = (pad - 1).toByte()
                if (pad > 1) packet[5] = if (pcr != null) 16 else 0
                if (pcr != null) {
                    for (i in 0..3) packet[6 + i] = (pcr ushr (25 - i * 8)).toByte()
                    packet[10] = (((pcr and 1) shl 7) or 126).toByte()
                    packet[11] = 0
                }
                data.copyInto(packet, 188 - count, pos, pos + count)
                out.write(packet)
                pos += count
            }
        }
        fun psi(pid: Int, body: ByteArray) {
            body[2] = (body.size + 1).toByte()
            val crc = ExternalTsPmt.crc(body)
            packets(pid, byteArrayOf(0) + body + ByteArray(4) { (crc ushr (24 - it * 8)).toByte() })
        }
        psi(0, byteArrayOf(0, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xe1.toByte(), 0))
        val pmt = byteArrayOf(2, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 1, 0xf0.toByte(), 0,
            0x1b, 0xe1.toByte(), 1, 0xf0.toByte(), 0)
        psi(256, if (samples.any { !it.video }) pmt + byteArrayOf(0x81.toByte(), 0xe1.toByte(), 2, 0xf0.toByte(), 0) else pmt)
        for (sample in samples.sortedBy { it.pts }) {
            val ticks = sample.pts * 9 / 100
            val size = if (sample.video) 0 else sample.data.size + 8
            val header = byteArrayOf(0, 0, 1, (if (sample.video) 0xe0 else 0xbd).toByte(), (size shr 8).toByte(), size.toByte(),
                0x80.toByte(), 0x80.toByte(), 5, (33 or ((ticks ushr 29).toInt() and 14)).toByte(),
                (ticks ushr 22).toByte(), ((ticks ushr 14) or 1).toByte(), (ticks ushr 7).toByte(), ((ticks shl 1) or 1).toByte())
            packets(if (sample.video) 257 else 258, header + sample.data, ticks.takeIf { sample.video })
        }
        return out.toByteArray()
    }
}
