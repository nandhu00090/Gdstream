package com.gdstream

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Each segment starts with PSI and an IDR. PCR uses the video timeline. */
internal class DvdTsMux(val streams: DvdStreams) {
    constructor(language: String = "", channels: Int = 0) : this(DvdStreams(
        if (channels > 0) listOf(DvdStreams.Track(0x80, language, channels)) else emptyList()))

    private companion object {
        const val AC3_FRAME_TICKS = 2880L // 1536 samples at 48 kHz
        val CLEAR = byteArrayOf(0x0f, 0x10, 0, 1, 0, 2, 30, 0x0b, 0x0f, 0x80.toByte(), 0, 1, 0, 0)
    }
    private class AudioClock(val channels: Int) {
        var next: Long? = null
        var phase: Long? = null
        val silence by lazy {
            val layout = channels.coerceIn(1, 6)
            DvdTsMux::class.java.getResourceAsStream("/dvd/silence-$layout.ac3")?.use { it.readBytes() }
                ?: throw IOException("Missing DVD AC-3 silence frame for $layout channels")
        }
    }
    private val clocks = streams.audio.map { AudioClock(it.channels) }.ifEmpty { listOf(AudioClock(0)) }
    private data class UnitData(val pid: Int, var ticks: Long, val data: ByteArray)
    private val samples = ArrayList<UnitData>()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lengthBytes = 4
    var frames = 0
        private set

    fun beginSegment(newEncoder: Boolean = false) {
        frames = 0
        samples.clear()
        if (newEncoder) {
            sps = null
            pps = null
            lengthBytes = 4
        }
    }

