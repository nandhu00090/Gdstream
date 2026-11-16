package com.gdstream

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A bounded current-session log; APP_BOOT invalidates the export append cursor. */
internal class DebugLogStore(private val file: File) {
    private var generation = 0L
    data class Snapshot(val text: String, val generation: Long)
    @Synchronized fun append(tag: String, message: String) {
        file.parentFile?.mkdirs()
        if (tag == "APP_BOOT") { file.writeText(""); generation++ }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        // Bound individual messages too; use byte lengths so non-ASCII names cannot exceed 2 MiB.
        val line = "[$timestamp] [$tag] ${message.take(16 * 1024)}\n".toByteArray(Charsets.UTF_8)
        if (file.length() + line.size > 2 * 1024 * 1024) {
            val old = file.readBytes()
            var from = (old.size - 1024 * 1024).coerceAtLeast(0)
            while (from < old.size && old[from] != 10.toByte()) from++
            file.writeBytes(old.copyOfRange(minOf(from + 1, old.size), old.size))
            generation++
        }
        file.appendBytes(line)
    }
    @Synchronized fun snapshot(): String = if (file.exists()) file.readText() else ""
    @Synchronized fun capture(): Snapshot = Snapshot(snapshot(), generation)
}

/** All native and JS diagnostics share this file, including cache hits and background playback. */
internal object DebugLogger {
    private val exporter = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "GDStreamDebugExport").apply { isDaemon = true }
    }
    private var store: DebugLogStore? = null
    private var export: ((String, Boolean) -> Unit)? = null
    private var exportedStore: DebugLogStore? = null
    private var exportedGeneration = -1L
    private var exportedChars = 0
    private var scheduled = false
    private var revision = 0L

    @Synchronized fun attach(logStore: DebugLogStore, mirror: (String, Boolean) -> Unit = { _, _ -> }) {
        store = logStore
        export = mirror
        exportedStore = null
        exportedChars = 0
    }

    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        // The private log survives a denied/unavailable Downloads provider. MediaStore I/O never
        // runs on a proxy worker or while it owns an upstream semaphore permit.
        val directory = context.filesDir ?: return // Also permits plain JVM bridge mocks.
        var target: Uri? = null
        attach(DebugLogStore(File(directory, "gdstream_debug.log.txt"))) { text, append ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                try {
                    if (target == null) {
                        resolver.query(collection, arrayOf(BaseColumns._ID),
                            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                            arrayOf("gdstream_debug.log%", "%GDStream%"), null)?.use { cursor ->
                            if (cursor.moveToFirst()) target = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        }
                        if (target == null) target = resolver.insert(collection, ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "gdstream_debug.log.txt")
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/GDStream")
                        })
                    }
                    val uri = target ?: throw java.io.IOException("Downloads provider returned no log URI")
                    val output = resolver.openOutputStream(uri, if (append) "wa" else "wt") ?: throw java.io.IOException("Cannot open exported debug log")
                    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                } catch (error: Exception) { target = null; throw error }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GDStream")
                directory.mkdirs()
                val file = File(directory, "gdstream_debug.log.txt")
                if (append) file.appendText(text) else file.writeText(text)
            }
        }
    }

    @Synchronized fun log(tag: String, message: String) {
        if (tag == "ISO_UPSTREAM_PROGRESS") return // Ignore old bridge/plugin per-read progress calls too.
        val active = store ?: return
        try {
            active.append(tag, message)
            revision++
            if (!scheduled) scheduleExport()
        } catch (error: Exception) {
            android.util.Log.e("GDStreamDebugLogger", "Cannot persist $tag", error)
        }
    }

    private fun scheduleExport(delayMs: Long = 500) {
        scheduled = true
        exporter.schedule({ flushExport() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun flushExport() {
        val snapshot = synchronized(this) { Triple(store, export, revision) }
        var failed = false
        try {
            snapshot.first?.let { active ->
                val data = active.capture()
                val offset = synchronized(this) {
                    if (exportedStore === active && exportedGeneration == data.generation && exportedChars <= data.text.length)
                        exportedChars else 0
                }
                // Append only new lines. Rewriting a multi-MB log every progress update can itself
                // stall storage during playback; truncate only on initial export or log rotation.
                snapshot.second?.invoke(data.text.substring(offset), offset > 0)
                synchronized(this) {
                    if (store === active) {
                        exportedStore = active
                        exportedGeneration = data.generation
                        exportedChars = data.text.length
                    }
                }
            }
        } catch (error: Exception) {
            failed = true
            synchronized(this) { exportedStore = null }
            try { snapshot.first?.append("DEBUG_EXPORT_ERR", isoFailureDetails(error)) } catch (_: Exception) { }
        } finally {
            synchronized(this) {
                scheduled = false
                if (failed) scheduleExport(30_000) else if (revision != snapshot.third) scheduleExport()
            }
        }
    }
}
