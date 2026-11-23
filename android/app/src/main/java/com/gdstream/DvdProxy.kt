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
    private external fun encode(handle: Long, bytes: ByteArray?, pts90k: Long, mux: DvdTsMux, boundary: Boolean, last: Boolean)
    private external fun close(handle: Long)

    fun canDecodeMpeg2(info: Map<String, Any>?): Boolean {
        val width = (info?.get("width") as? Number)?.toInt() ?: 720
        val height = (info?.get("height") as? Number)?.toInt() ?: 480
        val format = android.media.MediaFormat.createVideoFormat("video/mpeg2", width, height)
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
    }

    data class Encoder(val name: String, val color: Int)
    fun encoder(width: Int, height: Int): Encoder? {
        if (!runCatching { hasDecoder() }.getOrDefault(false)) return null
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        var softwareFallback: Encoder? = null
        for (codec in codecs) {
            if (!codec.isEncoder || !codec.supportedTypes.contains("video/avc")) continue
            val isHw = if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated else
                !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
            val caps = codec.getCapabilitiesForType("video/avc")
            if (caps.videoCapabilities?.isSizeSupported(width, height) != true) continue
            val color = listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar).firstOrNull { it in caps.colorFormats }
            if (color != null) {
                if (isHw) return Encoder(codec.name, color)
                if (softwareFallback == null) softwareFallback = Encoder(codec.name, color)
            }
        }
        return softwareFallback
    }

    data class Segment(val start: Long, val end: Long, val durationUs: Long, val discontinuity: Boolean = false)
    fun playlist(segments: List<Segment>) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-TARGETDURATION:${(segments.maxOf { it.durationUs } + 999999) / 1000000}\n")
        segments.forEachIndexed { index, segment ->
            if (index > 0 && segment.discontinuity) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:${java.math.BigDecimal.valueOf(segment.durationUs, 6)},\ndvd_$index.ts\n")
        }
        append("#EXT-X-ENDLIST\n")
    }.toByteArray()

    // Android hardware may expose a single AVC encoder. Keep the active stream warm
    // until another source or a non-sequential request takes ownership of it.
    private class Warm(val id: String, var next: Int, val handle: Long, val mux: DvdTsMux) {
        val audio = audioReader(mux::audio)
    }
    @Volatile private var warm: Warm? = null
    private val cleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "DVDCodecCleanup").apply { isDaemon = true }
    }
    private var idleClose: java.util.concurrent.ScheduledFuture<*>? = null

    fun stop(streamId: String? = null) {
        val owner = warm ?: return
        if (streamId != null && owner.id != streamId) return
        cleanup.execute { synchronized(this) { if (warm === owner) release() } }
    }

    private fun release() {
        val current = warm ?: return
        idleClose?.cancel(false)
        warm = null
        close(current.handle)
    }

    @Synchronized fun transcode(data: ByteArray, info: Map<String, Any>, encoder: Encoder,
                  session: IsoReadSession, streamId: String, segment: Int, boundary: Boolean, last: Boolean,
                  sequenceHeader: () -> ByteArray?): ByteArray {
        if (warm?.id != streamId || warm?.next != segment) release()
        fun convert(finish: Boolean): ByteArray {
            val fresh = warm == null
            val state = warm ?: run {
                val handle = open(encoder.name, encoder.color, (info.getValue("width") as Number).toInt(),
                    (info.getValue("height") as Number).toInt(), (info.getValue("frameRate") as Number).toDouble())
                if (handle == 0L) throw IOException("DVD AVC encoder initialization failed")
                Warm(streamId, segment, handle, DvdTsMux((info["audioLanguageCode"] as? String).orEmpty())).also { warm = it }
            }
            state.mux.beginSegment()
            val header = if (fresh) sequenceHeader else { -> null }
            demux(data, (info["ac3Substream"] as? Number)?.toInt() ?: -1, header, state.audio) { pts, sample ->
                session.checkActive()
                encode(state.handle, sample, pts, state.mux, false, false)
            }
            encode(state.handle, null, -1, state.mux, boundary, finish)
            val frames = state.mux.frames
            val result = state.mux.finish()
            state.next = segment + 1
            if (finish) release() else {
                idleClose?.cancel(false)
                idleClose = cleanup.schedule({
                    synchronized(this) { if (warm === state && state.next == segment + 1) release() }
                }, 30, java.util.concurrent.TimeUnit.SECONDS)
            }
            DebugLogger.log("DVD_SEG_OK", "seg=$segment frames=$frames bytes=${result.size} warm=${!fresh}")
            return result
        }
        val wasWarm = warm != null
        try {
            return try { convert(last) } catch (error: IOException) {
                release()
                session.checkActive()
                if (last && !wasWarm) throw error
                // Some vendor encoders buffer the last picture until EOS or ignore request-sync.
                convert(true)
            }
        } catch (error: Throwable) { release(); throw error }
    }

    fun sequenceHeader(head: ByteArray): ByteArray? {
        val es = ByteArrayOutputStream()
        forEachPes(head) { id, start, end, _ -> if (id == 0xe0) es.write(head, start, end - start) }
        val bytes = es.toByteArray()
        var sequence = -1
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] != 0.toByte() || bytes[i+1] != 0.toByte() || bytes[i+2] != 1.toByte()) continue
            when (bytes[i+3].toInt() and 255) {
                0xb3 -> sequence = i
                0xb8, 0 -> if (sequence >= 0) return bytes.copyOfRange(sequence, i)
            }
        }
        return null
    }

    private fun audioReader(emit: (Long, ByteArray) -> Unit): Ac3Reader {
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
                    emit(timeUs, bytes.copyOfRange(end - size, end))
                    buffer.reset()
                    buffer.write(bytes, end, offset)
                }
            }
        }
        return Ac3Reader(null, 0, "video/mpeg").apply { createTracks(output, TsPayloadReader.TrackIdGenerator(1, 1)) }
    }

    /** DVD packs are sector aligned. Deliver only elementary payload to the frame readers.
     * A private-stream PES can start with the tail of the preceding AC-3 frame. */
    fun demux(data: ByteArray, audioId: Int, sequenceHeader: () -> ByteArray? = { null },
              audio: Ac3Reader? = null, video: (Long, ByteArray) -> Unit) {
        var firstVideo = true
        var selectedAudio = audioId
        forEachPes(data) { id, start, end, pts ->
            var payload = start
            if (id == 0xbd && payload + 4 <= end && selectedAudio < 0 && (data[payload].toInt() and 255) in 0x80..0x87)
                selectedAudio = data[payload].toInt() and 255
            if (id == 0xe0) {
                if (firstVideo) {
                    firstVideo = false
                    val hasHeader = end - start >= 4 && data[start] == 0.toByte() && data[start + 1] == 0.toByte() &&
                        data[start + 2] == 1.toByte() && data[start + 3] == 0xb3.toByte()
                    if (!hasHeader) sequenceHeader()?.let { video(C.TIME_UNSET, it) }
                }
                // libmpeg2 associates a PES tag with the next picture start and reorders it
                // along with that picture. H262Reader would synthesize timestamps here.
                video(pts, data.copyOfRange(start, end))
            } else if (audio != null && payload + 4 <= end && (data[payload].toInt() and 255) == selectedAudio) {
                val pointer = ((data[payload + 2].toInt() and 255) shl 8) or (data[payload + 3].toInt() and 255)
                payload += 4
                // DVD's one-based pointer locates the first new AC-3 access unit. Finish
                // any previous frame before assigning the new PES presentation time.
                val firstAccessUnit = if (pointer > 0) minOf(end, payload + pointer - 1) else end
                if (payload < firstAccessUnit) audio.consume(ParsableByteArray(data, firstAccessUnit).apply { position = payload })
                payload = firstAccessUnit
                if (payload == end) return@forEachPes
                audio.packetStarted(if (pts == C.TIME_UNSET) pts else pts * 1000 / 90, 0)
                audio.consume(ParsableByteArray(data, end).apply { position = payload })
                audio.packetFinished(false)
            }
        }
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
                        ((u(t).toLong() and 14) shl 29) or (u(t + 1).toLong() shl 22) or
                            ((u(t + 2).toLong() and 254) shl 14) or (u(t + 3).toLong() shl 7) or (u(t + 4) ushr 1).toLong()
                    } else C.TIME_UNSET
                    if (payload < end) visit(id, payload, end, pts)
                }
                p = end
            }
        }
    }

    data class Standalone(val info: Map<String, Any>, val segments: List<Segment>)

    fun standalone(head: ByteArray, tail: ByteArray, size: Long, fileName: String = ""): Standalone? {
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
        val duration = (span and 0x1ffffffffL) * 1000 / 90
        if (duration <= 0) return null
        val inferredLang = inferLanguageCodeFromName(fileName)
        val streams = mutableListOf(BluRayStream(0xe0, 2, format = if (height == 576) 2 else 1, rate = if (height == 576) 3 else 4))
        if (audioId >= 0) streams.add(BluRayStream(audioId, 0x81, language = inferredLang, layout = audioFormat.layout, kbps = audioFormat.kbps))
        val info = BluRayProfile(duration, emptyList(), emptyList(), streams).mediaInfo(size) +
            mapOf("ac3Substream" to audioId, "width" to width, "audioLanguageCode" to inferredLang)
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
    fun navBoundary(file: IsoVirtualFile, offset: Long, fetch: (Long, Long) -> ByteArray): Long? {
        if (offset == 0L || offset >= file.size / 2048 * 2048) return offset
        val data = file.readRaw(offset, minOf(file.size - offset, 2L * 1024 * 1024).toInt(), fetch)
        for (p in 0 until data.size - 2047 step 2048) {
            if (data[p+3] == 0xba.toByte() && data[p+0x29] == 0xbf.toByte() &&
                data[p+0x403] == 0xbf.toByte()) return offset + p
        }
        return null
    }
}

