package desu.inugram.helpers.menu

import desu.inugram.InuConfig

object DialogsMenuHelper {
    @JvmStatic
    fun isEnabled(item: DialogsMenuConfig.Item): Boolean {
        if (item == DialogsMenuConfig.Item.FEED && !desu.inugram.helpers.feed.FeedHelper.isEnabled()) return false
        return InuConfig.DIALOGS_MENU_ITEMS.value.firstOrNull { it.item == item }?.enabled ?: true
    }
}
