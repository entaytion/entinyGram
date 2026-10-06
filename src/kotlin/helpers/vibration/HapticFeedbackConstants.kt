package desu.inugram.helpers.vibration

import desu.inugram.InuConfig
import android.view.HapticFeedbackConstants as Android

// entiny: stands in for android.view.HapticFeedbackConstants so every performHapticFeedback call can be silenced from one switch
object HapticFeedbackConstants {
    private const val NONE = -1

    @JvmField var KEYBOARD_TAP = Android.KEYBOARD_TAP
    @JvmField var KEYBOARD_PRESS = Android.KEYBOARD_PRESS
    @JvmField var KEYBOARD_RELEASE = Android.KEYBOARD_RELEASE
    @JvmField var LONG_PRESS = Android.LONG_PRESS
    @JvmField var TEXT_HANDLE_MOVE = Android.TEXT_HANDLE_MOVE
    @JvmField var CLOCK_TICK = Android.CLOCK_TICK
    @JvmField var VIRTUAL_KEY = Android.VIRTUAL_KEY

    @JvmField val FLAG_IGNORE_GLOBAL_SETTING = Android.FLAG_IGNORE_GLOBAL_SETTING
    @JvmField val FLAG_IGNORE_VIEW_SETTING = Android.FLAG_IGNORE_VIEW_SETTING

    @JvmStatic
    fun sync() {
        val off = InuConfig.FORCE_NO_VIBRATION.value
        KEYBOARD_TAP = if (off) NONE else Android.KEYBOARD_TAP
        KEYBOARD_PRESS = if (off) NONE else Android.KEYBOARD_PRESS
        KEYBOARD_RELEASE = if (off) NONE else Android.KEYBOARD_RELEASE
        LONG_PRESS = if (off) NONE else Android.LONG_PRESS
        TEXT_HANDLE_MOVE = if (off) NONE else Android.TEXT_HANDLE_MOVE
        CLOCK_TICK = if (off) NONE else Android.CLOCK_TICK
        VIRTUAL_KEY = if (off) NONE else Android.VIRTUAL_KEY
    }
}
