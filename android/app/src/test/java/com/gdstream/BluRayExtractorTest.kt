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
        val data = java.io.ByteArrayOutputStream()
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput {
            val output = mock(TrackOutput::class.java)
            doAnswer { formats.add(it.getArgument(0)); null }.`when`(output).format(any(Format::class.java))
            doAnswer {
                val source = it.getArgument<ParsableByteArray>(0)
                val count = it.getArgument<Int>(1)
                val bytes = ByteArray(count); source.readBytes(bytes, 0, count); data.write(bytes); null
            }.`when`(output).sampleData(any(ParsableByteArray::class.java), anyInt())
            doAnswer { samples.add(Sample(it.getArgument(0), it.getArgument(2))); null }.`when`(output)
                .sampleMetadata(anyLong(), anyInt(), anyInt(), anyInt(), isNull())
            return output
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
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
        assertEquals("Tamil LPCM • 5.1", capture.formats.single().label)
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
        assertEquals("Tamil DTS-HD MA • 5.1", capture.formats.single().label)
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

    @Test fun manualIsoSelectionClearsRendererDisableAndFlushesSameTrack() {
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
        verify(player, times(2)).setTrackSelectionParameters(chosen.capture())
        val params = chosen.value as androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
        assertFalse(params.getRendererDisabled(1))
        assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
        assertEquals(group, params.overrides.values.single().mediaTrackGroup)
        verify(player).seekTo(9000000)
        selector.release()
    }

}
