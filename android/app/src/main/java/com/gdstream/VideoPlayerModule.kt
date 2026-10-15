package com.gdstream

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlin.math.roundToInt

class VideoPlayerModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    init {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: VideoPlayerModule? = null

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

    @ReactMethod
    fun playVideo(url: String, promise: Promise) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.parse(url), "video/*")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val chooser = Intent.createChooser(intent, "Choose Player 🍿")
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK

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
            val volRatio = curVol.toDouble() / maxVol.toDouble()

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
                putDouble("volume", volRatio.coerceIn(0.0, 1.0))
                putDouble("brightness", brightRatio.coerceIn(0.05, 1.0))
            }
            promise.resolve(map)
        } catch (e: Exception) {
            val map = Arguments.createMap().apply {
                putDouble("volume", 0.7)
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
            val audioManager = reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetVol = (level.coerceIn(0.0, 1.0) * maxVol).roundToInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        } catch (_: Exception) {}
    }
}
