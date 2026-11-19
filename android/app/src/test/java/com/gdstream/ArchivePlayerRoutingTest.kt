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
    @Test fun allExternalPlayerIntentsMatchVideoHandlersIncludingLocalHls() {
        for (path in listOf("hls/disc/index.m3u8", "zip/disc/movie.ts", "zip/disc/movie.m2ts", "movie.mp4")) {
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

    @Test fun dvdProgramStreamsAndBlurayTransportStreamsChooseTheirOwnExtractors() {
        val dvd = Uri.parse("http://127.0.0.1:9090/zip/disc/%5BDVD%20Main%5D.VOB?label=.ts#name=disc.iso")
        assertTrue(isoExtractors(dvd, null, true).createExtractors().single() is PsExtractor)
        val bd = Uri.parse("http://127.0.0.1:9090/zip/disc/movie.m2ts")
        assertTrue(isoExtractors(bd, null, false).createExtractors().single() is BluRayTsExtractor)
    }
}
