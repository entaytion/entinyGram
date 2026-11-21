package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.RegexFilterHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.messenger.Utilities

class RegexFilterSettingsActivity : SettingsPageActivity(), NotificationCenter.NotificationCenterDelegate {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuRegexFilter)

    override fun onFragmentCreate(): Boolean {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.updateInterfaces) listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REGEX_FILTER_ENABLED,
                R.string.InuRegexFilterEnabled,
                R.string.InuRegexFilterEnabledInfo,
                InuConfig.REGEX_FILTER_ENABLED.value,
            )
        )
        items.add(UItem.asShadow(null))

        val filters = RegexFilterHelper.getGlobalFilters()
        val indexed = filters.withIndex().toList()
        val blocking = indexed.filter { it.value.action != RegexFilterHelper.ACTION_CLEAN }
        val cleaning = indexed.filter { it.value.action == RegexFilterHelper.ACTION_CLEAN }

        if (blocking.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexGroupBlockOptions)))
            items.add(
                UItem.asButton(
                    BUTTON_REGEX_FILTER_MODE,
                    LocaleController.getString(R.string.InuRegexFilterMode),
                    modeLabel(InuConfig.REGEX_FILTER_MODE.value)
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_REGEX_FILTER_HIDE_REPLIES,
                    R.string.InuRegexFilterHideReplies,
                    R.string.InuRegexFilterHideRepliesInfo,
                    InuConfig.REGEX_FILTER_HIDE_REPLIES.value,
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_REGEX_FILTER_BUTTONS,
                    R.string.InuRegexFilterButtons,
                    R.string.InuRegexFilterButtonsInfo,
                    InuConfig.REGEX_FILTER_BUTTONS.value,
                )
            )
            items.add(UItem.asShadow(null))
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexGroupBlock)))
            blocking.forEach { items.add(filterRow(it.index, it.value)) }
            items.add(UItem.asShadow(null))
        }
        if (cleaning.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexGroupClean)))
            cleaning.forEach { items.add(filterRow(it.index, it.value)) }
            items.add(UItem.asShadow(null))
        }

        if (filters.isEmpty()) items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexFiltersList)))
        items.add(
            UItem.asButton(
                BUTTON_ADD_FILTER,
                R.drawable.entiny_add_filter,
                LocaleController.getString(R.string.InuRegexFilterAdd),
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asButton(BUTTON_EXPORT_FILTERS, R.drawable.entiny_export_filters, LocaleController.getString(R.string.InuRegexFilterExport)))
        items.add(UItem.asButton(BUTTON_IMPORT_FILTERS, R.drawable.entiny_import_filters, LocaleController.getString(R.string.InuRegexFilterImport)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuRegexFilterHint)))
    }

    private fun filterRow(index: Int, filter: RegexFilterHelper.FilterEntry): UItem {
        val pattern = filter.pattern.lineSequence().firstOrNull().orEmpty().ifBlank { LocaleController.getString(R.string.InuRegexPatternEmpty) }
        val title = filter.name.ifBlank { pattern }
        val subtitle = ArrayList<String>()
        if (filter.name.isNotBlank()) subtitle.add(pattern)
        if (filter.reversed) subtitle.add(LocaleController.getString(R.string.InuRegexReversed))
        val text: CharSequence = title
        val sub = subtitle.joinToString(" • ").ifEmpty { null }
        return UItem.asButtonCheck(FILTER_BASE + index, text, sub).also {
            it.checked = filter.enabled
            it.bind = Utilities.Callback { view ->
                if (view !is NotificationsCheckCell) return@Callback
                view.setTextAndValueAndCheck(text, sub, it.checked, 0, sub != null, view.needDivider)
                view.setDrawLine(true)
            }
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_REGEX_FILTER_ENABLED -> {
                val new = InuConfig.REGEX_FILTER_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                refreshDialogs()
            }

            item.id == TOGGLE_REGEX_FILTER_HIDE_REPLIES -> {
                val new = InuConfig.REGEX_FILTER_HIDE_REPLIES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                refreshDialogs()
            }

            item.id == TOGGLE_REGEX_FILTER_BUTTONS -> {
                val new = InuConfig.REGEX_FILTER_BUTTONS.toggle()
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
                val filters = RegexFilterHelper.getGlobalFilters()
                val filter = filters.getOrNull(item.id - FILTER_BASE)
                if (filter != null) {
                    val onSwitch = if (LocaleController.isRTL) x < AndroidUtilities.dp(76f) else x > view.measuredWidth - AndroidUtilities.dp(76f)
                    if (onSwitch) {
                        RegexFilterHelper.setEnabled(filter.id, !filter.enabled)
                        (view as? NotificationsCheckCell)?.isChecked = !filter.enabled
                    } else {
                        presentFragment(RegexFilterEditActivity(filter.id, null))
                    }
                }
            }
        }
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        InuConfig.RegexFilterModeItem.SPOILER -> LocaleController.getString(R.string.InuRegexFilterModeSpoiler)
        else -> LocaleController.getString(R.string.InuRegexFilterModeHide)
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
        RegexFilterHelper.notifyChanged()
    }

    companion object {
        private val TOGGLE_REGEX_FILTER_ENABLED = InuUtils.generateId()
        private val BUTTON_REGEX_FILTER_MODE = InuUtils.generateId()
        private val TOGGLE_REGEX_FILTER_HIDE_REPLIES = InuUtils.generateId()
        private val TOGGLE_REGEX_FILTER_BUTTONS = InuUtils.generateId()
        private val BUTTON_ADD_FILTER = InuUtils.generateId()
        private val BUTTON_EXPORT_FILTERS = InuUtils.generateId()
        private val BUTTON_IMPORT_FILTERS = InuUtils.generateId()
        private const val FILTER_BASE = 22000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "regex-filter",
            titleRes = R.string.InuRegexFilter,
            iconRes = R.drawable.entiny_regex_filter,
            factory = ::RegexFilterSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("regex-filter-enabled", R.string.InuRegexFilterEnabled, TOGGLE_REGEX_FILTER_ENABLED),
                SearchRegistry.Entry("regex-filter-mode", R.string.InuRegexFilterMode, BUTTON_REGEX_FILTER_MODE),
                SearchRegistry.Entry("regex-filter-hide-replies", R.string.InuRegexFilterHideReplies, TOGGLE_REGEX_FILTER_HIDE_REPLIES),
                SearchRegistry.Entry("regex-filter-buttons", R.string.InuRegexFilterButtons, TOGGLE_REGEX_FILTER_BUTTONS),
                SearchRegistry.Entry("regex-filter-add", R.string.InuRegexFilterAdd, BUTTON_ADD_FILTER),
                SearchRegistry.Entry("regex-filter-export", R.string.InuRegexFilterExport, BUTTON_EXPORT_FILTERS),
                SearchRegistry.Entry("regex-filter-import", R.string.InuRegexFilterImport, BUTTON_IMPORT_FILTERS),
            ),
        )
    }
}
