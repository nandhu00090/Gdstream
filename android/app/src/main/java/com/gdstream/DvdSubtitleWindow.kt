package com.gdstream

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** A segment on the player's presentation clock. Keep SPUs compressed until their track is selected. */
internal data class DvdSubtitleWindow(val startUs: Long, val endUs: Long, val cues: List<DvdSpu.Cue>) {
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
