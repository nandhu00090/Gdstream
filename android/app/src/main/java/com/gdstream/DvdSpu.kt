package com.gdstream

/** Private stream 1 carries a one-byte SPU ID followed by part of a length-prefixed display unit.
 * The PTS belongs to the first fragment, which can be in the preceding HLS segment. */
internal class DvdSpu {
    data class Display(val show: Long, val stop: Long, val segments: ByteArray)
    private val header = ByteArray(2)
    private var used = 0
    private var packet: ByteArray? = null
    private var start: Long? = null

    fun reset() { packet = null; used = 0; start = null }

    fun pendingBefore(tick: Long): DvdSpu? {
        if ((start ?: return null) >= tick) return null
        return DvdSpu().also {
            it.start = start
            it.used = used
            header.copyInto(it.header)
            it.packet = packet?.copyOf()
        }
    }

    fun consume(data: ByteArray, from: Int, end: Int, pts: Long?, emit: (Long, ByteArray) -> Unit) {
        if (pts != null) { reset(); start = pts }
        val tick = start ?: return // Cold seek into a continuation.
        var pos = from
        if (used < 2) {
            val n = minOf(2 - used, end - pos)
            data.copyInto(header, used, pos, pos + n)
            used += n
            pos += n
            if (used < 2) return
            val size = ((header[0].toInt() and 255) shl 8) or (header[1].toInt() and 255)
            if (size < 8) { reset(); return }
            packet = ByteArray(size).also { header.copyInto(it) }
        }
        val bytes = checkNotNull(packet)
        val n = minOf(bytes.size - used, end - pos)
        data.copyInto(bytes, used, pos, pos + n)
        used += n
        if (used == bytes.size) {
            reset()
            emit(tick, bytes)
        }
    }
}
