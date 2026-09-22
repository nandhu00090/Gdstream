package com.gdstream

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.TextureView
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.uimanager.NativeViewHierarchyManager
import com.facebook.react.uimanager.UIBlock
import com.facebook.react.uimanager.UIManagerModule

class VideoZoomModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String {
        return "VideoZoomModule"
    }

    /**
     * Recursively searches the view hierarchy for the TextureView that
     * renders the video surface. The SubtitleView is a sibling, so it is
     * never touched by the scale transform.
     */
    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    @ReactMethod
    fun setVideoZoom(viewTag: Int, isZoomed: Boolean) {
        val reactContext = reactApplicationContext
        val uiManager = reactContext.getNativeModule(UIManagerModule::class.java) ?: return

        uiManager.addUIBlock(object : UIBlock {
            override fun execute(nativeViewHierarchyManager: NativeViewHierarchyManager) {
                try {
                    val exoView = nativeViewHierarchyManager.resolveView(viewTag)
                    val textureView = findTextureView(exoView) ?: return
                    val scale = if (isZoomed) 1.35f else 1.0f
                    textureView.scaleX = scale
                    textureView.scaleY = scale
                    // Keep the scaled surface centered within the player bounds
                    textureView.pivotX = textureView.width / 2f
                    textureView.pivotY = textureView.height / 2f
                } catch (e: Exception) {
                    // View may not be attached yet — safe to ignore
                }
            }
        })
    }
}
