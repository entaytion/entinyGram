package desu.inugram.helpers.theme

import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity

object ThemeSwitchHelper {

    // entiny: circular-reveal crossfade never ends if frames stop mid-anim, leaving the
    // snapshot overlay visible; LaunchActivity then drops every future theme change
    @JvmStatic
    fun resetStuckOverlay(activity: LaunchActivity) {
        if (activity.themeSwitchImageView.visibility != View.VISIBLE) return
        AndroidUtilities.runOnUIThread({
            val overlay = activity.themeSwitchImageView
            if (overlay == null || overlay.visibility != View.VISIBLE) return@runOnUIThread
            overlay.setImageDrawable(null)
            overlay.visibility = View.GONE
            activity.themeSwitchSunView.visibility = View.GONE
            DialogsActivity.switchingTheme = false
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.themeAccentListUpdated)
        }, 600)
    }
}
