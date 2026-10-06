package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class ProfileInfoMenuOrderActivity : MenuOrderActivity<ProfileInfoMenuConfig.Item>() {
    override val config get() = InuConfig.PROFILE_INFO_ROWS
    override val infoStringRes = R.string.InuProfileInfoRowsOrderInfo
    override val headerStringRes = R.string.InuProfileInfoRowsItems
    override val resetStringRes = R.string.InuProfileInfoRowsReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuProfileInfoRowsOrder)
}
