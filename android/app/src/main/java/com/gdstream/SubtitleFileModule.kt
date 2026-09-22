package com.gdstream

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class SubtitleFileModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    companion object {
        private const val REQUEST_CODE = 47123
    }

    private var pickPromise: Promise? = null

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName(): String {
        return "SubtitleFileModule"
    }

    /**
     * Opens the system document picker and resolves with
     * { name: String, content: String } for the chosen subtitle file,
     * or null if the user cancelled.
     */
    @ReactMethod
    fun pickSubtitleFile(promise: Promise) {
        if (pickPromise != null) {
            promise.reject("BUSY", "A file picker is already open")
            return
        }
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "No foreground activity")
            return
        }
        pickPromise = promise
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("text/*", "application/x-subrip", "application/ttml+xml", "application/octet-stream")
                )
            }
            activity.startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            pickPromise = null
            promise.reject("ERROR", e.message)
        }
    }

    override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
        val promise = pickPromise ?: return
        if (requestCode != REQUEST_CODE) return
        pickPromise = null

        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            promise.resolve(null)
            return
        }
        try {
            val uri: Uri = data.data!!
            val name = queryDisplayName(uri)
            val sb = StringBuilder()
            activity?.contentResolver?.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(8192)
                var len: Int
                while (ins.read(buf).also { len = it } > 0) {
                    sb.append(String(buf, 0, len, Charsets.UTF_8))
                }
            }
            val map = Arguments.createMap()
            map.putString("name", name)
            map.putString("content", sb.toString())
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        // not needed
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            reactContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else "subtitle"
            } ?: "subtitle"
        } catch (e: Exception) {
            "subtitle"
        }
    }
}
