package desu.inugram.helpers.menu

import desu.inugram.InuConfig

object DialogsMenuHelper {
    @JvmStatic
    fun isEnabled(item: DialogsMenuConfig.Item): Boolean =
        InuConfig.DIALOGS_MENU_ITEMS.value.firstOrNull { it.item == item }?.enabled ?: true
}
