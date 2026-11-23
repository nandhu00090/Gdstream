package com.gdstream

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Each segment starts with PSI and an IDR. PCR uses the video timeline. */
internal class DvdTsMux(private val audioLanguageCode: String = "") {
    private data class UnitData(val video: Boolean, var ticks: Long, val data: ByteArray)
    private val samples = ArrayList<UnitData>()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lengthBytes = 4
    var frames = 0
        private set

    fun beginSegment(newEncoder: Boolean = false) {
        frames = 0
        samples.clear()
        if (newEncoder) {
            sps = null
            pps = null
            lengthBytes = 4
        }
    }

    // Called by name from MediaCodec JNI. Format changes can deliver csd-0 and csd-1 separately.
    fun video(pts90k: Long, bytes: ByteArray, config: Boolean = false) {
        val nals = splitNals(bytes, config)
        fun type(nal: ByteArray) = nal[0].toInt() and 31
        for (nal in nals) when (type(nal)) {
            7 -> sps = nal
            8 -> pps = nal
        }
        if (nals.any { it.size < 2 && type(it) in 1..8 }) throw IOException("Truncated DVD AVC NAL")
        if (nals.none { type(it) in 1..5 }) return
        val idr = nals.any { type(it) == 5 }
        if (frames == 0 && !idr) throw IOException("DVD segment must begin with an IDR")
        val au = ByteArrayOutputStream()
        fun nal(data: ByteArray) {
            au.write(byteArrayOf(0, 0, 0, 1))
            au.write(data)
        }
        // Media3's HLS AVC reader uses access-unit delimiters to commit samples.
        nal(byteArrayOf(9, 0xf0.toByte()))
        if (idr) {
            nal(sps ?: throw IOException("DVD IDR missing SPS"))
            nal(pps ?: throw IOException("DVD IDR missing PPS"))
        }
        nals.filter { type(it) !in 7..9 }.forEach(::nal)
        samples.add(UnitData(true, pts90k, au.toByteArray()))
        frames++
    }

    private fun splitNals(data: ByteArray, config: Boolean): List<ByteArray> {
        fun u(i: Int) = data[i].toInt() and 255
        val result = ArrayList<ByteArray>()
        var pos = 0
        fun takeLength(prefix: Int) {
            if (pos + prefix > data.size) throw IOException("Truncated DVD AVC length")
            var size = 0L
            repeat(prefix) { size = (size shl 8) + u(pos++) }
            if (size !in 1L..(data.size - pos).toLong()) throw IOException("Invalid DVD AVC NAL size $size")
            result.add(data.copyOfRange(pos, pos + size.toInt()))
            pos += size.toInt()
        }
        if (config && data.size >= 7 && u(0) == 1) {
            lengthBytes = (u(4) and 3) + 1
            pos = 6
            repeat(u(5) and 31) { takeLength(2) }
            if (pos == data.size) throw IOException("DVD AVC configuration missing PPS count")
            repeat(u(pos++)) { takeLength(2) }
            return result
        }
        fun prefix(i: Int): Int {
            if (i + 3 > data.size || u(i) != 0 || u(i + 1) != 0) return 0
            if (u(i + 2) == 1) return 3
            return if (i + 4 <= data.size && u(i + 2) == 0 && u(i + 3) == 1) 4 else 0
        }
        // A four-byte length of 256..511 starts with 00 00 01, just like a short start code.
        // Only prefer that interpretation when lengths describe the entire access unit.
        fun hasCompleteLengths(): Boolean {
            var i = 0
            while (i < data.size) {
                if (i + lengthBytes >= data.size) return false
                var n = 0L
                repeat(lengthBytes) { n = (n shl 8) + u(i++) }
                if (n !in 1L..(data.size - i).toLong() || u(i) !in 1..127) return false
                i += n.toInt()
            }
            return true
        }
        val firstPrefix = prefix(0)
        if (firstPrefix == 4 || firstPrefix == 3 && (config || !hasCompleteLengths())) {
            while (pos < data.size) {
                val start = pos + prefix(pos)
                pos = start
                while (pos < data.size && prefix(pos) == 0) pos++
                if (pos > start) result.add(data.copyOfRange(start, pos))
            }
        } else if (config && data.isNotEmpty() && (u(0) and 31) in 7..8) {
            result.add(data)
        } else {
            while (pos < data.size) takeLength(lengthBytes)
        }
        return result
    }
    fun audio(ptsUs: Long, bytes: ByteArray) {
        // Ac3Reader timestamps are in microseconds. Round back to the original PES tick.
        samples.add(UnitData(false, (ptsUs * 9 + 50) / 100, bytes))
    }

    data class Timing(val firstVideo: Long, val lastVideo: Long, val nextVideo: Long,
                      val firstAudio: Long?, val nextAudio: Long?)
    var timing: Timing? = null
        private set
    private var audioPhase: Long? = null
    private val cc = IntArray(8192)

