package com.gdstream

/** HDMV LPCM is big-endian, with padding channels and Blu-ray (not WAV) speaker ordering. */
internal object HdmvPcm {
    data class Layout(val rate: Int, val bytesPerSample: Int, val channels: IntArray, val codedChannels: Int) {
        val frameBytes get() = bytesPerSample * codedChannels
    }
    fun layout(header: ByteArray): Layout {
        require(header.size >= 4)
        val channels = when ((header[2].toInt() ushr 4) and 15) {
            1 -> intArrayOf(0); 3 -> intArrayOf(0, 1); 4, 5 -> intArrayOf(0, 1, 2)
            6, 7 -> intArrayOf(0, 1, 2, 3); 8 -> intArrayOf(0, 1, 2, 3, 4)
            9 -> intArrayOf(0, 1, 2, 5, 3, 4)
            10 -> intArrayOf(0, 1, 2, 4, 5, 3, 6)
            11 -> intArrayOf(0, 1, 2, 7, 4, 5, 3, 6)
            else -> throw java.io.IOException("Unsupported HDMV LPCM channel assignment")
        }
        val rate = when (header[2].toInt() and 15) { 1 -> 48000; 4 -> 96000; 5 -> 192000; else -> throw java.io.IOException("Invalid HDMV LPCM sample rate") }
        val bytes = when ((header[3].toInt() ushr 6) and 3) { 1 -> 2; 2, 3 -> 3; else -> throw java.io.IOException("Invalid HDMV LPCM bit depth") }
        return Layout(rate, bytes, channels, (channels.size + 1) and -2)
    }
    fun decode(payload: ByteArray, layout: Layout): ByteArray {
        require(payload.size % layout.frameBytes == 0) { "Partial HDMV LPCM sample frame" }
        val samples = payload.size / layout.frameBytes
        val result = ByteArray(samples * layout.channels.size * layout.bytesPerSample)
        var dest = 0
        repeat(samples) { sample ->
            for (channel in layout.channels) {
                val at = sample * layout.frameBytes + channel * layout.bytesPerSample
                for (b in layout.bytesPerSample - 1 downTo 0) result[dest++] = payload[at + b]
            }
        }
        return result
    }
}
