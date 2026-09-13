package desu.inugram.helpers.menu

import desu.inugram.InuConfig

/**
 * Java-callable gate for [DialogsMenuConfig] entries — used to AND each existing precondition in
 * `DialogsActivity.showItemOptions()` / `DrawerHelper.addDialogsActivityOptions()` with the user's
 * enable/disable choice for that row. No caching (unlike `MainTabsHelper.enabledOrder()`): this
 * menu is opened by an infrequent user tap, not a per-frame hot path.
 */
object DialogsMenuHelper {
    @JvmStatic
    fun isEnabled(item: DialogsMenuConfig.Item): Boolean =
        InuConfig.DIALOGS_MENU_ITEMS.value.firstOrNull { it.item == item }?.enabled ?: true
}
