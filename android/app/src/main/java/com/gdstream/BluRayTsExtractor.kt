package com.gdstream

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
    private val pcr = profile?.pcrIndex
    private val indexed: SeekMap? = if (pcr != null && profile != null) object : SeekMap {
        override fun getDurationUs() = profile.durationUs
        override fun isSeekable() = true
        override fun getSeekPoints(timeUs: Long) =
            SeekMap.SeekPoints(SeekPoint(timeUs, pcr.estimate(timeUs).position))
    } else profile?.let { BluRaySeekMap(it) }?.takeIf { it.isSeekable }
    private var checkSeekUs = 0L
    private var remainingProbes = 0
    private var inputLength = C.LENGTH_UNSET.toLong()
    private val videoReaders = ArrayList<EndOfInputPayloadReader>()
    private val pidReaders = ArrayList<BluRayPidReader>()
    private val audioTracks = profile?.audio.orEmpty().associateByTo(linkedMapOf()) { it.pid }
    private val streams = profile?.streams.orEmpty().associateByTo(linkedMapOf()) { it.pid }
    private val primaryLanguage = profile?.primaryLanguageHint.orEmpty().ifBlank {
        inferLanguageCodeFromName(profile?.discLabel.orEmpty())
    }
    private val fallback = DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
    private val payloads = object : TsPayloadReader.Factory {
        override fun createInitialPayloadReaders() = fallback.createInitialPayloadReaders()
        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader =
            BluRayPidReader(profile?.audio.orEmpty(), streamType, dv != null, ::pmtAudioFormat) { pid, resolved ->
                val stream = streams[pid] ?: BluRayStream(pid, resolved)
                val lang = sequenceOf(esInfo.language, audioTracks[pid]?.language, stream.language,
                    primaryLanguage.takeIf { stream.audio && (audioTracks.keys.firstOrNull() ?: pid) == pid })
                    .firstOrNull { !it.isNullOrBlank() && it != "und" }
                streams[pid] = stream.copy(language = lang.orEmpty())
                if (stream.audio) audioTracks[pid] = BluRayAudio(pid, resolved, lang.orEmpty())
                reader(resolved, TsPayloadReader.EsInfo(esInfo.streamType, lang, esInfo.audioType, esInfo.dvbSubtitleInfos, esInfo.descriptorBytes))
            }.also(pidReaders::add)
        private fun reader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? = when (streamType) {
            0x81, 0x84, 0xa1 -> PesReader(Ac3Reader(esInfo.language, 0, androidx.media3.common.MimeTypes.VIDEO_MP2T))
            0x83 -> PesReader(BluRayTrueHdReader(audioTracks.values.toList(), esInfo.language))
            0x90 -> PesReader(BluRayPgsReader(streams.values.toList(), esInfo.language))
            0x24 -> fallback.createPayloadReader(streamType, esInfo)!!.let {
                EndOfInputPayloadReader(dv?.payload(it) ?: BaseLayerPayloadReader(it)).also(videoReaders::add)
            }
            0x80 -> PesReader(HdmvPcmReader(audioTracks.values.toList(), esInfo.language))
            0x82, 0x85, 0x86, 0x8a, 0xa2 -> PesReader(BluRayDtsReader(streamType != 0x82, audioTracks.values.toList(), esInfo.language))
            else -> fallback.createPayloadReader(streamType, esInfo)
        }
    }
    private fun pmtAudioFormat(pid: Int, type: Int, formatId: String): Format? {
        val mime = when (type) {
            0x81 -> MimeTypes.AUDIO_AC3
            0x82 -> MimeTypes.AUDIO_DTS
            0x83 -> MimeTypes.AUDIO_TRUEHD
            0x84, 0xa1 -> MimeTypes.AUDIO_E_AC3
            0x85, 0x86 -> MimeTypes.AUDIO_DTS_HD
            else -> return null
        }
        val layout = streams[pid]?.layout.orEmpty().substringBefore(' ').split('.')
        val channels = layout.mapNotNull { it.toIntOrNull() }.sum()
        return Format.Builder().setId(formatId).setContainerMimeType(MimeTypes.VIDEO_MP2T)
            .setSampleMimeType(mime).setChannelCount(channels.takeIf { it > 0 } ?: Format.NO_VALUE).build()
    }

    private var subtitles: androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput? = null
    private val delegate = TsExtractor(TsExtractor.MODE_SINGLE_PMT, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        DefaultSubtitleParserFactory(), TimestampAdjuster(0), payloads, 600 * 188)
    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) {
        val transcoded = androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput(output, DefaultSubtitleParserFactory())
        subtitles = transcoded
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val target = transcoded.track(id, type)
                val audioIdx = audioTracks.keys.indexOf(id)
                val audio = audioTracks[id]
                return object : TrackOutput by target {
                    override fun format(format: Format) {
                        val enriched = if (audio != null && type == C.TRACK_TYPE_AUDIO) {
                            val ch = audioChannelLabel(format.channelCount)
                            val lang = audio.language.takeIf { it.isNotBlank() && it != "und" } ?: format.language
                            format.buildUpon().setLanguage(lang)
                                .setLabel(audio.copy(language = lang.orEmpty()).displayLabel(audioIdx, ch)).build()
                        } else format
                        if (type == C.TRACK_TYPE_AUDIO) DebugLogger.log("ISO_AUDIO_FORMAT",
                            "pid=$id id=${enriched.id} mime=${enriched.sampleMimeType} channels=${enriched.channelCount} rate=${enriched.sampleRate} language=${enriched.language} label=${enriched.label}")
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
                DebugLogger.log("ISO_SEEK_MAP", "source=${if (pcr != null) "sparse PCR" else if (indexed != null) "CLPI" else "PCR"} points=${pcr?.pointCount ?: profile?.points?.size ?: 0} durationUs=${profile?.durationUs ?: 0}")
                val duration = profile?.durationUs ?: 0
                val measured = if (duration > 0 && inputLength >= 188) object : SeekMap {
                    override fun getDurationUs() = duration
                    override fun isSeekable() = true
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                        if (seekMap.isSeekable) {
                            // The proxy strips the four arrival-time bytes. Start PCR probes on its TS grid.
                            val points = seekMap.getSeekPoints(timeUs)
                            fun align(p: SeekPoint) = SeekPoint(p.timeUs, p.position / 188 * 188)
                            return SeekMap.SeekPoints(align(points.first), align(points.second))
                        }
                        val fraction = timeUs.coerceIn(0L, duration).toDouble() / duration
                        val packet = (fraction * (inputLength / 188)).toLong().coerceAtMost(inputLength / 188 - 1)
                        return SeekMap.SeekPoints(SeekPoint(timeUs, packet * 188))
                    }
                } else seekMap
                output.seekMap(indexed ?: measured)
            }
        })
    }
    private fun measureClock(input: ExtractorInput, index: StandalonePcrIndex): BluRayPoint? {
        val length = minOf(188L * 3072, (input.length - input.position).coerceAtLeast(0)).toInt()
        val bytes = ByteArray(188 * 256)
        try {
            var peeked = 0
            while (peeked + 188 <= length) {
                val count = minOf(bytes.size, length - peeked) / 188 * 188
                if (!input.peekFully(bytes, 0, count, true)) break
                index.sample(bytes, 188, input.position + peeked, count)?.let { return it }
                peeked += count
            }
            return null
        } finally { input.resetPeekPosition() }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        inputLength = input.length
        if (pcr != null && remainingProbes > 0) {
            remainingProbes--
            // Correct VBR interpolation from the local clock before the delegate consumes any packets.
            val measured = measureClock(input, pcr)
            if (measured != null && (measured.timeUs > checkSeekUs || checkSeekUs - measured.timeUs > 2_000_000)) {
                val corrected = pcr.estimate(checkSeekUs)
                if (corrected.position != input.position) {
                    seekPosition.position = corrected.position
                    return Extractor.RESULT_SEEK
                }
            }
            remainingProbes = 0
        }
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
        checkSeekUs = timeUs
        remainingProbes = if (pcr != null && position > 0) 2 else 0
        val pointTime = pcr?.estimate(timeUs)?.timeUs ?: profile?.points?.lastOrNull { it.position <= position }?.timeUs ?: timeUs
        DebugLogger.log(if (pcr == null) "ISO_CLPI_SEEK" else "BDAV_PCR_SEEK", "position=$position requestedUs=$timeUs pointUs=$pointTime indexed=${indexed != null}")
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
    private val pmtFormat: (Int, Int, String) -> Format?,
    private val create: (Int, Int) -> TsPayloadReader?
) : TsPayloadReader {
    private var reader: TsPayloadReader? = null
    override fun init(adjuster: TimestampAdjuster, output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) {
        ids.generateNewId()
        val pid = ids.trackId
        val type = if (dualPidDv && (pid == 0x1011 || pid == 0x1015)) 0x24
            else audio.firstOrNull { it.pid == pid && pid in 0x1100..0x111f }?.type ?: advertisedType
        val freshIds = TsPayloadReader.TrackIdGenerator(ids.formatId.substringBefore('/', "").toIntOrNull() ?: Int.MIN_VALUE, pid, 8192)
        reader = create(pid, type)
        // Publish the PMT format before endTracks, even when the first audio PES is far away.
        // The elementary reader refines the same track and format ID when its first frame arrives.
        pmtFormat(pid, type, ids.formatId)?.let { output.track(pid, C.TRACK_TYPE_AUDIO).format(it) }
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
