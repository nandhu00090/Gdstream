package com.gdstream

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class SubtitleFileModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val REQUEST_CODE_SUBTITLE = 47123
        private const val REQUEST_CODE_AUDIO = 47124
    }

    private var pickPromise: Promise? = null
    private var activeRequestCode: Int = 0

    init {
        reactContext.addActivityEventListener(object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity,
                requestCode: Int,
                resultCode: Int,
                data: Intent?
            ) {
                this@SubtitleFileModule.onActivityResult(activity, requestCode, resultCode, data)
            }
        })
    }

    override fun getName(): String {
        return "SubtitleFileModule"
    }

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
        activeRequestCode = REQUEST_CODE_SUBTITLE
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "text/*",
                        "application/x-subrip",
                        "application/ttml+xml",
                        "application/octet-stream"
                    )
                )
            }
            activity.startActivityForResult(intent, REQUEST_CODE_SUBTITLE)
        } catch (e: Exception) {
            pickPromise = null
            activeRequestCode = 0
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun pickAudioFile(promise: Promise) {
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
        activeRequestCode = REQUEST_CODE_AUDIO
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "audio/*",
                        "video/*",
                        "application/ogg",
                        "application/octet-stream"
                    )
                )
            }
            activity.startActivityForResult(intent, REQUEST_CODE_AUDIO)
        } catch (e: Exception) {
            pickPromise = null
            activeRequestCode = 0
            promise.reject("ERROR", e.message)
        }
    }

    private fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (requestCode != REQUEST_CODE_SUBTITLE && requestCode != REQUEST_CODE_AUDIO) return
        val promise = pickPromise ?: return
        pickPromise = null
        activeRequestCode = 0

        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            promise.resolve(null)
            return
        }

        try {
            val uri: Uri = data.data!!
            val name = queryDisplayName(uri)

            if (requestCode == REQUEST_CODE_AUDIO) {
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                val map = Arguments.createMap()
                map.putString("name", name)
                map.putString("uri", uri.toString())
                promise.resolve(map)
                return
            }

            val sb = StringBuilder()
            activity.contentResolver.openInputStream(uri)?.use { ins ->
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

    private fun queryDisplayName(uri: Uri): String {
        return try {
            reactContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else "track"
            } ?: "track"
        } catch (e: Exception) {
            "track"
        }
    }
}
