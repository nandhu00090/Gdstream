package com.gdstream

/** Decoder probes and pauses get the same two-minute grace as the initial chooser. */
internal class ArchiveBridgeLifetime(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var lastTransfer = now()
    @Synchronized fun launched() { lastTransfer = now() }
    @Synchronized fun transferred(bytes: Long) { if (bytes > 0) lastTransfer = now() }
    @Synchronized fun canStop(): Boolean = now() - lastTransfer >= 120_000
}
