package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.feed.FeedHelper
import desu.inugram.helpers.menu.DrawerMenuConfig
import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.menu.MenuOrderEntry
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DrawerMenuOrderActivity : MenuOrderActivity<DrawerMenuConfig.Item>() {
    override val config get() = InuConfig.DRAWER_MENU_ITEMS
    override val infoStringRes = R.string.InuDrawerMenuOrderInfo
    override val headerStringRes = R.string.InuDrawerMenuItems
    override val resetStringRes = R.string.InuDrawerMenuReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDrawerMenuOrder)

    // dividers are fixed slots: only the ones the user added show up, the rest stay hidden
    override fun rowVisible(entry: MenuOrderEntry<DrawerMenuConfig.Item>): Boolean =
        (entry.item != DrawerMenuConfig.Item.FEED || FeedHelper.isEnabled()) &&
            (!entry.item.isDivider || entry.enabled)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        fillMainSection(items, adapter)
        if (entries.any { it.item.isDivider && !it.enabled }) {
            items.add(UItem.asButton(BUTTON_ADD_DIVIDER, R.drawable.msg_add, LocaleController.getString(R.string.InuDrawerMenuAddDivider)))
        }
        fillResetSection(items, adapter)
    }

    // turning a divider off removes it from the list right away; "Add divider" brings it back
    override fun onRowToggle(entry: MenuOrderEntry<DrawerMenuConfig.Item>, row: MenuOrderRow?) {
        super.onRowToggle(entry, row)
        if (entry.item.isDivider) listView.adapter.update(true)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD_DIVIDER) {
            addDivider()
        } else {
            super.onClick(item, view, position, x, y)
        }
    }

    // appends the next free divider slot after the last main row, like the old standalone screen
    private fun addDivider() {
        val slot = entries.firstOrNull { it.item.isDivider && !it.enabled } ?: return
        val rest = entries.filter { it !== slot }.toMutableList()
        val lastMain = rest.indexOfLast { !it.bottom }
        rest.add(lastMain + 1, slot.copy(enabled = true))
        entries = rest
        config.value = entries
        listView.adapter.update(true)
    }

    override fun onFragmentDestroy() {
        // entiny: drawer adapter only rebuilds its rows on reloadInterface, so apply edits when leaving
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
        super.onFragmentDestroy()
    }

    companion object {
        private val BUTTON_ADD_DIVIDER = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "drawer-menu-order",
            titleRes = R.string.InuDrawerMenuOrder,
            iconRes = R.drawable.inu_tabler_list,
            factory = ::DrawerMenuOrderActivity,
            entries = listOf(
                // entiny: legacy slug of the former standalone drawer toggle
                SearchRegistry.Entry("drawer-recent-chats", R.string.InuRecentChats),
            ),
        )
    }
}
