package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.RecentChatsHelper
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class RecentChatsSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = getString(R.string.InuRecentChats)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asButton(BUTTON_STYLE, getString(R.string.InuRecentChatsStyle), styleLabel(InuConfig.RECENT_CHATS_STYLE.value)))
        items.add(UItem.asButton(BUTTON_LIMIT, getString(R.string.InuRecentChatsLimit), limitLabel(InuConfig.RECENT_CHATS_LIMIT.value)))
        items.add(UItem.asShadow(getString(R.string.InuRecentChatsStyleInfo)))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_SHORTCUTS,
                R.string.InuHideRecentChatsShortcuts,
                R.string.InuHideRecentChatsShortcutsInfo,
                InuConfig.HIDE_RECENT_CHATS_SHORTCUTS.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asButton(BUTTON_CLEAR, R.drawable.msg_delete, getString(R.string.InuClearRecentChats)).also { it.red = true })
        items.add(UItem.asShadow(null))
    }

    private fun styleLabel(style: Int): String = getString(
        when (style) {
            InuConfig.RecentChatsStyleItem.SIDEBAR -> R.string.InuRecentChatsStyleSidebar
            InuConfig.RecentChatsStyleItem.PAGE -> R.string.InuRecentChatsStylePage
            InuConfig.RecentChatsStyleItem.STRIP -> R.string.InuRecentChatsStyleStrip
            else -> R.string.InuRecentChatsStylePopup
        }
    )

    private fun limitLabel(limit: Int): String =
        if (limit <= 0) getString(R.string.InuRecentChatsLimitUnlimited) else limit.toString()

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_STYLE -> RadioItemOptions.show(
                this, view,
                listOf(
                    getString(R.string.InuRecentChatsStylePopup),
                    getString(R.string.InuRecentChatsStyleSidebar),
                    getString(R.string.InuRecentChatsStylePage),
                    getString(R.string.InuRecentChatsStyleStrip),
                ),
                InuConfig.RECENT_CHATS_STYLE.value,
            ) { which ->
                InuConfig.RECENT_CHATS_STYLE.value = which
                RecentChatsHelper.notifyChanged()
            }

            BUTTON_LIMIT -> RadioItemOptions.show(
                this, view,
                LIMITS.map { limitLabel(it) },
                LIMITS.indexOf(InuConfig.RECENT_CHATS_LIMIT.value).coerceAtLeast(0),
            ) { which -> InuConfig.RECENT_CHATS_LIMIT.value = LIMITS[which] }

            TOGGLE_HIDE_SHORTCUTS -> {
                val new = InuConfig.HIDE_RECENT_CHATS_SHORTCUTS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                MediaDataController.getInstance(currentAccount).buildShortcuts()
            }

            BUTTON_CLEAR -> {
                val ctx = parentActivity ?: return
                AlertDialog.Builder(ctx, resourceProvider)
                    .setTitle(getString(R.string.InuClearRecentChats))
                    .setMessage(getString(R.string.InuClearRecentChatsAlert))
                    .setPositiveButton(getString(R.string.ClearButton).uppercase()) { _, _ ->
                        RecentChatsHelper.clearRecentDialogs(currentAccount)
                    }
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .makeRed(AlertDialog.BUTTON_POSITIVE)
                    .show()
            }
        }
    }

    companion object {
        private val LIMITS = listOf(25, 100, 500, 0)
        private val BUTTON_STYLE = InuUtils.generateId()
        private val BUTTON_LIMIT = InuUtils.generateId()
        private val TOGGLE_HIDE_SHORTCUTS = InuUtils.generateId()
        private val BUTTON_CLEAR = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "recent-chats",
            titleRes = R.string.InuRecentChats,
            iconRes = R.drawable.msg_recent,
            factory = ::RecentChatsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("recent-chats-style", R.string.InuRecentChatsStyle, BUTTON_STYLE),
                SearchRegistry.Entry("recent-chats-limit", R.string.InuRecentChatsLimit, BUTTON_LIMIT),
                SearchRegistry.Entry("hide-recent-chats-shortcuts", R.string.InuHideRecentChatsShortcuts, TOGGLE_HIDE_SHORTCUTS),
            ),
        )
    }
}
