package desu.inugram.helpers.profile

import android.os.Bundle
import desu.inugram.InuConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity

// entiny: 11.14.1 settings screen, ported from the retro-views plugin (@sophietelle)
object RetroSettingsHelper {

    const val ARG = "retro_settings"

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.RETRO_SETTINGS.value

    @JvmStatic
    fun isRetroSettings(fragment: BaseFragment?): Boolean {
        if (fragment == null) return false
        val args = fragment.arguments ?: return false
        return args.getBoolean(ARG, false)
    }

    @JvmStatic
    fun createSettingsTab(args: Bundle, account: Int): BaseFragment =
        if (isEnabled()) profile(args, account) else SettingsActivity(args)

    @JvmStatic
    fun replaceSettings(params: INavigationLayout.NavigationParams) {
        if (!isEnabled()) return
        val fragment = params.fragment
        if (fragment !is SettingsActivity) return
        params.fragment = profile(fragment.arguments ?: Bundle(), fragment.currentAccount)
    }

    private fun profile(args: Bundle, account: Int): ProfileActivity {
        args.putLong("user_id", UserConfig.getInstance(account).clientUserId)
        args.putBoolean(ARG, true)
        return ProfileActivity(args)
    }
}
