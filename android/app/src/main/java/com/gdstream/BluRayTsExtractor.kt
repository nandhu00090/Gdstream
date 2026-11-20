package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.*
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.*

internal class BluRaySeekMap(private val profile: BluRayProfile) : SeekMap {
    override fun isSeekable() = profile.points.size >= 2 && profile.durationUs > 0
    override fun getDurationUs() = profile.durationUs
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val points = profile.points
        var low = 0; var high = points.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (points[middle].timeUs <= timeUs) low = middle else high = middle - 1
        }
        val point = points[low]
        val first = SeekPoint(point.timeUs, point.position)
        val next = points.getOrNull(low + 1)
        return if (next != null && point.timeUs < timeUs) SeekMap.SeekPoints(first, SeekPoint(next.timeUs, next.position)) else SeekMap.SeekPoints(first)
    }
}

/** Reads the 188-byte view of a registered Blu-ray or standalone BDAV source. */
internal class BluRayTsExtractor(private val profile: BluRayProfile?, dolbyVision: Boolean = false) : Extractor {
    private val dv = if (dolbyVision && profile?.streams?.any { it.pid == 0x1011 && it.type == 0x24 && it.height == 2160 } == true &&
        profile.streams.any { it.pid == 0x1015 && it.type == 0x24 }) BluRayDolbyVision() else null
    private val indexed = profile?.let { BluRaySeekMap(it) }?.takeIf { it.isSeekable }
    private var inputLength = C.LENGTH_UNSET.toLong()
    private val videoReaders = ArrayList<EndOfInputPayloadReader>()
    private val pidReaders = ArrayList<BluRayPidReader>()
    private val fallback = DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
    private val payloads = object : TsPayloadReader.Factory {
        override fun createInitialPayloadReaders() = fallback.createInitialPayloadReaders()
        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader =
            BluRayPidReader(profile?.audio.orEmpty(), streamType, dv != null) { resolved -> reader(resolved, esInfo) }.also(pidReaders::add)
        private fun reader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? = when (streamType) {
            0x81, 0x84, 0xa1 -> PesReader(Ac3Reader(esInfo.language, 0, androidx.media3.common.MimeTypes.VIDEO_MP2T))
            0x83 -> PesReader(BluRayTrueHdReader(profile?.audio.orEmpty(), esInfo.language))
            0x90 -> PesReader(BluRayPgsReader(profile?.streams.orEmpty(), esInfo.language))
            0x24 -> fallback.createPayloadReader(streamType, esInfo)!!.let {
                EndOfInputPayloadReader(dv?.payload(it) ?: BaseLayerPayloadReader(it)).also(videoReaders::add)
            }
            0x80 -> PesReader(HdmvPcmReader(profile?.audio.orEmpty(), esInfo.language))
            0x82, 0x85, 0x86, 0x8a, 0xa2 -> PesReader(BluRayDtsReader(streamType != 0x82, profile?.audio.orEmpty(), esInfo.language))
            else -> fallback.createPayloadReader(streamType, esInfo)
        }
    }
    private var subtitles: androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput? = null
    private val delegate = TsExtractor(TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        DefaultSubtitleParserFactory(), TimestampAdjuster(0), payloads, 112800)
    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) {
        val transcoded = androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput(output, DefaultSubtitleParserFactory())
        subtitles = transcoded
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val target = transcoded.track(id, type)
                val audioIdx = profile?.audio?.indexOfFirst { it.pid == id } ?: -1
                val audio = if (audioIdx >= 0) profile!!.audio[audioIdx] else null
                return object : TrackOutput by target {
                    override fun format(format: androidx.media3.common.Format) {
                        val enriched = if (audio != null && type == C.TRACK_TYPE_AUDIO) {
                            val ch = when (format.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "" }
                            val lang = audio.language.takeIf { it.isNotBlank() && it != "und" }
                            format.buildUpon().setLanguage(lang)
                                .setLabel(format.label ?: audio.displayLabel(audioIdx, ch)).build()
                        } else format
                        if (type == C.TRACK_TYPE_AUDIO) DebugLogger.log("ISO_AUDIO_FORMAT",
                            "pid=$id id=${enriched.id} mime=${enriched.sampleMimeType} channels=${enriched.channelCount} rate=${enriched.sampleRate} language=${enriched.language}")
                        if (type == C.TRACK_TYPE_VIDEO) {
                            val mode = if (enriched.sampleMimeType == "video/dolby-vision") "DV"
                                else if (enriched.colorInfo?.colorTransfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)) "HDR10" else "SDR"
                            DebugLogger.log("ISO_VIDEO_FORMAT", "pid=$id mode=$mode mime=${enriched.sampleMimeType} codecs=${enriched.codecs}")
                        }
                        target.format(enriched)
                    }
                }
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                DebugLogger.log("ISO_SEEK_MAP", "source=${if (indexed != null) "CLPI" else "PCR"} points=${profile?.points?.size ?: 0} durationUs=${profile?.durationUs ?: 0}")
                val duration = profile?.durationUs ?: 0
                val measured = if (duration > 0 && inputLength >= 188) object : SeekMap {
                    override fun getDurationUs() = duration
                    override fun isSeekable() = true
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                        if (seekMap.isSeekable) return seekMap.getSeekPoints(timeUs)
                        val fraction = timeUs.coerceIn(0L, duration).toDouble() / duration
                        val packet = (fraction * (inputLength / 188)).toLong().coerceAtMost(inputLength / 188 - 1)
                        return SeekMap.SeekPoints(SeekPoint(timeUs, packet * 188))
                    }
                } else seekMap
                output.seekMap(indexed ?: measured)
            }
        })
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        inputLength = input.length
        val source = if (indexed == null) input else object : ForwardingExtractorInput(input) {
            override fun getLength() = C.LENGTH_UNSET.toLong()
        }
        return delegate.read(source, seekPosition).also {
            if (it == Extractor.RESULT_END_OF_INPUT) {
                pidReaders.forEach { reader -> reader.finish() }
                videoReaders.forEach { reader -> reader.finish() }
                dv?.finish()
            }
        }
    }
    override fun seek(position: Long, timeUs: Long) {
        val pointTime = profile?.points?.lastOrNull { it.position <= position }?.timeUs ?: timeUs
        DebugLogger.log("ISO_CLPI_SEEK", "position=$position requestedUs=$timeUs pointUs=$pointTime indexed=${indexed != null}")
        dv?.reset()
        subtitles?.resetSubtitleParsers()
        delegate.seek(position, if (indexed != null) pointTime else timeUs)
    }
    override fun release() = delegate.release()
}

