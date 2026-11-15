package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class ArchiveBridgeLifetimeTest {
    @Test fun backgroundAndChooserTransitionsRetainExternalPlaybackUntilForegroundAndIdle() {
        var clock = 0L
        val bridge = ArchiveBridgeLifetime { clock }
        bridge.launched()
        bridge.foreground(true) // Chooser transiently returns to active before the player opens.
        assertFalse(bridge.canStop())
        bridge.foreground(false)
        clock += 8 * 60 * 60 * 1000 // No dataSync six-hour lifetime cap.
        assertFalse(bridge.canStop())
        bridge.opened()
        bridge.foreground(true)
        clock += 120_000
        assertFalse(bridge.canStop())
        bridge.closed()
        assertFalse(bridge.canStop())
        clock += 60_000
        assertTrue(bridge.canStop())
        bridge.launched()
        assertFalse(bridge.canStop())
    }
}
