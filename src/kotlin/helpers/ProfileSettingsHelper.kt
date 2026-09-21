package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import desu.inugram.helpers.menu.reorderByMenu
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

object ProfileSettingsHelper {

    private fun classify(item: UItem): ProfileMenuConfig.Item? =
        if (item.`object` is TLRPC.TL_attachMenuBot) ProfileMenuConfig.Item.WALLET
        else ProfileMenuConfig.Item.forSettingsId(item.id)

    // entiny: stock section of a row: 0 inugram, 1 main, 2 premium, 3 help; -1 unknown
    private fun groupOf(item: UItem): Int = when (item.id) {
        99 -> 0
        in 1..3, in 5..10 -> 1
        in 11..13, 15, 16 -> 2
        17, 18, 19, 23 -> 3
        else -> if (item.`object` is TLRPC.TL_attachMenuBot) 2 else -1
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
        val ordered = reorderByMenu(rows, InuConfig.PROFILE_SETTINGS_ROWS.value) { classify(it) }
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
