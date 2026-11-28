package com.gdstream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Used on the proxy worker only when a player requests a WebVTT segment. */
internal class DvdSubtitleOcr : AutoCloseable {
    private var recognizer: TextRecognizer? = null

    fun recognize(source: DvdSpu.Source): String {
        return try {
            val image = DvdProxy.bitmap(source) ?: return ""
            val client = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            val page = ocrBitmap(image)
            try {
                Tasks.await(client.process(InputImage.fromBitmap(page, 0))).text
            } finally {
                page.recycle()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("DVD subtitle recognition interrupted")
        } catch (error: Exception) {
            DebugLogger.log("DVD_SUB_OCR", isoFailureDetails(error))
            ""
        }
    }

    private fun ocrBitmap(image: DvdSpu.Bitmap): Bitmap {
        val glyphs = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        // Preserve contrast on transparent DVDs and include a margin around tightly cropped glyphs.
        val page = Bitmap.createBitmap(image.width + 32, image.height + 32, Bitmap.Config.ARGB_8888)
        val hasLightInk = image.argb.any { Color.alpha(it) > 128 && Color.red(it) + Color.green(it) + Color.blue(it) > 384 }
        Canvas(page).apply {
            drawColor(if (hasLightInk) Color.BLACK else Color.WHITE)
            drawBitmap(glyphs, 16f, 16f, null)
        }
        glyphs.recycle()
        return page
    }

    override fun close() { recognizer?.close() }
}
