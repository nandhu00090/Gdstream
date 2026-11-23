package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BluRayExtractorTest {
    private data class Sample(val time: Long, val size: Int)
    private class Capture : ExtractorOutput {
        val formats = ArrayList<Format>()
        val samples = ArrayList<Sample>()
        val syncSamples = ArrayList<Long>()
        val samplesByPid = HashMap<Int, Int>()
        val data = java.io.ByteArrayOutputStream()
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput {
            // Run TrackOutput's two-argument default method so both Media3 write paths consume input.
            val output = mock(TrackOutput::class.java, CALLS_REAL_METHODS)
            doAnswer { formats.add(it.getArgument(0)); null }.`when`(output).format(any(Format::class.java))
            doAnswer {
                val source = it.getArgument<ParsableByteArray>(0)
                val count = it.getArgument<Int>(1)
                val bytes = ByteArray(count); source.readBytes(bytes, 0, count); data.write(bytes); null
            }.`when`(output).sampleData(any(ParsableByteArray::class.java), anyInt(), anyInt())
            doAnswer {
                samples.add(Sample(it.getArgument(0), it.getArgument(2)))
                if (it.getArgument<Int>(1) and C.BUFFER_FLAG_KEY_FRAME != 0) syncSamples.add(it.getArgument(0))
                samplesByPid[id] = (samplesByPid[id] ?: 0) + 1
                null
            }.`when`(output)
                .sampleMetadata(anyLong(), anyInt(), anyInt(), anyInt(), isNull())
            return output
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
    @Test fun hlsReaderCommitsDvdIdrAndSubsequentFramesWithPresentationTimes() {
        // Three real baseline 32x32 access units from libx264, stripped of AUD and SEI.
        fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mux = DvdTsMux()
        mux.video(0, hex("000000016742c00ad9096c0440000003004000000c83c48992"), true)
        mux.video(0, hex("0000000168cb83cb20"), true)
        mux.video(90_000, hex("000000016588840bf2628000abcc9d75e0"))
        mux.video(93_600, hex("00000001419a3817e580"))
        mux.video(97_200, hex("00000001419a54057960"))
        val bytes = mux.finish(90_000, 100_800)
        val ts = androidx.media3.extractor.ts.TsExtractor(androidx.media3.extractor.ts.TsExtractor.MODE_HLS,
            androidx.media3.common.util.TimestampAdjuster(0), androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory())
        val capture = Capture()
        ts.init(capture)
        val stream = bytes.inputStream()
        val input = DefaultExtractorInput(stream::read, 0, C.LENGTH_UNSET.toLong())
        while (ts.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
        assertTrue(capture.formats.any { it.sampleMimeType == "video/avc" && it.width == 32 })
        assertFalse(capture.formats.any { it.sampleMimeType == "application/cea-608" })
        assertEquals(listOf(0L, 40_000L), capture.samples.take(2).map { it.time })
        ts.release()
    }

    @Test fun standaloneSeekParsesBothRenditionsAndReadsFirstIdrWithoutInBandParameterSets() {
        val head = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes()
        val m2ts = ByteArray(head.size / 188 * 192)
        for (p in head.indices step 188) head.copyInto(m2ts, p / 188 * 192 + 4, p, p + 188)
        val opening = StandaloneBdav.profile(m2ts, m2ts, "Movie.tamil.m2ts")
        val program = checkNotNull(opening.program)
        val avc = checkNotNull(opening.avc)
        val streams = program.streams.map { if (it.pid == 4352) it.copy(type = 0x81, language = "tam") else it }
        val profile = opening.copy(streams = streams).also { it.avc = avc }
        val hls = BluRayHls.create(profile, head.size.toLong(), standalone = true, convertDts = true)!!
        val uri = android.net.Uri.parse("http://localhost/hls/test/index.m3u8")
        val master = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(uri, hls.master().inputStream())
            as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
        assertEquals(listOf("audio_4352.m3u8", "audio_4353.m3u8"), master.audios.map { it.url!!.lastPathSegment })
        assertEquals(listOf(C.SELECTION_FLAG_AUTOSELECT or C.SELECTION_FLAG_DEFAULT, C.SELECTION_FLAG_AUTOSELECT),
            master.audios.map { it.format.selectionFlags })
        assertTrue(master.audios.all { it.format.sampleMimeType == "audio/ac3" })

        // Keep the real IDR and subsequent slices, remove the fixture's in-band
        // SPS/PPS from the first video PES. Leading zero bytes are valid Annex B padding.
        val body = head.copyOf()
        val video = (body.indices step 188).first { body[it+1].toInt() and 0x5f == 0x50 && body[it+2] == 0x11.toByte() }
        val sps = (video until video + 184).first { body[it] == 1.toByte() && body[it+1] == 0x67.toByte() } - 3
        val sei = (sps + 4 until video + 184).first { body[it] == 1.toByte() && body[it+1] == 6.toByte() } - 2
        body.fill(0, sps, sei)
        assertNull(StandaloneBdav.avcConfig(body, 0x1011))
        val psi = hls.prefix(program, streams, null)
        val source = head + body
        fun read(pos: Long, n: Int) = source.copyOfRange(pos.toInt(), pos.toInt() + n)
        val (segment, header) = avc.videoSegment(BluRayHls.Segment(head.size.toLong(), source.size.toLong(), 120_000),
            psi, source.size.toLong(), ::read)
        val seek = segment.read(header, 0, segment.size(header).toInt(), ::read)
        ExternalTsPmt.patch(seek, streams, videoOnly = true)
        val extractor = androidx.media3.extractor.ts.TsExtractor(androidx.media3.extractor.ts.TsExtractor.MODE_HLS,
            androidx.media3.common.util.TimestampAdjuster(0), androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory())
        val capture = Capture()
        extractor.init(capture)
        val input = DefaultExtractorInput(seek.inputStream()::read, 0, C.LENGTH_UNSET.toLong())
        while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
        extractor.release()
        assertTrue(capture.formats.any { it.sampleMimeType == "video/avc" && it.width == 64 && it.height == 64 })
        assertEquals(0L, capture.syncSamples.first())
        assertEquals(listOf(0L, 40_000L), capture.samples.take(2).map { it.time })
    }

    @Test fun latePmtAudioUsesInferredPrimaryLanguageAndNumberedSecondaryTrack() {
        val bytes = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes()
        val profile = BluRayProfile(120000, listOf(BluRayPoint(0, 0), BluRayPoint(100000, 188)),
            emptyList(), discLabel = "Jilla.Tamil.m2ts")
        val extractor = BluRayTsExtractor(profile)
        val capture = Capture()
        extractor.init(capture)
        val input = DefaultExtractorInput(bytes.inputStream()::read, 0, bytes.size.toLong())
        while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
        assertTrue(capture.formats.any { it.sampleMimeType == "audio/raw" && it.language == "ta" })
        assertTrue(capture.formats.any { it.label?.startsWith("Audio 2 • DTS") == true })
        extractor.release()
    }

    @Test fun secondaryDtsGetsAFormatFromPmtWithoutAnyPesOrProfileAudioCatalog() {
        val bytes = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes()
        val psi = BluRayPsi.program(bytes)!!
        // Keep only segment-zero PAT/PMT packets. No DTS reader can discover a frame.
        val tables = bytes.asList().chunked(188).filter { packet ->
            val pid = ((packet[1].toInt() and 31) shl 8) or (packet[2].toInt() and 255)
            pid == 0 || pid == 0x100
        }.flatten().toByteArray()
        val streams = psi.streams.map { if (it.pid == 4353) it.copy(layout = "5.1 ch") else it }
        val extractor = BluRayTsExtractor(BluRayProfile(120_000, emptyList(), emptyList(), streams))
        val capture = Capture()
        extractor.init(capture)
        val input = DefaultExtractorInput(tables.inputStream()::read, 0, C.LENGTH_UNSET.toLong())
        while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
        assertTrue(capture.samples.isEmpty())
        assertTrue(capture.formats.any { it.sampleMimeType == "audio/vnd.dts" && it.label == "Audio 2 • DTS • 5.1" })
        extractor.release()
    }

    @Test fun fragmentedLpcmPesExposesTamilRawTrackWithExactSamplesAndPts() {
        val capture = Capture()
        val reader = HdmvPcmReader(listOf(BluRayAudio(0x1100, 0x80, "tam")), null)
        reader.createTracks(capture, TsPayloadReader.TrackIdGenerator(0x1100, 8192))
        val frame = byteArrayOf(0, 12, 0x91.toByte(), 0x40) + ByteArray(12) { (it + 1).toByte() }
        reader.packetStarted(1_000_000, 0)
        frame.forEach { reader.consume(ParsableByteArray(byteArrayOf(it))) }
        assertEquals("audio/raw", capture.formats.single().sampleMimeType)
        assertEquals("ta", capture.formats.single().language)
        assertEquals("Tamil • LPCM • 5.1", capture.formats.single().label)
        assertEquals(6, capture.formats.single().channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, capture.formats.single().pcmEncoding)
        assertEquals(listOf(Sample(1_000_000, 12)), capture.samples)
        assertArrayEquals(byteArrayOf(2, 1, 4, 3, 6, 5, 12, 11, 8, 7, 10, 9), capture.data.toByteArray())
        reader.seek(); reader.packetStarted(8_000_000, 0); reader.consume(ParsableByteArray(frame))
        assertEquals(Sample(8_000_000, 12), capture.samples.last())
    }
    private fun core(): ByteArray {
        val data = ByteArray(100)
        TestBits(data).put(0x7ffe8001, 32).put(1, 1).put(31, 5).put(0, 1).put(15, 7).put(99, 14)
            .put(9, 6).put(13, 4).put(10, 5).put(0, 10).put(1, 2)
        return data
    }
    private fun extension(): ByteArray {
        val data = ByteArray(40)
        TestBits(data).put(0x64582025, 32).put(0, 8).put(0, 2).put(0, 1).put(19, 8).put(39, 16)
            .put(0, 1).put(19, 16).put(0, 12) // no static fields; use core rate/channels
        return data
    }
    @Test fun dtsHdCoreAndExtensionRemainOneSampleAcrossPesBoundaries() {
        val capture = Capture()
        val reader = BluRayDtsReader(true, listOf(BluRayAudio(0x1101, 0x86, "tam")), null)
        reader.createTracks(capture, TsPayloadReader.TrackIdGenerator(0x1101, 8192))
        val combined = core() + extension()
        reader.packetStarted(2_000_000, 0)
        reader.consume(ParsableByteArray(combined.copyOfRange(0, 102)))
        reader.packetFinished(false)
        assertTrue(capture.samples.isEmpty())
        reader.packetStarted(C.TIME_UNSET, 0)
        combined.copyOfRange(102, combined.size).forEach { reader.consume(ParsableByteArray(byteArrayOf(it))) }
        assertEquals(listOf(Sample(2_000_000, 140)), capture.samples)
        assertArrayEquals(combined, capture.data.toByteArray())
        assertEquals("audio/vnd.dts.hd", capture.formats.single().sampleMimeType)
        assertEquals(6, capture.formats.single().channelCount)
        assertEquals("Tamil • DTS-HD MA • 5.1", capture.formats.single().label)
        // A core-only HD frame is emitted at EOF, not silently discarded waiting for EXTSS.
        reader.consume(ParsableByteArray(core())); reader.packetFinished(true)
        assertEquals(2, capture.samples.size)
        reader.seek()
        reader.packetStarted(9_000_000, 0)
        reader.consume(ParsableByteArray(extension())) // No cached pre-seek configuration.
        assertEquals(2, capture.samples.size)
        reader.consume(ParsableByteArray(core() + extension()))
        assertEquals(Sample(9_000_000, 140), capture.samples.last())
    }
    @Test fun seekMapDirectlySelectsEndOfMovieAccessPointAbove40Gb() {
        val profile = BluRayProfile(10009_000_000, listOf(BluRayPoint(0, 0),
            BluRayPoint(9990_000_000, 41_360_000_000), BluRayPoint(10000_000_000, 41_400_000_000)), emptyList())
        val map = BluRaySeekMap(profile)
        assertTrue(map.isSeekable)
        assertEquals(10009_000_000, map.durationUs)
        val points = map.getSeekPoints(9995_000_000)
        assertEquals(41_360_000_000, points.first.position)
        assertEquals(41_400_000_000, points.second.position)
        assertEquals(points.first, map.getSeekPoints(9990_000_000).first)
    }
    @Test fun realHdmvTransportExposesBothAudioTracksAndNeverRequestsPcrBinarySearch() {
        val bytes = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.use { it.readBytes() }
        val profile = BluRayProfile(10009_000_000, listOf(BluRayPoint(0, 0),
            BluRayPoint(9990_000_000, 41_360_000_000)), listOf(
                BluRayAudio(0x1100, 0x80, "tam"), BluRayAudio(0x1101, 0x82, "tam")))
        val extractor = BluRayTsExtractor(profile)
        val capture = Capture()
        extractor.init(capture)
        fun readAt(position: Long) {
            val stream = java.io.ByteArrayInputStream(bytes)
            val input = DefaultExtractorInput({ target, offset, count -> stream.read(target, offset, count) }, position, 43_000_000_000L)
            if (position == 0L) { assertTrue(extractor.sniff(input)); input.resetPeekPosition() }
            val holder = PositionHolder()
            var ended = false
            repeat(3000) {
                if (!ended) {
                    val result = extractor.read(input, holder)
                    assertNotEquals("CLPI must avoid PCR probe at ${holder.position}", Extractor.RESULT_SEEK, result)
                    ended = result == Extractor.RESULT_END_OF_INPUT
                }
            }
            assertTrue(ended)
        }
        readAt(0)
        assertTrue(capture.formats.any { it.sampleMimeType == "video/avc" })
        assertTrue(capture.formats.any { it.sampleMimeType == "audio/raw" && it.channelCount == 6 })
        assertTrue(capture.formats.any { it.sampleMimeType == "audio/vnd.dts" && it.channelCount == 6 })
        assertTrue(capture.samples.size > 10)
        assertEquals(10009_000_000, capture.map!!.durationUs)
        val access = capture.map!!.getSeekPoints(9995_000_000).first
        assertEquals(41_360_000_000, access.position)
        extractor.seek(access.position, 9995_000_000)
        readAt(access.position) // One direct reopen at the EP; zero follow-up binary-search reopens.
        extractor.release()
    }

    @Test fun unlabelledSecondaryDtsHasTheSameNameInCatalogAndMediaInfo() {
        fun group(mime: String, language: String?) = androidx.media3.common.Tracks.Group(
            androidx.media3.common.TrackGroup(mime, Format.Builder().setContainerMimeType("video/mp2t")
                .setSampleMimeType(mime).setChannelCount(6).setLanguage(language).build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val catalog = AudioTrackCatalog.list(androidx.media3.common.Tracks(listOf(
            group("audio/ac3", "tam"), group("audio/vnd.dts", null))))
        assertNull(catalog[1].language)
        assertEquals("Audio 2 • DTS • 5.1", catalog[1].title)
        val profile = BluRayProfile(1, emptyList(), emptyList(), listOf(
            BluRayStream(4352, 0x81, language = "tam"), BluRayStream(4353, 0x82, layout = "5.1 ch")))
        val info = profile.mediaInfo(1)
        assertEquals(listOf("Tamil"), info["languages"])
        assertEquals(catalog[1].title, ((info["audioTracks"] as List<*>)[1] as Map<*, *>)["summary"])
    }

    @Test fun selectorListsAndSwitchesAudioAcrossSoftwareAndPlatformRenderers() {
        val pcm = androidx.media3.common.TrackGroup("pcm", Format.Builder().setSampleMimeType("audio/raw").setLabel("Tamil LPCM").setChannelCount(6).build())
        val dts = androidx.media3.common.TrackGroup("dts", Format.Builder().setSampleMimeType("audio/vnd.dts.hd").setLabel("Tamil DTS-HD MA").setChannelCount(6).build())
        val tracks = androidx.media3.common.Tracks(listOf(
            androidx.media3.common.Tracks.Group(dts, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)),
            androidx.media3.common.Tracks.Group(pcm, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        val list = AudioTrackCatalog.list(tracks)
        assertEquals(listOf("Tamil DTS-HD MA", "Tamil LPCM"), list.map { it.title })
        assertEquals(listOf(0, 1), list.map { it.index })
        assertFalse(list[0].isSelected); assertTrue(list[1].isSelected)
        val player = mock(androidx.media3.exoplayer.ExoPlayer::class.java)
        `when`(player.currentTracks).thenReturn(tracks)
        `when`(player.trackSelectionParameters).thenReturn(androidx.media3.common.TrackSelectionParameters.Builder().build())
        assertFalse(AudioTrackCatalog.select(player, "default", null, false)) // Preserve non-ISO auto policy.
        assertTrue(AudioTrackCatalog.select(player, "default", null, true))
        AudioTrackCatalog.select(player, "index", "0")
        val chosen = org.mockito.ArgumentCaptor.forClass(androidx.media3.common.TrackSelectionParameters::class.java)
        verify(player).setTrackSelectionParameters(chosen.capture())
        assertEquals(dts, chosen.value.overrides.values.single().mediaTrackGroup)
        clearInvocations(player)
        AudioTrackCatalog.select(player, "index", "1")
        verify(player).setTrackSelectionParameters(chosen.capture())
        assertEquals(pcm, chosen.value.overrides.values.single().mediaTrackGroup)
    }

    @Test fun enhancementPidNeverCreatesAVideoTrack() {
        val delegate = mock(TsPayloadReader::class.java)
        val reader = BaseLayerPayloadReader(delegate)
        reader.init(androidx.media3.common.util.TimestampAdjuster(0), Capture(), TsPayloadReader.TrackIdGenerator(0x1015, 8192))
        val bytes = ParsableByteArray(ByteArray(100))
        reader.consume(bytes, 0); reader.seek()
        assertEquals(0, bytes.bytesLeft())
        verifyNoInteractions(delegate)
    }

    @Test fun pgsDisplaySetSpansPesAndSeekDropsPartialSubtitle() {
        val capture = Capture()
        val reader = BluRayPgsReader(listOf(BluRayStream(0x1200, 0x90, language = "tam")), null)
        reader.createTracks(capture, TsPayloadReader.TrackIdGenerator(0x1200, 8192))
        val display = byteArrayOf(0x16, 0, 2, 1, 2, 0x80.toByte(), 0, 0)
        reader.packetStarted(1000, 0)
        display.forEach { reader.consume(ParsableByteArray(byteArrayOf(it))) }
        assertEquals("application/pgs", capture.formats.single().sampleMimeType)
        assertEquals(listOf(Sample(1000, 8)), capture.samples)
        assertArrayEquals(display, capture.data.toByteArray())
        reader.consume(ParsableByteArray(display.copyOf(4)))
        reader.seek(); reader.packetStarted(9000000, 0)
        reader.consume(ParsableByteArray(display))
        assertEquals(Sample(9000000, 8), capture.samples.last())
    }

    @Test fun trueHdWaitsForMajorSyncAfterSeekAndSkipsAc3Companion() {
        val capture = Capture()
        val reader = BluRayTrueHdReader(listOf(BluRayAudio(0x1100, 0x83, "tam")), null)
        reader.createTracks(capture, TsPayloadReader.TrackIdGenerator(0x1100, 8192))
        val major = ByteArray(32)
        major[1] = 16; major[4] = 0xf8.toByte(); major[5] = 0x72; major[6] = 0x6f; major[7] = 0xba.toByte()
        major[11] = 1 // stereo, 48 kHz
        val ac3 = ByteArray(128); ac3[0] = 0x0b; ac3[1] = 0x77
        val minor = ByteArray(12); minor[1] = 6
        reader.packetStarted(1000, 0)
        (ac3 + major + minor).forEach { reader.consume(ParsableByteArray(byteArrayOf(it))) }
        assertEquals("audio/true-hd", capture.formats.single().sampleMimeType)
        assertEquals(2, capture.formats.single().channelCount)
        assertEquals(listOf(32, 12), capture.samples.map { it.size })
        reader.seek(); reader.packetStarted(9000000, 0)
        reader.consume(ParsableByteArray(minor + major))
        assertEquals(Sample(9000000, 32), capture.samples.last())
        assertEquals(3, capture.samples.size)
    }

    @Test fun manualIsoSelectionClearsRendererDisableWithoutSeekingOrReloading() {
        val group = androidx.media3.common.TrackGroup("ac3", Format.Builder().setSampleMimeType("audio/ac3").build())
        val tracks = androidx.media3.common.Tracks(listOf(androidx.media3.common.Tracks.Group(
            group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))))
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
        selector.setParameters(selector.buildUponParameters().setRendererDisabled(1, true).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true))
        val player = mock(androidx.media3.exoplayer.ExoPlayer::class.java)
        `when`(player.trackSelector).thenReturn(selector)
        `when`(player.currentTracks).thenReturn(tracks)
        `when`(player.rendererCount).thenReturn(2)
        `when`(player.getRendererType(1)).thenReturn(C.TRACK_TYPE_AUDIO)
        `when`(player.currentPosition).thenReturn(9000000)
        AudioTrackCatalog.select(player, "index", "0")
        val chosen = org.mockito.ArgumentCaptor.forClass(androidx.media3.common.TrackSelectionParameters::class.java)
        verify(player).setTrackSelectionParameters(chosen.capture())
        val params = chosen.value as androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
        assertFalse(params.getRendererDisabled(1))
        assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
        assertEquals(group, params.overrides.values.single().mediaTrackGroup)
        verify(player, never()).seekTo(anyLong())
        verify(player, never()).prepare()
        selector.release()
    }

    @Test fun all32AudioPidsIncludingTamil1106EmitSamplesEvenWithPrivatePmtTypes() {
        val fixture = javaClass.getResourceAsStream("/bluray/hdmv.ts.bin")!!.readBytes()
        val packets = fixture.asList().chunked(188).map { it.toByteArray() }
        fun pid(packet: ByteArray) = ((packet[1].toInt() and 31) shl 8) + (packet[2].toInt() and 255)
        val audio = (0x1100..0x111f).mapIndexed { index, pid -> BluRayAudio(pid, listOf(0x80, 0x82, 0x81, 0x84)[index % 4], "tam") }
        fun packetize(pid: Int, payload: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var offset = 0; var cc = 0
            while (offset < payload.size) {
                val count = minOf(184, payload.size - offset)
                val packet = ByteArray(188) { 0xff.toByte() }
                packet[0] = 0x47; packet[1] = ((pid ushr 8) or if (offset == 0) 0x40 else 0).toByte()
                packet[2] = pid.toByte(); packet[3] = ((if (count == 184) 0x10 else 0x30) or cc++).toByte()
                if (count < 184) { packet[4] = (183 - count).toByte(); if (count < 183) packet[5] = 0 }
                payload.copyInto(packet, 188 - count, offset, offset + count)
                out.write(packet); offset += count
            }
            return out.toByteArray()
        }
        // Private ES with no descriptors resolves to -1 in Media3; CLPI must bind by PID.
        val body = byteArrayOf(2, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0xf0.toByte(), 0x11, 0xf0.toByte(), 0) +
            (listOf(0x1b to 0x1011) + audio.map { 0x06 to it.pid }).flatMap { (type, pid) ->
                listOf(type.toByte(), (0xe0 or (pid ushr 8)).toByte(), pid.toByte(), 0xf0.toByte(), 0)
            }.toByteArray()
        body[1] = (0xb0 or ((body.size + 1) ushr 8)).toByte(); body[2] = (body.size + 1).toByte()
        val crc = ExternalTsPmt.crc(body)
        val section = body + ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() }
        val stream = java.io.ByteArrayOutputStream()
        stream.write(packets.first { pid(it) == 0 })
        stream.write(packetize(0x100, byteArrayOf(0) + section))
        packets.filter { pid(it) == 0x1011 }.forEach(stream::write)
        for (track in audio) {
            if (track.type == 0x80 || track.type == 0x82) {
                packets.filter { pid(it) == if (track.type == 0x80) 0x1100 else 0x1101 }.forEach { original ->
                    val packet = original.copyOf(); packet[1] = ((packet[1].toInt() and 0xe0) or (track.pid ushr 8)).toByte()
                    packet[2] = track.pid.toByte(); stream.write(packet)
                }
            } else {
                val frame = javaClass.getResourceAsStream(if (track.type == 0x81) "/bluray/silent.ac3.bin" else "/bluray/silent.eac3.bin")!!.readBytes()
                val length = frame.size + 8
                val pes = byteArrayOf(0, 0, 1, 0xbd.toByte(), (length ushr 8).toByte(), length.toByte(),
                    0x80.toByte(), 0x80.toByte(), 5, 0x21, 0, 7, 0xd8.toByte(), 0x61) + frame
                stream.write(packetize(track.pid, pes))
            }
        }
        val bytes = stream.toByteArray()
        val capture = Capture()
        val extractor = BluRayTsExtractor(BluRayProfile(120000, listOf(BluRayPoint(0, 0), BluRayPoint(100000, 188)), audio))
        extractor.init(capture)
        val source = java.io.ByteArrayInputStream(bytes)
        val input = DefaultExtractorInput({ target, offset, count -> source.read(target, offset, count) }, 0, bytes.size.toLong())
        val holder = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        while (result != Extractor.RESULT_END_OF_INPUT) {
            result = extractor.read(input, holder)
            assertNotEquals(Extractor.RESULT_SEEK, result)
        }
        for (track in audio) {
            assertTrue("No samples on PID ${track.pid.toString(16)}", (capture.samplesByPid[track.pid] ?: 0) > 0)
            assertTrue("Missing Tamil format on PID ${track.pid}", capture.formats.any { it.id == "1/${track.pid}" && it.language == "ta" })
        }
        assertEquals(setOf("audio/raw", "audio/vnd.dts", "audio/ac3", "audio/eac3"), capture.formats.mapNotNull { it.sampleMimeType }.filter { it.startsWith("audio/") }.toSet())
        extractor.release()
    }

}
