package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class DebugLogStoreTest {
    @Test fun rotatedUnicodeLogsAndOversizedMessagesNeverExceedTwoMiB() {
        val file = java.io.File.createTempFile("gdstream-log-cap", ".txt").apply { deleteOnExit() }
        val store = DebugLogStore(file)
        repeat(100) {
            store.append("ISO_TEST", "தமிழ்".repeat(5000))
            assertTrue(file.length() <= 2 * 1024 * 1024)
        }
        store.append("COMPLETE", "done")
        assertTrue(store.snapshot().endsWith("[COMPLETE] done\n"))
        assertTrue(store.capture().generation > 0)
    }
    @Test fun bootReplacesFullExportAndRotationKeepsNewestEntries() {
        val file = java.io.File.createTempFile("gdstream-session", ".txt").apply { deleteOnExit() }
        val store = DebugLogStore(file)
        val mirrored = java.util.concurrent.atomic.AtomicReference("")
        DebugLogger.attach(store) { text, append -> mirrored.set(if (append) mirrored.get() + text else text) }
        repeat(140) { DebugLogger.log("OLD_SESSION", "x".repeat(16000)) }
        DebugLogger.log("APP_BOOT", "fresh session")
        DebugLogger.log("ISO_NEW", "latest request")
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!mirrored.get().contains("latest request") && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(mirrored.get().contains("APP_BOOT"))
        assertTrue(mirrored.get().contains("latest request"))
        assertFalse(mirrored.get().contains("OLD_SESSION"))
        assertTrue(file.length() <= 2 * 1024 * 1024)
    }

}
