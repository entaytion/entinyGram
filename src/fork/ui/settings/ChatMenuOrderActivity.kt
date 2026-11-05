package desu.inugram.ui.settings

import desu.inugram.InuConfig
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import desu.inugram.helpers.menu.ChatMenuConfig
import desu.inugram.helpers.menu.MenuOrderEntry
// #if PLUGINS
import desu.inugram.helpers.plugins.ui.PluginActions
// #endif
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class ChatMenuOrderActivity : MenuOrderActivity<ChatMenuConfig.Item>() {
    override val config get() = InuConfig.CHAT_MENU_ITEMS
    override val infoStringRes = R.string.InuChatMenuOrderInfo
    override val headerStringRes = R.string.InuChatMenuItems
    override val resetStringRes = R.string.InuChatMenuReset
    // #if PLUGINS
    override val pluginActionKind = PluginActions.KIND_CHAT
    // #endif

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatMenuOrder)

    override fun mainRowVisible(entry: MenuOrderEntry<ChatMenuConfig.Item>): Boolean =
        super.mainRowVisible(entry) && !entry.item.isEntinyMenuItem

    override fun isToggleable(item: ChatMenuConfig.Item): Boolean =
        item != ChatMenuConfig.Item.ENTINY_MENU

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        fillMainSection(items, adapter)
        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatMenuEntinyGram)))
        val groupedEntries = entries.filter {
            !it.bottom && rowVisible(it) && isAvailable(it.item) && it.item.isEntinyMenuItem
        }
        openReorderSection(adapter, toBottom = false) { reordered -> reorderEntinyItems(reordered) }
        for (entry in groupedEntries) items.add(buildRow(entry))
        adapter.reorderSectionEnd()
        // #if PLUGINS
        fillPluginSection(items, adapter)
        // #endif
        fillResetSection(items, adapter)
    }

    private fun reorderEntinyItems(items: List<UItem>) {
        val reordered = items.mapNotNull { it.`object` as? ChatMenuConfig.Item }
        val byItem = entries.associateBy { it.item }
        val reorderedEntries = reordered.mapNotNull(byItem::get)
        val visible = entries.filter {
            !it.bottom && rowVisible(it) && isAvailable(it.item) && it.item.isEntinyMenuItem
        }
        if (reorderedEntries.size != visible.size) return
        val next = reorderedEntries.iterator()
        entries = entries.map { if (it in visible) next.next() else it }.toMutableList()
        config.value = entries
    }

}