/** Each segment starts with PSI and an IDR. PCR uses the video timeline. */
internal class DvdTsMux(private val audioLanguageCode: String = "") {
    private data class UnitData(val video: Boolean, val ticks: Long, val data: ByteArray)
    private val samples = ArrayList<UnitData>()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lengthBytes = 4
    private var lastVideoTicks = -1L
    private var lastAudioTicks = -1L
    var frames = 0
        private set

    fun beginSegment() {
        frames = 0
        lastVideoTicks = -1
        lastAudioTicks = -1
    }

    // Called by name from MediaCodec JNI. Format changes can deliver csd-0 and csd-1 separately.
    fun video(pts: Long, bytes: ByteArray, config: Boolean = false) {
        val nals = splitNals(bytes, config)
        fun type(nal: ByteArray) = nal[0].toInt() and 31
        for (nal in nals) when (type(nal)) {
            7 -> sps = nal
            8 -> pps = nal
        }
        if (nals.any { it.size < 2 && type(it) in 1..8 }) throw IOException("Truncated DVD AVC NAL")
        if (nals.none { type(it) in 1..5 }) return
        val idr = nals.any { type(it) == 5 }
        if (frames == 0 && !idr) throw IOException("DVD segment must begin with an IDR")
        val au = ByteArrayOutputStream()
        fun nal(data: ByteArray) {
            au.write(byteArrayOf(0, 0, 0, 1))
            au.write(data)
        }
        // Media3's HLS AVC reader uses access-unit delimiters to commit samples.
        nal(byteArrayOf(9, 0xf0.toByte()))
        if (idr) {
            nal(sps ?: throw IOException("DVD IDR missing SPS"))
            nal(pps ?: throw IOException("DVD IDR missing PPS"))
        }
        nals.filter { type(it) !in 7..9 }.forEach(::nal)
        lastVideoTicks = maxOf((pts * 9 + 50) / 100, lastVideoTicks + 1)
        samples.add(UnitData(true, lastVideoTicks, au.toByteArray()))
        frames++
    }

