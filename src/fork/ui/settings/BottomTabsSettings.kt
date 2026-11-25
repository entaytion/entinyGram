package desu.inugram.ui.settings

import android.view.View
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.menu.MainTabsMenuConfig.Item
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem

internal object BottomTabsSettings {
    private enum class Style(val label: Int) {
        TELEGRAM(R.string.InuBottomTabsStyleTelegram),
        MATERIAL(R.string.InuMaterial3),
        IOS(R.string.InuIosBottomBar),
        FLOATING(R.string.InuBottomTabsV2),
    }

    private val previewId = InuUtils.generateId()
    private val orderRowsId = InuUtils.generateId()

    fun orderRows(page: SchemaPageActivity): BottomTabsOrderRows =
        page.remember(orderRowsId) { BottomTabsOrderRows(page) }

    private val style: Style
        get() = when {
            MainTabsHelper.isV2 -> Style.FLOATING
            MainTabsHelper.isMaterial -> Style.MATERIAL
            MainTabsHelper.isIos -> Style.IOS
            else -> Style.TELEGRAM
        }

    fun styleLabel(): String = getString(style.label)

    fun showStyle(page: SchemaPageActivity, anchor: View) {
        RadioItemOptions.show(page, anchor, Style.entries.map { getString(it.label) }, style.ordinal) { index ->
            val next = Style.entries[index]
            if (next == style) return@show
            InuConfig.prefs.edit {
                InuConfig.BOTTOM_TABS_V2.unsafeSet(next == Style.FLOATING, this)
                InuConfig.M3_BOTTOM_TABS.unsafeSet(next == Style.MATERIAL, this)
                InuConfig.IOS_BOTTOM_NAVIGATION_BAR.unsafeSet(next == Style.IOS, this)
            }
            changed(page)
        }
    }

    fun previewItem(page: SchemaPageActivity, id: Int): UItem {
        val preview = page.remember(previewId) {
            MainTabsPreviewCell(page.context, page.currentAccount, page.resourceProvider)
        }
        preview.refresh()
        return UItem.asCustom(preview, MainTabsPreviewCell.HEIGHT_DP).apply { this.id = id }
    }

    fun refreshPreview(page: SchemaPageActivity) {
        page.peek<MainTabsPreviewCell>(previewId)?.refresh()
    }

    fun changed(page: SchemaPageActivity) {
        page.refresh()
        page.showRestart()
    }

    fun onSeparateSearchChanged(page: SchemaPageActivity, separate: Boolean) {
        if (separate) MainTabsHelper.setEnabled(Item.SEARCH.index, true)
        changed(page)
    }

    fun resetMainTabs(page: SchemaPageActivity) {
        InuConfig.prefs.edit {
            InuConfig.BOTTOM_TABS_ORDER.unsafeSet(InuConfig.BOTTOM_TABS_ORDER.default, this)
            InuConfig.BOTTOM_TABS_SEARCH_SEPARATE.unsafeSet(false, this)
        }
        changed(page)
    }

    private fun startTabItems() = MainTabsHelper.configuredOrder().filterNot { it.isShortcut }

    fun startTabLabel(): String =
        getString((startTabItems().firstOrNull { it.index == InuConfig.START_TAB.value } ?: Item.CHATS).labelRes)

    fun showStartTab(page: SchemaPageActivity) {
        val context = page.context ?: return
        val items = startTabItems()
        val selected = items.indexOfFirst { it.index == InuConfig.START_TAB.value }
            .takeIf { it >= 0 } ?: items.indexOf(Item.CHATS)
        page.showDialog(
            RadioDialogBuilder(context, page.resourceProvider)
                .setTitle(getString(R.string.InuStartTab))
                .setItems(items.map { RadioDialogBuilder.Item(getString(it.labelRes)) }, selected) { _, which ->
                    InuConfig.START_TAB.value = items[which].index
                    changed(page)
                }.create()
        )
    }
}
