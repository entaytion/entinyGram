package desu.inugram.helpers

import android.os.Build
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.ui.appicons.AppIcons
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController.LauncherIcon

object SplashThemeHelper {
    @JvmStatic
    fun apply(activity: LaunchActivity?) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = MonetHelper.getThemeMode()
        val icon = AppIcons.current()
        // entiny: monet recolors only our own dish icons; every other launcher icon keeps its own splash
        val theme = if (mode != MonetHelper.ThemeMode.DISABLED && (icon == LauncherIcon.DEFAULT || icon == LauncherIcon.OLD)) {
            monetTheme(mode, icon == LauncherIcon.OLD)
        } else {
            iconTheme(icon)
        }
        try {
            activity.splashScreen.setSplashScreenTheme(theme)
        } catch (_: Throwable) {
        }
    }

    private fun iconTheme(icon: LauncherIcon): Int = when (icon) {
        LauncherIcon.DEFAULT -> R.style.Theme_TMessages_Start
        LauncherIcon.OLD -> R.style.Theme_TMessages_Start_Old
        LauncherIcon.INU_TRIBUTE -> R.style.Theme_TMessages_Start_Tribute
        LauncherIcon.STOCK, LauncherIcon.DISGUISE -> R.style.InuSplashStock
        LauncherIcon.VINTAGE -> R.style.InuSplashVintage
        LauncherIcon.AQUA -> R.style.InuSplashAqua
        LauncherIcon.PREMIUM -> R.style.InuSplashPremium
        LauncherIcon.TURBO -> R.style.InuSplashTurbo
        LauncherIcon.NOX -> R.style.InuSplashNox
            LauncherIcon.RADAR -> R.style.InuSplashConceptRadar
            LauncherIcon.CONSTELLATION -> R.style.InuSplashConceptConstellation
            LauncherIcon.ORBIT -> R.style.InuSplashConceptOrbit
            LauncherIcon.AVATAR -> R.style.InuSplashConceptAvatar
            LauncherIcon.NOTHING -> R.style.InuSplashConceptNothing
            LauncherIcon.MONO -> R.style.InuSplashConceptMono
            LauncherIcon.SUNSET -> R.style.InuSplashConceptSunset
            LauncherIcon.AMETHYST -> R.style.InuSplashConceptAmethyst
            LauncherIcon.AURORA -> R.style.InuSplashConceptAurora
            LauncherIcon.GOOGLE26 -> R.style.InuSplashConceptGoogle26
            LauncherIcon.LUNAR -> R.style.InuSplashConceptLunar
            LauncherIcon.MATHBOOK -> R.style.InuSplashConceptMathbook
            LauncherIcon.JUNGLE -> R.style.InuSplashConceptJungle
            LauncherIcon.GOLD -> R.style.InuSplashConceptGold
            LauncherIcon.DISH -> R.style.InuSplashConceptDish
            LauncherIcon.BLUSH -> R.style.InuSplashConceptBlush
            LauncherIcon.NOX_SKY -> R.style.InuSplashConceptNoxSky
            LauncherIcon.INVERSE -> R.style.InuSplashConceptInverse
            LauncherIcon.OSU -> R.style.InuSplashConceptOsu
            LauncherIcon.AMONG_US -> R.style.InuSplashConceptAmongUs
            LauncherIcon.CRIMSON -> R.style.InuSplashConceptCrimson
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
