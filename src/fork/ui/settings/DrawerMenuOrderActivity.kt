package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.feed.FeedHelper
import desu.inugram.helpers.menu.DrawerMenuConfig
import desu.inugram.helpers.menu.MenuOrderEntry
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DrawerMenuOrderActivity : SettingsPageActivity() {

    private var entries = InuConfig.DRAWER_MENU_ITEMS.value.toMutableList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDrawerMenuOrder)

    override fun createView(context: android.content.Context): View {
        val view = super.createView(context)
        listView.listenReorder { _, items -> applyReorder(items) }
        listView.allowReorder(true)
        return view
    }

    override fun onFragmentDestroy() {
        // entiny: drawer adapter only rebuilds its rows on reloadInterface, so apply edits when leaving
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
        super.onFragmentDestroy()
    }

    private fun rowVisible(entry: MenuOrderEntry<DrawerMenuConfig.Item>): Boolean =
        entry.item != DrawerMenuConfig.Item.FEED || FeedHelper.isEnabled()

    private fun shown() = entries.filter { it.enabled && rowVisible(it) }

    private fun hidden() = entries.filter { !it.enabled && !it.item.isDivider && rowVisible(it) }

    private fun canAddDivider() = entries.any { it.item.isDivider && !it.enabled }

    private fun rowItem(item: DrawerMenuConfig.Item): UItem {
        val uItem = UItem.asButton(ITEM_BASE + item.ordinal, item.iconRes, LocaleController.getString(item.labelRes))
        uItem.`object` = item
        return uItem
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDrawerMenuItems)))
        adapter.reorderSectionStart()
        for (entry in shown()) items.add(rowItem(entry.item))
        adapter.reorderSectionEnd()
        if (canAddDivider()) {
            items.add(UItem.asButton(BUTTON_ADD_DIVIDER, R.drawable.msg_add, LocaleController.getString(R.string.InuDrawerMenuAddDivider)))
        }
        items.add(UItem.asShadow(SHADOW_INFO, LocaleController.getString(R.string.InuDrawerMenuOrderInfo)))

        val hiddenEntries = hidden()
        if (hiddenEntries.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuDrawerMenuHidden)))
            for (entry in hiddenEntries) items.add(rowItem(entry.item))
            items.add(UItem.asShadow(SHADOW_HIDDEN, null))
        }
        items.add(UItem.asButton(BUTTON_RESET, R.drawable.msg_reset, LocaleController.getString(R.string.InuDrawerMenuReset)))
        items.add(UItem.asShadow(SHADOW_END, null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_ADD_DIVIDER -> {
                val divider = entries.firstOrNull { it.item.isDivider && !it.enabled } ?: return
                showAtEnd(divider.item)
            }

            BUTTON_RESET -> {
                InuConfig.DRAWER_MENU_ITEMS.resetToDefault()
                entries = InuConfig.DRAWER_MENU_ITEMS.default.toMutableList()
                listView.adapter.update(true)
            }

            else -> {
                val menuItem = item.`object` as? DrawerMenuConfig.Item ?: return
                val entry = entries.firstOrNull { it.item == menuItem } ?: return
                if (entry.enabled) setEnabled(menuItem, false) else showAtEnd(menuItem)
            }
        }
    }

    private fun setEnabled(item: DrawerMenuConfig.Item, enabled: Boolean) {
        val idx = entries.indexOfFirst { it.item == item }
        if (idx < 0) return
        entries[idx] = entries[idx].copy(enabled = enabled)
        save()
    }

    // entiny: revealed rows land after the last visible one, matching how the drawer reads its list
    private fun showAtEnd(item: DrawerMenuConfig.Item) {
        val idx = entries.indexOfFirst { it.item == item }
        if (idx < 0) return
        val entry = entries.removeAt(idx).copy(enabled = true)
        entries.add(entries.indexOfLast { it.enabled } + 1, entry)
        save()
    }

    private fun applyReorder(items: List<UItem>) {
        val order = items.mapNotNull { it.`object` as? DrawerMenuConfig.Item }
        val orderSet = order.toSet()
        val slots = entries.indices.filter { entries[it].item in orderSet }
        if (slots.size != order.size) return
        val byItem = entries.associateBy { it.item }
        val out = entries.toMutableList()
        slots.forEachIndexed { i, slot -> out[slot] = byItem.getValue(order[i]) }
        entries = out
        InuConfig.DRAWER_MENU_ITEMS.value = entries.toList()
    }

    private fun save() {
        InuConfig.DRAWER_MENU_ITEMS.value = entries.toList()
        listView.adapter.update(true)
    }

    companion object {
        private const val ITEM_BASE = 30000
        private val BUTTON_ADD_DIVIDER = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()
        // entiny: distinct ids because DiffUtil aliases identical shadows and crashes animated diff
        private val SHADOW_INFO = InuUtils.generateId()
        private val SHADOW_HIDDEN = InuUtils.generateId()
        private val SHADOW_END = InuUtils.generateId()

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
