package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.*
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Only an exact PTS match may augment a base AU. Missing metadata must never stop
 * the HDR10-compatible base pictures. Allow a short interleave window in decode order.
 */
internal class DolbyVisionSampleQueue(private val emit: (Long, Int, ByteArray) -> Unit) {
    private data class Sample(val time: Long, val flags: Int, val data: ByteArray)
    private val base = java.util.ArrayDeque<Sample>()
    private val rpus = LinkedHashMap<Long, ByteArray>()
    private var baseBytes = 0

    fun base(time: Long, flags: Int, data: ByteArray) {
        base.add(Sample(time, flags, data))
        baseBytes += data.size
        drain()
    }

    fun rpu(time: Long, nal: ByteArray) {
        if (time == C.TIME_UNSET || time in rpus) return
        rpus[time] = DolbyVisionRpu.toProfile81(nal)
        if (rpus.size > 64) rpus.remove(rpus.keys.first())
        drain()
    }

    private fun drain(finishing: Boolean = false) {
        while (base.isNotEmpty()) {
            val sample = base.first
            val rpu = rpus.remove(sample.time)
            if (rpu == null && !finishing && base.size <= 2 && baseBytes <= 16 * 1024 * 1024) break
            base.removeFirst()
            baseBytes -= sample.data.size
            val data = if (rpu == null) sample.data else sample.data + byteArrayOf(0, 0, 0, 1) + rpu
            emit(sample.time, sample.flags, data)
        }
    }

    fun finish() { drain(finishing = true); rpus.clear() }
    fun reset() { base.clear(); rpus.clear(); baseBytes = 0 }
}

/** Annex-B scanner retains only type 62, including split start codes and NALs across PES packets.
 * VCL, type 63 and all other EL data are discarded as they arrive, without buffering EL pictures.
 */
internal class DolbyVisionNalReader(private val emit: (Long, ByteArray) -> Unit) {
    private var zeros = 0
    private var started = false
    private var type = -1
    private var nalTime = C.TIME_UNSET
    private var prefixTime = C.TIME_UNSET
    private val nal = ByteArrayOutputStream()
    fun consume(data: ParsableByteArray, time: Long) {
        while (data.bytesLeft() > 0) {
            val value = data.readUnsignedByte()
            if (value == 0) {
                if (zeros == 0) prefixTime = time
                zeros = minOf(3, zeros + 1)
                continue
            }
            if (value == 1 && zeros >= 2) {
                finishNal()
                started = true; type = -1; nalTime = prefixTime
                zeros = 0
                continue
            }
            if (started) {
                repeat(zeros) { append(0) }
                append(value)
            }
            zeros = 0
        }
    }
    private fun append(value: Int) {
        if (type == -1) type = (value ushr 1) and 63
        if (type == 62) {
            if (nal.size() >= 65536) throw IOException("Oversized Dolby Vision RPU")
            nal.write(value)
        }
    }
    private fun finishNal() {
        if (type == 62 && nal.size() > 0) emit(nalTime, nal.toByteArray())
        nal.reset()
    }
    fun finish() { finishNal(); started = false; zeros = 0; type = -1 }
    fun reset() { nal.reset(); started = false; zeros = 0; type = -1; nalTime = C.TIME_UNSET; prefixTime = C.TIME_UNSET }
}

internal class BluRayDolbyVision {
    private lateinit var target: TrackOutput
    private val queue = DolbyVisionSampleQueue { time, flags, bytes ->
        target.sampleData(ParsableByteArray(bytes), bytes.size)
        target.sampleMetadata(time, flags, bytes.size, 0, null)
    }
    private val enhancement = DolbyVisionNalReader(queue::rpu)
    private val pending = ByteArrayOutputStream()
    private var elTime = C.TIME_UNSET
    private val capture = object : TrackOutput {
        override fun format(format: Format) {
            target.format(format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.08.06").build())
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(minOf(length, 64 * 1024))
            val count = input.read(buffer, 0, buffer.size)
            if (count < 0 && !allowEndOfInput) throw java.io.EOFException()
            if (count > 0) append(buffer, 0, count)
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            append(data.data, data.position, length)
            data.skipBytes(length)
        }
        private fun append(bytes: ByteArray, offset: Int, count: Int) {
            if (pending.size() + count > 16 * 1024 * 1024) throw IOException("Oversized Dolby Vision base AU")
            pending.write(bytes, offset, count)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val data = pending.toByteArray()
            val end = data.size - offset
            if (size < 0 || offset < 0 || end < size) throw IOException("Invalid HEVC AU boundary")
            pending.reset()
            pending.write(data, end, offset)
            queue.base(timeUs, flags, data.copyOfRange(end - size, end))
        }
    }
    fun payload(fallback: TsPayloadReader): TsPayloadReader = object : TsPayloadReader {
        private var reader: TsPayloadReader? = null
        override fun init(adjuster: TimestampAdjuster, output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) {
            ids.generateNewId()
            val pid = ids.trackId
            reader = when (pid) {
                0x1011 -> PesReader(H265Reader(SeiReader(emptyList(), MimeTypes.VIDEO_MP2T), MimeTypes.VIDEO_MP2T)).also {
                    it.init(adjuster, object : ExtractorOutput by output {
                        override fun track(id: Int, type: Int): TrackOutput {
                            if (type != C.TRACK_TYPE_VIDEO) return output.track(id, type)
                            target = output.track(id, type)
                            return capture
                        }
                    }, TsPayloadReader.TrackIdGenerator(pid, 8192))
                }
                0x1015 -> PesReader(object : ElementaryStreamReader {
                    override fun createTracks(output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) {}
                    override fun packetStarted(pesTimeUs: Long, flags: Int) { if (pesTimeUs != C.TIME_UNSET) elTime = pesTimeUs }
                    override fun consume(data: ParsableByteArray) = enhancement.consume(data, elTime)
                    override fun packetFinished(isEndOfInput: Boolean) { if (isEndOfInput) enhancement.finish() }
                    override fun seek() { enhancement.reset(); elTime = C.TIME_UNSET }
                }).also { it.init(adjuster, output, TsPayloadReader.TrackIdGenerator(pid, 8192)) }
                else -> fallback.also { it.init(adjuster, output, TsPayloadReader.TrackIdGenerator(pid, 8192)) }
            }
        }
        override fun seek() { reader?.seek() }
        override fun consume(data: ParsableByteArray, flags: Int) { reader?.consume(data, flags) }
    }
    fun reset() { queue.reset(); enhancement.reset(); pending.reset(); elTime = C.TIME_UNSET }
    fun finish() { enhancement.finish(); queue.finish() }
}
