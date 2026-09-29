package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.feed.FeedHelper
import desu.inugram.helpers.menu.DialogsMenuConfig
import desu.inugram.helpers.menu.MenuOrderEntry
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class DialogsMenuOrderActivity : MenuOrderActivity<DialogsMenuConfig.Item>() {
    override val config get() = InuConfig.DIALOGS_MENU_ITEMS
    override val infoStringRes = R.string.InuDialogsMenuOrderInfo
    override val headerStringRes = R.string.InuDialogsMenuItems
    override val resetStringRes = R.string.InuDialogsMenuReset

    override fun rowVisible(entry: MenuOrderEntry<DialogsMenuConfig.Item>): Boolean =
        entry.item != DialogsMenuConfig.Item.FEED || FeedHelper.isEnabled()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDialogsMenuOrder)
}
