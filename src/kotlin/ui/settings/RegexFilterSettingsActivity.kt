package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.RegexFilterHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class RegexFilterSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuRegexFilter)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader("AdBlock"))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_SPONSORED_MESSAGES,
                R.string.InuHideSponsoredMessages,
                R.string.InuHideSponsoredMessagesInfo,
                InuConfig.HIDE_SPONSORED_MESSAGES.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexFilter)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REGEX_FILTER_ENABLED,
                R.string.InuRegexFilterEnabled,
                R.string.InuRegexFilterEnabledInfo,
                InuConfig.REGEX_FILTER_ENABLED.value,
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_REGEX_FILTER_MODE,
                LocaleController.getString(R.string.InuRegexFilterMode),
                modeLabel(InuConfig.REGEX_FILTER_MODE.value)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexFiltersList)))
        val filters = RegexFilterHelper.getGlobalFilters()
        filters.forEachIndexed { index, filter ->
            items.add(
                UItem.asButton(
                    FILTER_BASE + index,
                    filter.pattern.ifBlank { LocaleController.getString(R.string.InuRegexPatternEmpty) },
                    statusLabel(filter),
                )
            )
        }
        items.add(
            UItem.asButton(
                BUTTON_ADD_FILTER,
                LocaleController.getString(R.string.InuRegexFilterAdd),
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asButton(BUTTON_EXPORT_FILTERS, R.drawable.inu_tabler_clipboard, LocaleController.getString(R.string.InuRegexFilterExport)))
        items.add(UItem.asButton(BUTTON_IMPORT_FILTERS, R.drawable.inu_tabler_file_diff, LocaleController.getString(R.string.InuRegexFilterImport)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuRegexFilterHint)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_HIDE_SPONSORED_MESSAGES -> {
                val new = InuConfig.HIDE_SPONSORED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            item.id == TOGGLE_REGEX_FILTER_ENABLED -> {
                val new = InuConfig.REGEX_FILTER_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                refreshDialogs()
            }

            item.id == BUTTON_REGEX_FILTER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuRegexFilterModeHide),
                    LocaleController.getString(R.string.InuRegexFilterModeSpoiler),
                ),
                InuConfig.REGEX_FILTER_MODE.value,
            ) { which ->
                InuConfig.REGEX_FILTER_MODE.value = which
                softRebuild()
                refreshDialogs()
            }

            item.id == BUTTON_ADD_FILTER -> presentFragment(RegexFilterEditActivity(null, null))

            item.id == BUTTON_EXPORT_FILTERS -> exportFilters()

            item.id == BUTTON_IMPORT_FILTERS -> importFilters()

            item.id in FILTER_BASE until FILTER_BASE + RegexFilterHelper.getGlobalFilters().size -> {
                val filter = RegexFilterHelper.getGlobalFilters()[item.id - FILTER_BASE]
                presentFragment(RegexFilterEditActivity(filter.id, null))
            }
        }
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        InuConfig.RegexFilterModeItem.SPOILER -> LocaleController.getString(R.string.InuRegexFilterModeSpoiler)
        else -> LocaleController.getString(R.string.InuRegexFilterModeHide)
    }

    private fun statusLabel(filter: RegexFilterHelper.FilterEntry): String {
        val parts = ArrayList<String>()
        parts.add(
            LocaleController.getString(if (filter.enabled) R.string.InuRegexFilterEnabled else R.string.InuRegexFilterDisabled)
        )
        if (filter.reversed) parts.add(LocaleController.getString(R.string.InuRegexReversed))
        return parts.joinToString(" • ")
    }

    private fun exportFilters() {
        val json = RegexFilterHelper.exportJson()
        val cm = org.telegram.messenger.ApplicationLoader.applicationContext
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("regex-filters", json))
        org.telegram.ui.Components.BulletinFactory.of(this)
            .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuRegexFilterExportDone))
            .show()
    }

    private fun importFilters() {
        desu.inugram.ui.showInputDialog(
            this,
            LocaleController.getString(R.string.InuRegexFilterImport),
            hint = LocaleController.getString(R.string.InuRegexFilterImportHint),
        ) { text ->
            val count = RegexFilterHelper.importJson(text)
            if (count > 0) {
                listView?.adapter?.update(true)
                org.telegram.ui.Components.BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.info, LocaleController.formatString(R.string.InuRegexFilterImportDone, count))
                    .show()
                true
            } else {
                false
            }
        }
    }

    private fun refreshDialogs() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(
            NotificationCenter.updateInterfaces,
            MessagesController.UPDATE_MASK_ALL,
        )
    }

    companion object {
        private val TOGGLE_HIDE_SPONSORED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_REGEX_FILTER_ENABLED = InuUtils.generateId()
        private val BUTTON_REGEX_FILTER_MODE = InuUtils.generateId()
        private val BUTTON_ADD_FILTER = InuUtils.generateId()
        private val BUTTON_EXPORT_FILTERS = InuUtils.generateId()
        private val BUTTON_IMPORT_FILTERS = InuUtils.generateId()
        private const val FILTER_BASE = 22000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "regex-filter",
            titleRes = R.string.InuRegexFilter,
            iconRes = R.drawable.inu_tabler_filter,
            factory = ::RegexFilterSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-sponsored-messages", R.string.InuHideSponsoredMessages, TOGGLE_HIDE_SPONSORED_MESSAGES),
                SearchRegistry.Entry("regex-filter-enabled", R.string.InuRegexFilterEnabled, TOGGLE_REGEX_FILTER_ENABLED),
                SearchRegistry.Entry("regex-filter-mode", R.string.InuRegexFilterMode, BUTTON_REGEX_FILTER_MODE),
                SearchRegistry.Entry("regex-filter-add", R.string.InuRegexFilterAdd, BUTTON_ADD_FILTER),
                SearchRegistry.Entry("regex-filter-export", R.string.InuRegexFilterExport, BUTTON_EXPORT_FILTERS),
                SearchRegistry.Entry("regex-filter-import", R.string.InuRegexFilterImport, BUTTON_IMPORT_FILTERS),
            ),
        )
    }
}
