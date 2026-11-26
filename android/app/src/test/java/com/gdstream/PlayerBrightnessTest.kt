package com.gdstream

import android.app.Activity
import android.os.Looper
import android.view.WindowManager
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PlayerBrightnessTest {
    @Test fun leavingPlaybackReturnsTheActivityWindowToSystemBrightness() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = mock(ReactApplicationContext::class.java)
        `when`(context.currentActivity).thenReturn(activity)
        val bridge = VideoPlayerModule(context)
        bridge.setSystemBrightness(0.25)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0.25f, activity.window.attributes.screenBrightness, 0f)
        bridge.resetWindowBrightness()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, activity.window.attributes.screenBrightness, 0f)
    }
}
