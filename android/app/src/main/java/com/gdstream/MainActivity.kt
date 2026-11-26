package com.gdstream

import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

internal fun Window.resetPlayerBrightness() {
  attributes = attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
}

class MainActivity : ReactActivity() {

  override fun onStop() {
    window.resetPlayerBrightness()
    super.onStop()
  }

  override fun getMainComponentName(): String = "Gdstream"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
      if (event.action == KeyEvent.ACTION_DOWN) {
          VideoPlayerModule.sendKeyEvent(event.keyCode, event.action)
      }
      return super.dispatchKeyEvent(event)
  }
}
