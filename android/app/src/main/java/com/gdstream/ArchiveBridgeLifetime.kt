package com.gdstream

/** Activity/chooser transitions cannot tear down a bridge that is streaming in another app. */
internal class ArchiveBridgeLifetime(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var foreground = true
    private var clients = 0
    private var lastActivity = now()
    @Synchronized fun launched() { lastActivity = now() }
    @Synchronized fun foreground(value: Boolean) { foreground = value; lastActivity = now() }
    @Synchronized fun opened() { clients++; lastActivity = now() }
    @Synchronized fun closed() { clients = (clients - 1).coerceAtLeast(0); lastActivity = now() }
    @Synchronized fun canStop(): Boolean = foreground && clients == 0 && now() - lastActivity >= 60_000
}
