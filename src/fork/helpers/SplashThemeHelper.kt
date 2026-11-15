package desu.inugram.helpers

import android.os.Build
import desu.inugram.helpers.theme.MonetHelper
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController

object SplashThemeHelper {
    @JvmStatic
    fun apply(activity: LaunchActivity?) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = MonetHelper.getThemeMode()
        val monet = mode != MonetHelper.ThemeMode.DISABLED
        val oldIcon = LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.OLD)
        val ownIcon = LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.DEFAULT) ||
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.INU_TRIBUTE)
        val theme = when {
            monet && (ownIcon || oldIcon) -> monetTheme(mode, oldIcon)
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.DEFAULT) -> R.style.Theme_TMessages_Start
            oldIcon -> R.style.Theme_TMessages_Start_Old
            LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.INU_TRIBUTE) -> R.style.Theme_TMessages_Start_Tribute
            else -> R.style.Theme_TMessages_Start_Plain
        }
        try {
            activity.splashScreen.setSplashScreenTheme(theme)
        } catch (_: Throwable) {
        }
    }

    private fun monetTheme(mode: MonetHelper.ThemeMode, old: Boolean): Int = when (mode) {
        MonetHelper.ThemeMode.LIGHT -> if (old) R.style.InuSplashOldMonetIconLight else R.style.InuSplashMonetIconLight
        MonetHelper.ThemeMode.DARK -> if (old) R.style.InuSplashOldMonetIconDark else R.style.InuSplashMonetIconDark
        MonetHelper.ThemeMode.AMOLED -> if (old) R.style.InuSplashOldMonetIconAmoled else R.style.InuSplashMonetIconAmoled
        MonetHelper.ThemeMode.AUTO -> if (old) R.style.InuSplashOldMonetIconAuto else R.style.InuSplashMonetIconAuto
        MonetHelper.ThemeMode.AUTO_AMOLED -> if (old) R.style.InuSplashOldMonetIconAutoAmoled else R.style.InuSplashMonetIconAutoAmoled
        else -> if (old) R.style.InuSplashOldMonetIcon else R.style.InuSplashMonetIcon
    }
}
