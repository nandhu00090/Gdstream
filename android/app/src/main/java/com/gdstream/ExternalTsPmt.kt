package com.gdstream

/** In-place PSI rewriting: packet count, PCR, PES bytes and virtual seek offsets stay unchanged.
 * Call with a packet-aligned window including 4096 bytes of context on either side of a range.
 */
internal object ExternalTsPmt {
    fun crc(bytes: ByteArray): Int {
        var value = -1
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 255) shl 24)
            repeat(8) { value = (value shl 1) xor (if (value < 0) 0x04c11db7 else 0) }
        }
        return value
    }
    fun rewrite(section: ByteArray): ByteArray? {
        fun u(i: Int) = section[i].toInt() and 255
        if (section.size < 16 || u(0) != 2 || crc(section) != 0) return null
        val programEnd = 12 + ((u(10) and 15) shl 8) + u(11)
        if (programEnd > section.size - 4) return null
        val result = java.io.ByteArrayOutputStream()
        result.write(section, 0, programEnd)
        var scan = programEnd
        var nativeDolby = false
        while (scan < section.size - 4) {
            if (scan + 5 > section.size - 4) return null
            val size = 5 + ((u(scan + 3) and 15) shl 8) + u(scan + 4)
            if (scan + size > section.size - 4) return null
            if (u(scan) == 0x81 || u(scan) == 0x84) nativeDolby = true
            scan += size
        }
        var pos = programEnd
        while (pos < section.size - 4) {
            if (pos + 5 > section.size - 4) return null
            val type = u(pos)
            val pid = ((u(pos + 1) and 31) shl 8) + u(pos + 2)
            val size = 5 + ((u(pos + 3) and 15) shl 8) + u(pos + 4)
            if (pos + size > section.size - 4) return null
            if (type != 0x80 && pid != 0x1015 && !(type == 0x83 && nativeDolby)) {
                val entry = section.copyOfRange(pos, pos + size)
                // Preserve ES descriptors (especially HEVC registration) byte for byte.
                // Only expose the interleaved AC-3 companion when no native Dolby PID exists.
                entry[0] = when (type) { 0x85, 0x86 -> 0x82.toByte(); 0x83 -> 0x81.toByte(); else -> type.toByte() }
                result.write(entry)
            }
            pos += size
        }
        val body = result.toByteArray()
        val length = body.size + 1 // bytes after section_length, including CRC
        body[1] = ((body[1].toInt() and 0xf0) or (length ushr 8)).toByte()
        body[2] = length.toByte()
        val checksum = crc(body)
        return body + byteArrayOf((checksum ushr 24).toByte(), (checksum ushr 16).toByte(), (checksum ushr 8).toByte(), checksum.toByte())
    }
    fun patch(data: ByteArray) {
        fun u(i: Int) = data[i].toInt() and 255
        fun payload(p: Int): Int {
            if (u(p) != 0x47 || u(p + 1) and 0x80 != 0 || u(p + 3) and 0xc0 != 0 || u(p + 3) and 0x10 == 0) return p + 188
            return p + 4 + if (u(p + 3) and 0x20 != 0) 1 + u(p + 4) else 0
        }
        for (p in 0 until data.size - 187 step 188) {
            var start = payload(p)
            if (start >= p + 188 || u(p + 1) and 0x40 == 0) continue
            start += 1 + u(start)
            if (start + 3 > p + 188 || u(start) != 2) continue
            val length = 3 + ((u(start + 1) and 15) shl 8) + u(start + 2)
            if (length !in 16..1024) continue
            val pid = ((u(p + 1) and 31) shl 8) + u(p + 2)
            val positions = ArrayList<Int>()
            var packet = p
            var cc = u(p + 3) and 15
            while (packet + 188 <= data.size && packet < p + 32 * 188 && positions.size < length) {
                if (packet != p) {
                    if (((u(packet + 1) and 31) shl 8) + u(packet + 2) != pid) { packet += 188; continue }
                    start = payload(packet)
                    if (start >= packet + 188) { packet += 188; continue }
                    if (u(packet + 1) and 0x40 != 0 || (u(packet + 3) and 15) != ((cc + 1) and 15)) break
                    cc = u(packet + 3) and 15
                }
                while (start < packet + 188 && positions.size < length) positions.add(start++)
                packet += 188
            }
            if (positions.size != length) continue
            val section = ByteArray(length) { data[positions[it]] }
            val changed = rewrite(section) ?: continue
            positions.forEachIndexed { i, at -> data[at] = if (i < changed.size) changed[i] else 0xff.toByte() }
        }
    }
}
