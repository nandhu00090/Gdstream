package com.gdstream

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Attached only to localhost ISO transport streams; video uses its own clock if audio cannot run. */
internal class IsoAudioClockGuard(private val player: ExoPlayer) : AutoCloseable, Player.Listener, AnalyticsListener {
    private companion object {
        val ac3FailureCodes = setOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        )
    }
    private val policy = IsoAudioPolicy()
    private val handler = Handler(player.applicationLooper)
    private var configured = false
    private var closed = false
    private var firstFrame = false
    private var audioDisabled = false
    private var selectedKey: String? = null
    private var retriedDiscontinuity = false
    private val clock = IsoClockProgress { android.os.SystemClock.elapsedRealtime() }

    private fun key(group: Tracks.Group, index: Int) = "${group.mediaTrackGroup.id}:$index:${group.getTrackFormat(index).sampleMimeType}"
    private fun selectAudio(rejectSelected: Boolean): Boolean {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (rejectSelected) {
            val selected = groups.flatMap { group -> (0 until group.length)
                .filter { group.isTrackSelected(it) }.map { key(group, it) } }
            if (selected.isEmpty()) selectedKey?.let { policy.reject(it) } else selected.forEach { policy.reject(it) }
        }
        val candidates = groups.flatMap { group -> (0 until group.length).map { index ->
            IsoAudioCandidate(key(group, index), group.getTrackFormat(index).sampleMimeType, group.isTrackSupported(index))
        } }
        val chosen = policy.choose(candidates)
        val params = AudioTrackCatalog.clearedAudioParameters(player)
        if (chosen == null) {
            if (audioDisabled || !rejectSelected && candidates.any { it.mime == "audio/ac3" }) return false
            audioDisabled = true
            player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            DebugLogger.log("ISO_AUDIO_FALLBACK", "No playable audio remains; disabled audio renderer so video clock can advance")
        } else {
            for (group in groups) for (i in 0 until group.length) if (key(group, i) == chosen.key) {
                selectedKey = chosen.key
                player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                DebugLogger.log("ISO_AUDIO_SELECT", "track=${chosen.key} supported=true")
            }
        }
        clock.reset()
        return true
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        clock.reset()
        if (reason == Player.DISCONTINUITY_REASON_SEEK) retriedDiscontinuity = false
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        // The sink handles nonfatal timestamp jumps itself; give the new clock time to settle.
        if (audioSinkError is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException) clock.reset()
    }

    override fun onTracksChanged(tracks: Tracks) {
        val selected = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { key(group, it) }
        }
        if (selected != null && selected != selectedKey) { selectedKey = selected; clock.reset() }
        if (audioDisabled && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }) {
            audioDisabled = false
            clock.reset()
        }
        if (!configured && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
            configured = true
            selectAudio(false)
        }
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        firstFrame = true
        clock.reset()
    }

    fun recover(error: PlaybackException): Boolean {
        val renderer = error as? ExoPlaybackException ?: return false
        val audio = renderer.type == ExoPlaybackException.TYPE_RENDERER &&
            (renderer.rendererFormat?.sampleMimeType?.startsWith("audio/") == true ||
                (renderer.rendererIndex in 0 until player.rendererCount && player.getRendererType(renderer.rendererIndex) == C.TRACK_TYPE_AUDIO))
        if (!audio || audioDisabled) return false
        DebugLogger.log("ISO_AUDIO_ERR", isoFailureDetails(error))
        val timestampJump = generateSequence<Throwable>(error) { it.cause }
            .any { it is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException }
        if (timestampJump && !retriedDiscontinuity) {
            retriedDiscontinuity = true
            clock.reset()
            player.prepare()
            return true
        }
        val ac3 = renderer.rendererFormat?.sampleMimeType == "audio/ac3" || selectedKey?.endsWith(":audio/ac3") == true
        if (ac3 && !timestampJump && error.errorCode !in ac3FailureCodes) return false
        if (!selectAudio(true)) return false
        player.prepare() // Reuse the existing MediaSource/player; no recursive source reload.
        return true
    }

    private fun canRejectStalledTrack(): Boolean {
        val selected = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        return selected.isNotEmpty() && selected.none { group ->
            (0 until group.length).any { group.isTrackSelected(it) && group.getTrackFormat(it).sampleMimeType == "audio/ac3" }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (closed) return
            val position = player.currentPosition
            val playing = player.playbackState == Player.STATE_READY && player.isPlaying
            if (clock.stalled(position, player.totalBufferedDuration, playing, firstFrame) &&
                !audioDisabled && configured && canRejectStalledTrack()) {
                DebugLogger.log("ISO_AUDIO_CLOCK", "Video frame rendered but position=$position unchanged with bufferedMs=${player.totalBufferedDuration}; trying next supported audio track")
                selectAudio(true)
            }
            handler.postDelayed(this, 500)
        }
    }

    init {
        player.addListener(this)
        player.addAnalyticsListener(this)
        handler.postDelayed(watchdog, 500)
    }
    override fun close() {
        closed = true
        handler.removeCallbacks(watchdog)
        player.removeListener(this)
        player.removeAnalyticsListener(this)
    }
}
