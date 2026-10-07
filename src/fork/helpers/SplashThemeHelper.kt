package desu.inugram.helpers

import android.os.Build
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController

object SplashThemeHelper {
    @JvmStatic
    fun apply(activity: LaunchActivity?) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val animated = LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.DEFAULT) ||
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.OLD)
        val theme = if (animated) R.style.Theme_TMessages_Start else R.style.Theme_TMessages_Start_Plain
        try {
            activity.splashScreen.setSplashScreenTheme(theme)
        } catch (_: Throwable) {
        }
    }
}