    private fun splitNals(data: ByteArray, config: Boolean): List<ByteArray> {
        fun u(i: Int) = data[i].toInt() and 255
        val result = ArrayList<ByteArray>()
        var pos = 0
        fun takeLength(prefix: Int) {
            if (pos + prefix > data.size) throw IOException("Truncated DVD AVC length")
            var size = 0L
            repeat(prefix) { size = (size shl 8) + u(pos++) }
            if (size !in 1L..(data.size - pos).toLong()) throw IOException("Invalid DVD AVC NAL size $size")
            result.add(data.copyOfRange(pos, pos + size.toInt()))
            pos += size.toInt()
        }
        if (config && data.size >= 7 && u(0) == 1) {
            lengthBytes = (u(4) and 3) + 1
            pos = 6
            repeat(u(5) and 31) { takeLength(2) }
            if (pos == data.size) throw IOException("DVD AVC configuration missing PPS count")
            repeat(u(pos++)) { takeLength(2) }
            return result
        }
        fun prefix(i: Int): Int {
            if (i + 3 > data.size || u(i) != 0 || u(i + 1) != 0) return 0
            if (u(i + 2) == 1) return 3
            return if (i + 4 <= data.size && u(i + 2) == 0 && u(i + 3) == 1) 4 else 0
        }
        // A four-byte length of 256..511 starts with 00 00 01, just like a short start code.
        // Only prefer that interpretation when lengths describe the entire access unit.
        fun hasCompleteLengths(): Boolean {
            var i = 0
            while (i < data.size) {
                if (i + lengthBytes >= data.size) return false
                var n = 0L
                repeat(lengthBytes) { n = (n shl 8) + u(i++) }
                if (n !in 1L..(data.size - i).toLong() || u(i) !in 1..127) return false
                i += n.toInt()
            }
            return true
        }
        val firstPrefix = prefix(0)
        if (firstPrefix == 4 || firstPrefix == 3 && !hasCompleteLengths()) {
            while (pos < data.size) {
                val start = pos + prefix(pos)
                pos = start
                while (pos < data.size && prefix(pos) == 0) pos++
                if (pos > start) result.add(data.copyOfRange(start, pos))
            }
        } else if (config && data.isNotEmpty() && (u(0) and 31) in 7..8) {
            result.add(data)
        } else {
            while (pos < data.size) takeLength(lengthBytes)
        }
        return result
    }
    fun audio(pts: Long, bytes: ByteArray) {
        val ticks = (pts * 9 + 50) / 100
        val wrap = 1L shl 33
        val reference = if (lastAudioTicks >= 0) lastAudioTicks else lastVideoTicks
        val epoch = (reference + wrap / 2 - ticks) / wrap
        lastAudioTicks = maxOf(ticks + epoch * wrap, lastAudioTicks + 1)
        samples.add(UnitData(false, lastAudioTicks, bytes))
    }

