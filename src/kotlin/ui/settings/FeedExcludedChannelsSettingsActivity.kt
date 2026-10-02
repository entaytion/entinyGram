package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.helpers.feed.FeedConfig
import desu.inugram.helpers.feed.FeedController
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.feed.FeedChannelSet
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

// entiny: channel list layout ported from exteraless (https://github.com/exteraless/exteraless)
class FeedExcludedChannelsSettingsActivity : SettingsPageActivity(), NotificationCenter.NotificationCenterDelegate {

    private val channels = ArrayList<TLRPC.Chat>()
    private var otherItem: ActionBarMenuItem? = null
    private var query: String? = null
    private var searching = false

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuFeedManageChannels)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        actionBar.setAllowOverlayTitle(false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_SELECT_ALL -> setAllExcluded(false)
                    MENU_DESELECT_ALL -> setAllExcluded(true)
                }
            }
        })
        val menu = actionBar.createMenu()
        menu.addItem(MENU_SEARCH, R.drawable.outline_header_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onSearchExpand() {
                    searching = true
                    otherItem?.visibility = View.GONE
                }

                override fun onSearchCollapse() {
                    searching = false
                    query = null
                    otherItem?.visibility = View.VISIBLE
                    listView?.adapter?.update(true)
                }

                override fun onTextChanged(editText: EditText) {
                    query = editText.text.toString().trim().lowercase(Locale.ROOT)
                    listView?.adapter?.update(true)
                }
            })
            .setSearchFieldHint(LocaleController.getString(R.string.Search))
        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other).also {
            it.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll))
            it.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, LocaleController.getString(R.string.DeselectAll))
        }
        reloadChannels()
        return view
    }

    override fun onFragmentCreate(): Boolean {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.feedNeedReload)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.feedNeedReload) reloadChannels()

    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (!searching) return super.onBackPressed(invoked)
        if (invoked) actionBar.closeSearchField()
        return false
    }

    private fun reloadChannels() {
        FeedController.getInstance(currentAccount).loadChannels { loaded, _ ->
            channels.clear()
            channels.addAll(loaded)
            channels.sortBy { it.title?.lowercase(Locale.ROOT) ?: "" }
            listView?.adapter?.update(true)
        }
    }

    private fun setAllExcluded(excluded: Boolean) {
        val config = FeedConfig.getInstance(currentAccount)
        if (excluded) config.excludeAll(channels.map { -it.id }) else InuConfig.FEED_EXCLUDED_CHANNELS.value = emptySet()
        FeedChannelSet.invalidate()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val config = FeedConfig.getInstance(currentAccount)
        val q = query
        val noQuery = q.isNullOrEmpty()
        if (noQuery) {
            items.add(mkTwoLineCheckItem(TOGGLE_INCLUDE_ARCHIVED, R.string.InuFeedIncludeArchived, 0, InuConfig.FEED_INCLUDE_ARCHIVED.value))
            items.add(mkTwoLineCheckItem(TOGGLE_MARK_READ_ON_SCROLL, R.string.InuFeedMarkReadOnScroll, R.string.InuFeedMarkReadOnScrollInfo, InuConfig.FEED_MARK_READ_ON_SCROLL.value))
            items.add(UItem.asShadow(null))
        }

        val shown = ArrayList<UItem>()
        val hidden = ArrayList<UItem>()
        for ((index, chat) in channels.withIndex()) {
            if (!noQuery && chat.title?.lowercase(Locale.ROOT)?.contains(q!!) != true) continue
            val excluded = config.isExcluded(-chat.id)
            val item = UItem.asUserCheckbox(CHANNEL_BASE + index, chat).setChecked(!excluded)
            (if (excluded) hidden else shown).add(item)
        }

        if (shown.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuFeedShownChannels)))
            items.addAll(shown)
        }
        if (hidden.isNotEmpty()) {
            if (shown.isNotEmpty()) items.add(UItem.asShadow(null))
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuFeedHiddenChannels)))
            items.addAll(hidden)
        }
        if (noQuery && shown.isEmpty() && hidden.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuFeedNoChannels)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val chat = item.`object` as? TLRPC.Chat
        when {
            item.id == TOGGLE_INCLUDE_ARCHIVED -> {
                InuConfig.FEED_INCLUDE_ARCHIVED.value = !InuConfig.FEED_INCLUDE_ARCHIVED.value
                FeedChannelSet.invalidate()
                reloadChannels()
            }
            item.id == TOGGLE_MARK_READ_ON_SCROLL -> {
                InuConfig.FEED_MARK_READ_ON_SCROLL.value = !InuConfig.FEED_MARK_READ_ON_SCROLL.value
                listView?.adapter?.update(true)
            }
            chat != null -> {
                val checked = !item.checked
                FeedConfig.getInstance(currentAccount).setExcluded(-chat.id, !checked)
                item.setChecked(checked)
                (listView?.findViewByItemId(item.id) as? org.telegram.ui.Cells.CheckBoxCell)?.setChecked(checked, true)
                listView?.adapter?.update(true)
            }
        }
    }

    companion object {
        private const val CHANNEL_BASE = 100_000
        private const val MENU_SEARCH = 0
        private const val MENU_SELECT_ALL = 1
        private const val MENU_DESELECT_ALL = 2
        private const val MENU_OTHER = 3

        private val TOGGLE_INCLUDE_ARCHIVED = InuUtils.generateId()
        private val TOGGLE_MARK_READ_ON_SCROLL = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "feed-channels",
            titleRes = R.string.InuFeedManageChannels,
            iconRes = R.drawable.msg_channel,
            factory = ::FeedExcludedChannelsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("feed", R.string.InuFeed),
                SearchRegistry.Entry("feed-include-archived", R.string.InuFeedIncludeArchived, TOGGLE_INCLUDE_ARCHIVED),
                SearchRegistry.Entry("feed-mark-read-on-scroll", R.string.InuFeedMarkReadOnScroll, TOGGLE_MARK_READ_ON_SCROLL),
            ),
        )
    }
}
