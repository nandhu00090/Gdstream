package com.gdstream

import org.junit.Assert.*
import org.junit.Test

class BluRayPlaylistTest {
    private fun clip(id: String, seconds: Long, bytes: Long) = BluRayPlaylist.Clip(id, seconds * 45000L, bytes, listOf(4200000000L to bytes))

    @Test fun aadhavanDisclaimerIsExcludedFromFeatureBytesAndTimeline() {
        val intro = clip("00000", 14, 50000000)
        val movie = clip("00006", 9995, 42256656256L)
        val selected = BluRayPlaylist.playbackClips(listOf(intro, movie))
        assertEquals(listOf(movie), selected)
        assertEquals(9995L, selected.sumOf { it.ticks } / 45000)
        assertEquals(42256656256L, selected.sumOf { it.size })
    }

    @Test fun keepsMultiPartFeaturesAndShortProgrammesIntact() {
        val clips = listOf(clip("00001", 6000, 22000000000), clip("00002", 4000, 18000000000))
        assertEquals(clips, BluRayPlaylist.playbackClips(clips))
        val shorts = listOf(clip("00000", 14, 1000), clip("00006", 200, 2000))
        assertEquals(shorts, BluRayPlaylist.playbackClips(shorts))
    }
}
