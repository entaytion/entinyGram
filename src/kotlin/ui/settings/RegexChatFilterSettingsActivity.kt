package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.RegexFilterHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Filters scoped to one chat, plus which global/shared filters this chat opts out of. */
class RegexChatFilterSettingsActivity(private val dialogId: Long) : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuRegexChatFilters)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexChatFiltersOwn)))
        val chatFilters = RegexFilterHelper.getChatFilters(dialogId)
        chatFilters.forEachIndexed { index, filter ->
            items.add(
                UItem.asButton(
                    CHAT_FILTER_BASE + index,
                    filter.pattern.ifBlank { LocaleController.getString(R.string.InuRegexPatternEmpty) },
                    LocaleController.getString(if (filter.enabled) R.string.InuRegexFilterEnabled else R.string.InuRegexFilterDisabled),
                )
            )
        }
        items.add(UItem.asButton(BUTTON_ADD_CHAT_FILTER, LocaleController.getString(R.string.InuRegexFilterAdd)))
        items.add(UItem.asShadow(null))

        val globalFilters = RegexFilterHelper.getGlobalFilters()
        if (globalFilters.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexChatFiltersExclude)))
            globalFilters.forEachIndexed { index, filter ->
                val excluded = RegexFilterHelper.isExcluded(dialogId, filter.id)
                items.add(
                    UItem.asCheck(
                        EXCLUDE_BASE + index,
                        filter.pattern.ifBlank { LocaleController.getString(R.string.InuRegexPatternEmpty) },
                    ).setChecked(!excluded)
                )
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuRegexChatFiltersExcludeInfo)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val chatFilters = RegexFilterHelper.getChatFilters(dialogId)
        val globalFilters = RegexFilterHelper.getGlobalFilters()
        when {
            item.id == BUTTON_ADD_CHAT_FILTER -> presentFragment(RegexFilterEditActivity(null, dialogId))

            item.id in CHAT_FILTER_BASE until CHAT_FILTER_BASE + chatFilters.size -> {
                val filter = chatFilters[item.id - CHAT_FILTER_BASE]
                presentFragment(RegexFilterEditActivity(filter.id, dialogId))
            }

            item.id in EXCLUDE_BASE until EXCLUDE_BASE + globalFilters.size -> {
                val filter = globalFilters[item.id - EXCLUDE_BASE]
                val nowExcluded = !RegexFilterHelper.isExcluded(dialogId, filter.id)
                RegexFilterHelper.setExcluded(dialogId, filter.id, nowExcluded)
                (view as? TextCheckCell)?.isChecked = !nowExcluded
                refreshDialogs()
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
        private val BUTTON_ADD_CHAT_FILTER = InuUtils.generateId()
        private const val CHAT_FILTER_BASE = 23000
        private const val EXCLUDE_BASE = 24000
    }
}
