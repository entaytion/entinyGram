package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.DialogsMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

/** Enable/disable and reorder rows shown in the chats screen's "☰" options menu. */
class DialogsMenuOrderActivity : MenuOrderActivity<DialogsMenuConfig.Item>() {
    override val config get() = InuConfig.DIALOGS_MENU_ITEMS
    override val infoStringRes = R.string.InuDialogsMenuOrderInfo
    override val headerStringRes = R.string.InuDialogsMenuItems
    override val resetStringRes = R.string.InuDialogsMenuReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDialogsMenuOrder)
}
