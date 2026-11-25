package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig.Item
import org.telegram.ui.Components.UItem

internal class BottomTabsOrderRows(private val page: SchemaPageActivity) {
    private val rows = mutableMapOf<Item, MenuOrderRow>()
    private val rowIds = Item.entries.associateWith { desu.inugram.helpers.InuUtils.generateId() }

    fun build(): List<UItem> = buildList {
        for (entry in InuConfig.BOTTOM_TABS_ORDER.value) {
            if (!entry.item.isFeatureOn) continue
            val row = rows.getOrPut(entry.item) { createRow(entry.item) }
            row.setChecked(entry.enabled || entry.item == Item.CHATS)
            add(UItem.asCustom(row, row.mainHeightDp).apply {
                id = rowIds.getValue(entry.item)
                `object` = entry.item
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createRow(item: Item): MenuOrderRow = MenuOrderRow(page.context).apply {
        bind(item)
        setSwitchVisible(item != Item.CHATS)
        if (item != Item.CHATS) setOnClickListener { toggleTab(item) }
        setOnReorderTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val list = page.listView
                list.findContainingViewHolder(this)?.let { list.itemTouchHelper.startDrag(it) }
            }
            false
        }
    }

    fun save(items: List<UItem>) {
        val order = items.mapNotNull { it.`object` as? Item }
        val entries = InuConfig.BOTTOM_TABS_ORDER.value
        val byItem = entries.associateBy { it.item }
        val shown = order.toSet()
        if (shown.size != order.size || !byItem.keys.containsAll(shown)) return
        val moved = order.map { byItem.getValue(it) }.iterator()
        val reordered = entries.map { if (it.item in shown) moved.next() else it }
        if (reordered == entries) return
        InuConfig.BOTTOM_TABS_ORDER.value = reordered
        BottomTabsSettings.refreshPreview(page)
        page.showRestart()
    }

    private fun toggleTab(tab: Item) {
        val entries = InuConfig.BOTTOM_TABS_ORDER.value
        val enabled = !entries.first { it.item == tab }.enabled
        InuConfig.prefs.edit {
            InuConfig.BOTTOM_TABS_ORDER.unsafeSet(entries.map { if (it.item == tab) it.copy(enabled = enabled) else it }, this)
            if (tab == Item.SEARCH && !enabled) InuConfig.BOTTOM_TABS_SEARCH_SEPARATE.unsafeSet(false, this)
        }
        rows[tab]?.setChecked(enabled)
        BottomTabsSettings.changed(page)
    }
}
