package com.gdstream

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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

    private fun title(format: Format, index: Int): String? {
        val label = format.label
        if (format.containerMimeType != MimeTypes.VIDEO_MP2T ||
            !label.isNullOrBlank() && !label.equals("Unknown", true)) return label
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_RAW -> "LPCM"
            MimeTypes.AUDIO_AAC -> "AAC"
            else -> ""
        }
        val name = bluRayLanguage(format.language.orEmpty()).takeUnless { it == "Unknown" } ?: "Audio ${index + 1}"
        return listOf(name, codec, audioChannelLabel(format.channelCount)).filter { it.isNotEmpty() }.joinToString(" • ")
    }

    fun list(tracks: Tracks): ArrayList<Track> = ArrayList(entries(tracks).mapIndexed { flatIndex, entry ->
        val format = entry.group.getTrackFormat(entry.index)
        Track().apply {
            index = flatIndex
            title = title(format, flatIndex)
            mimeType = format.sampleMimeType
            language = format.language
            bitrate = maxOf(0, format.bitrate)
            isSelected = entry.group.isTrackSelected(entry.index)
        }
    })

    fun clearedAudioParameters(player: ExoPlayer): androidx.media3.common.TrackSelectionParameters.Builder {
        val selector = player.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector
        val params = (selector?.parameters ?: player.trackSelectionParameters).buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (params is androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters.Builder) {
            for (renderer in 0 until player.rendererCount) if (player.getRendererType(renderer) == C.TRACK_TYPE_AUDIO) {
                params.setRendererDisabled(renderer, false)
                params.clearSelectionOverrides(renderer)
            }
        }
        return params
    }

    fun select(player: ExoPlayer, type: String?, value: String?, preserveDefault: Boolean = true): Boolean {
        // Initial undefined/default props must not undo IsoAudioClockGuard's automatic selection.
        if (type.isNullOrEmpty() || type == "default" || type == "system") return preserveDefault
        val params = clearedAudioParameters(player)
        if (type == "disabled") {
            player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            return true
        }
        val entry = entries(player.currentTracks).withIndex().firstOrNull { (index, entry) ->
            val format = entry.group.getTrackFormat(entry.index)
            when (type) {
                "index" -> index == value?.toIntOrNull()
                "title" -> title(format, index) == value
                "language" -> format.language == value
                else -> false
            }
        }?.value ?: return true
        player.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(entry.group.mediaTrackGroup, entry.index)).build()
        // A renderer selection consumes the existing sample queues. Never seek/reopen the ISO.
        if (preserveDefault && player.playerError != null) player.prepare()
        DebugLogger.log(if (preserveDefault) "ISO_AUDIO_USER_SELECT" else "PLAYER_AUDIO_SELECT", "id=${entry.group.getTrackFormat(entry.index).id} type=$type value=$value")
        return true
    }
}
