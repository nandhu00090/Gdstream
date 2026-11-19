package com.gdstream

/** Endpoints are aligned to the same BDAV packet grid before inspecting PES timestamps. */
internal object StandaloneBdav {
    fun matches(head: ByteArray) = head.size >= 3 * 192 &&
        (0..2).all { head[it * 192 + 4] == 0x47.toByte() }

    fun profile(head: ByteArray, tail: ByteArray, label: String): BluRayProfile {
        require(matches(head))
        val ts = ByteArray(head.size / 192 * 188)
        for (packet in 0 until ts.size / 188) {
            head.copyInto(ts, packet * 188, packet * 192 + 4, (packet + 1) * 192)
        }
        val streams = DiscAudio.inspectM2ts(head, BluRayPsi.streams(ts))
        val first = timestamps(head)
        val last = timestamps(tail)
        // Use one elementary clock at both ends. Prefer the main video when it spans both samples.
        val pid = (streams.filter { it.video }.map { it.pid } + first.keys)
            .firstOrNull { it in first && it in last }
        val ticks = if (pid == null) 0L else (last.getValue(pid).last - first.getValue(pid).first) and 0x1ffffffffL
        return BluRayProfile(ticks * 1_000_000 / 90_000, emptyList(),
            streams.filter { it.audio }.map { BluRayAudio(it.pid, it.type, it.language) }, streams, label)
    }

    private fun timestamps(data: ByteArray): Map<Int, LongRange> {
        fun u(i: Int) = data[i].toInt() and 255
        val result = linkedMapOf<Int, LongRange>()
        for (packet in 0 until data.size - 191 step 192) {
            val p = packet + 4
            if (u(p) != 0x47 || u(p + 1) and 0xc0 != 0x40 || u(p + 3) and 0xd0 != 0x10) continue
            val pes = p + 4 + if (u(p + 3) and 32 != 0) 1 + u(p + 4) else 0
            if (pes + 14 > p + 188 || u(pes) != 0 || u(pes + 1) != 0 || u(pes + 2) != 1 ||
                u(pes + 7) and 128 == 0 || u(pes + 8) < 5) continue
            val pts = pes + 9
            if (u(pts) and 1 == 0 || u(pts + 2) and 1 == 0 || u(pts + 4) and 1 == 0) continue
            val value = ((u(pts).toLong() and 14) shl 29) or (u(pts + 1).toLong() shl 22) or
                ((u(pts + 2).toLong() and 254) shl 14) or (u(pts + 3).toLong() shl 7) or (u(pts + 4) ushr 1).toLong()
            val pid = ((u(p + 1) and 31) shl 8) or u(p + 2)
            result[pid] = (result[pid]?.first ?: value)..value
        }
        return result
    }
}
