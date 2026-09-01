package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import desu.inugram.helpers.menu.reorderByMenu
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.UItem

/**
 * Java bridge for the two reorderable/hideable row pools:
 *
 * - the stock **Settings screen** rows built by `SettingsActivity.fillItems`
 *   ([InuConfig.PROFILE_SETTINGS_ROWS], applied by [reorder]);
 * - the **My Profile** account-info rows (phone / bio / username) built by
 *   `ProfileActivity.updateRowsIds` ([InuConfig.PROFILE_INFO_ROWS], applied by
 *   [orderedEnabledInfoRows]).
 */
object ProfileSettingsHelper {

    /** classify one built `UItem` back to its config item, or null for shadows/headers/etc. */
    private fun classify(item: UItem): ProfileMenuConfig.Item? =
        if (item.`object` is TLRPC.TL_attachMenuBot) ProfileMenuConfig.Item.WALLET
        else ProfileMenuConfig.Item.forSettingsId(item.id)

    /** true when the user actually reordered/hid something — stock rendering otherwise */
    @JvmStatic
    fun isCustomized(): Boolean = InuConfig.PROFILE_SETTINGS_ROWS.value != InuConfig.PROFILE_SETTINGS_ROWS.default

    /**
     * Permutes `items[from..]` in place to the user's saved Settings-screen order, dropping
     * disabled rows. Section shadows/headers ride along with the row they followed. With an
     * untouched config this is a no-op, so the screen stays stock-identical by default.
     */
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

    /** [unavailable] items are excluded regardless of the user's toggle (row has no content) */
    @JvmStatic
    fun orderedEnabledInfoRows(unavailable: Set<ProfileInfoMenuConfig.Item>): List<ProfileInfoMenuConfig.Item> =
        InuConfig.PROFILE_INFO_ROWS.value
            .filter { it.enabled && it.item !in unavailable }
            .map { it.item }
}
