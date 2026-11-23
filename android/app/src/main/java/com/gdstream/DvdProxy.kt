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
    private external fun open(encoder: String, color: Int, width: Int, height: Int, fps: Double, basePts90k: Long): Long
    private external fun encode(handle: Long, bytes: ByteArray?, mux: DvdTsMux, boundary: Boolean, last: Boolean): Long
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
    private class Warm(val id: String, val sequence: Long, var next: Int, val mux: DvdTsMux) {
        var handle = 0L
        val audio = audioReader(mux::audio)
        fun closeEncoder() {
            if (handle != 0L) close(handle)
            handle = 0L
        }
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
        current.closeEncoder()
    }

    @Synchronized fun transcode(data: ByteArray, info: Map<String, Any>, encoder: Encoder,
                  session: IsoReadSession, streamId: String, sequence: Long, segment: Int, boundary: Boolean, last: Boolean,
                  discontinuity: Boolean, sequenceHeader: () -> ByteArray?): ByteArray {
        if (warm?.id != streamId || warm?.sequence != sequence || warm?.next != segment) release()
        val sequential = warm != null
        val state = warm ?: Warm(streamId, sequence, segment, DvdTsMux((info["audioLanguageCode"] as? String).orEmpty())).also { warm = it }
        val audioId = (info["ac3Substream"] as? Number)?.toInt() ?: -1
        val source = sourceTiming(data, audioId)
        fun convertVideo(finish: Boolean): Long {
            val fresh = state.handle == 0L
            if (fresh) {
                state.handle = open(encoder.name, encoder.color, (info.getValue("width") as Number).toInt(),
                    (info.getValue("height") as Number).toInt(), (info.getValue("frameRate") as Number).toDouble(),
                    state.mux.timing?.nextVideo ?: source.basePts)
                if (state.handle == 0L) throw IOException("DVD AVC encoder initialization failed")
            }
            state.mux.beginSegment(fresh)
            demux(data, audioId, if (fresh) sequenceHeader else { -> null }) { _, sample ->
                session.checkActive()
                encode(state.handle, sample, state.mux, false, false)
            }
            val end = encode(state.handle, null, state.mux, boundary, finish)
            if (state.mux.frames == 0) throw IOException("DVD segment produced no decodable video")
            if (finish) state.closeEncoder()
            return end
        }
        try {
            val hadEncoder = state.handle != 0L
            val nativeEnd = try { convertVideo(last) } catch (error: IOException) {
                state.closeEncoder()
                session.checkActive()
                if (last && !hadEncoder) throw error
                // Retry vendor encoders that need EOS to release the last picture.
                convertVideo(true)
            }
            // Consume audio once after any encoder retry. A frame can straddle two
            // VOBUs and its pending bytes belong to the sequential AC-3 reader.
            if (discontinuity) state.audio.seek()
            demux(data, audioId, audio = state.audio, video = null)
            val result = state.mux.finish(source.basePts, nativeEnd, source.navDuration)
            val pts = checkNotNull(state.mux.timing)
            state.next = segment + 1
            if (last) release() else {
                idleClose?.cancel(false)
                idleClose = cleanup.schedule({
                    synchronized(this) { if (warm === state && state.next == segment + 1) state.closeEncoder() }
                }, 30, java.util.concurrent.TimeUnit.SECONDS)
            }
            DebugLogger.log("DVD_SEG_OK", "seg=$segment frames=${state.mux.frames} bytes=${result.size} warm=$sequential " +
                "pts90k=${pts.firstVideo}..${pts.lastVideo} nextVideo90k=${pts.nextVideo} firstAudio90k=${pts.firstAudio} nextAudio90k=${pts.nextAudio}")
            return result
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
              audio: Ac3Reader? = null, video: ((Long, ByteArray) -> Unit)?) {
        var firstVideo = true
        var selectedAudio = audioId
        forEachPes(data) { id, start, end, pts ->
            var payload = start
            if (id == 0xbd && payload + 4 <= end && selectedAudio < 0 && (data[payload].toInt() and 255) in 0x80..0x87)
                selectedAudio = data[payload].toInt() and 255
            if (id == 0xe0 && video != null) {
                if (firstVideo) {
                    firstVideo = false
                    val hasHeader = end - start >= 4 && data[start] == 0.toByte() && data[start + 1] == 0.toByte() &&
                        data[start + 2] == 1.toByte() && data[start + 3] == 0xb3.toByte()
                    if (!hasHeader) sequenceHeader()?.let { video(C.TIME_UNSET, it) }
                }
                video(pts, data.copyOfRange(start, end))
            } else if (id == 0xbd && audio != null && payload + 4 <= end && (data[payload].toInt() and 255) == selectedAudio) {
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

    /** PCI_GI.vobu_s_ptm is the low 32 bits of the first presentation time of a VOBU. */
    fun navStart(data: ByteArray, sector: Int = 0): Long? {
        fun code(offset: Int, id: Int) = ptm(data, sector + offset) == 0x100L + id
        if (sector < 0 || data.size - sector < 2048 || !code(0, 0xba) || !code(14, 0xbb) ||
            !code(0x26, 0xbf) || data[sector + 0x2c] != 0.toByte() ||
            !code(0x400, 0xbf) || data[sector + 0x406] != 1.toByte()) return null
        return ptm(data, sector + 0x39)
    }

    private fun ptm(data: ByteArray, pos: Int): Long =
        (pos until pos + 4).fold(0L) { n, i -> (n shl 8) or (data[i].toLong() and 255) }

    data class SourceTiming(val basePts: Long, val navDuration: Long)

    fun sourceTiming(data: ByteArray, audioId: Int): SourceTiming {
        var audioPts: Long? = null
        var videoPts: Long? = null
        var navDuration = 0L
        for (p in 0 until data.size - 2047 step 2048) {
            val start = navStart(data, p) ?: continue
            val duration = (ptm(data, p + 0x3d) - start).toInt()
            if (duration > 0) navDuration += duration
        }
        forEachPes(data) { id, start, _, pts ->
            if (pts != C.TIME_UNSET) {
                val substream = data[start].toInt() and 255
                if (id == 0xbd && substream in 0x80..0x87 && (audioId < 0 || substream == audioId) && audioPts == null) audioPts = pts
                if (id == 0xe0 && videoPts == null) videoPts = pts
            }
        }
        return SourceTiming(videoPts ?: audioPts ?: navStart(data) ?: 0, navDuration)
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
            if (navStart(data, p) != null) return offset + p
        }
        return null
    }
}

/** One encoder serves both HTTP demand and look-ahead. Completed hits never acquire its lock. */
internal class DvdSegmentCache {
    private val encoder = Any()
    private val cache = object : LinkedHashMap<Pair<String, Int>, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, ByteArray>?) = size > 2
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "DVDPretranscode").apply { isDaemon = true }
    }
    private var pending: Pair<Pair<String, Int>, IsoReadSession>? = null

    private var lastRequest: Pair<String, Int>? = null
    private var sequence = 0L

    fun segment(id: String, index: Int, last: Int, session: IsoReadSession, prefetch: Boolean,
                load: (Int, IsoReadSession, Long) -> ByteArray): ByteArray {
        val key = id to index
        val next = id to index + 1
        var displaced: IsoReadSession? = null
        val requestSequence = synchronized(cache) {
            val previous = lastRequest
            if (previous == null || id != previous.first || index !in previous.second..previous.second + 1) {
                // A cached response may have been normalized on a different playback
                // timeline. Only retries and forward playback can reuse it.
                sequence++
                cache.clear()
                displaced = pending?.second
                pending = null
            }
            lastRequest = key
            sequence
        }
        displaced?.cancel("DVD seek")
        fun get(n: Int, owner: IsoReadSession): ByteArray {
            val key = id to n
            fun cached() = synchronized(cache) {
                if (sequence != requestSequence) throw java.io.InterruptedIOException("DVD seek")
                cache[key]
            }
            cached()?.let { return it }
            return synchronized(encoder) {
                owner.checkActive()
                cached() ?: load(n, owner, requestSequence).also { bytes ->
                    owner.checkActive()
                    synchronized(cache) { if (sequence == requestSequence) cache[key] = bytes }
                }
            }
        }
        val body = get(index, session)
        if (prefetch && index < last) synchronized(cache) {
            session.checkActive()
            if (sequence == requestSequence && lastRequest == key && next !in cache && pending?.first != next) {
                val owner = IsoReadSession()
                val lease = ArchiveProxyStreams.registry.register(id, false, owner::cancel)
                pending = next to owner
                worker.execute {
                    try { get(index + 1, owner) }
                    catch (error: Exception) {
                        if (!owner.isCancelled) DebugLogger.log("DVD_PREFETCH_ERR", "seg=${index + 1} ${isoFailureDetails(error)}")
                    } finally {
                        synchronized(cache) { if (pending?.second === owner) pending = null }
                        lease.close()
                        owner.cancel()
                    }
                }
            }
        }
        return body
    }
}
