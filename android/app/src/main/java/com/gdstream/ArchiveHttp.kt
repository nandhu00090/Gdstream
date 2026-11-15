package com.gdstream

import java.io.OutputStream

/** OPTIONS is metadata only; it must never prefetch media or supersede an active GET. */
internal fun writeArchiveOptions(method: String, out: OutputStream): Boolean {
    if (method != "OPTIONS") return false
    out.write(("HTTP/1.1 200 OK\r\nAllow: GET, HEAD, OPTIONS\r\n" +
        "Accept-Ranges: bytes\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
    out.flush()
    DebugLogger.log("ARCHIVE_PROXY_STATUS", "method=OPTIONS HTTP=200 length=0")
    return true
}

/** Count body bytes even when BufferedOutputStream coalesces headers and media into one write. */
internal class HttpBodyCounter {
    private var matched = 0
    fun bodyBytes(bytes: ByteArray, offset: Int, length: Int): Int {
        if (matched == 4) return length
        val delimiter = byteArrayOf(13, 10, 13, 10)
        for (i in offset until offset + length) {
            matched = if (bytes[i] == delimiter[matched]) matched + 1 else if (bytes[i] == delimiter[0]) 1 else 0
            if (matched == 4) return offset + length - i - 1
        }
        return 0
    }
}