    fun finish(): ByteArray {
        if (frames == 0) throw IOException("DVD segment produced 0 decodable AVC frames (no IDR)")
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
                // A cached or independently requested segment can follow a different
                // encoder session. Signal the counter restart once per PID.
                val firstPacket = cc[pid] == 0
                val count = minOf(data.size - pos, if (pcr != null) 176 else if (firstPacket) 182 else 184)
                val pad = 184 - count
                packet[3] = ((if (pad > 0) 0x30 else 0x10) or (cc[pid]++ and 15)).toByte()
                if (pad > 0) packet[4] = (pad - 1).toByte()
                if (pad > 1) packet[5] = ((if (firstPacket) 128 else 0) or (if (pcr != null) 16 else 0)).toByte()
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
        val pmtBase = byteArrayOf(2, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 1, 0xf0.toByte(), 0,
            // No caption services are present in the transcoded AVC. An explicit empty
            // caption_service_descriptor prevents Media3 from inventing a CEA-608 track.
            0x1b, 0xe1.toByte(), 1, 0xf0.toByte(), 3, 0x86.toByte(), 1, 0xe0.toByte())
        val audioDesc = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x04, 'A'.code.toByte(), 'C'.code.toByte(), '-'.code.toByte(), '3'.code.toByte()))
            write(byteArrayOf(0x6A, 0x01, 0x00))
            if (audioLanguageCode.length == 3 && audioLanguageCode.all { it in 'a'..'z' }) {
                write(byteArrayOf(0x0A, 0x04))
                write(audioLanguageCode.toByteArray(Charsets.US_ASCII))
                write(0)
            }
        }.toByteArray()
        val pmtAudio = byteArrayOf(0x81.toByte(), 0xe1.toByte(), 2, (0xf0 or (audioDesc.size ushr 8)).toByte(), audioDesc.size.toByte()) + audioDesc
        psi(256, if (samples.any { !it.video }) pmtBase + pmtAudio else pmtBase)
        for (sample in samples.sortedBy { it.ticks }) {
            val ticks = sample.ticks
            val size = if (sample.video) 0 else sample.data.size + 8
            val header = byteArrayOf(0, 0, 1, (if (sample.video) 0xe0 else 0xbd).toByte(), (size shr 8).toByte(), size.toByte(),
                0x80.toByte(), 0x80.toByte(), 5, (33 or ((ticks ushr 29).toInt() and 14)).toByte(),
                (ticks ushr 22).toByte(), ((ticks ushr 14) or 1).toByte(), (ticks ushr 7).toByte(), ((ticks shl 1) or 1).toByte())
            packets(if (sample.video) 257 else 258, header + sample.data, ticks.takeIf { sample.video })
        }
        samples.clear()
        return out.toByteArray()
    }
}