/** HDMV PID 0x1015 is a dependent enhancement stream, never an independently renderable video. */
internal class BaseLayerPayloadReader(private val reader: TsPayloadReader) : TsPayloadReader {
    private var enhancement = false
    override fun init(adjuster: TimestampAdjuster, output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) {
        ids.generateNewId()
        enhancement = ids.trackId == 0x1015
        if (!enhancement) reader.init(adjuster, output, TsPayloadReader.TrackIdGenerator(ids.trackId, 8192))
    }
    override fun seek() { if (!enhancement) reader.seek() }
    override fun consume(data: androidx.media3.common.util.ParsableByteArray, flags: Int) {
        if (enhancement) data.skipBytes(data.bytesLeft()) else reader.consume(data, flags)
    }
}

/** Forwards the same empty-PUSI EOF signal Media3 sends to unwrapped PES readers. */
internal class EndOfInputPayloadReader(private val reader: TsPayloadReader) : TsPayloadReader {
    private var initialized = false
    private var finished = false
    override fun init(adjuster: TimestampAdjuster, output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) {
        reader.init(adjuster, output, ids)
        initialized = true
        finished = false
    }
    override fun consume(data: androidx.media3.common.util.ParsableByteArray, flags: Int) = reader.consume(data, flags)
    override fun seek() {
        finished = false
        reader.seek()
    }
    fun finish() {
        if (!initialized || finished) return
        finished = true
        reader.consume(androidx.media3.common.util.ParsableByteArray(), TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR)
    }
}

/** Resolve private/incorrect PMT audio types from CLPI only after Media3 supplies the actual PID. */
internal class BluRayPidReader(
    private val audio: List<BluRayAudio>, private val advertisedType: Int, private val dualPidDv: Boolean,
    private val create: (Int) -> TsPayloadReader?
) : TsPayloadReader {
    private var reader: TsPayloadReader? = null
    override fun init(adjuster: TimestampAdjuster, output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) {
        ids.generateNewId()
        val pid = ids.trackId
        val type = if (dualPidDv && (pid == 0x1011 || pid == 0x1015)) 0x24
            else audio.firstOrNull { it.pid == pid && pid in 0x1100..0x111f }?.type ?: advertisedType
        val freshIds = TsPayloadReader.TrackIdGenerator(ids.formatId.substringBefore('/', "").toIntOrNull() ?: Int.MIN_VALUE, pid, 8192)
        reader = create(type)
        reader?.init(adjuster, output, freshIds)
        if (pid in 0x1100..0x111f) DebugLogger.log("ISO_AUDIO_BIND", "pid=$pid type=$type active=${reader != null}")
    }
    override fun consume(data: androidx.media3.common.util.ParsableByteArray, flags: Int) {
        reader?.consume(data, flags) ?: data.skipBytes(data.bytesLeft())
    }
    override fun seek() { reader?.seek() }
    fun finish() {
        (reader as? PesReader)?.consume(androidx.media3.common.util.ParsableByteArray(), TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR)
    }
}
