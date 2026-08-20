package com.gdstream

import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise

class VideoPlayerModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String {
        return "VideoPlayerManager"
    }

    @ReactMethod
    fun playVideo(url: String, promise: Promise) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            // OS-kitta ithu oru Video file nu theliva solrom
            intent.setDataAndType(Uri.parse(url), "video/*")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            // "Open With" (Chooser) Pop-up ah force panrom!
            val chooser = Intent.createChooser(intent, "Choose Player 🍿")
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            reactApplicationContext.startActivity(chooser)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", "No player found")
        }
    }
}
