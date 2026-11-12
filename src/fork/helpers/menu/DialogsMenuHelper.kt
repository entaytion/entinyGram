package desu.inugram.helpers.menu

import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.MainTabsHelper

object DialogsMenuHelper {
    @JvmStatic
    fun isEnabled(item: DialogsMenuConfig.Item): Boolean {
        if (item == DialogsMenuConfig.Item.FEED && !desu.inugram.helpers.feed.FeedHelper.isEnabled()) return false
        if (item == DialogsMenuConfig.Item.DOWNLOADS && !InuConfig.DOWNLOAD_QUEUE.value) return false
        return InuConfig.DIALOGS_MENU_ITEMS.value.firstOrNull { it.item == item }?.enabled ?: true
    }

    @JvmStatic
    fun hasMainItems(walletAvailable: Boolean, proxyVisible: Boolean): Boolean {
        // #if PLUGINS
        if (!InuConfig.NAVIGATION_DRAWER.value && desu.inugram.helpers.plugins.ui.PluginActions.hasRows(desu.inugram.helpers.plugins.ui.PluginActions.KIND_GLOBAL)) return true
        // #endif
        val tabsHidden = MainTabsHelper.isHidden
        return DialogsMenuConfig.Item.values().any { item ->
            isEnabled(item) && when (item) {
                DialogsMenuConfig.Item.WALLET -> walletAvailable
                DialogsMenuConfig.Item.PROXY -> proxyVisible
                DialogsMenuConfig.Item.MY_PROFILE -> tabsHidden
                DialogsMenuConfig.Item.CONTACTS -> tabsHidden || !MainTabsHelper.isEnabled(MainTabsMenuConfig.Item.CONTACTS.index)
                DialogsMenuConfig.Item.SETTINGS -> tabsHidden || !MainTabsHelper.isEnabled(MainTabsMenuConfig.Item.SETTINGS.index)
                DialogsMenuConfig.Item.RECENT_CHATS -> desu.inugram.helpers.dialogs.RecentChatsHelper.isEnabled()
                DialogsMenuConfig.Item.PARANOIA -> !desu.inugram.helpers.security.ParanoiaHelper.isParanoia()
                else -> true
            }
        }
    }
}
