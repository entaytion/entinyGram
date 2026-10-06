package desu.inugram.helpers.vibration

import android.content.Context
import desu.inugram.InuConfig

object VibrationHelper {
    private const val NOTIFICATION_VIBRATE_OFF = 2

    @JvmStatic
    fun blocked(): Boolean = InuConfig.FORCE_NO_VIBRATION.value

    // entiny: stock vibrates only when it gets a Vibrator, and null-checks it almost everywhere
    @JvmStatic
    fun service(name: String?, service: Any?): Any? =
        if (name == Context.VIBRATOR_SERVICE && blocked()) null else service

    @JvmStatic
    fun notificationMode(mode: Int): Int = if (blocked()) NOTIFICATION_VIBRATE_OFF else mode
}
