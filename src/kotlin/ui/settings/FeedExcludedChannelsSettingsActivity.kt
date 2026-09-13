package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.feed.FeedChannelSet
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Per-channel show/hide list for the Feed screen, plus the include-archived toggle. */
class FeedExcludedChannelsSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuFeedManageChannels)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(mkTwoLineCheckItem(TOGGLE_INCLUDE_ARCHIVED, R.string.InuFeedIncludeArchived, 0, InuConfig.FEED_INCLUDE_ARCHIVED.value))
        items.add(UItem.asShadow(null))

        val (shown, hidden) = FeedChannelSet.allChannelsSplit(currentAccount)
        if (shown.isEmpty() && hidden.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuFeedNoChannels)))
            return
        }

        if (shown.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuFeedShownChannels)))
            for ((index, dialogId) in shown.withIndex()) {
                items.add(UItem.asCheck(CHANNEL_BASE + index, channelName(dialogId)).also { it.checked = true })
            }
        }
        if (hidden.isNotEmpty()) {
            items.add(UItem.asShadow(null))
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuFeedHiddenChannels)))
            for ((index, dialogId) in hidden.withIndex()) {
                items.add(UItem.asCheck(HIDDEN_BASE + index, channelName(dialogId)).also { it.checked = false })
            }
        }
        // Item ids only need to round-trip through onClick within a single fillItems() call, so
        // stashing the actual dialog ids alongside the shown/hidden lists (rather than re-deriving
        // them from a list index that can shift between rebuilds) keeps onClick simple below.
        shownIds = shown
        hiddenIds = hidden
    }

    private var shownIds: List<Long> = emptyList()
    private var hiddenIds: List<Long> = emptyList()

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_INCLUDE_ARCHIVED -> {
                InuConfig.FEED_INCLUDE_ARCHIVED.value = !InuConfig.FEED_INCLUDE_ARCHIVED.value
                FeedChannelSet.invalidate()
                listView?.adapter?.update(true)
            }
            item.id in CHANNEL_BASE until CHANNEL_BASE + shownIds.size -> {
                toggleExcluded(shownIds[item.id - CHANNEL_BASE])
            }
            item.id in HIDDEN_BASE until HIDDEN_BASE + hiddenIds.size -> {
                toggleExcluded(hiddenIds[item.id - HIDDEN_BASE])
            }
        }
    }

    private fun toggleExcluded(dialogId: Long) {
        val current = InuConfig.FEED_EXCLUDED_CHANNELS.value.toMutableSet()
        val key = dialogId.toString()
        if (!current.remove(key)) current.add(key)
        InuConfig.FEED_EXCLUDED_CHANNELS.value = current
        FeedChannelSet.invalidate()
        listView?.adapter?.update(true)
    }

    private fun channelName(dialogId: Long): String {
        val chat = MessagesController.getInstance(currentAccount).getChat(-dialogId)
        return chat?.title ?: "ID $dialogId"
    }

    companion object {
        private val TOGGLE_INCLUDE_ARCHIVED = InuUtils.generateId()
        private const val CHANNEL_BASE = 10_000
        private const val HIDDEN_BASE = 20_000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "feed-channels",
            titleRes = R.string.InuFeedManageChannels,
            iconRes = R.drawable.msg_channel,
            factory = ::FeedExcludedChannelsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("feed-include-archived", R.string.InuFeedIncludeArchived, TOGGLE_INCLUDE_ARCHIVED),
            ),
        )
    }
}
