package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.*
import androidx.media3.extractor.text.SubtitleParser
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
internal class BluRayTsExtractor(private val profile: BluRayProfile?) : Extractor {
    private val indexed = profile?.let { BluRaySeekMap(it) }?.takeIf { it.isSeekable }
    private val fallback = DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
    private val payloads = object : TsPayloadReader.Factory {
        override fun createInitialPayloadReaders() = fallback.createInitialPayloadReaders()
        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? = when (streamType) {
            0x80 -> PesReader(HdmvPcmReader(profile?.audio.orEmpty(), esInfo.language))
            0x82, 0x85, 0x86, 0x8a -> PesReader(BluRayDtsReader(streamType != 0x82, profile?.audio.orEmpty(), esInfo.language))
            else -> fallback.createPayloadReader(streamType, esInfo)
        }
    }
    private val delegate = TsExtractor(TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        SubtitleParser.Factory.UNSUPPORTED, TimestampAdjuster(0), payloads, 112800)
    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)
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
        return delegate.read(source, seekPosition)
    }
    override fun seek(position: Long, timeUs: Long) {
        val pointTime = profile?.points?.lastOrNull { it.position <= position }?.timeUs ?: timeUs
        DebugLogger.log("ISO_CLPI_SEEK", "position=$position requestedUs=$timeUs pointUs=$pointTime indexed=${indexed != null}")
        delegate.seek(position, if (indexed != null) pointTime else timeUs)
    }
    override fun release() = delegate.release()
}
