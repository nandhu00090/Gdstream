package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/** A bounded elementary stream queue; PES/TS boundaries need not coincide with audio frames. */
internal abstract class BluRayAudioReader(private val tracks: List<BluRayAudio>, private val language: String?) : ElementaryStreamReader {
    protected data class Frame(val consumed: Int, val data: ByteArray, val format: Format, val durationUs: Long)
    private var buffer = ByteArray(8192)
    private var length = 0
    private var absoluteStart = 0L
    private val timestamps = java.util.ArrayDeque<Pair<Long, Long>>()
    private var timeUs = C.TIME_UNSET
    private lateinit var output: TrackOutput
    private var id = ""
    private var track: BluRayAudio? = null
    private var lastFormat: Format? = null
    protected abstract fun frame(data: ByteArray, length: Int, end: Boolean): Frame?
    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        id = idGenerator.formatId
        track = tracks.firstOrNull { it.pid == idGenerator.trackId }
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }
    protected open fun resetFrameState() {}
    override fun seek() { resetFrameState(); length = 0; absoluteStart = 0; timestamps.clear(); timeUs = C.TIME_UNSET }
    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        if (pesTimeUs != C.TIME_UNSET) timestamps.addLast((absoluteStart + length) to pesTimeUs)
    }
    override fun consume(data: ParsableByteArray) {
        val count = data.bytesLeft()
        require(length.toLong() + count <= 2 * 1024 * 1024) { "Blu-ray audio frame exceeds 2 MiB" }
        if (length + count > buffer.size) buffer = buffer.copyOf(maxOf(length + count, buffer.size * 2))
        data.readBytes(buffer, length, count)
        length += count
        drain(false)
    }
    override fun packetFinished(isEndOfInput: Boolean) { if (isEndOfInput) drain(true) }
    private fun drain(end: Boolean) {
        while (length > 0) {
            val frame = frame(buffer, length, end) ?: return
            require(frame.consumed in 1..length)
            while (timestamps.isNotEmpty() && timestamps.first.first <= absoluteStart) timeUs = timestamps.removeFirst().second
            if (frame.data.isNotEmpty()) {
                val channels = when (frame.format.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${frame.format.channelCount} ch" }
                val label = track?.label?.let { "$it • $channels" }
                val format = frame.format.buildUpon().setId(id).setLanguage(track?.language ?: language)
                    .setLabel(label).setContainerMimeType(MimeTypes.VIDEO_MP2T).build()
                if (format != lastFormat) {
                    output.format(format)
                    lastFormat = format
                    DebugLogger.log("ISO_AUDIO_FORMAT", "pid=$id mime=${format.sampleMimeType} channels=${format.channelCount} rate=${format.sampleRate} language=${format.language}")
                }
                if (timeUs != C.TIME_UNSET) {
                    output.sampleData(ParsableByteArray(frame.data), frame.data.size)
                    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frame.data.size, 0, null)
                    timeUs += frame.durationUs
                }
            }
            length -= frame.consumed
            absoluteStart += frame.consumed
            buffer.copyInto(buffer, 0, frame.consumed, frame.consumed + length)
        }
    }
}

internal class HdmvPcmReader(tracks: List<BluRayAudio>, language: String?) : BluRayAudioReader(tracks, language) {
    override fun frame(data: ByteArray, length: Int, end: Boolean): Frame? {
        if (length < 4) return null
        val bytes = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
        val layout = try { HdmvPcm.layout(data) } catch (_: Exception) {
            return Frame(1, ByteArray(0), Format.Builder().build(), 0)
        }
        if (bytes == 0 || bytes % layout.frameBytes != 0)
            return Frame(1, ByteArray(0), Format.Builder().build(), 0)
        if (length < bytes + 4) return null
        val decoded = HdmvPcm.decode(data.copyOfRange(4, bytes + 4), layout)
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(layout.channels.size)
            .setSampleRate(layout.rate).setPcmEncoding(if (layout.bytesPerSample == 2) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_24BIT).build()
        return Frame(bytes + 4, decoded, format, (bytes / layout.frameBytes).toLong() * 1_000_000 / layout.rate)
    }
}

