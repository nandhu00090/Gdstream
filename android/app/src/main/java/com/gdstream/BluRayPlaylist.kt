package com.gdstream

internal object BluRayPlaylist {
    data class Clip(val id: String, val ticks: Long, val size: Long, val extents: List<Pair<Long, Long>>, val inTicks: Long = 0)

    /** Independent M2TS clips can reset PCR/PTS and PIDs. Avoid splicing a warning into a feature. */
    fun playbackClips(clips: List<Clip>): List<Clip> {
        val substantial = clips.filter { it.ticks >= 30L * 45000L }
        val feature = substantial.singleOrNull()
        return if (feature != null && feature.ticks >= 600L * 45000L &&
            clips.all { it === feature || it.ticks in 1 until 30L * 45000L }) listOf(feature) else clips
    }
}
