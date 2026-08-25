package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MainTabsCustomizeActivity : MenuOrderActivity<MainTabsMenuConfig.Item>() {
    override val config get() = InuConfig.BOTTOM_TABS_ORDER
    override val infoStringRes = R.string.InuMainTabsCustomizeInfo
    override val headerStringRes = R.string.InuMainTabsItems
    override val resetStringRes = R.string.InuMainTabsCustomizeReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCustomizeBottomTabs)

    override fun buildPreviewCell(context: Context): View =
        MainTabsPreviewCell(
            context,
            onToggle = { item -> onRowToggle(entries.first { it.item == item }, rows[item]) },
            onReorder = { newOrder -> applyPreviewReorder(newOrder) },
        )

    override fun refreshPreviewCell(cell: View) {
        (cell as? MainTabsPreviewCell)?.setState(
            entries.map { it.item },
            entries.filter { it.enabled }.map { it.item }.toSet(),
        )
    }

    // the preview is fully interactive (tap to toggle, drag to reorder) — the switch/drag-handle
    // list the base class normally builds would just be a redundant second control for the same state
    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(infoStringRes)))
        val preview = previewCell ?: buildPreviewCell(context).also { previewCell = it }
        refreshPreviewCell(preview)
        items.add(UItem.asCustom(preview))
        fillResetSection(items, adapter)
    }

    private fun applyPreviewReorder(newOrder: List<MainTabsMenuConfig.Item>) {
        val byItem = entries.associateBy { it.item }
        entries = newOrder.mapNotNull { byItem[it] }.toMutableList()
        config.value = entries
    }

    // the bottom tab bar is built once when MainTabsActivity is created, so reorder/show-hide needs a restart
    override fun onBackPressed(invoked: Boolean): Boolean {
        if (invoked) showRestartBulletin()
        return super.onBackPressed(invoked)
    }
}
