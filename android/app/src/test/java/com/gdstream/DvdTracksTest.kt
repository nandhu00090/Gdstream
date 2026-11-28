package com.gdstream

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.text.Cue
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.*
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.dvb.DvbParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DvdTracksTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun picture(mux: DvdTsMux, start: Long, count: Int = 1) {
        mux.beginSegment(true)
        mux.video(0, hex("000000016742c00ad9096c0440000003004000000c83c48992"), true)
        mux.video(0, hex("0000000168cb83cb20"), true)
        mux.video(start, hex("000000016588840bf2628000abcc9d75e0"))
        for (i in 1 until count) mux.video(start + i * 3600L, hex("000000016588840bf2628000abcc9d75e0"))
    }

    private fun tracks(): DvdStreams {
        val palette = IntArray(16)
        palette[1] = 0xeb8080 // white
        palette[2] = 0x515af0 // blue (Y, Cr, Cb)
        val audio = listOf(DvdStreams.Track(0x83, "tam", 6, "AC-3"), DvdStreams.Track(0x89, "eng", 6, "DTS"))
        val subs = listOf("eng", "tam", "fra", "und").mapIndexed { i, code -> DvdStreams.Track(0x25 + i, code) }
        val pgc = DvdStreams.Program(audio.map { it.id }, subs.map { it.id }, palette)
        return DvdStreams(audio, subs, listOf(pgc), listOf(0L to 0))
    }

    @Test fun segmentZeroAdvertisesEveryPidWithItsDescriptorsEvenForAMaximumSizePmt() {
        val tracks = DvdStreams(List(8) { DvdStreams.Track(0x80 + it, "tam", 6) },
            List(32) { DvdStreams.Track(0x20 + it, "eng") })
        val mux = DvdTsMux(tracks)
        picture(mux, 90_000)
        val ts = mux.finish(90_000, 93_600)
        val program = checkNotNull(BluRayPsi.program(ts)) // verifies section length, continuation and CRC
        val pmt = program.pmt
        assertTrue(pmt.size > 256)
        assertEquals(tracks.videoPid, program.pcrPid)
        var p = 12
        val ids = mutableSetOf<Int>()
        while (p < pmt.size - 4) {
            val type = pmt[p].toInt() and 255
            val pid = ((pmt[p + 1].toInt() and 31) shl 8) or (pmt[p + 2].toInt() and 255)
            val n = ((pmt[p + 3].toInt() and 15) shl 8) or (pmt[p + 4].toInt() and 255)
            assertTrue(ids.add(pid))
            val desc = pmt.copyOfRange(p + 5, p + 5 + n)
            when (pid) {
                in 0x101..0x108 -> { assertEquals(0x81, type); assertArrayEquals(hex("050441432d336a01000a0474616d00"), desc) }
                in 0x120..0x13f -> { assertEquals(0x06, type); assertArrayEquals(hex("5908656e671000010001"), desc) }
                else -> { assertEquals(tracks.videoPid, pid); assertEquals(0x1b, type) }
            }
            p += 5 + n
        }
        assertEquals(41, ids.size)
    }

    @Test fun hlsAdvertisesSeparateSelectableRenditionsUsingTheSharedMultiplex() {
        val tracks = tracks()
        val hls = DvdHls(tracks)
        val segments = listOf(DvdProxy.Segment(0, 2048, 40000))
        fun playlist(name: String) = hls.playlist(checkNotNull(hls.resource(name, 1)), segments)
        val uri = Uri.parse("http://127.0.0.1/hls/title/dvd.m3u8")
        val master = HlsPlaylistParser().parse(uri, playlist("dvd.m3u8").inputStream()) as HlsMultivariantPlaylist
        assertEquals(2, master.audios.size)
        assertEquals(tracks.subtitles.size, master.subtitles.size)
        for ((index, sub) in master.subtitles.withIndex()) {
            assertEquals("text/vtt", sub.format.sampleMimeType)
            val name = sub.url!!.lastPathSegment!!
            assertEquals("dvd_sub_$index.m3u8", name)
            assertTrue(String(playlist(name)).contains("\ndvd_sub_${index}_0.vtt\n"))
        }
        assertNull(hls.resource("dvd_sub_4.m3u8", 1))
        assertNull(hls.resource("dvd_sub_1_1.vtt", 1))
        assertNull(hls.resource("dvd_sub_0_0.vtt", 1, muxed = true))
        assertNull(hls.resource("dvd_pid_288.m3u8", 1))
        assertTrue(master.audios.all { it.format.sampleMimeType == "audio/ac3" })
        assertTrue(String(playlist(master.variants.single().url.lastPathSegment!!)).contains("\ndvd_pid_${tracks.videoPid}_0.ts\n"))
        val vlc = checkNotNull(hls.resource("dvd.m3u8", 1, muxed = true))
        assertTrue(String(hls.playlist(vlc, segments)).contains("dvd_0.ts?muxed=1"))
        assertNull(hls.resource("dvd_0.ts", 1, muxed = true)!!.pid)
        assertEquals(tracks.videoPid, hls.resource("dvd_0.ts", 1)!!.pid)
        val mux = DvdTsMux(tracks)
        picture(mux, 90_000)
        val ts = mux.finish(90_000, 93_600)
        val video = hls.rendition(ts, tracks.videoPid, 0)
        assertEquals(listOf(tracks.videoPid), BluRayPsi.streams(video).map { it.pid })
        assertTrue(extract(ts, TsExtractor.MODE_SINGLE_PMT).formats.any { it.sampleMimeType == "application/dvbsubs" })
        for (r in master.audios) {
            val media = String(playlist(r.url!!.lastPathSegment!!))
            val pid = hls.resource(media.lines().single { it.endsWith(".ts") }, 1)!!.pid!!
            val view = hls.rendition(ts, pid, 0)
            val program = checkNotNull(BluRayPsi.program(view))
            assertEquals(pid, program.pcrPid)
            assertEquals(pid, program.streams.single().pid)
            assertEquals(6, extract(view, TsExtractor.MODE_HLS).formats.single { it.sampleMimeType == "audio/ac3" }.channelCount)
        }
    }

    @Test fun webVttClipsOpenCuesToTheSegmentAndEscapesRecognizedText() {
        val source = DvdSpu.Source(byteArrayOf(), intArrayOf(), 480)
        val window = DvdSubtitleWindow(700_000, 3_700_000, listOf(
            DvdSpu.Cue(0, -100_000, 120_000, source),
            DvdSpu.Cue(0, 1_234_000, Long.MAX_VALUE, source),
            DvdSpu.Cue(0, 4_000_000, Long.MAX_VALUE, source),
            DvdSpu.Cue(1, 0, 1_000_000, source)))
        val header = "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:90000\n\n"
        val calls = mutableListOf<DvdSpu.Source>()
        val vtt = window.webVtt(0, (1L shl 33) + 90_000) { calls.add(it); "First & <last>\n\nline" }
        assertEquals(listOf(source, source), calls)
        assertEquals(header + "00:00:00.000 --> 00:00:00.120\nFirst &amp; &lt;last&gt;\nline\n\n" +
            "00:00:01.234 --> 00:00:03.000\nFirst &amp; &lt;last&gt;\nline\n\n", String(vtt))
        assertEquals(header, String(window.webVtt(1, 90_000) { " " }))
    }

    @get:Rule val folder = TemporaryFolder()

    @Test fun stockHlsPlayerReadsBothAudioRenditionsWithoutDvbTextGroups() {
        val hls = DvdHls(tracks())
        val mux = DvdTsMux(hls.tracks)
        picture(mux, 90_000, 4)
        val ts = mux.finish(90_000, 104_400)
        val cuts = listOf(DvdProxy.Segment(0, 2048, 160_000))
        val pids = listOf(0x101, 0x102, hls.tracks.videoPid)
        for (i in hls.tracks.subtitles.indices) {
            val name = "dvd_sub_$i.m3u8"
            folder.newFile(name).writeBytes(hls.playlist(checkNotNull(hls.resource(name, 1)), cuts))
            val source = DvdSpu.Source(byteArrayOf(), intArrayOf(), 480)
            val window = DvdSubtitleWindow(0, 160_000, listOf(DvdSpu.Cue(i, 0, 160_000, source)))
            folder.newFile("dvd_sub_${i}_0.vtt").writeBytes(window.webVtt(i, 90_000) { "Hello" })
        }
        for (name in listOf("dvd.m3u8", "dvd_video.m3u8") + pids.map { "dvd_pid_$it.m3u8" }) {
            folder.newFile(name).writeBytes(hls.playlist(checkNotNull(hls.resource(name, 1)), cuts))
        }
        pids.forEach { folder.newFile("dvd_pid_${it}_0.ts").writeBytes(hls.rendition(ts, it, 0)) }
        fun awaitReady(ready: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!ready()) {
                assertTrue("Media3 HLS preparation or read timed out", System.nanoTime() < deadline)
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
        }
        val uri = Uri.fromFile(folder.root.resolve("dvd.m3u8"))
        val source = HlsMediaSource.Factory(DefaultDataSource.Factory(RuntimeEnvironment.getApplication()))
            .createMediaSource(MediaItem.fromUri(uri))
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, update -> timeline = update }
        source.prepareSource(caller, null, PlayerId.UNSET)
        try {
            awaitReady { source.maybeThrowSourceInfoRefreshError(); timeline != null }
            val period = source.createPeriod(MediaSource.MediaPeriodId(timeline!!.getUidOfPeriod(0)), DefaultAllocator(true, 65536), 0)
            try {
                var prepared = false
                period.prepare(object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) { prepared = true }
                    override fun onContinueLoadingRequested(source: MediaPeriod) {}
                }, 0)
                awaitReady { period.maybeThrowPrepareError(); prepared }
                val groups = period.trackGroups.let { list -> (0 until list.length).map { list[it] } }
                val video = groups.single { it.type == C.TRACK_TYPE_VIDEO }
                val audios = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val texts = groups.filter { it.type == C.TRACK_TYPE_TEXT }
                assertEquals(2, audios.size)
                assertEquals(hls.tracks.subtitles.size, texts.size)
                assertTrue(texts.all { it.getFormat(0).sampleMimeType != "application/dvbsubs" })
                val streams = arrayOfNulls<SampleStream>(3)
                val loading = LoadingInfo.Builder().setPlaybackPositionUs(0).build()
                for (i in audios.indices) {
                    val groupsToRead = listOf(video, audios[i], texts[i])
                    period.selectTracks(groupsToRead.map { FixedTrackSelection(it, 0) }.toTypedArray(),
                        booleanArrayOf(i > 0, false, false), streams, BooleanArray(3), 0)
                    period.readDiscontinuity()
                    val stream = checkNotNull(streams[1])
                    val holder = FormatHolder()
                    val buf = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
                    awaitReady {
                        period.continueLoading(loading)
                        stream.maybeThrowError()
                        stream.readData(holder, buf, 0) == C.RESULT_BUFFER_READ
                    }
                    assertFalse("selected rendition should supply a sample", buf.isEndOfStream)
                    assertEquals("audio/ac3", holder.format?.sampleMimeType)
                    assertEquals(audios[i].getFormat(0).language, holder.format?.language)
                    val text = checkNotNull(streams[2])
                    awaitReady {
                        period.continueLoading(loading)
                        text.maybeThrowError()
                        buf.clear()
                        text.readData(holder, buf, 0) == C.RESULT_BUFFER_READ
                    }
                    assertFalse("selected WebVTT rendition should supply a cue", buf.isEndOfStream)
                    assertEquals(0L, buf.timeUs)
                }
            } finally { source.releasePeriod(period) }
        } finally { source.releaseSource(caller) }
    }

    private class Output : ExtractorOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableMapOf<Int, MutableList<Pair<Long, ByteArray>>>()
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val data = ByteArrayOutputStream()
            override fun format(format: Format) { formats.add(format) }
            override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean,
                                    sampleDataPart: Int): Int = error("Unexpected streaming input")
            override fun sampleData(source: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {
                data.write(source.data, source.position, length)
                source.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                val bytes = data.toByteArray()
                val end = bytes.size - offset
                samples.getOrPut(id) { mutableListOf() }.add(timeUs to bytes.copyOfRange(end - size, end))
                data.reset()
                data.write(bytes, end, offset)
            }
        }
    }

    private fun extract(bytes: ByteArray, mode: Int): Output {
        val ts = TsExtractor(mode, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED,
            TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET), DefaultTsPayloadReaderFactory(), 112800)
        val out = Output()
        ts.init(out)
        val input = DefaultExtractorInput(bytes.inputStream()::read, 0, C.LENGTH_UNSET.toLong())
        while (ts.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) { }
        ts.release()
        return out
    }

    // One independently addressed Private Stream 1 PES per DVD sector.
    private fun sector(body: ByteArray, pts: Long? = null): ByteArray {
        val packet = ByteArray(2048)
        val timestamp = pts?.let { t -> byteArrayOf((33 or ((t ushr 29).toInt() and 14)).toByte(),
            (t ushr 22).toByte(), ((t ushr 14) or 1).toByte(), (t ushr 7).toByte(), ((t shl 1) or 1).toByte()) } ?: byteArrayOf()
        val n = 3 + timestamp.size + body.size
        (byteArrayOf(0, 0, 1, 0xbd.toByte(), (n ushr 8).toByte(), n.toByte(), 0x80.toByte(),
            (if (pts != null) 128 else 0).toByte(), timestamp.size.toByte()) + timestamp + body).copyInto(packet)
        return packet
    }

    @Test fun rawMpeg2FallbackReassemblesSpuAcrossCutsAndPreservesPngTransparency() {
        val jni = System.getenv("GDSTREAM_TEST_DVD_JNI")
        assumeNotNull(jni)
        System.load(jni!!)
        val spu = javaClass.getResourceAsStream("/dvd/caption.spu")!!.readBytes()
        spu[16] = 0xf0.toByte() // alpha for palette index 2 (blue) is zero
        val video = sector(byteArrayOf(1), 90_000).also { it[3] = 0xe0.toByte() }
        val vob = video + sector(byteArrayOf(0x25) + spu.copyOf(1), 90_000) +
            sector(byteArrayOf(0x25) + spu.copyOfRange(1, spu.size))
        val segments = listOf(DvdProxy.Segment(0, 4096, 40_000), DvdProxy.Segment(4096, 6144, 1_000_000))
        val starts = listOf(0L, 40_000L, 1_040_000L)
        fun window(index: Int, prev: DvdSubtitleWindow? = null) =
            DvdProxy.rawSubtitleWindow(segments, starts, index, tracks(), 0, 576, prev) { offset, count ->
                vob.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        val opening = window(0)
        val image = (opening.render(0)["cues"] as List<*>).single() as Map<*, *>
        assertEquals((2048 * 100 / 9).toDouble(), image["showUs"])
        assertEquals(40_000.0, image["stopUs"])
        val png = android.util.Base64.decode((image["uri"] as String).substringAfter(','), 0)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(3, 0)))
        // Enter the next cut both sequentially and by seeking into the split SPU.
        for (next in listOf(window(1, opening), window(1))) {
            val carried = (next.render(0)["cues"] as List<*>).single() as Map<*, *>
            assertEquals(40_000.0, carried["showUs"])
            assertEquals((90 * 1024L * 100 / 9).toDouble(), carried["stopUs"])
        }
    }

    @Test fun fragmentedDtsAndSpuReachMedia3AlongsideCopiedAc3OnTheWarmClock() {
        // scripts/test-dvd-tracks.sh builds these same JNI entry points against FFmpeg 6.0 for the host.
        val jni = System.getenv("GDSTREAM_TEST_DVD_JNI")
        assumeNotNull(jni)
        System.load(jni!!)
        val tracks = tracks()
        val mux = DvdTsMux(tracks)
        val readers = DvdProxy.TrackReaders(mux)
        fun bytes(path: String) = javaClass.getResourceAsStream(path)!!.use { it.readBytes() }
        val spu = bytes("/dvd/caption.spu")
        val tone = bytes("/dvd/tone.dts")
        val ac3 = bytes("/dvd/tone.ac3")
        val silence = bytes("/dvd/silence-6.ac3")
        try {
            picture(mux, 90_000)
            readers.read(sector(byteArrayOf(0x25) + spu.copyOfRange(0, 1), 93_000), 0, 90_000, 576, false, false) {}
            val opening = mux.finish(90_000, 93_600)
            val data = ByteArrayOutputStream()
            repeat(4) { data.write(sector(hex("83010001") + ac3, 5_000_000 + it * 2880L)) }
            // Split inside syncframes and leave two complete DTS frames for the next segment's encoder FIFO.
            val split = 7 * 1024 + 113
            var at = 0
            while (at < split) {
                val end = minOf(split, at + 1311)
                val pointer = (1024 - at % 1024) % 1024 + 1
                data.write(sector(byteArrayOf(0x89.toByte(), 1, (pointer ushr 8).toByte(), pointer.toByte()) +
                    tone.copyOfRange(at, end), 5_000_000))
                at = end
            }
            data.write(sector(byteArrayOf(0x25) + spu.copyOfRange(1, spu.size)))
            picture(mux, 93_600, 4)
            readers.read(data.toByteArray(), 2048, 5_000_000, 576, false, false) {}
            val overlay = mutableListOf<DvdSpu.Cue>()
            val dialogue = mux.finish(5_000_000, 108_000, overlay = overlay::add)
            val window = DvdSubtitleWindow(40_000, 200_000, overlay)
            val images = window.render(0)["cues"] as List<*>
            val image = images.single() as Map<*, *>
            assertEquals(40_000.0 + (93_000 + 2 * 1024 - 93_600) * 100 / 9, image["showUs"])
            assertEquals(200_000.0, image["stopUs"]) // carried into the following segment
            assertEquals(100, image["x"])
            assertEquals(200, image["y"])
            val png = android.util.Base64.decode((image["uri"] as String).substringAfter(','), 0)
            val decoded = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals(4, decoded.width)
            assertEquals(2, decoded.height)
            assertEquals(android.graphics.Color.WHITE, decoded.getPixel(0, 0))
            assertTrue(android.graphics.Color.blue(decoded.getPixel(3, 0)) > 220)
            assertTrue((window.render(1)["cues"] as List<*>).isEmpty())
            val tail = ByteArrayOutputStream()
            while (at < tone.size) {
                val end = minOf(tone.size, at + 1200)
                tail.write(sector(hex("89010001") + tone.copyOfRange(at, end), 6_000_000))
                at = end
            }
            picture(mux, 108_000, 24)
            readers.read(tail.toByteArray(), 2048L + data.size(), 6_000_000, 576, false, true) {}
            val ending = mux.finish(6_000_000, 194_400)
            // Use single-PMT mode to read all in-band PIDs, as a TS demuxer does.
            val out = extract(opening + dialogue + ending, TsExtractor.MODE_SINGLE_PMT)
            val primary = out.samples.getValue(0x101)
            val secondary = out.samples.getValue(0x102)
            assertTrue(primary.drop(2).take(4).all { it.second.contentEquals(ac3) })
            // 2 silent frames + ceil(10 DTS frames * 512 / 1536) real 5.1 AC-3 frames.
            assertEquals(6, secondary.size)
            assertTrue(secondary.take(2).all { it.second.contentEquals(silence) })
            assertTrue(secondary.drop(2).all { !it.second.contentEquals(silence) })
            val formats = out.formats.filter { it.sampleMimeType == "audio/ac3" }
            assertEquals(setOf("ta", "en"), formats.map { it.language }.toSet())
            assertTrue(formats.all { it.channelCount == 6 && it.sampleRate == 48000 })
            for (samples in listOf(primary, secondary)) {
                assertEquals(samples.indices.map { 1_000_000L + it * 32_000 }, samples.map { it.first })
            }
            val parser = DvbParser(listOf(byteArrayOf(0, 1, 0, 1)))
            fun cues(data: ByteArray): List<Cue> {
                var result = emptyList<Cue>()
                parser.parse(data, 0, data.size, SubtitleParser.OutputOptions.allCues()) { result = it.cues }
                return result
            }
            val subtitles = out.samples.getValue(0x120)
            val show = subtitles.single { it.first == (93_000L + 2 * 1024) * 100 / 9 }
            val cue = cues(show.second).single()
            val bitmap = checkNotNull(cue.bitmap)
            assertEquals(4, bitmap.width)
            assertEquals(2, bitmap.height)
            assertEquals(100f / 719, cue.position, 0.0001f)
            assertEquals(200f / 575, cue.line, 0.0001f)
            assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(0xffebebeb.toInt(), bitmap.getPixel(0, 0))
            assertTrue(android.graphics.Color.blue(bitmap.getPixel(3, 0)) > 220)
            assertTrue(android.graphics.Color.red(bitmap.getPixel(3, 0)) < 50)
            assertEquals(bitmap.getPixel(0, 0), bitmap.getPixel(3, 1))
            assertTrue(cues(subtitles.single { it.first == (93_000L + 90 * 1024) * 100 / 9 }.second).isEmpty())
            assertEquals(4, out.formats.count { it.sampleMimeType == "application/dvbsubs" })
            // When a split packet is already due, the segment containing its show PTS owns the display.
            val earlyMux = DvdTsMux(tracks)
            val early = DvdProxy.TrackReaders(earlyMux)
            picture(earlyMux, 90_000)
            early.read(sector(byteArrayOf(0x25) + spu.copyOfRange(0, 1), 90_000), 0, 90_000, 576, false, false) {}
            early.completePending(2048, 93_600, 576) { _, _ -> sector(byteArrayOf(0x25) + spu.copyOfRange(1, spu.size)) }
            val earlySamples = extract(earlyMux.finish(90_000, 93_600), TsExtractor.MODE_SINGLE_PMT).samples
            assertEquals(1, cues(earlySamples.getValue(0x120).single { it.first == 92_048L * 100 / 9 }.second).size)
            early.reset()
            // The native pcm_dvd decoder receives each PES's format header; 96 kHz stereo is resampled.
            val pcmMux = DvdTsMux(DvdStreams(listOf(DvdStreams.Track(0xa1, "eng", 2, "LPCM"))))
            val pcm = DvdProxy.TrackReaders(pcmMux)
            try {
                picture(pcmMux, 90_000)
                val payload = hex("a1010004001180") + ByteArray(1920) { if (it % 4 == 0) 0x20 else 0 }
                val sectors = ByteArrayOutputStream()
                repeat(16) { sectors.write(sector(payload, 90_000 + it * 450L)) }
                pcm.read(sectors.toByteArray(), 0, 90_000, 480, false, true) {}
                val decoded = extract(pcmMux.finish(90_000, 97_200), TsExtractor.MODE_SINGLE_PMT)
                val format = decoded.formats.single { it.sampleMimeType == "audio/ac3" }
                assertEquals(2, format.channelCount)
                assertEquals(48000, format.sampleRate)
                assertEquals(3, decoded.samples.getValue(0x101).size)
            } finally { pcm.reset() }
        } finally { readers.reset() }
    }
}
