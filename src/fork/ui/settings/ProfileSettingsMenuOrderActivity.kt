package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class ProfileSettingsMenuOrderActivity : MenuOrderActivity<ProfileMenuConfig.Item>() {
    override val config get() = InuConfig.PROFILE_SETTINGS_ROWS
    override val infoStringRes = R.string.InuProfileSettingsRowsOrderInfo
    override val headerStringRes = R.string.InuProfileSettingsRowsItems
    override val resetStringRes = R.string.InuProfileSettingsRowsReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuProfileSettingsRowsOrder)
}
