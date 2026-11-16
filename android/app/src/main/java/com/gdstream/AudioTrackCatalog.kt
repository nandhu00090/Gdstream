package com.gdstream

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.brentvatne.common.api.Track

/** One stable catalog across FFmpeg and MediaCodec renderers; both listing and selection use it. */
internal object AudioTrackCatalog {
    private data class Entry(val group: Tracks.Group, val index: Int)
    private fun entries(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).map { Entry(group, it) } }

    fun list(tracks: Tracks): ArrayList<Track> = ArrayList(entries(tracks).mapIndexed { flatIndex, entry ->
        val format = entry.group.getTrackFormat(entry.index)
        Track().apply {
            index = flatIndex
            title = format.label
            mimeType = format.sampleMimeType
            language = format.language
            bitrate = maxOf(0, format.bitrate)
            isSelected = entry.group.isTrackSelected(entry.index)
        }
    })

    fun select(player: ExoPlayer, type: String?, value: String?, preserveDefault: Boolean = true): Boolean {
        // Initial undefined/default props must not undo IsoAudioClockGuard's automatic selection.
        if (type.isNullOrEmpty() || type == "default" || type == "system") return preserveDefault
        val selector = player.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector
        val params = (selector?.parameters ?: player.trackSelectionParameters).buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (type == "disabled") {
            player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            return true
        }
        val entry = entries(player.currentTracks).withIndex().firstOrNull { (index, entry) ->
            val format = entry.group.getTrackFormat(entry.index)
            when (type) {
                "index" -> index == value?.toIntOrNull()
                "title" -> format.label == value
                "language" -> format.language == value
                else -> false
            }
        }?.value ?: return true
        if (params is androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters.Builder) {
            for (renderer in 0 until player.rendererCount) {
                if (player.getRendererType(renderer) == C.TRACK_TYPE_AUDIO) {
                    params.setRendererDisabled(renderer, false)
                    params.clearSelectionOverrides(renderer)
                }
            }
        }
        if (preserveDefault && selector != null) {
            player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        }
        player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(entry.group.mediaTrackGroup, entry.index)).build()
        // An explicit ISO selection also retries the current track after a sink failure.
        if (preserveDefault) {
            player.seekTo(player.currentPosition)
            if (player.playerError != null) player.prepare()
        }
        DebugLogger.log(if (preserveDefault) "ISO_AUDIO_USER_SELECT" else "PLAYER_AUDIO_SELECT", "id=${entry.group.getTrackFormat(entry.index).id} type=$type value=$value")
        return true
    }
}