/** Keep the core and its EXTSS together: decoding them as separate samples discards MA residuals. */
internal class BluRayDtsReader(private val hd: Boolean, tracks: List<BluRayAudio>, language: String?) : BluRayAudioReader(tracks, language) {
    private var knownFormat: Format? = null
    private var knownDuration = 0L
    override fun resetFrameState() { knownFormat = null; knownDuration = 0L }
    private fun type(data: ByteArray, at: Int): Int {
        var sync = 0
        repeat(4) { sync = (sync shl 8) or (data[at + it].toInt() and 255) }
        return DtsUtil.getFrameType(sync)
    }
    private fun extension(data: ByteArray, at: Int, length: Int): DtsUtil.DtsHeader? {
        if (length - at < 10) return null
        val size = DtsUtil.parseDtsHdHeaderSize(data.copyOfRange(at, at + 10))
        require(size in 10..4096)
        if (length - at < size) return null
        return DtsUtil.parseDtsHdHeader(data.copyOfRange(at, at + size))
    }
    override fun frame(data: ByteArray, length: Int, end: Boolean): Frame? = try {
        parseFrame(data, length, end)
    } catch (_: IllegalArgumentException) {
        resetFrameState()
        Frame(1, ByteArray(0), Format.Builder().build(), 0)
    } catch (_: java.io.IOException) {
        resetFrameState()
        Frame(1, ByteArray(0), Format.Builder().build(), 0)
    }
    private fun parseFrame(data: ByteArray, length: Int, end: Boolean): Frame? {
        if (length < 4) return null
        val type = type(data, 0)
        if (type == DtsUtil.FRAME_TYPE_UNKNOWN) return Frame(1, ByteArray(0), Format.Builder().build(), 0)
        var size: Int
        var format: Format
        var duration: Long
        if (type == DtsUtil.FRAME_TYPE_CORE) {
            if (length < 18) return null
            val header = data.copyOf(18)
            format = DtsUtil.parseDtsFormat(header, null, null, 0, MimeTypes.VIDEO_MP2T, null)
            require(format.sampleRate > 0)
            duration = DtsUtil.parseDtsAudioSampleCount(header).toLong() * 1_000_000 / format.sampleRate
            size = DtsUtil.getDtsFrameSize(header)
            require(size in 18..2 * 1024 * 1024)
            if (length < size) return null
            if (hd) {
                if (length < size + 4 && !end) return null
                if (length >= size + 4 && type(data, size) == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
                    val ext = extension(data, size, length) ?: return null
                    require(ext.frameSize in 10..1024 * 1024)
                    size += ext.frameSize
                    if (length < size) return null
                    if (ext.channelCount > 0 && ext.sampleRate > 0) format = format.buildUpon()
                        .setChannelCount(ext.channelCount).setSampleRate(ext.sampleRate).build()
                }
                format = format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).build()
            }
        } else if (type == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
            val ext = extension(data, 0, length) ?: return null
            size = ext.frameSize
            require(size in 10..1024 * 1024)
            if (length < size) return null
            format = if (ext.channelCount > 0 && ext.sampleRate > 0) Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
                .setChannelCount(ext.channelCount).setSampleRate(ext.sampleRate).build() else knownFormat ?: return Frame(size, ByteArray(0), Format.Builder().build(), 0)
            duration = if (ext.frameDurationUs > 0) ext.frameDurationUs else knownDuration
        } else throw java.io.IOException("Unsupported Blu-ray DTS frame type=$type")
        knownFormat = format
        knownDuration = duration
        return Frame(size, data.copyOf(size), format, duration)
    }
}

/** TrueHD access units interleaved with independent AC-3 syncframes on the HDMV PID.
 * Skip the AC-3 companion before handing lossless access units to FFmpeg. After a seek,
 * minor syncs are unusable until a new major sync supplies the decoder configuration.
 */
internal class BluRayTrueHdReader(tracks: List<BluRayAudio>, language: String?) : BluRayAudioReader(tracks, language) {
    private var format: Format? = null
    private var duration = 0L
    override fun resetFrameState() { format = null; duration = 0L }
    override fun frame(data: ByteArray, length: Int, end: Boolean): Frame? {
        fun skip(n: Int) = Frame(n, ByteArray(0), Format.Builder().build(), 0)
        if (length < 10) return null
        if (data[0] == 0x0b.toByte() && data[1] == 0x77.toByte()) {
            val size = androidx.media3.extractor.Ac3Util.parseAc3SyncframeSize(data)
            if (size <= 0) return skip(1)
            return if (length < size) null else skip(size)
        }
        val major = data[4] == 0xf8.toByte() && data[5] == 0x72.toByte() &&
            data[6] == 0x6f.toByte() && data[7] == 0xba.toByte()
        if (format == null && !major) {
            // Search in one pass after byte seeks; retaining nine bytes handles split sync words.
            val next = (1 until length - 9).firstOrNull { at ->
                data[at + 4] == 0xf8.toByte() && data[at + 5] == 0x72.toByte() &&
                    data[at + 6] == 0x6f.toByte() && data[at + 7] == 0xba.toByte()
            }
            return skip(next ?: (length - 9))
        }
        val size = (((data[0].toInt() and 15) shl 8) or (data[1].toInt() and 255)) * 2
        if (size < 10) { resetFrameState(); return skip(1) }
        if (length < size) return null
        if (major) {
            if (size < 32) { resetFrameState(); return skip(1) }
            val rateCode = (data[8].toInt() ushr 4) and 15
            if ((rateCode and 7) > 2) { resetFrameState(); return skip(1) }
            val rate = (if (rateCode and 8 != 0) 44100 else 48000) shl (rateCode and 7)
            val map2 = ((data[10].toInt() and 31) shl 8) or (data[11].toInt() and 255)
            val map1 = ((data[9].toInt() and 15) shl 1) or ((data[10].toInt() ushr 7) and 1)
            val map = if (map2 != 0) map2 else map1
            val counts = intArrayOf(2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1)
            val channels = counts.indices.sumOf { if (map and (1 shl it) != 0) counts[it] else 0 }
            if (channels == 0) { resetFrameState(); return skip(size) }
            format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
                .setSampleRate(rate).setChannelCount(channels).build()
            duration = (40L shl (rateCode and 7)) * 1_000_000 / rate
        }
        return Frame(size, data.copyOf(size), format!!, duration)
    }
}
