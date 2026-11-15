package com.gdstream

internal data class IsoAudioCandidate(val key: String, val mime: String?, val supported: Boolean)

/** Rank only formats the installed renderer reports as playable; never force Blu-ray track zero. */
internal class IsoAudioPolicy {
    private val rejected = HashSet<String>()
    fun reject(key: String) { rejected.add(key) }
    fun choose(tracks: List<IsoAudioCandidate>): IsoAudioCandidate? = tracks
        .filter { it.supported && it.key !in rejected }
        .minByOrNull { when (it.mime) {
            "audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/eac3-joc" -> 0
            "audio/mpeg", "audio/opus", "audio/flac" -> 1
            "audio/raw" -> 2
            else -> 3
        } }
}

internal class IsoClockProgress(private val now: () -> Long) {
    private var lastPosition = -1L
    private var lastAdvance = now()
    fun reset() { lastAdvance = now() }
    fun stalled(position: Long, bufferedMs: Long, playing: Boolean, renderedFrame: Boolean): Boolean {
        val time = now()
        if (!playing || !renderedFrame || bufferedMs < 1500 || position != lastPosition) lastAdvance = time
        lastPosition = position
        return playing && renderedFrame && bufferedMs >= 1500 && time - lastAdvance >= 4000
    }
}
