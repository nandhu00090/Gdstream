package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.ByteArrayOutputStream

/** HDMV carries PGS segments without the SUP file's PG/timestamp prefix. */
internal class BluRayPgsReader(private val tracks: List<BluRayStream>, private val language: String?) : ElementaryStreamReader {
    private lateinit var output: TrackOutput
    private val display = ByteArrayOutputStream()
    private var pending = ByteArray(0)
    private var timeUs = C.TIME_UNSET
    private var displayTimeUs = C.TIME_UNSET
    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        val pgsTracks = tracks.filter { it.type == 0x90 }
        val idx = pgsTracks.indexOfFirst { it.pid == idGenerator.trackId }
        val track = if (idx >= 0) pgsTracks[idx] else tracks.firstOrNull { it.pid == idGenerator.trackId }
        val rawLang = (track?.language?.takeIf { it.isNotBlank() && it != "und" } ?: language?.takeIf { it.isNotBlank() && it != "und" })
        val resolvedLang = rawLang?.let { bluRayLanguage(it) } ?: "Unknown"
        val label = if (resolvedLang == "Unknown") "PGS Subtitle ${(if (idx >= 0) idx else 0) + 1}" else "$resolvedLang PGS"
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        output.format(Format.Builder().setId(idGenerator.formatId).setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .setLanguage(rawLang).setLabel(label)
            .setContainerMimeType(MimeTypes.VIDEO_MP2T).build())
    }
    override fun seek() { pending = ByteArray(0); display.reset(); timeUs = C.TIME_UNSET; displayTimeUs = C.TIME_UNSET }
    override fun packetStarted(pesTimeUs: Long, flags: Int) { if (pesTimeUs != C.TIME_UNSET) timeUs = pesTimeUs }
    override fun consume(data: ParsableByteArray) {
        val bytes = ByteArray(data.bytesLeft()); data.readBytes(bytes, 0, bytes.size)
        pending += bytes
        var pos = 0
        while (pending.size - pos >= 3) {
            val type = pending[pos].toInt() and 255
            val size = 3 + ((pending[pos + 1].toInt() and 255) shl 8) + (pending[pos + 2].toInt() and 255)
            if (pending.size - pos < size) break
            if (type == 0x16) { display.reset(); displayTimeUs = timeUs }
            if (display.size() + size > 4 * 1024 * 1024) { seek(); return }
            display.write(pending, pos, size)
            pos += size
            if (type == 0x80) {
                val sample = display.toByteArray()
                if (displayTimeUs != C.TIME_UNSET) {
                    output.sampleData(ParsableByteArray(sample), sample.size)
                    output.sampleMetadata(displayTimeUs, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
                }
                display.reset(); displayTimeUs = C.TIME_UNSET
            }
        }
        pending = pending.copyOfRange(pos, pending.size)
    }
    override fun packetFinished(isEndOfInput: Boolean) {}
}
