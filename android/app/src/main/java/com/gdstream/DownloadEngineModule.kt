package com.gdstream

import android.media.MediaScannerConnection
import android.os.Environment
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DownloadEngineModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    data class ChunkRange(
        val index: Int,
        val start: Long,
        @Volatile var current: Long,
        val end: Long
    )

    class DownloadTask(
        val id: String,
        val fileName: String,
        val title: String,
        val poster: String,
        val url: String,
        val filePath: String,
        @Volatile var totalBytes: Long,
        val downloadedBytes: AtomicLong,
        @Volatile var speedBytesPerSec: Long,
        @Volatile var status: String,
        @Volatile var threadsCount: Int,
        val chunks: MutableList<ChunkRange>
    ) {
        val stopRequested = AtomicBoolean(false)
        val futures = mutableListOf<Future<*>>()
    }

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val workerPool = Executors.newCachedThreadPool()
    private val stateFile: File
        get() = File(reactContext.filesDir, "gdstream_downloads_v1.json")

    init {
        loadSavedTasks()
    }

    override fun getName(): String = "DownloadEngine"

    private fun getPublicDownloadDir(): File {
        val pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val gdDir = File(pubDownload, "GDStream")
        if (gdDir.exists() || gdDir.mkdirs()) {
            return gdDir
        }
        val pubMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val movieDir = File(pubMovies, "GDStream")
        if (movieDir.exists() || movieDir.mkdirs()) {
            return movieDir
        }
        val fallback = File(reactContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "GDStream")
        fallback.mkdirs()
        return fallback
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/*"
        }
    }

    private fun scanMediaFile(filePath: String, fileName: String) {
        try {
            MediaScannerConnection.scanFile(
                reactContext,
                arrayOf(filePath),
                arrayOf(guessMimeType(fileName)),
                null
            )
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun persistTasks() {
        try {
            val arr = JSONArray()
            for (task in tasks.values) {
                val obj = JSONObject().apply {
                    put("id", task.id)
                    put("fileName", task.fileName)
                    put("title", task.title)
                    put("poster", task.poster)
                    put("url", task.url)
                    put("filePath", task.filePath)
                    put("totalBytes", task.totalBytes)
                    put("downloadedBytes", task.downloadedBytes.get())
                    put(
                        "status",
                        if (task.status == "downloading") "paused" else task.status
                    )
                    put("threadsCount", task.threadsCount)
                    val chunkArr = JSONArray()
                    synchronized(task.chunks) {
                        for (c in task.chunks) {
                            chunkArr.put(
                                JSONObject().apply {
                                    put("index", c.index)
                                    put("start", c.start)
                                    put("current", c.current)
                                    put("end", c.end)
                                }
                            )
                        }
                    }
                    put("chunks", chunkArr)
                }
                arr.put(obj)
            }
            stateFile.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun loadSavedTasks() {
        try {
            if (!stateFile.exists()) return
            val arr = JSONArray(stateFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val filePath = obj.optString("filePath")
                if (id.isEmpty()) continue

                val chunks = mutableListOf<ChunkRange>()
                val chunkArr = obj.optJSONArray("chunks")
                if (chunkArr != null) {
                    for (j in 0 until chunkArr.length()) {
                        val cObj = chunkArr.getJSONObject(j)
                        chunks.add(
                            ChunkRange(
                                cObj.optInt("index", j),
                                cObj.optLong("start", 0L),
                                cObj.optLong("current", 0L),
                                cObj.optLong("end", 0L)
                            )
                        )
                    }
                }

                var status = obj.optString("status", "paused")
                if (status == "completed" && !File(filePath).exists()) {
                    continue
                }
                if (status == "downloading") status = "paused"

                val task = DownloadTask(
                    id = id,
                    fileName = obj.optString("fileName", id),
                    title = obj.optString("title", id),
                    poster = obj.optString("poster", ""),
                    url = obj.optString("url", ""),
                    filePath = filePath,
                    totalBytes = obj.optLong("totalBytes", 0L),
                    downloadedBytes = AtomicLong(obj.optLong("downloadedBytes", 0L)),
                    speedBytesPerSec = 0L,
                    status = status,
                    threadsCount = obj.optInt("threadsCount", 8),
                    chunks = chunks
                )
                tasks[id] = task
            }
        } catch (_: Exception) {}
    }

    private fun taskToMap(task: DownloadTask): WritableMap {
        val total = task.totalBytes
        val done = task.downloadedBytes.get().coerceAtMost(if (total > 0) total else Long.MAX_VALUE)
        val progress = if (total > 0) (done.toDouble() / total.toDouble()) * 100.0 else 0.0
        return Arguments.createMap().apply {
            putString("id", task.id)
            putString("fileName", task.fileName)
            putString("title", task.title)
            putString("poster", task.poster)
            putString("url", task.url)
            putString("filePath", task.filePath)
            putDouble("totalBytes", total.toDouble())
            putDouble("downloadedBytes", done.toDouble())
            putDouble("speedBytesPerSec", task.speedBytesPerSec.toDouble())
            putDouble("progress", progress.coerceIn(0.0, 100.0))
            putString("status", task.status)
            putInt("threadsCount", task.threadsCount)
        }
    }

    private fun buildAllTasksArray(): WritableArray {
        val array = Arguments.createArray()
        for (task in tasks.values) {
            array.pushMap(taskToMap(task))
        }
        return array
    }

    private fun emitUpdate() {
        try {
            if (reactContext.hasActiveReactInstance()) {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onDownloadUpdate", buildAllTasksArray())
            }
        } catch (_: Exception) {}
    }

    @ReactMethod
    fun getDownloads(promise: Promise) {
        promise.resolve(buildAllTasksArray())
    }

    @ReactMethod
    fun startDownload(
        url: String,
        fileName: String,
        title: String,
        poster: String?,
        promise: Promise
    ) {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val existing = tasks[safeName]
        if (existing != null && existing.status == "downloading") {
            promise.resolve(taskToMap(existing))
            return
        }

        val targetDir = getPublicDownloadDir()
        val targetFile = File(targetDir, safeName)

        val task = existing?.apply {
            this.status = "downloading"
            this.stopRequested.set(false)
            this.speedBytesPerSec = 0L
        } ?: DownloadTask(
            id = safeName,
            fileName = safeName,
            title = title.ifEmpty { safeName },
            poster = poster ?: "",
            url = url,
            filePath = targetFile.absolutePath,
            totalBytes = 0L,
            downloadedBytes = AtomicLong(0L),
            speedBytesPerSec = 0L,
            status = "downloading",
            threadsCount = 8,
            chunks = mutableListOf()
        )

        tasks[safeName] = task
        emitUpdate()
        promise.resolve(taskToMap(task))

        workerPool.submit {
            executeSegmentedDownload(task)
        }
    }

    @ReactMethod
    fun pauseDownload(id: String, promise: Promise) {
        val task = tasks[id]
        if (task != null) {
            task.stopRequested.set(true)
            task.status = "paused"
            task.speedBytesPerSec = 0L
            for (f in task.futures) {
                f.cancel(true)
            }
            persistTasks()
            emitUpdate()
            promise.resolve(taskToMap(task))
        } else {
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun resumeDownload(id: String, promise: Promise) {
        val task = tasks[id]
        if (task == null) {
            promise.reject("NOT_FOUND", "Download task not found")
            return
        }
        if (task.status == "downloading") {
            promise.resolve(taskToMap(task))
            return
        }
        task.stopRequested.set(false)
        task.status = "downloading"
        task.speedBytesPerSec = 0L
        emitUpdate()
        promise.resolve(taskToMap(task))

        workerPool.submit {
            executeSegmentedDownload(task)
        }
    }

    @ReactMethod
    fun deleteDownload(id: String, promise: Promise) {
        val task = tasks.remove(id)
        if (task != null) {
            task.stopRequested.set(true)
            for (f in task.futures) {
                f.cancel(true)
            }
            try {
                val f = File(task.filePath)
                if (f.exists()) f.delete()
                scanMediaFile(task.filePath, task.fileName)
            } catch (_: Exception) {}
            persistTasks()
            emitUpdate()
        }
        promise.resolve(true)
    }

    private fun openConnection(urlStr: String, rangeHeader: String?): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; GDStream/2.0)")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeHeader != null) {
                    setRequestProperty("Range", rangeHeader)
                }
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) break
                currentUrl = URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                return conn
            }
        }
        throw Exception("Too many redirects")
    }

    private fun probeRemoteFile(urlStr: String): Pair<Long, Boolean> {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(urlStr, "bytes=0-0")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = conn.getHeaderField("Content-Range")
                if (!contentRange.isNullOrEmpty() && contentRange.contains("/")) {
                    val totalStr = contentRange.substringAfter("/").trim()
                    val total = totalStr.toLongOrNull() ?: -1L
                    if (total > 0) return Pair(total, true)
                }
            }
            val len = conn.contentLengthLong
            return Pair(if (len > 0) len else -1L, code == HttpURLConnection.HTTP_PARTIAL)
        } finally {
            conn?.disconnect()
        }
    }

    private fun executeSegmentedDownload(task: DownloadTask) {
        try {
            val targetFile = File(task.filePath)
            targetFile.parentFile?.mkdirs()

            if (task.totalBytes <= 0L || task.chunks.isEmpty()) {
                val (probedSize, supportsRange) = probeRemoteFile(task.url)
                task.totalBytes = probedSize

                synchronized(task.chunks) {
                    task.chunks.clear()
                    if (supportsRange && probedSize > 4 * 1024 * 1024L) {
                        val numParts = 8
                        task.threadsCount = numParts
                        RandomAccessFile(targetFile, "rw").use { raf ->
                            raf.setLength(probedSize)
                        }
                        val partSize = probedSize / numParts
                        for (i in 0 until numParts) {
                            val start = i * partSize
                            val end = if (i == numParts - 1) probedSize - 1 else (start + partSize - 1)
                            task.chunks.add(ChunkRange(i, start, start, end))
                        }
                    } else {
                        task.threadsCount = 1
                        val end = if (probedSize > 0) probedSize - 1 else Long.MAX_VALUE
                        task.chunks.add(ChunkRange(0, 0L, 0L, end))
                    }
                }
                task.downloadedBytes.set(0L)
                persistTasks()
                emitUpdate()
            } else {
                var sum = 0L
                synchronized(task.chunks) {
                    for (c in task.chunks) {
                        sum += (c.current - c.start).coerceAtLeast(0L)
                    }
                }
                task.downloadedBytes.set(sum)
            }

            task.futures.clear()

            val speedThread = workerPool.submit {
                var lastBytes = task.downloadedBytes.get()
                var lastTime = System.currentTimeMillis()
                var tick = 0
                while (!task.stopRequested.get() && task.status == "downloading") {
                    try {
                        Thread.sleep(450)
                    } catch (_: InterruptedException) {
                        break
                    }
                    val now = System.currentTimeMillis()
                    val curBytes = task.downloadedBytes.get()
                    val elapsedMs = (now - lastTime).coerceAtLeast(1L)
                    val instantSpeed = ((curBytes - lastBytes) * 1000L) / elapsedMs
                    task.speedBytesPerSec =
                        if (task.speedBytesPerSec == 0L) instantSpeed
                        else ((task.speedBytesPerSec * 3 + instantSpeed * 2) / 5)
                    lastBytes = curBytes
                    lastTime = now
                    emitUpdate()
                    tick++
                    if (tick % 5 == 0) persistTasks()
                }
            }

            val chunkFutures = mutableListOf<Future<Boolean>>()
            for (chunk in task.chunks) {
                if (chunk.current > chunk.end) continue
                val f = workerPool.submit<Boolean> {
                    downloadSingleChunk(task, chunk, task.threadsCount > 1)
                }
                chunkFutures.add(f)
                task.futures.add(f)
            }

            var allSucceeded = true
            for (f in chunkFutures) {
                try {
                    val ok = f.get()
                    if (!ok) allSucceeded = false
                } catch (_: Exception) {
                    allSucceeded = false
                }
            }

            task.stopRequested.set(true)
            speedThread.cancel(true)
            task.speedBytesPerSec = 0L

            if (task.status == "paused") {
                persistTasks()
                emitUpdate()
                return
            }

            if (allSucceeded) {
                if (task.totalBytes <= 0L) {
                    task.totalBytes = targetFile.length()
                }
                task.downloadedBytes.set(task.totalBytes)
                task.status = "completed"
                persistTasks()
                scanMediaFile(task.filePath, task.fileName)
                emitUpdate()
            } else if (task.status != "paused") {
                task.status = "error"
                persistTasks()
                emitUpdate()
            }
        } catch (e: Exception) {
            if (task.status != "paused") {
                task.status = "error"
                task.speedBytesPerSec = 0L
                persistTasks()
                emitUpdate()
            }
        }
    }

    private fun downloadSingleChunk(
        task: DownloadTask,
        chunk: ChunkRange,
        useRange: Boolean
    ): Boolean {
        var attempts = 0
        while (attempts < 4 && !task.stopRequested.get()) {
            var conn: HttpURLConnection? = null
            try {
                if (chunk.current > chunk.end) return true
                val rangeHeader = if (useRange) "bytes=${chunk.current}-${chunk.end}" else null
                conn = openConnection(task.url, rangeHeader)
                val code = conn.responseCode
                if (code !in 200..299) {
                    attempts++
                    Thread.sleep(600L * attempts)
                    continue
                }

                conn.inputStream.use { input ->
                    RandomAccessFile(File(task.filePath), "rw").use { raf ->
                        raf.seek(chunk.current)
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (!task.stopRequested.get()) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            val remaining = if (chunk.end != Long.MAX_VALUE) {
                                (chunk.end - chunk.current + 1).coerceAtLeast(0L)
                            } else {
                                bytesRead.toLong()
                            }
                            val toWrite = minOf(bytesRead.toLong(), remaining).toInt()
                            if (toWrite <= 0) break
                            raf.write(buffer, 0, toWrite)
                            chunk.current += toWrite
                            task.downloadedBytes.addAndGet(toWrite.toLong())
                            if (chunk.end != Long.MAX_VALUE && chunk.current > chunk.end) break
                        }
                    }
                }

                if (task.stopRequested.get()) return false
                if (chunk.end == Long.MAX_VALUE || chunk.current > chunk.end) {
                    return true
                }
            } catch (_: Exception) {
                if (task.stopRequested.get()) return false
                attempts++
                try {
                    Thread.sleep(600L * attempts)
                } catch (_: InterruptedException) {
                    return false
                }
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }
}
