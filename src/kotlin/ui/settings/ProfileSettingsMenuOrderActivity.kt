package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

/** Reorder/hide the "Chat Settings / Privacy / Notifications / ..." rows on the stock
 * Telegram self-profile settings screen. See [desu.inugram.helpers.ProfileSettingsHelper]. */
class ProfileSettingsMenuOrderActivity : MenuOrderActivity<ProfileMenuConfig.Item>() {
    override val config get() = InuConfig.PROFILE_SETTINGS_ROWS
    override val infoStringRes = R.string.InuProfileSettingsRowsOrderInfo
    override val headerStringRes = R.string.InuProfileSettingsRowsItems
    override val resetStringRes = R.string.InuProfileSettingsRowsReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuProfileSettingsRowsOrder)
}
