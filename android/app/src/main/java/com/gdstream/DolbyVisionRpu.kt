package com.gdstream

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Profile 7 -> 8.1 (dovi_tool mode 2 semantics). See THIRD_PARTY_NOTICES.md.
 * Parses variable-length mapping/NLQ syntax; a flag-only patch would leave an invalid RPU.
 * DM extension blocks are preserved, realigned, and protected by a new MPEG-2 CRC32.
 */
internal object DolbyVisionRpu {
    fun toProfile81(nal: ByteArray): ByteArray {
        try { return convert(nal) } catch (e: IllegalArgumentException) {
            throw IOException("Invalid/unsupported Dolby Vision RPU: ${e.message}", e)
        }
    }

    private fun convert(nal: ByteArray): ByteArray {
        require(nal.size in 9..65536 && nal[0] == 0x7c.toByte() && nal[1] == 1.toByte()) { "NAL 62 header" }
        val raw = ByteArrayOutputStream()
        var zeros = 0
        for (i in 2 until nal.size) {
            val value = nal[i].toInt() and 255
            if (zeros == 2 && value == 3) {
                require(i + 1 < nal.size && (nal[i + 1].toInt() and 255) <= 3) { "emulation prevention" }
                zeros = 0
            } else {
                raw.write(value)
                zeros = if (value == 0) zeros + 1 else 0
            }
        }
        val bytes = raw.toByteArray()
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        require(end >= 7 && bytes[end - 1] == 0x80.toByte() && bytes[0] == 0x19.toByte()) { "RPU framing" }
        val crcStart = end - 5
        var received = 0
        repeat(4) { received = (received shl 8) or (bytes[crcStart + it].toInt() and 255) }
        require(crc(bytes, 1, crcStart) == received) { "CRC mismatch" }
        val r = Bits(bytes, crcStart * 8)
        val w = Writer()
        fun copy(n: Int): Long = r.read(n).also { w.put(it, n) }
        fun ue(): Int = r.ue().also { w.ue(it) }
        copy(8)
        require(copy(6) == 2L) { "RPU type" }
        val format = copy(11).toInt()
        require(format and 0x700 == 0 && copy(4) == 1L && copy(4) == 0L) { "Profile 7/8 required" }
        require(copy(1) == 1L) { "missing sequence header" }
        copy(1) // chroma resampling filter
        val coefficientType = copy(2).toInt()
        require(coefficientType in 0..1)
        val denominator = if (coefficientType == 0) ue().also { require(it <= 23) } else 32
        copy(2)
        require(copy(1) == 0L) { "HDR10 base must be limited range" }
        val blDepth = ue() + 8
        val elDepth = ue() + 8
        val vdrDepth = ue() + 8
        require(blDepth == 10 && elDepth == 10 && vdrDepth in 8..14)
        r.read(1); w.put(0, 1) // spatial_resampling_filter_flag
        val reserved = copy(3).toInt()
        require(reserved in 0..1)
        val elSpatial = r.read(1) != 0L
        val residualDisabled = r.read(1) != 0L
        w.put(0, 1); w.put(1, 1)
        require(residualDisabled || (elSpatial && vdrDepth == 12)) { "not Profile 7" }
        val dmPresent = copy(1) != 0L
        val previous = copy(1) != 0L
        if (previous) {
            require(ue() <= 15)
        } else {
            val mappingId = r.ue(); require(mappingId <= 15)
            require(r.ue() == 0 && r.ue() == 0)
            val curvesStart = r.position
            val pieces = IntArray(3) {
                val pivots = r.ue() + 2
                require(pivots in 2..9)
                repeat(pivots) { r.read(blDepth) }
                pivots - 1
            }
            val curvesEnd = r.position
            if (!residualDisabled) {
                require(r.read(3) == 0L) { "NLQ method" }
                require(r.read(blDepth) + r.read(blDepth) == 1023L)
            }
            r.ue(); r.ue() // partitions no longer refer to enhancement layer
            val mappingStart = r.position
            fun coefficient() { if (coefficientType == 0) r.ue(); r.read(denominator) }
            for (count in pieces) repeat(count) {
                when (r.ue()) {
                    0 -> {
                        val order = r.ue(); require(order in 0..1)
                        if (order == 0) require(r.read(1) == 0L) { "polynomial interpolation" }
                        repeat(order + 2) { coefficient() }
                    }
                    1 -> {
                        val order = r.read(2).toInt(); require(order <= 2)
                        repeat(1 + (order + 1) * 7) { coefficient() }
                    }
                    else -> throw IllegalArgumentException("mapping method")
                }
            }
            val mappingEnd = r.position
            var mel = true
            if (!residualDisabled) repeat(3) {
                val offset = r.read(elDepth)
                val maxInt = if (coefficientType == 0) r.ue() else 0
                val max = r.read(denominator)
                val slopeInt = if (coefficientType == 0) r.ue() else 0
                val slope = r.read(denominator)
                val thresholdInt = if (coefficientType == 0) r.ue() else 0
                val threshold = r.read(denominator)
                mel = mel && offset == 0L && maxInt == 1 && max == 0L && slopeInt == 0 && slope == 0L && thresholdInt == 0 && threshold == 0L
            }
            w.ue(mappingId); w.ue(0); w.ue(0)
            if (mel) {
                w.copy(bytes, curvesStart, curvesEnd)
                w.ue(0); w.ue(0)
                w.copy(bytes, mappingStart, mappingEnd)
            } else {
                // FEL reshaping assumes residual reconstruction. Replace with identity for HDR10 BL.
                require(coefficientType == 0) { "FEL floating coefficients" }
                repeat(3) { w.ue(0); w.put(0, 10); w.put(1023, 10) }
                w.ue(0); w.ue(0)
                repeat(3) {
                    w.ue(0); w.ue(0); w.put(0, 1)
                    w.ue(0); w.put(0, denominator) // signed 0
                    w.ue(2); w.put(0, denominator) // signed +1
                }
            }
        }
        if (dmPresent) {
            require(ue() <= 15); ue(); ue()
            if (reserved == 0) {
                r.readMany(9 * 16 + 3 * 32 + 9 * 16)
                intArrayOf(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0).forEach { w.put(it.toLong(), 16) }
                longArrayOf(16777216, 134217728, 134217728).forEach { w.put(it, 32) }
                intArrayOf(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962).forEach { w.put(it.toLong(), 16) }
                repeat(5) { copy(16) } // EOTF + three parameters (16,16,16,32)
                copy(5); r.read(2); w.put(0, 2) // signal color space
                copy(2); copy(2); copy(12); copy(12); copy(10)
            }
            fun extensions() {
                val count = ue(); require(count <= 255)
                r.align(); w.align()
                repeat(count) {
                    val length = ue(); require(length <= 65536)
                    copy(8) // level
                    repeat(length) { copy(8) }
                }
            }
            extensions()
            if (r.remaining >= 16) extensions() // CM v4, excludes CRC + terminator
        }
        r.align(); w.align()
        // Preserve reserved byte-aligned data before CRC.
        while (r.remaining >= 8) copy(8)
        require(r.remaining == 0)
        val payload = w.bytes()
        val result = ByteArrayOutputStream()
        result.write(payload)
        val checksum = crc(payload, 1, payload.size)
        for (shift in intArrayOf(24, 16, 8, 0)) result.write(checksum ushr shift)
        result.write(0x80)
        repeat(bytes.size - end) { result.write(0) }
        val escaped = ByteArrayOutputStream()
        escaped.write(0x7c); escaped.write(1)
        zeros = 0
        for (b in result.toByteArray()) {
            val v = b.toInt() and 255
            if (zeros == 2 && v <= 3) { escaped.write(3); zeros = 0 }
            escaped.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return escaped.toByteArray()
    }

    private fun crc(bytes: ByteArray, start: Int, end: Int): Int {
        var crc = -1
        for (i in start until end) {
            crc = crc xor ((bytes[i].toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        return crc
    }

    private class Bits(val bytes: ByteArray, val limit: Int) {
        var position = 0
        val remaining get() = limit - position
        fun read(n: Int): Long {
            require(n in 0..32 && n <= remaining) { "truncated bitstream" }
            var value = 0L
            repeat(n) { value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position++ % 8)) and 1).toLong() }
            return value
        }
        fun readMany(n: Int) { require(n <= remaining); position += n }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0L) { zeros++; require(zeros < 30) { "Exp-Golomb overflow" } }
            return ((1L shl zeros) - 1 + read(zeros)).toInt()
        }
        fun align() { while (position % 8 != 0) require(read(1) == 0L) { "alignment" } }
    }
    private class Writer {
        private val out = ByteArrayOutputStream()
        private var byte = 0
        private var count = 0
        fun put(value: Long, n: Int) {
            for (i in n - 1 downTo 0) {
                byte = (byte shl 1) or ((value ushr i) and 1).toInt()
                if (++count == 8) { out.write(byte); count = 0; byte = 0 }
            }
        }
        fun ue(value: Int) {
            val v = value.toLong() + 1
            val n = 64 - java.lang.Long.numberOfLeadingZeros(v)
            put(0, n - 1); put(v, n)
        }
        fun copy(bytes: ByteArray, start: Int, end: Int) {
            for (i in start until end) put(((bytes[i / 8].toInt() ushr (7 - i % 8)) and 1).toLong(), 1)
        }
        fun align() { if (count > 0) put(0, 8 - count) }
        fun bytes(): ByteArray { require(count == 0); return out.toByteArray() }
    }
}
