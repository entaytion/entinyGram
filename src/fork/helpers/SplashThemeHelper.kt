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
        val theme = when {
            monet && LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.DEFAULT) -> when (mode) {
                MonetHelper.ThemeMode.LIGHT -> R.style.InuSplashMonetIconLight
                MonetHelper.ThemeMode.DARK -> R.style.InuSplashMonetIconDark
                MonetHelper.ThemeMode.AMOLED -> R.style.InuSplashMonetIconAmoled
                MonetHelper.ThemeMode.AUTO -> R.style.InuSplashMonetIconAuto
                MonetHelper.ThemeMode.AUTO_AMOLED -> R.style.InuSplashMonetIconAutoAmoled
                else -> R.style.InuSplashMonetIcon
            }
            monet && LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.INU_TRIBUTE) -> when (mode) {
                MonetHelper.ThemeMode.LIGHT -> R.style.InuSplashMonetIconLight
                MonetHelper.ThemeMode.DARK -> R.style.InuSplashMonetIconDark
                MonetHelper.ThemeMode.AMOLED -> R.style.InuSplashMonetIconAmoled
                MonetHelper.ThemeMode.AUTO -> R.style.InuSplashMonetIconAuto
                MonetHelper.ThemeMode.AUTO_AMOLED -> R.style.InuSplashMonetIconAutoAmoled
                else -> R.style.InuSplashMonetIcon
            }
            monet && LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.OLD) -> when (mode) {
                MonetHelper.ThemeMode.LIGHT -> R.style.InuSplashOldMonetIconLight
                MonetHelper.ThemeMode.DARK -> R.style.InuSplashOldMonetIconDark
                MonetHelper.ThemeMode.AMOLED -> R.style.InuSplashOldMonetIconAmoled
                MonetHelper.ThemeMode.AUTO -> R.style.InuSplashOldMonetIconAuto
                MonetHelper.ThemeMode.AUTO_AMOLED -> R.style.InuSplashOldMonetIconAutoAmoled
                else -> R.style.InuSplashOldMonetIcon
            }
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
