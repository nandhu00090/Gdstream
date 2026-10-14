package com.gdstream

import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule

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
}
