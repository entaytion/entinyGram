package desu.inugram.helpers.vibration

import android.content.Context
import desu.inugram.InuConfig

object VibrationHelper {
    private const val NOTIFICATION_VIBRATE_OFF = 2

    private val plumbing = hashSetOf(
        "android.app.AliasedContext",
        "android.app.ContextImpl",
        "android.app.SystemServiceRegistry",
        "android.content.ContextWrapper",
        "android.view.ContextThemeWrapper",
        "desu.inugram.helpers.vibration.",
        "org.telegram.messenger.ApplicationLoader",
        "org.telegram.messenger.voip.VoIPService",
        "org.telegram.ui.LaunchActivity",
    )

    @JvmStatic
    fun blocked(): Boolean = InuConfig.FORCE_NO_VIBRATION.value

    // entiny: a null Vibrator crashes OEM haptic hooks (miui HapticFeedbackUtil), so hide it only from our own callers
    @JvmStatic
    fun service(name: String?, service: Any?): Any? {
        if (name != Context.VIBRATOR_SERVICE || !blocked()) return service
        return if (requestedByApp()) null else service
    }

    private fun requestedByApp(): Boolean {
        for (frame in Thread.currentThread().stackTrace) {
            val name = frame.className
            if (plumbing.any { name.startsWith(it) } || name.startsWith("java.lang.") || name.startsWith("dalvik.") || name.startsWith("kotlin.")) {
                continue
            }
            return name.startsWith("org.telegram.") || name.startsWith("desu.inugram.")
        }
        return false
    }

    @JvmStatic
    fun notificationMode(mode: Int): Int = if (blocked()) NOTIFICATION_VIBRATE_OFF else mode
}
