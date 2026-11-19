package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class StandaloneBdavTest {
    private fun packet(pid: Int, pts: Long) = ByteArray(192).apply {
        this[4] = 0x47
        this[5] = (64 or (pid ushr 8)).toByte()
        this[6] = pid.toByte()
        this[7] = 0x10
        byteArrayOf(0, 0, 1, 0xe0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
            (33 or ((pts ushr 29).toInt() and 14)).toByte(), (pts ushr 22).toByte(),
            ((pts ushr 14) or 1).toByte(), (pts ushr 7).toByte(), ((pts shl 1) or 1).toByte()).copyInto(this, 8)
    }

    @Test fun endpointsUseTheSamePidAndHandlePtsRollover() {
        val head = packet(0x1011, (1L shl 33) - 90000) + packet(0x1100, 0) + packet(0x1011, 100)
        val tail = packet(0x1011, 180000) + packet(0x1100, 99999999)
        val profile = StandaloneBdav.profile(head, tail, "opaque download")
        assertEquals(3000000L, profile.durationUs)
        assertFalse(StandaloneBdav.matches(ByteArray(192 * 3).apply { this[4] = 0x47 }))
        val size = 188L * 100
        val hls = BluRayHls.create(profile, size, standalone = true)!!
        assertEquals(3000000L, hls.segments.single().durationUs)
        assertEquals(size, hls.segments.single().end)
    }

    @Test fun virtualPlaylistCoversEveryPacketOfLargeVariableDurationFile() {
        val size = 188L * 111111111
        val duration = 60123456L
        val hls = BluRayHls.create(BluRayProfile(duration, emptyList(), emptyList()), size, true)!!
        assertEquals(duration, hls.segments.sumOf { it.durationUs })
        assertEquals(size, hls.segments.sumOf { it.end - it.start })
        assertTrue(hls.segments.zipWithNext().all { (a,b) -> a.end == b.start && b.start % 188 == 0L })
    }
}
