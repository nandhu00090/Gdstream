package com.gdstream

import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.PsExtractor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ArchivePlayerRoutingTest {
    @Test fun dvdProbeSuppressionFollowsTheSelectedEntryAndPlaybackLifetime() {
        val base = "http://127.0.0.1:9090"
        val vob = "$base/zip/disc/%5BDVD%20Main%5D.vob"
        val hls = "$base/hls/disc/dvd.m3u8"
        try {
            ArchiveProxyStreams.prepare(hls)
            assertTrue(ArchiveProxyStreams.skipDvdTrackProbe(vob))
            assertFalse(ArchiveProxyStreams.skipDvdTrackProbe(vob.replace("/disc/", "/other/")))
            ArchiveProxyStreams.prepare(vob)
            assertFalse(ArchiveProxyStreams.skipDvdTrackProbe(vob))
            ArchiveProxyStreams.prepare(hls)
            ArchiveProxyStreams.stop(hls)
            assertFalse(ArchiveProxyStreams.skipDvdTrackProbe(vob))
        } finally { ArchiveProxyStreams.stop(hls) }
    }

    @Test fun externalDvdLaunchUsesDirectVobFromEitherEntryPoint() {
        val base = "http://127.0.0.1:9090"
        val direct = "$base/zip/disc/Arundhati%20%5BDVD%20Main%5D.vob"
        for (url in listOf(direct, "$base/hls/disc/dvd.m3u8")) {
            assertEquals(direct, externalDvdUrl(url, "disc", "Arundhati [DVD Main].vob"))
        }
    }

    @Test fun allExternalPlayerIntentsMatchVideoHandlersIncludingLocalHls() {
        for (path in listOf("hls/disc/dvd.m3u8", "hls/disc/index.m3u8", "zip/disc/movie.ts", "zip/disc/movie.m2ts", "movie.mp4")) {
            val uri = Uri.parse("http://127.0.0.1:9090/$path")
            val intent = externalVideoIntent(uri)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("video/*", intent.type)
            assertEquals(uri, intent.data)
        }
    }

    @Test fun interlacedDvdFixtureEmitsMpeg2VideoFrames() {
        // Generated with ffmpeg -f lavfi -i color=black:s=720x576:r=25 -frames:v 4
        // -target pal-dvd -flags +ildct+ilme -top 1 -an -f dvd interlaced.vob
        val uri = Uri.parse("http://127.0.0.1:9090/zip/disc/%5BDVD%20Main%5D.vob")
        val extractor = isoExtractors(uri, null, false).createExtractors().single()
        var videoFormat: Format? = null
        var frames = 0
        val discard = DiscardingTrackOutput()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type != C.TRACK_TYPE_VIDEO) return discard
                return object : TrackOutput by discard {
                    override fun format(format: Format) { videoFormat = format }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        if (size > 0) frames++
                    }
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        javaClass.getResourceAsStream("/dvd/interlaced.vob.bin")!!.use { stream ->
            val input = DefaultExtractorInput(stream::read, 0, C.LENGTH_UNSET.toLong())
            assertTrue(extractor.sniff(input))
            input.resetPeekPosition()
            repeat(100) {
                val result = extractor.read(input, PositionHolder())
                if (result == Extractor.RESULT_END_OF_INPUT) return@use
                assertEquals(Extractor.RESULT_CONTINUE, result)
            }
            fail("DVD extractor did not reach EOF")
        }
        extractor.release()
        assertEquals("video/mpeg2", videoFormat?.sampleMimeType)
        assertEquals(720, videoFormat?.width)
        assertEquals(576, videoFormat?.height)
        assertTrue(frames > 0)
    }

    @Test fun dvdDemuxPreservesOnlyOriginalPesTimestamps() {
        val data = javaClass.getResourceAsStream("/dvd/interlaced.vob.bin")!!.readBytes()
        val frames = mutableListOf<ByteArray>()
        val timestamps = mutableListOf<Long>()
        DvdProxy.demuxVideo(data) { time, bytes ->
            timestamps.add(time)
            frames.add(bytes)
        }
        assertEquals(listOf(48_600L, C.TIME_UNSET, C.TIME_UNSET, 52_200L), timestamps)
        assertTrue(frames.size >= 3)
        assertTrue(frames.first().size > 4)
    }

    @Test fun dvdGopWithoutRepeatedSequenceHeaderUsesTitleConfiguration() {
        val data = javaClass.getResourceAsStream("/dvd/interlaced.vob.bin")!!.readBytes()
        val header = requireNotNull(DvdProxy.sequenceHeader(data))
        // Keep all PES lengths and timestamps intact while removing the local sequence headers.
        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte() &&
                data[i+3] == 0xb3.toByte()) data[i+3] = 0xb2.toByte()
        }
        var first: ByteArray? = null
        DvdProxy.demuxVideo(data, { header }) { _, bytes -> if (first == null) first = bytes }
        assertArrayEquals(header, first)
    }

    @Test fun dvdProgramStreamsAndBlurayTransportStreamsChooseTheirOwnExtractors() {
        val dvd = Uri.parse("http://127.0.0.1:9090/zip/disc/%5BDVD%20Main%5D.VOB?label=.ts#name=disc.iso")
        assertTrue(isoExtractors(dvd, null, true).createExtractors().single() is PsExtractor)
        val bd = Uri.parse("http://127.0.0.1:9090/zip/disc/movie.m2ts")
        assertTrue(isoExtractors(bd, null, false).createExtractors().single() is BluRayTsExtractor)
    }

    @Test fun playVideoUsesSystemChooserForDvdIsoWhilePlayVideoInVlcTargetsVlcDirectly() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val reactContext = com.facebook.react.bridge.BridgeReactContext(app)
        val security = SecurityVaultModule(reactContext)
        val player = VideoPlayerModule(reactContext)
        val registryField = SecurityVaultModule::class.java.getDeclaredField("archiveEntryRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val entries = registryField.get(security) as java.util.concurrent.ConcurrentHashMap<String, SecurityVaultModule.ArchiveEntryTarget>
        val target = SecurityVaultModule.ArchiveEntryTarget(
            id = "dvd_intent",
            archiveUrl = "http://127.0.0.1:1/never-fetch",
            fileName = "Movie [DVD Main].vob",
            archiveFormat = "ISO",
            compressionMethod = 0,
            compressedSize = 4096,
            uncompressedSize = 4096,
            localHeaderOffset = 0,
            segments = listOf(0L to 4096L),
            resolvedInfo = mapOf("videoCodec" to "MPEG-2", "width" to 720, "height" to 480),
            dvdSegments = listOf(DvdProxy.Segment(0, 2048, 4_000_000)),
            dvdEncoderOverride = DvdProxy.Encoder("c2.test.avc", 0)
        )
        entries[target.id] = target
        val sourceUrl = "http://127.0.0.1:9090/zip/${target.id}/Movie%20%5BDVD%20Main%5D.vob"
        try {
            val chooserPromise = org.mockito.Mockito.mock(com.facebook.react.bridge.Promise::class.java)
            player.playVideo(sourceUrl, "Open with external player", chooserPromise)
            val chooserIntent = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooserIntent.action)
            assertEquals("Open with external player", chooserIntent.getCharSequenceExtra(Intent.EXTRA_TITLE))
            @Suppress("DEPRECATION")
            val innerIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_VIEW, innerIntent.action)
            assertEquals("video/*", innerIntent.type)
            assertNull(innerIntent.`package`)
            assertEquals("http://127.0.0.1:9090/hls/${target.id}/dvd.m3u8", innerIntent.dataString)
            org.mockito.Mockito.verify(chooserPromise).resolve(true)

            val vlcPromise = org.mockito.Mockito.mock(com.facebook.react.bridge.Promise::class.java)
            player.playVideoInVlc(sourceUrl, vlcPromise)
            val vlcIntent = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, vlcIntent.action)
            assertEquals("org.videolan.vlc", vlcIntent.`package`)
            assertEquals("http://127.0.0.1:9090/hls/${target.id}/dvd.m3u8?muxed=1", vlcIntent.dataString)
            org.mockito.Mockito.verify(vlcPromise).resolve(true)
        } finally {
            entries.remove(target.id)
            ArchiveProxyStreams.stop("http://127.0.0.1:9090/hls/${target.id}/dvd.m3u8")
            val ioField = SecurityVaultModule::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }
            (ioField.get(security) as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
