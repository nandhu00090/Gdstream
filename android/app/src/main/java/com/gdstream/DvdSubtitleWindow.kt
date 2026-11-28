package com.gdstream

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** A segment on the player's presentation clock. Keep SPUs compressed until their track is selected. */
internal data class DvdSubtitleWindow(val startUs: Long, val endUs: Long, val cues: List<DvdSpu.Cue>) {
    /** Cue times are relative to this segment. Map zero to the PTS of its first encoded picture,
     * including after a cold seek, a PGC discontinuity or a 33-bit MPEG clock wrap. */
    fun webVtt(track: Int, firstPts: Long, recognize: (DvdSpu.Source) -> String): ByteArray = buildString {
        append("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:${firstPts and 0x1ffffffffL}\n\n")
        for (cue in cues) {
            if (cue.track != track) continue
            val fromMs = cue.showUs.coerceAtLeast(0) / 1000
            val untilMs = minOf(cue.stopUs, endUs - startUs) / 1000
            if (untilMs <= fromMs) continue
            val lines = recognize(cue.source).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.isEmpty()) continue
            append("${timestamp(fromMs)} --> ${timestamp(untilMs)}\n")
            for (line in lines) append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append('\n')
            append('\n')
        }
    }.toByteArray()

    private fun timestamp(ms: Long) = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    fun render(track: Int): Map<String, Any> {
        val images = cues.filter { it.track == track && it.showUs < endUs - startUs && it.stopUs > 0 }.mapNotNull { cue ->
            val image = DvdProxy.bitmap(cue.source) ?: return@mapNotNull null
            val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            mapOf("showUs" to (startUs + cue.showUs.coerceAtLeast(0)).toDouble(),
                "stopUs" to (startUs + minOf(cue.stopUs, endUs - startUs)).toDouble(),
                "x" to image.x, "y" to image.y, "width" to image.width, "height" to image.height,
                "canvasHeight" to cue.source.canvasHeight,
                "uri" to "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        }
        return mapOf("startUs" to startUs.toDouble(), "endUs" to endUs.toDouble(), "cues" to images)
    }
}

internal class DvdSubtitleWindows {
    private val windows = LinkedHashMap<Int, DvdSubtitleWindow>()
    private var streamId: String? = null
    @Synchronized fun put(id: String, segment: Int, window: DvdSubtitleWindow) {
        if (streamId != id) {
            windows.clear()
            streamId = id
        }
        windows.remove(segment)
        windows[segment] = window
        // Cover the player's 45-second buffer independently of the much larger TS cache.
        while (windows.size > 128) windows.remove(windows.keys.first())
    }
    @Synchronized fun at(id: String, timeUs: Long) = if (streamId != id) null else
        windows.values.lastOrNull { timeUs >= it.startUs && timeUs < it.endUs }
}
