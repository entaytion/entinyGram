package desu.inugram.helpers.theme

import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity

object ThemeSwitchHelper {
    private const val WATCHDOG_DELAY_MS = 1500L

    @JvmStatic
    fun scheduleRecovery(activity: LaunchActivity) {
        AndroidUtilities.runOnUIThread({
            if (!isSwitchInProgress(activity)) return@runOnUIThread
            clearOverlay(activity)
            MonetHelper.refreshMonetThemeIfChanged()
        }, WATCHDOG_DELAY_MS)
    }

    @JvmStatic
    fun recoverStuckOverlay(activity: LaunchActivity) {
        if (!isSwitchInProgress(activity)) return
        clearOverlay(activity)
    }

    private fun isSwitchInProgress(activity: LaunchActivity): Boolean =
        DialogsActivity.switchingTheme || activity.themeSwitchImageView?.visibility == View.VISIBLE

    private fun clearOverlay(activity: LaunchActivity) {
        activity.themeSwitchImageView?.apply {
            setImageDrawable(null)
            visibility = View.GONE
        }
        activity.themeSwitchSunView?.visibility = View.GONE
        DialogsActivity.switchingTheme = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.themeAccentListUpdated)
    }
}
