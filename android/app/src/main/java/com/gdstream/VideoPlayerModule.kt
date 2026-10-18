package com.gdstream

import android.app.PictureInPictureParams
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.Display
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class VideoPlayerModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val watchHistoryFile: File
        get() = File(reactApplicationContext.filesDir, "gdstream_watch_history_v1.json")

    init {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: VideoPlayerModule? = null

        @JvmField
        @Volatile
        var preferredHdrMode: String = "dv"

        @Volatile
        private var currentAudioSessionId: Int = 0

        @Volatile
        private var currentVolumeScale: Double = 0.8 // 0.0 to 2.0 (0% to 200%)

        private var loudnessEnhancer: LoudnessEnhancer? = null
        private var enhancerSessionId: Int = -1

        @JvmStatic
        fun attachAudioSession(sessionId: Int) {
            if (sessionId <= 0) return
            currentAudioSessionId = sessionId
            applyLoudnessBoostInternal()
        }

        @Synchronized
        private fun applyLoudnessBoostInternal() {
            val boostRatio = (currentVolumeScale - 1.0).coerceIn(0.0, 1.0)
            // Up to +1500 millibels (+15 dB) hardware pre-amp gain at 200%
            val targetGainMb = (boostRatio * 1500.0).roundToInt()

            if (targetGainMb <= 0) {
                try {
                    loudnessEnhancer?.setTargetGain(0)
                    loudnessEnhancer?.enabled = false
                } catch (_: Exception) {}
                return
            }

            val targetSession = if (currentAudioSessionId > 0) currentAudioSessionId else 0
            try {
                if (loudnessEnhancer == null || enhancerSessionId != targetSession) {
                    try { loudnessEnhancer?.release() } catch (_: Exception) {}
                    loudnessEnhancer = LoudnessEnhancer(targetSession)
                    enhancerSessionId = targetSession
                }
                loudnessEnhancer?.setTargetGain(targetGainMb)
                loudnessEnhancer?.enabled = true
            } catch (_: Exception) {
                // Fallback to global mix session 0 if specific session rejected effect
                if (targetSession != 0) {
                    try {
                        loudnessEnhancer?.release()
                        loudnessEnhancer = LoudnessEnhancer(0).apply {
                            setTargetGain(targetGainMb)
                            enabled = true
                        }
                        enhancerSessionId = 0
                    } catch (_: Exception) {}
                }
            }
        }

        fun sendKeyEvent(keyCode: Int, action: Int) {
            val module = instance ?: return
            val context = module.reactApplicationContext
            if (context.hasActiveReactInstance()) {
                val params = Arguments.createMap().apply {
                    putInt("keyCode", keyCode)
                    putInt("action", action)
                }
                context
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onTVRemoteKey", params)
            }
        }
    }

    override fun getName(): String {
        return "VideoPlayerManager"
    }

    @Synchronized
    private fun readHistoryJson(): JSONObject {
        return try {
            if (watchHistoryFile.exists()) {
                JSONObject(watchHistoryFile.readText())
            } else {
                JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun writeHistoryJson(json: JSONObject) {
        try {
            watchHistoryFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    @ReactMethod
    fun saveWatchProgress(fileName: String, positionSec: Double, durationSec: Double, promise: Promise) {
        try {
            val root = readHistoryJson()
            if (positionSec <= 3.0 || (durationSec > 0.0 && durationSec - positionSec < 90.0)) {
                root.remove(fileName)
            } else {
                val existing = root.optJSONObject(fileName) ?: JSONObject()
                existing.put("time", positionSec)
                if (durationSec > 0.0) {
                    existing.put("dur", durationSec)
                }
                existing.put("updatedAt", System.currentTimeMillis())
                root.put(fileName, existing)
            }
            writeHistoryJson(root)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun removeWatchProgress(fileName: String, promise: Promise) {
        try {
            val root = readHistoryJson()
            root.remove(fileName)
            writeHistoryJson(root)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getAllWatchProgress(promise: Promise) {
        try {
            val root = readHistoryJson()
            val timesMap = Arguments.createMap()
            val dursMap = Arguments.createMap()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val item = root.optJSONObject(k) ?: continue
                val t = item.optDouble("time", 0.0)
                val d = item.optDouble("dur", 0.0)
                if (t > 3.0) {
                    timesMap.putDouble(k, t)
                }
                if (d > 0.0) {
                    dursMap.putDouble(k, d)
                }
            }
            val result = Arguments.createMap().apply {
                putMap("times", timesMap)
                putMap("durations", dursMap)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            val empty = Arguments.createMap().apply {
                putMap("times", Arguments.createMap())
                putMap("durations", Arguments.createMap())
            }
            promise.resolve(empty)
        }
    }

    @ReactMethod
    fun setPreferredHdrMode(mode: String, promise: Promise) {
        preferredHdrMode = mode.lowercase()
        promise.resolve(preferredHdrMode)
    }

    @ReactMethod
    fun enterPiP(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val activity = reactApplicationContext.currentActivity
                if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    val entered = activity.enterPictureInPictureMode(params)
                    promise.resolve(entered)
                } else {
                    promise.resolve(false)
                }
            } catch (e: Exception) {
                promise.resolve(false)
            }
        }
    }

    @ReactMethod
    fun getDeviceHdrCapabilities(promise: Promise) {
        try {
            var hasDvDecoder = false
            var hasHevcHdrDecoder = false
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals("video/dolby-vision", ignoreCase = true)) {
                        hasDvDecoder = true
                    }
                    if (type.equals("video/hevc", ignoreCase = true)) {
                        hasHevcHdrDecoder = true
                    }
                }
            }

            var displaySupportsDv = false
            var displaySupportsHdr10 = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val activity = reactApplicationContext.currentActivity
                @Suppress("DEPRECATION")
                val hdrTypes = activity?.windowManager?.defaultDisplay?.hdrCapabilities?.supportedHdrTypes
                if (hdrTypes != null) {
                    for (t in hdrTypes) {
                        if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) displaySupportsDv = true
                        if (t == Display.HdrCapabilities.HDR_TYPE_HDR10 || t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) {
                            displaySupportsHdr10 = true
                        }
                    }
                }
            }

            val map = Arguments.createMap().apply {
                putBoolean("dolbyVision", hasDvDecoder || displaySupportsDv)
                putBoolean("hdr10", hasHevcHdrDecoder || displaySupportsHdr10)
                putString("preferredMode", preferredHdrMode)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            val map = Arguments.createMap().apply {
                putBoolean("dolbyVision", true)
                putBoolean("hdr10", true)
                putString("preferredMode", preferredHdrMode)
            }
            promise.resolve(map)
        }
    }

    private fun resolvePlayableUri(urlOrPath: String): Uri {
        if (urlOrPath.startsWith("/") || urlOrPath.startsWith("file://")) {
            val cleanPath = urlOrPath.removePrefix("file://")
            val file = File(cleanPath)
            try {
                val projection = arrayOf(MediaStore.Video.Media._ID)
                val selection = "${MediaStore.Video.Media.DATA} = ?"
                val selectionArgs = arrayOf(file.absolutePath)
                reactApplicationContext.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        return ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                    }
                }
            } catch (_: Exception) {}

            val builder = StrictMode.VmPolicy.Builder()
            StrictMode.setVmPolicy(builder.build())
            return Uri.fromFile(file)
        }
        return Uri.parse(urlOrPath)
    }

    @ReactMethod
    fun playVideo(url: String, promise: Promise) {
        try {
            val playableUri = resolvePlayableUri(url)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(playableUri, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Choose Player 🍿").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            reactApplicationContext.startActivity(chooser)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", "No player found")
        }
    }

    @ReactMethod
    fun getSystemLevels(promise: Promise) {
        try {
            val audioManager = reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val hwRatio = (curVol.toDouble() / maxVol.toDouble()).coerceIn(0.0, 1.0)
            val effectiveVol = if (currentVolumeScale > 1.0 && hwRatio >= 0.9) currentVolumeScale else hwRatio
            currentVolumeScale = effectiveVol

            var brightRatio = 0.5
            val activity = reactApplicationContext.currentActivity
            val winBright = activity?.window?.attributes?.screenBrightness ?: -1f
            if (winBright in 0f..1f) {
                brightRatio = winBright.toDouble()
            } else {
                try {
                    val sysBright = Settings.System.getInt(
                        reactApplicationContext.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS
                    )
                    brightRatio = (sysBright / 255.0).coerceIn(0.05, 1.0)
                } catch (_: Exception) {}
            }

            val map = Arguments.createMap().apply {
                putDouble("volume", effectiveVol.coerceIn(0.0, 2.0))
                putDouble("brightness", brightRatio.coerceIn(0.05, 1.0))
            }
            promise.resolve(map)
        } catch (e: Exception) {
            val map = Arguments.createMap().apply {
                putDouble("volume", 0.8)
                putDouble("brightness", 0.6)
            }
            promise.resolve(map)
        }
    }

    @ReactMethod
    fun setSystemBrightness(level: Double) {
        val clamped = level.toFloat().coerceIn(0.02f, 1.0f)
        UiThreadUtil.runOnUiThread {
            try {
                val activity = reactApplicationContext.currentActivity ?: return@runOnUiThread
                val window = activity.window ?: return@runOnUiThread
                val params = window.attributes
                params.screenBrightness = clamped
                window.attributes = params
            } catch (_: Exception) {}
        }
    }

    @ReactMethod
    fun setSystemVolume(level: Double) {
        try {
            val clampedScale = level.coerceIn(0.0, 2.0)
            currentVolumeScale = clampedScale
            val audioManager = reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val hwScale = clampedScale.coerceIn(0.0, 1.0)
            val targetVol = (hwScale * maxVol).roundToInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            applyLoudnessBoostInternal()
        } catch (_: Exception) {}
    }
}
