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

    private fun isVideoType(type: Int) = type in listOf(0x01, 0x02, 0x1b, 0x20, 0x24, 0xea)

    fun rewrite(
        section: ByteArray,
        streams: List<BluRayStream> = emptyList(),
        audioPid: Int? = null,
        offline: Boolean = false,
        videoOnly: Boolean = false,
        transcodeAc3: Boolean = false
    ): ByteArray? {
        fun u(i: Int) = section[i].toInt() and 255
        if (section.size < 16 || u(0) != 2 || crc(section) != 0) return null
        val programEnd = 12 + ((u(10) and 15) shl 8) + u(11)
        if (programEnd > section.size - 4) return null
        val result = java.io.ByteArrayOutputStream()
        result.write(section, 0, programEnd)

        var scan = programEnd
        var nativeDolby = false
        var primaryVideoPid = -1
        while (scan < section.size - 4) {
            if (scan + 5 > section.size - 4) return null
            val t = u(scan)
            val pid = ((u(scan + 1) and 31) shl 8) + u(scan + 2)
            val size = 5 + ((u(scan + 3) and 15) shl 8) + u(scan + 4)
            if (scan + size > section.size - 4) return null
            if (t == 0x81 || t == 0x84) nativeDolby = true
            if (isVideoType(t) && pid != 0x1015) {
                if (primaryVideoPid < 0 || pid == 0x1011) primaryVideoPid = pid
            }
            scan += size
        }

        var pos = programEnd
        while (pos < section.size - 4) {
            if (pos + 5 > section.size - 4) return null
            val type = u(pos)
            val pid = ((u(pos + 1) and 31) shl 8) + u(pos + 2)
            val size = 5 + ((u(pos + 3) and 15) shl 8) + u(pos + 4)
            if (pos + size > section.size - 4) return null
            val stream = streams.firstOrNull { it.pid == pid }
            val isSecondaryVideo = isVideoType(type) && (pid == 0x1015 || (primaryVideoPid > 0 && pid != primaryVideoPid))
            val include = if (audioPid != null) {
                pid == audioPid
            } else {
                offline || (!isSecondaryVideo &&
                    (type != 0x80 || stream != null) &&
                    (type != 0x83 || !nativeDolby || stream != null))
            }
            if (include && !(videoOnly && (stream ?: BluRayStream(pid, type)).audio)) {
                val language = stream?.takeIf { it.audio || type == 0x90 }?.language
                    ?.takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } && it != "und" }
                val outType = if (transcodeAc3 && pid == audioPid) 0x81 else when (type) {
                    0x85, 0x86 -> if (offline) type else 0x82
                    0x83 -> 0x81
                    else -> type
                }
                val descriptors = java.io.ByteArrayOutputStream()
                var hasReg = false
                var hasAc3Desc = false
                var hasEac3Desc = false
                var at = pos + 5
                while (at < pos + size) {
                    if (at + 2 > pos + size) return null
                    val tag = u(at)
                    val next = at + 2 + u(at + 1)
                    if (next > pos + size) return null
                    // A TrueHD registration/codec descriptor contradicts the exposed AC-3 core.
                    val drop = (transcodeAc3 && tag != 10) || (language != null && tag == 10) ||
                        (type == 0x83 && tag in listOf(5, 0x81, 0x83)) ||
                        (audioPid != null && tag == 5)
                    if (!drop) {
                        if (tag == 5) hasReg = true
                        if (tag == 0x6A) hasAc3Desc = true
                        if (tag == 0x7A) hasEac3Desc = true
                        descriptors.write(section, at, next - at)
                    }
                    at = next
                }
                // For audio-only HLS PMTs (LibVLC 3.0 & ExoPlayer), emit ATSC registration + DVB descriptors.
                if (audioPid != null) {
                    if (!hasReg) {
                        val regTag = when (outType) {
                            0x81 -> "AC-3"
                            0x84, 0xA1 -> "EAC3"
                            0x82, 0x85, 0x86, 0xA2 -> "DTS1"
                            else -> null
                        }
                        if (regTag != null) {
                            descriptors.write(byteArrayOf(0x05, 0x04))
                            descriptors.write(regTag.toByteArray(Charsets.US_ASCII))
                        }
                    }
                    if (outType == 0x81 && !hasAc3Desc) descriptors.write(byteArrayOf(0x6A, 0x01, 0x00))
                    if ((outType == 0x84 || outType == 0xA1) && !hasEac3Desc) descriptors.write(byteArrayOf(0x7A, 0x01, 0x00))
                }
                if (language != null) {
                    descriptors.write(byteArrayOf(10, 4))
                    descriptors.write(language.toByteArray(Charsets.US_ASCII))
                    descriptors.write(0)
                }
                result.write(outType)
                result.write(section, pos + 1, 2)
                result.write(0xf0 or (descriptors.size() ushr 8))
                result.write(descriptors.size())
                descriptors.writeTo(result)
            }
            pos += size
        }
        val body = result.toByteArray()
        // For audio-only HLS segments, point PCR_PID directly at audioPid so LibVLC clock-syncs cleanly.
        if (audioPid != null) {
            body[8] = (0xe0 or ((audioPid ushr 8) and 0x1f)).toByte()
            body[9] = (audioPid and 0xff).toByte()
        }
        val length = body.size + 1
        if (length > 1021) return null
        body[1] = ((body[1].toInt() and 0xf0) or (length ushr 8)).toByte()
        body[2] = length.toByte()
        val checksum = crc(body)
        return body + byteArrayOf((checksum ushr 24).toByte(), (checksum ushr 16).toByte(), (checksum ushr 8).toByte(), checksum.toByte())
    }

    /** Keep arrival timestamps and byte addressing intact, including unaligned resumed downloads. */
    fun readM2ts(start: Long, length: Int, size: Long, streams: List<BluRayStream>,
                 fetch: (Long, Int) -> ByteArray): ByteArray {
        val from = (start / 192 - 64).coerceAtLeast(0) * 192
        val until = minOf(size, ((start + length + 191) / 192 + 64) * 192)
        val raw = fetch(from, (until - from).toInt())
        patch(raw, streams, m2ts = true)
        val offset = (start - from).toInt()
        return raw.copyOfRange(offset, offset + length)
    }

    fun patch(data: ByteArray, streams: List<BluRayStream> = emptyList(), m2ts: Boolean = false, videoOnly: Boolean = false) {
        val stride = if (m2ts) 192 else 188
        val first = stride - 188
        fun u(i: Int) = data[i].toInt() and 255
        fun payload(p: Int): Int {
            if (u(p) != 0x47 || u(p + 1) and 0x80 != 0 || u(p + 3) and 0xc0 != 0 || u(p + 3) and 0x10 == 0) return p + 188
            return p + 4 + if (u(p + 3) and 0x20 != 0) 1 + u(p + 4) else 0
        }
        // Keep byte-range offsets stable by replacing removed PES with null packets.
        // Strip secondary DV enhancement video PID (0x1015) and audio PIDs in video-only HLS segments.
        val primaryVideoPid = streams.filter { it.video && it.pid != 0x1015 }
            .maxByOrNull { it.height }?.pid ?: 0x1011
        val strippedPids = HashSet<Int>()
        if (!m2ts) {
            strippedPids.add(0x1015)
            streams.filter { it.video && it.pid != primaryVideoPid }.forEach { strippedPids.add(it.pid) }
        }
        if (videoOnly) {
            streams.filter { it.audio }.forEach { strippedPids.add(it.pid) }
        }
        if (strippedPids.isNotEmpty()) {
            for (p in first until data.size - 187 step stride) {
                if (u(p) != 0x47 || (((u(p + 1) and 31) shl 8) or u(p + 2)) !in strippedPids) continue
                if (u(p + 3) and 32 != 0 && u(p + 4) in 7..183 && u(p + 5) and 16 != 0) {
                    data[p + 1] = (u(p + 1) and 31).toByte()
                    data[p + 3] = (32 or (u(p + 3) and 15)).toByte()
                    data[p + 4] = 183.toByte()
                    data[p + 5] = 16
                    data.fill(0xff.toByte(), p + 12, p + 188)
                } else {
                    data[p + 1] = 31
                    data[p + 2] = 0xff.toByte()
                    data[p + 3] = 0x10
                    data.fill(0xff.toByte(), p + 4, p + 188)
                }
            }
        }
        for (p in first until data.size - 187 step stride) {
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
            while (packet + 188 <= data.size && packet < p + 32 * stride && positions.size < length) {
                if (packet != p) {
                    if (((u(packet + 1) and 31) shl 8) + u(packet + 2) != pid) { packet += stride; continue }
                    start = payload(packet)
                    if (start >= packet + 188) { packet += stride; continue }
                    if (u(packet + 1) and 0x40 != 0 || (u(packet + 3) and 15) != ((cc + 1) and 15)) break
                    cc = u(packet + 3) and 15
                }
                while (start < packet + 188 && positions.size < length) positions.add(start++)
                packet += stride
            }
            if (positions.size != length) continue
            val section = ByteArray(length) { data[positions[it]] }
            val changed = rewrite(section, streams, offline = m2ts, videoOnly = videoOnly) ?: continue
            while (positions.size < changed.size && start < packet - first && u(start) == 0xff) positions.add(start++)
            if (positions.size < changed.size) {
                if (u(payload(p)) != 0) continue
                val donors = ArrayList<Int>()
                var donor = p - stride
                var capacity = positions.size
                while (capacity < changed.size && donor >= first && donor >= p - 32 * stride) {
                    val previousPid = ((u(donor + 1) and 31) shl 8) or u(donor + 2)
                    if (previousPid == pid) break
                    if (u(donor) == 0x47 && previousPid == 0x1fff) {
                        donors.add(donor)
                        capacity += if (donors.size == 1) 181 else 184
                    }
                    donor -= stride
                }
                if (capacity < changed.size) {
                    donors.clear()
                    val flags = u(p+3) and 0x20 != 0
                    val reserve = if (!flags) 2 else if (u(p+4) == 0) 1 else 0
                    capacity = positions.size - reserve
                    donor = packet
                    while (capacity < changed.size && donor+188 <= data.size && donor < p+32*stride) {
                        val nextPid = ((u(donor+1) and 31) shl 8) or u(donor+2)
                        if (nextPid == pid) break
                        if (u(donor) == 0x47 && nextPid == 0x1fff) {
                            donors.add(donor)
                            capacity += 184
                        }
                        donor += stride
                    }
                    if (capacity < changed.size || positions.take(reserve).any { it >= p+188 }) continue
                    val headers = positions.map { ((it-first)/stride)*stride+first }.distinct()
                    for (header in headers) {
                        data[header+3] = ((u(header+3) and 0xf0) or ((u(header+3)-donors.size) and 15)).toByte()
                    }
                    if (reserve > 0) {
                        data[p+3] = (u(p+3) or 0x20).toByte()
                        data[p+4] = 1
                        data[p+5] = 0
                        data[p+6] = 0
                        repeat(reserve) { positions.removeAt(0) }
                    }
                    data[p+5] = (u(p+5) or 0x80).toByte()
                    donors.forEachIndexed { i, header ->
                        data[header+1] = (pid ushr 8).toByte()
                        data[header+2] = pid.toByte()
                        data[header+3] = (0x10 or ((cc-donors.size+i+1) and 15)).toByte()
                        for (at in header+4 until header+188) positions.add(at)
                    }
                } else {
                    val extra = ArrayList<Int>()
                    donors.asReversed().forEachIndexed { i, header ->
                        val begin = i == 0
                        data[header+1] = ((pid ushr 8) or if (begin) 0x40 else 0).toByte()
                        data[header+2] = pid.toByte()
                        val counter = (u(p+3) - donors.size + i) and 15
                        data[header+3] = ((if (begin) 0x30 else 0x10) or counter).toByte()
                        if (begin) {
                            data[header+4] = 1
                            data[header+5] = 0x80.toByte()
                            data[header+6] = 0
                        }
                        for (at in header + (if (begin) 7 else 4) until header+188) extra.add(at)
                    }
                    data[p+1] = (u(p+1) and 0xbf).toByte()
                    for (at in payload(p) until positions.first()) extra.add(at)
                    positions.addAll(0, extra)
                }
            }
            positions.forEachIndexed { i, at -> data[at] = if (i < changed.size) changed[i] else 0xff.toByte() }
        }
    }
}
