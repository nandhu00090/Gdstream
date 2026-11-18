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

/** Scoped to registered BD ISO streams. Other sources keep react-native-video's default extractors. */
internal class BluRayTsExtractor(private val profile: BluRayProfile?, dolbyVision: Boolean = false) : Extractor {
    private val dv = if (dolbyVision && profile?.streams?.any { it.pid == 0x1011 && it.type == 0x24 && it.height == 2160 } == true &&
        profile.streams.any { it.pid == 0x1015 && it.type == 0x24 }) BluRayDolbyVision() else null
    private val indexed = profile?.let { BluRaySeekMap(it) }?.takeIf { it.isSeekable }
    private val fallback = DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
    private val payloads = object : TsPayloadReader.Factory {
        override fun createInitialPayloadReaders() = fallback.createInitialPayloadReaders()
        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? = when (streamType) {
            0x81 -> PesReader(Ac3Reader(esInfo.language, 0, androidx.media3.common.MimeTypes.VIDEO_MP2T))
            0x83 -> PesReader(BluRayTrueHdReader(profile?.audio.orEmpty(), esInfo.language))
            0x90 -> PesReader(BluRayPgsReader(profile?.streams.orEmpty(), esInfo.language))
            0x24 -> fallback.createPayloadReader(streamType, esInfo)!!.let { dv?.payload(it) ?: BaseLayerPayloadReader(it) }
            0x80 -> PesReader(HdmvPcmReader(profile?.audio.orEmpty(), esInfo.language))
            0x82, 0x85, 0x86, 0x8a -> PesReader(BluRayDtsReader(streamType != 0x82, profile?.audio.orEmpty(), esInfo.language))
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
                val audio = profile?.audio?.firstOrNull { it.pid == id }
                return if (audio == null || type != C.TRACK_TYPE_AUDIO) target else object : TrackOutput by target {
                    override fun format(format: androidx.media3.common.Format) {
                        target.format(format.buildUpon().setLanguage(audio.language)
                            .setLabel(format.label ?: audio.label).build())
                    }
                }
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                DebugLogger.log("ISO_SEEK_MAP", "source=${if (indexed != null) "CLPI" else "PCR"} points=${profile?.points?.size ?: 0} durationUs=${profile?.durationUs ?: 0}")
                output.seekMap(indexed ?: seekMap)
            }
        })
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Unknown length disables the delegate's end-of-file PCR scan and binary-search seeker.
        val source = if (indexed == null) input else object : ForwardingExtractorInput(input) {
            override fun getLength() = C.LENGTH_UNSET.toLong()
        }
        return delegate.read(source, seekPosition).also {
            if (it == Extractor.RESULT_END_OF_INPUT) dv?.finish()
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
