package com.gdstream

/** Allow time for the Android chooser, then retain PR4's 15-second media inactivity deadline. */
internal class ArchiveBridgeLifetime(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var lastTransfer = now()
    private var awaitingMedia = true
    @Synchronized fun launched() { lastTransfer = now(); awaitingMedia = true }
    @Synchronized fun transferred(bytes: Long) {
        if (bytes > 0) { lastTransfer = now(); awaitingMedia = false }
    }
    @Synchronized fun canStop(): Boolean = now() - lastTransfer >= if (awaitingMedia) 120_000 else 15_000
}
