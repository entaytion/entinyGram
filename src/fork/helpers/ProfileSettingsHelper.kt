package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import desu.inugram.helpers.menu.ProfileRow
import desu.inugram.helpers.menu.ProfileRowRegistry
import desu.inugram.helpers.menu.reorderByMenu
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

object ProfileSettingsHelper {

    private const val MONEY_ROW_ID = 25

    // entiny: rows without a built-in entry are registered on the fly so new stock rows become orderable
    private fun classify(item: UItem, customized: Boolean): ProfileRow? {
        if (item.id == MONEY_ROW_ID && !customized) return null
        ProfileMenuConfig.Item.forSettingsId(item.id)?.let { return it }
        if (item.id == 0 || item.text.isNullOrEmpty()) return null
        val row = ProfileRowRegistry.discover(item.id, item.text, item.iconResId)
        return if (customized) row else null
    }

    // entiny: stock section of a row: 0 inugram, 1 main, 2 premium, 3 help; -1 unknown
    private fun stockGroupOf(item: UItem): Int = when (item.id) {
        99 -> 0
        in 1..3, in 5..10 -> 1
        in 11..13, 15, 16, MONEY_ROW_ID -> 2
        17, 18, 19, 23, 24 -> 3
        else -> -1
    }

    private fun inheritedGroups(rows: List<UItem>): Map<Int, Int> {
        val groups = HashMap<Int, Int>()
        var last = -1
        for (row in rows) {
            val group = stockGroupOf(row)
            if (group >= 0) {
                last = group
            } else if (last >= 0) {
                groups[row.id] = last
            }
        }
        return groups
    }

    @JvmStatic
    fun reorder(items: MutableList<UItem>, from: Int) {
        if (from < 0 || from >= items.size) return
        val head = ArrayList(items.subList(0, from))
        val rows = ArrayList<UItem>(items.size - from)
        for (i in from until items.size) {
            val it = items[i]
            if (it.viewType != UniversalAdapter.VIEW_TYPE_SHADOW && it.viewType != UniversalAdapter.VIEW_TYPE_HEADER)
                rows.add(it)
        }
        val customized = InuConfig.PROFILE_SETTINGS_ROWS.isCustomized()
        val inherited = inheritedGroups(rows)
        fun groupOf(item: UItem): Int = stockGroupOf(item).takeIf { it >= 0 } ?: inherited[item.id] ?: -1
        val ordered = reorderByMenu(rows, InuConfig.PROFILE_SETTINGS_ROWS.value) { classify(it, customized) }
        val rebuilt = ArrayList<UItem>(ordered.size + 4)
        var lastGroup = -2
        for (row in ordered) {
            val group = groupOf(row)
            if (lastGroup != -1 && group != lastGroup) {
                if (rebuilt.isNotEmpty() && group != -1) rebuilt.add(UItem.asShadow(null))
                if (group == 3) rebuilt.add(UItem.asHeader(LocaleController.getString(R.string.SettingsHelp)))
            }
            rebuilt.add(row)
            lastGroup = group
        }
        if (rebuilt.isNotEmpty() && lastGroup != -1 &&
            rebuilt[rebuilt.size - 1].viewType != UniversalAdapter.VIEW_TYPE_SHADOW
        ) {
            rebuilt.add(UItem.asShadow(null))
        }
        items.clear()
        items.addAll(head)
        items.addAll(rebuilt)
    }

    @JvmStatic
    fun orderedEnabledInfoRows(unavailable: Set<ProfileInfoMenuConfig.Item>): List<ProfileInfoMenuConfig.Item> =
        InuConfig.PROFILE_INFO_ROWS.value
            .filter { it.enabled && it.item !in unavailable }
            .map { it.item }
}
