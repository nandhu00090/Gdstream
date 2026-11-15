package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class IsoAudioPolicyTest {
    @Test fun choosesSupportedAudioAndExhaustsEachFailureBeforeFallingBackToVideoClock() {
        val policy = IsoAudioPolicy()
        val tracks = listOf(IsoAudioCandidate("lpcm", "audio/raw", false),
            IsoAudioCandidate("dts-hd", "audio/vnd.dts.hd", true), IsoAudioCandidate("ac3", "audio/ac3", true))
        assertEquals("ac3", policy.choose(tracks)?.key)
        policy.reject("ac3")
        assertEquals("dts-hd", policy.choose(tracks)?.key)
        policy.reject("dts-hd")
        assertNull(policy.choose(tracks))
        assertNull(policy.choose(listOf(IsoAudioCandidate("hdmv-lpcm", "audio/raw", false))))
    }

    @Test fun clockFallbackRequiresRenderedVideoAndBufferedDataAndNeverTriggersForPauseOrNetworkStarvation() {
        var now = 0L
        val clock = IsoClockProgress { now }
        assertFalse(clock.stalled(0, 2000, true, true))
        now = 3999
        assertFalse(clock.stalled(0, 2000, true, true))
        now = 4000
        assertTrue(clock.stalled(0, 2000, true, true))
        clock.reset()
        now += 3999
        assertFalse(clock.stalled(0, 2000, true, true))
        assertFalse(clock.stalled(100, 2000, true, true))
        now += 10000
        assertFalse(clock.stalled(100, 1000, true, true))
        now += 10000
        assertFalse(clock.stalled(100, 2000, false, true))
        now += 10000
        assertFalse(clock.stalled(100, 2000, true, false))
    }
}
