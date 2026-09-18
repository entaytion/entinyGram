package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import desu.inugram.helpers.menu.reorderByMenu
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.UItem

object ProfileSettingsHelper {

    private fun classify(item: UItem): ProfileMenuConfig.Item? =
        if (item.`object` is TLRPC.TL_attachMenuBot) ProfileMenuConfig.Item.WALLET
        else ProfileMenuConfig.Item.forSettingsId(item.id)

    @JvmStatic
    fun isCustomized(): Boolean = InuConfig.PROFILE_SETTINGS_ROWS.value != InuConfig.PROFILE_SETTINGS_ROWS.default

    @JvmStatic
    fun reorder(items: MutableList<UItem>, from: Int) {
        if (from < 0 || from >= items.size) return
        val head = ArrayList(items.subList(0, from))
        val tail = ArrayList(items.subList(from, items.size))
        val ordered = reorderByMenu(tail, InuConfig.PROFILE_SETTINGS_ROWS.value) { classify(it) }
        items.clear()
        items.addAll(head)
        items.addAll(ordered)
    }

    @JvmStatic
    fun orderedEnabledInfoRows(unavailable: Set<ProfileInfoMenuConfig.Item>): List<ProfileInfoMenuConfig.Item> =
        InuConfig.PROFILE_INFO_ROWS.value
            .filter { it.enabled && it.item !in unavailable }
            .map { it.item }
}
