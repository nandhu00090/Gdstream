package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class ArchiveBridgeLifetimeTest {
    @Test fun watchdogStopsAfter120SecondsWithoutTransferredBytesEvenWithZombieClients() {
        var clock = 0L
        val bridge = ArchiveBridgeLifetime { clock }
        bridge.launched()
        bridge.transferred(188)
        clock = 119_999
        assertFalse(bridge.canStop())
        bridge.transferred(0)
        clock = 120_000
        assertTrue(bridge.canStop())
        bridge.transferred(65536)
        clock += 119_999
        assertFalse(bridge.canStop())
        clock++
        assertTrue(bridge.canStop())
        bridge.launched()
        assertFalse(bridge.canStop())
    }

    @Test fun chooserGetsGraceButHeadAndOptionsCannotExtendIt() {
        var clock = 0L
        val bridge = ArchiveBridgeLifetime { clock }
        bridge.launched()
        val head = "HTTP/1.1 200 OK\r\nContent-Length: 41000000000\r\n\r\n".toByteArray()
        val counter = HttpBodyCounter()
        bridge.transferred(counter.bodyBytes(head, 0, head.size).toLong())
        clock = 60_000
        assertFalse(bridge.canStop())
        clock = 120_000
        assertTrue(bridge.canStop())
        bridge.launched()
        val combined = head + ByteArray(188)
        val body = HttpBodyCounter()
        assertEquals(0, body.bodyBytes(combined, 0, 20))
        bridge.transferred(body.bodyBytes(combined, 20, combined.size - 20).toLong())
        clock += 120_000
        assertTrue(bridge.canStop())
    }

    @Test fun fullReloadSupersedesOnlyTheSameStreamAndShutdownCancelsAllWork() {
        val registry = ProxyStreamRegistry()
        val cancelled = ArrayList<String>()
        val old = registry.register("disc", true) { cancelled.add("old") }
        registry.register("disc", false) { cancelled.add("probe") }
        registry.register("other", true) { cancelled.add("other") }
        registry.register("disc", true) { cancelled.add("new") }
        assertEquals(listOf("old"), cancelled)
        old.close() // Finishing the old handler must not unregister its replacement.
        registry.cancelStream("disc", "external handoff")
        assertEquals(listOf("old", "probe", "new"), cancelled)
        registry.stopAll("Recents / idle timeout")
        assertEquals(listOf("old", "probe", "new", "other"), cancelled)
        assertThrows(java.io.InterruptedIOException::class.java) { registry.register("zombie", true) {} }
        registry.resume()
        registry.register("next", true) { cancelled.add("next") }
        registry.stopAll("stop")
        assertEquals("next", cancelled.last())
    }
}
