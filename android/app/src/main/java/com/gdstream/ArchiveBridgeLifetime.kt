package com.gdstream

/** Only delivered bytes reset the deadline. Connected sockets alone cannot extend the deadline. */
internal class ArchiveBridgeLifetime(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var lastTransfer = now()
    @Synchronized fun launched() { lastTransfer = now() }
    @Synchronized fun transferred(bytes: Long) { if (bytes > 0) lastTransfer = now() }
    @Synchronized fun canStop(): Boolean = now() - lastTransfer >= 15_000
}
