package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class MainTabsCustomizeActivity : MenuOrderActivity<MainTabsMenuConfig.Item>() {
    override val config get() = InuConfig.BOTTOM_TABS_ORDER
    override val infoStringRes = R.string.InuMainTabsCustomizeInfo
    override val headerStringRes = R.string.InuMainTabsItems
    override val resetStringRes = R.string.InuMainTabsCustomizeReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCustomizeBottomTabs)

    // the bottom tab bar is built once when MainTabsActivity is created, so reorder/show-hide needs a restart
    override fun onBackPressed(invoked: Boolean): Boolean {
        if (invoked) showRestartBulletin()
        return super.onBackPressed(invoked)
    }
}