    // Called by name from MediaCodec JNI. Format changes can deliver csd-0 and csd-1 separately.
    fun video(pts90k: Long, bytes: ByteArray, config: Boolean = false) {
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
        samples.add(UnitData(streams.videoPid, pts90k, au.toByteArray()))
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
        if (firstPrefix == 4 || firstPrefix == 3 && (config || !hasCompleteLengths())) {
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
    fun audio(ptsUs: Long, bytes: ByteArray) = audio(0, ptsUs, bytes)

    fun audio(track: Int, ptsUs: Long, bytes: ByteArray) {
        // Ac3Reader timestamps are in microseconds. Round back to the original PES tick.
        samples.add(UnitData(DvdStreams.AUDIO_PID + track, (ptsUs * 9 + 50) / 100, bytes))
    }

    data class Timing(val firstVideo: Long, val lastVideo: Long, val nextVideo: Long,
                      val firstAudio: Long?, val nextAudio: Long?)
    var timing: Timing? = null
        private set
    private val cc = IntArray(8192)

    fun outputPts(raw: Long, sourceStart: Long): Long {
        val halfWrap = 1L shl 32
        val offset = ((raw - sourceStart + halfWrap) and 0x1ffffffffL) - halfWrap
        return (timing?.nextVideo ?: sourceStart) + offset
    }

    private data class Cue(val start: Long, val stop: Long?, val display: ByteArray)
    private val subtitleVersions = IntArray(streams.subtitles.size)
    private val cues = List(streams.subtitles.size) { mutableListOf<Cue>() }
    fun subtitle(track: Int, show: Long, stop: Long?, display: ByteArray) {
        cues[track].add(Cue(show, stop, display))
    }

    fun endSubtitles(tick: Long) {
        for (queue in cues) {
            queue.removeAll { it.start >= tick }
            for (i in queue.indices) {
                val cue = queue[i]
                if (cue.stop == null || cue.stop > tick) queue[i] = cue.copy(stop = tick)
            }
        }
    }

    private fun addSubtitles(start: Long, end: Long) {
        for ((index, queue) in cues.withIndex()) {
            val pid = DvdStreams.SUBTITLE_PID + index
            queue.sortBy { it.start }
            var atStart = false
            val remaining = mutableListOf<Cue>()
            for ((i, cue) in queue.withIndex()) {
                val stop = minOf(cue.stop ?: Long.MAX_VALUE, queue.getOrNull(i + 1)?.start ?: Long.MAX_VALUE)
                if (stop <= start) continue
                if (cue.start < end) {
                    val show = maxOf(start, cue.start)
                    samples.add(UnitData(pid, show, cue.display))
                    if (show == start) atStart = true
                    if (stop < end && stop != queue.getOrNull(i + 1)?.start)
                        samples.add(UnitData(pid, stop, CLEAR))
                }
                if (stop >= end) remaining.add(cue)
            }
            // An acquisition point for switches and seeks, including empty opening cells.
            if (!atStart) samples.add(UnitData(pid, start, CLEAR))
            queue.clear()
            queue.addAll(remaining)
        }
    }

    fun finish(sourceStart: Long, nativeEnd: Long, navDuration: Long = 0): ByteArray {
        if (frames == 0) throw IOException("DVD segment produced 0 decodable AVC frames (no IDR)")
        val video = samples.filter { it.pid == streams.videoPid }
        val start = timing?.nextVideo ?: sourceStart
        val audio = clocks.indices.map { index -> samples.filter { it.pid == DvdStreams.AUDIO_PID + index } }
        val firstAudio = arrayOfNulls<Long>(clocks.size)
        for ((i, clock) in clocks.withIndex()) {
            val first = audio[i].firstOrNull() ?: continue
            val tick = clock.next ?: outputPts(first.ticks, sourceStart)
            firstAudio[i] = tick
            if (clock.phase == null) clock.phase = tick - start
            audio[i].forEachIndexed { frame, sample -> sample.ticks = tick + frame * AC3_FRAME_TICKS }
            clock.next = tick + audio[i].size * AC3_FRAME_TICKS
        }

        val first = video.first().ticks
        val nativeSpan = nativeEnd - first
        if (nativeSpan < frames) throw IOException("Invalid DVD display cadence")
        // Keep the native field cadence, including repeat-first-field. Some discs
        // report twice the display duration. Fit those pictures to the copied
        // audio frames (or PCI duration for silent video), on our output clock.
        val primary = streams.primary
        val reference = if (audio[primary].isNotEmpty()) clocks[primary].next!! - clocks[primary].phase!! - start else navDuration
        val span = if (reference >= frames && kotlin.math.abs(nativeSpan - reference) > 4500) reference else nativeSpan
        val end = start + span
        var previous = start - 1
        video.forEachIndexed { i, sample ->
            val fitted = start + (sample.ticks - first) * span / nativeSpan
            sample.ticks = fitted.coerceIn(previous + 1, end - (frames - i))
            previous = sample.ticks
        }
        for ((i, clock) in clocks.withIndex()) {
            if (clock.channels <= 0 || audio[i].isNotEmpty()) continue
            var tick = clock.next ?: start
            while (tick < end) {
                if (firstAudio[i] == null) firstAudio[i] = tick
                samples.add(UnitData(DvdStreams.AUDIO_PID + i, tick, clock.silence))
                tick += AC3_FRAME_TICKS
            }
            clock.next = tick
        }
        addSubtitles(start, end)
        val writer = DvdTsWriter(cc)
        writer.tables(streams, legacyAudio = audio[0].isNotEmpty())
        for (sample in samples.sortedBy { it.ticks }) {
            val version = if (sample.pid >= DvdStreams.SUBTITLE_PID) subtitleVersions[sample.pid - DvdStreams.SUBTITLE_PID]++ and 15 else 0
            writer.sample(sample.pid, sample.ticks, sample.data, sample.pid == streams.videoPid, version)
        }
        samples.clear()
        timing = Timing(start, video.last().ticks, end, firstAudio[primary], clocks[primary].next)
        return writer.out.toByteArray()
    }
}

internal class DvdTsWriter(private val cc: IntArray) {
    val out = ByteArrayOutputStream()
    fun packets(pid: Int, data: ByteArray, clock: Long? = null) {
        var pos = 0
        while (pos < data.size) {
            val packet = ByteArray(188) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = ((pid shr 8) or if (pos == 0) 64 else 0).toByte()
            packet[2] = pid.toByte()
            val pcr = clock?.takeIf { pos == 0 }
            // Signal a counter restart only on the first packet of a cold encoder session.
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
    private fun psi(pid: Int, body: ByteArray) {
        val length = body.size + 1 // bytes following section_length, including CRC32
        body[1] = (0xb0 or (length ushr 8)).toByte()
        body[2] = length.toByte()
        val crc = ExternalTsPmt.crc(body)
        packets(pid, byteArrayOf(0) + body + ByteArray(4) { (crc ushr (24 - it * 8)).toByte() })
    }

    fun tables(streams: DvdStreams, onlyPid: Int? = null, legacyAudio: Boolean = false) {
        psi(0, byteArrayOf(0, 0, 0, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xe1.toByte(), 0))
        val pcrPid = onlyPid ?: streams.videoPid
        val pmt = ByteArrayOutputStream()
        pmt.write(byteArrayOf(2, 0, 0, 0, 1, 0xc1.toByte(), 0, 0, (0xe0 or (pcrPid ushr 8)).toByte(),
            pcrPid.toByte(), 0xf0.toByte(), 0))
        fun stream(type: Int, pid: Int, descriptors: ByteArray) {
            if (onlyPid != null && onlyPid != pid) return
            pmt.write(type)
            pmt.write(0xe0 or (pid ushr 8))
            pmt.write(pid)
            pmt.write(0xf0)
            pmt.write(descriptors.size)
            pmt.write(descriptors)
        }
        fun lang(code: String) = (code.takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } } ?: "und")
            .toByteArray(Charsets.US_ASCII)
        // Explicitly empty caption_service_descriptor disables synthetic CEA-608 in Media3.
        stream(0x1b, streams.videoPid, byteArrayOf(0x86.toByte(), 1, 0xe0.toByte()))
        val audio = streams.audio.ifEmpty { if (legacyAudio) listOf(DvdStreams.Track(0x80, "und")) else emptyList() }
        audio.forEachIndexed { i, track ->
            stream(0x81, DvdStreams.AUDIO_PID + i, byteArrayOf(5, 4, 65, 67, 45, 51, 0x6a, 1, 0, 0x0a, 4) +
                lang(track.language) + 0.toByte())
        }
        streams.subtitles.forEachIndexed { i, track ->
            stream(0x06, DvdStreams.SUBTITLE_PID + i, byteArrayOf(0x59, 8) + lang(track.language) +
                byteArrayOf(0x10, 0, 1, 0, 1))
        }
        psi(0x100, pmt.toByteArray())
    }

    fun sample(pid: Int, ticks: Long, data: ByteArray, video: Boolean, version: Int) {
        val subtitle = pid in DvdStreams.SUBTITLE_PID until DvdStreams.SUBTITLE_PID + 32
        fun pes(payload: ByteArray) {
            val size = if (video) 0 else payload.size + 8
            require(size <= 65535)
            val header = byteArrayOf(0, 0, 1, (if (video) 0xe0 else 0xbd).toByte(), (size ushr 8).toByte(), size.toByte(),
                (if (subtitle) 0x84 else 0x80).toByte(), 0x80.toByte(), 5, (33 or ((ticks ushr 29).toInt() and 14)).toByte(),
                (ticks ushr 22).toByte(), ((ticks ushr 14) or 1).toByte(), (ticks ushr 7).toByte(), ((ticks shl 1) or 1).toByte())
            packets(pid, header + payload, ticks.takeIf { video })
        }
        if (!subtitle) { pes(data); return }
        // Keep each EN 300 743 segment intact, even for display sets spanning multiple PES.
        val display = data.copyOf()
        var first = 0
        var pos = 0
        fun emit(until: Int) = pes(byteArrayOf(0x20, 0) + display.copyOfRange(first, until) + 0xff.toByte())
        while (pos < data.size) {
            require(pos + 6 <= data.size && data[pos] == 0x0f.toByte())
            val length = 6 + ((data[pos + 4].toInt() and 255) shl 8) + (data[pos + 5].toInt() and 255)
            require(length <= 65524 && pos + length <= data.size)
            // VLC/FFmpeg discard a PCS whose version equals the previous show or clear.
            val versionByte = when (data[pos + 1].toInt()) {
                0x10, 0x11, 0x12 -> pos + 7
                0x13 -> pos + 8
                else -> -1
            }
            if (versionByte >= 0) display[versionByte] = ((version shl 4) or (display[versionByte].toInt() and 15)).toByte()
            if (pos - first + length > 65524) { emit(pos); first = pos }
            pos += length
        }
        emit(pos)
    }
}
