package desu.inugram.helpers

import android.os.Build
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController

object SplashThemeHelper {
    @JvmStatic
    fun apply(activity: LaunchActivity?) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val theme = when {
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.DEFAULT) -> R.style.Theme_TMessages_Start
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.OLD) -> R.style.Theme_TMessages_Start_Old
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.INU_TRIBUTE) -> R.style.Theme_TMessages_Start_Tribute
            else -> R.style.Theme_TMessages_Start_Plain
        }
        try {
            activity.splashScreen.setSplashScreenTheme(theme)
        } catch (_: Throwable) {
        }
    }
}
