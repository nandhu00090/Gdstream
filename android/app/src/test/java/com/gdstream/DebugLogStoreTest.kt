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
}
