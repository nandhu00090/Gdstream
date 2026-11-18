package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BluRayDolbyVisionTest {
    private fun rpu(name: String = "mel_orig") = byteArrayOf(0x7c, 1) + javaClass.getResourceAsStream("/dovi/$name.bin")!!.use { it.readBytes().drop(4).toByteArray() }
    private val start = byteArrayOf(0, 0, 0, 1)
    @Test fun timestampMatchingPreservesDecodeOrderWithLateAndEarlyEnhancement() {
        val times = ArrayList<Long>()
        val samples = ArrayList<ByteArray>()
        val queue = DolbyVisionSampleQueue { time, _, data -> times.add(time); samples.add(data) }
        queue.base(300, 1, byteArrayOf(3)); queue.base(100, 0, byteArrayOf(1))
        queue.rpu(100, rpu())
        assertTrue(times.isEmpty())
        queue.rpu(300, rpu("fel_orig"))
        queue.rpu(200, rpu()); queue.base(200, 0, byteArrayOf(2)); queue.finish()
        assertEquals(listOf(300L, 100L, 200L), times)
        assertArrayEquals(byteArrayOf(3) + start + rpu("fel_to_81"), samples[0])
        assertArrayEquals(byteArrayOf(1) + start + rpu("mel_to_81"), samples[1])
    }
    @Test fun scannerHandlesEverySplitAndDropsEnhancementSlicesAndType63() {
        val source = rpu()
        val bytes = start + byteArrayOf(2, 1) + ByteArray(1000) { 0x55 } + start + byteArrayOf(0x7e, 1, 22) + start + source
        for (split in 0..bytes.size) {
            val captured = ArrayList<ByteArray>()
            val times = ArrayList<Long>()
            val reader = DolbyVisionNalReader { time, nal -> times.add(time); captured.add(nal) }
            reader.consume(ParsableByteArray(bytes.copyOfRange(0, split)), 9000)
            reader.consume(ParsableByteArray(bytes.copyOfRange(split, bytes.size)), 9000)
            reader.finish()
            assertEquals(listOf(9000L), times)
            assertArrayEquals(source, captured.single())
        }
    }
    @Test fun seekDropsPartialNalsAndQueuedBaseAndMetadata() {
        var emitted = 0
        val queue = DolbyVisionSampleQueue { _, _, _ -> emitted++ }
        val scanner = DolbyVisionNalReader(queue::rpu)
        queue.base(0, 1, byteArrayOf(1)); queue.rpu(100, rpu())
        scanner.consume(ParsableByteArray(start + rpu().copyOf(12)), 0)
        scanner.reset(); queue.reset()
        scanner.consume(ParsableByteArray(start + rpu()), 9000); scanner.finish()
        queue.base(9000, 1, byteArrayOf(2)); queue.finish()
        assertEquals(1, emitted)
    }
    @Test fun missingOrUnboundedMetadataFailsInsteadOfCrossFrameInjection() {
        val queue = DolbyVisionSampleQueue { _, _, _ -> fail("mismatched PTS") }
        queue.base(0, 1, byteArrayOf(1)); queue.rpu(1, rpu())
        assertThrows(IOException::class.java) { queue.finish() }
        queue.reset()
        assertThrows(IOException::class.java) { repeat(33) { queue.base(it.toLong(), 0, byteArrayOf(1)) } }
        queue.reset()
        assertThrows(IOException::class.java) { queue.rpu(C.TIME_UNSET, rpu()) }
    }

    private data class Captured(val time: Long, val bytes: ByteArray)
    private class Capture : ExtractorOutput {
        val formats = ArrayList<Format>()
        val samples = ArrayList<Captured>()
        val tracks = ArrayList<Int>()
        private val bytes = ByteArrayOutputStream()
        override fun track(id: Int, type: Int): TrackOutput {
            tracks.add(id)
            val output = mock(TrackOutput::class.java)
            doAnswer { formats.add(it.getArgument(0)); null }.`when`(output).format(any(Format::class.java))
            doAnswer {
                val data = it.getArgument<ParsableByteArray>(0)
                val count = it.getArgument<Int>(1)
                val chunk = ByteArray(count); data.readBytes(chunk, 0, count); bytes.write(chunk); null
            }.`when`(output).sampleData(any(ParsableByteArray::class.java), anyInt())
            doAnswer {
                val data = bytes.toByteArray()
                val size = it.getArgument<Int>(2)
                val end = data.size - it.getArgument<Int>(3)
                samples.add(Captured(it.getArgument(0), data.copyOfRange(end - size, end))); null
            }.`when`(output).sampleMetadata(anyLong(), anyInt(), anyInt(), anyInt(), isNull())
            return output
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }
    @Test fun actualDualPidTransportExposesOneDvTrackAndSixInjectedAccessUnitsAndHdrStaysHevc() {
        val bytes = javaClass.getResourceAsStream("/bluray/dual-dv.ts.bin")!!.use { it.readBytes() }
        val profile = BluRayProfile(1000000, listOf(BluRayPoint(0, 0), BluRayPoint(500000, 188)), emptyList(),
            listOf(BluRayStream(0x1011, 0x24, 8), BluRayStream(0x1015, 0x24, 6)))
        fun read(extractor: Extractor) {
            val stream = ByteArrayInputStream(bytes)
            val input = DefaultExtractorInput({ target, offset, count -> stream.read(target, offset, count) }, 0, bytes.size.toLong())
            val holder = PositionHolder()
            var result = Extractor.RESULT_CONTINUE
            var calls = 0
            while (result != Extractor.RESULT_END_OF_INPUT && calls++ < 1000) {
                result = extractor.read(input, holder)
                assertNotEquals(Extractor.RESULT_SEEK, result)
            }
            assertEquals(Extractor.RESULT_END_OF_INPUT, result)
        }
        val captures = listOf(false, true).map { dv ->
            val output = Capture()
            val extractor = BluRayTsExtractor(profile, dv)
            extractor.init(output); read(extractor)
            assertEquals(listOf(0x1011), output.tracks)
            assertEquals(if (dv) "video/dolby-vision" else "video/hevc", output.formats.single().sampleMimeType)
            if (dv) assertEquals("dvhe.08.06", output.formats.single().codecs)
            assertEquals(6, output.samples.size)
            if (dv) {
                extractor.seek(0, 0); read(extractor)
                assertEquals(12, output.samples.size)
                assertEquals(output.samples.take(6).map { it.time }, output.samples.drop(6).map { it.time })
            }
            extractor.release()
            output.samples.take(6)
        }
        val suffix = start + rpu("mel_to_81")
        captures[0].zip(captures[1]).forEach { (hdr, dv) ->
            assertEquals(hdr.time, dv.time)
            assertArrayEquals(hdr.bytes + suffix, dv.bytes)
        }
    }
}