    fun finish(sourceStart: Long, nativeEnd: Long, navDuration: Long = 0): ByteArray {
        if (frames == 0) throw IOException("DVD segment produced 0 decodable AVC frames (no IDR)")
        val video = samples.filter { it.video }
        val audio = samples.filter { !it.video } // Ac3Reader emits complete frames in decode order.
        val start = timing?.nextVideo ?: sourceStart
        val firstAudio = audio.firstOrNull()?.let { sample ->
            // Only the first audio PES establishes A/V phase. Unwrap its 33-bit PTS
            // relative to the first video PES, including audio just before rollover.
            val halfWrap = 1L shl 32
            val offset = ((sample.ticks - sourceStart + halfWrap) and 0x1ffffffffL) - halfWrap
            timing?.nextAudio ?: (start + offset)
        }
        val phase = audioPhase ?: firstAudio?.minus(start)
        val audioEnd = firstAudio?.let { first ->
            audio.forEachIndexed { i, sample -> sample.ticks = first + i * 2880L }
            first + audio.size * 2880L // DVD AC-3: 1536 samples at 48 kHz
        }

        val first = video.first().ticks
        val nativeSpan = nativeEnd - first
        if (nativeSpan < frames) throw IOException("Invalid DVD display cadence")
        // Keep the native field cadence, including repeat-first-field. Some discs
        // report twice the display duration. Fit those pictures to the copied
        // audio frames (or PCI duration for silent video), on our output clock.
        val reference = if (audioEnd != null && phase != null) audioEnd - phase - start else navDuration
        val span = if (reference >= frames && kotlin.math.abs(nativeSpan - reference) > 4500) reference else nativeSpan
        val end = start + span
        var previous = start - 1
        video.forEachIndexed { i, sample ->
            val fitted = start + (sample.ticks - first) * span / nativeSpan
            sample.ticks = fitted.coerceIn(previous + 1, end - (frames - i))
            previous = sample.ticks
        }
        val next = Timing(start, video.last().ticks, end, firstAudio, audioEnd ?: timing?.nextAudio)
        val out = ByteArrayOutputStream()
        fun packets(pid: Int, data: ByteArray, clock: Long? = null) {
            var pos = 0
            while (pos < data.size) {
                val packet = ByteArray(188) { 0xff.toByte() }
                packet[0] = 0x47
                packet[1] = ((pid shr 8) or if (pos == 0) 64 else 0).toByte()
                packet[2] = pid.toByte()
                val pcr = clock?.takeIf { pos == 0 }
                // Signal a counter restart only on the first packet of a cold encoder session.
                val firstPacket = cc[pid] == 0
                val count = minOf(data.size - pos, if (pcr != null) 176 else if (firstPacket) 182 else 184)
                val pad = 184 - count
                packet[3] = ((if (pad > 0) 0x30 else 0x10) or (cc[pid]++ and 15)).toByte()
                if (pad > 0) packet[4] = (pad - 1).toByte()
                if (pad > 1) packet[5] = ((if (firstPacket) 128 else 0) or (if (pcr != null) 16 else 0)).toByte()
                if (pcr != null) {
                    for (i in 0..3) packet[6 + i] = (pcr ushr (25 - i * 8)).toByte()
                    packet[10] = (((pcr and 1) shl 7) or 126).toByte()
                    packet[11] = 0
                }
                data.copyInto(packet, 188 - count, pos, pos + count)
                out.write(packet)
                pos += count
            }
        }
        fun psi(pid: Int, body: ByteArray) {
            body[2] = (body.size + 1).toByte()
            val crc = ExternalTsPmt.crc(body)
            packets(pid, byteArrayOf(0) + body + ByteArray(4) { (crc ushr (24 - it * 8)).toByte() })
        }
        psi(0, byteArrayOf(0, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xe1.toByte(), 0))
        val pmtBase = byteArrayOf(2, 0xb0.toByte(), 0, 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 1, 0xf0.toByte(), 0,
            // No caption services are present in the transcoded AVC. An explicit empty
            // caption_service_descriptor prevents Media3 from inventing a CEA-608 track.
            0x1b, 0xe1.toByte(), 1, 0xf0.toByte(), 3, 0x86.toByte(), 1, 0xe0.toByte())
        val audioDesc = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x04, 'A'.code.toByte(), 'C'.code.toByte(), '-'.code.toByte(), '3'.code.toByte()))
            write(byteArrayOf(0x6A, 0x01, 0x00))
            if (audioLanguageCode.length == 3 && audioLanguageCode.all { it in 'a'..'z' }) {
                write(byteArrayOf(0x0A, 0x04))
                write(audioLanguageCode.toByteArray(Charsets.US_ASCII))
                write(0)
            }
        }.toByteArray()
        val pmtAudio = byteArrayOf(0x81.toByte(), 0xe1.toByte(), 2, (0xf0 or (audioDesc.size ushr 8)).toByte(), audioDesc.size.toByte()) + audioDesc
        psi(256, if (samples.any { !it.video }) pmtBase + pmtAudio else pmtBase)
        for (sample in samples.sortedBy { it.ticks }) {
            val ticks = sample.ticks
            val size = if (sample.video) 0 else sample.data.size + 8
            val header = byteArrayOf(0, 0, 1, (if (sample.video) 0xe0 else 0xbd).toByte(), (size shr 8).toByte(), size.toByte(),
                0x80.toByte(), 0x80.toByte(), 5, (33 or ((ticks ushr 29).toInt() and 14)).toByte(),
                (ticks ushr 22).toByte(), ((ticks ushr 14) or 1).toByte(), (ticks ushr 7).toByte(), ((ticks shl 1) or 1).toByte())
            packets(if (sample.video) 257 else 258, header + sample.data, ticks.takeIf { sample.video })
        }
        samples.clear()
        timing = next
        audioPhase = phase
        return out.toByteArray()
    }
}
