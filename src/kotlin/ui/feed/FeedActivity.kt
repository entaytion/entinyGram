package desu.inugram.ui.feed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.feed.FeedChannelSet
import desu.inugram.helpers.feed.FeedController
import desu.inugram.ui.settings.FeedExcludedChannelsSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout

/**
 * Unread-only queue of posts from every eligible channel, rendered as real native message bubbles
 * ([ChatMessageCell]) -- entirely outside [ChatActivity], following the exact same pattern this
 * fork's own `AyuMessageHistoryActivity` already uses. See the Feed plan for why: patching
 * `ChatActivity`/`ChatMessageCell` themselves (ExteraGram's own approach) is deliberately avoided
 * as an unnecessary stock-hotspot rewrite.
 *
 * Deliberately NOT a persistent archive: this is something you go through, not something you
 * browse back through. Every load path (`loadInitial`, `FeedController.loadOlder`, live pushes)
 * only ever surfaces posts [FeedUnreadTracker] still considers unread; a post that's been scrolled
 * past stays visible for the rest of the current viewing session (removing it out from under an
 * active scroll would be jarring) but is gone the next time Feed is opened, and "Mark all read"
 * clears everything immediately -- down to the empty state once there's nothing left unread.
 *
 * Layout is chat-style: oldest at the top, newest at the bottom, opened scrolled to the bottom --
 * `stackFromEnd(true)` on a plain (non-reversed) [LinearLayoutManager], the same technique stock
 * `ChannelAdminLogActivity` uses for its own single-list-of-heterogeneous-rows screen, rather than
 * `reverseLayout` (which stock does not use here either). [rows] is kept oldest-first to match.
 *
 * Because rows come from many different channels interleaved by time, [displayItems] additionally
 * splices a small [ChannelHeaderCell] row in front of every run of consecutive same-channel
 * messages -- otherwise a bare stream of bubbles with no chat header is unreadable noise.
 */
class FeedActivity : BaseFragment() {

    private sealed class Row {
        data class Msg(val message: MessageObject) : Row()
        data class Header(val dialogId: Long) : Row()
    }

    /** Source of truth, oldest first (index 0 = oldest, last = newest). */
    private val rows = ArrayList<MessageObject>()

    /** [rows] with [Row.Header] rows spliced in before each new channel run. What the adapter binds. */
    private val displayItems = ArrayList<Row>()

    private var listView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingOlder = false
    private var reachedEnd = false

    private val controller get() = FeedController.get(currentAccount)

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(LocaleController.getString(R.string.InuFeed))
        actionBar.setAllowOverlayTitle(true)
        val menu = actionBar.createMenu()
        menu.addItem(MENU_OVERFLOW, R.drawable.ic_ab_other)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_OVERFLOW -> showOverflowMenu()
                }
            }
        })

        val frameLayout = object : SizeNotifierFrameLayout(context) {
            override fun useRootView(): Boolean = false
        }
        frameLayout.setOccupyStatusBar(false)
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion())

        val recycler = RecyclerListView(context).apply {
            setItemAnimator(null)
            setLayoutAnimation(null)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false).apply {
                stackFromEnd = true
            }
            setVerticalScrollBarEnabled(true)
            clipToPadding = false
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
            adapter = FeedAdapter(context)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    // dy < 0: content is moving down, i.e. the user scrolled toward the top / older
                    // end of the list -- the only direction that ever needs more history here.
                    if (dy >= 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    if (lm.findFirstVisibleItemPosition() <= LOAD_MORE_THRESHOLD) maybeLoadOlder()
                }
            })
        }
        listView = recycler
        frameLayout.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val empty = object : TextView(context) {
            private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                backgroundPaint.color = Theme.getColor(Theme.key_chat_serviceBackground)
                AndroidUtilities.rectTmp.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(14f).toFloat(), AndroidUtilities.dp(14f).toFloat(), backgroundPaint)
                super.onDraw(canvas)
            }
        }
        empty.text = LocaleController.getString(R.string.InuFeedEmpty)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        empty.typeface = AndroidUtilities.bold()
        empty.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        empty.gravity = Gravity.CENTER
        empty.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(6f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        empty.visibility = View.GONE
        emptyView = empty
        frameLayout.addView(empty, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = frameLayout
        loadInitial()
        return frameLayout
    }

    private fun loadInitial() {
        // Live hooks: an already-open Feed updates in place instead of only picking up changes
        // the next time it's opened. The controller is per-account and outlives this screen, so
        // these are registered here and cleared in onFragmentDestroy rather than passed at
        // construction time.
        controller.onLiveMessagesAdded = { added -> appendLive(added) }
        controller.onLiveMessagesRemoved = { dialogId, ids -> removeLive(dialogId, ids) }
        controller.onLiveDialogRemoved = { dialogId -> removeDialogLive(dialogId) }
        controller.onScreenOpened { _ ->
            if (fragmentView == null) return@onScreenOpened
            // Take the full accumulated snapshot, not just this call's delta: on a second open
            // within the same session everything may already be merged from before, in which case
            // the delta comes back empty even though the store itself is not. The store hands back
            // newest-first; reverse it to the oldest-first order this screen displays in, filtered
            // to what's still unread -- see the class doc for why this isn't a full archive.
            val snapshot = controller.store.snapshot().asReversed()
                .filter { controller.unreadTracker.isUnread(it.getDialogId(), it.id) }
            rows.clear()
            rows.addAll(snapshot)
            reachedEnd = false
            rebuildDisplayItems()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
            // An empty first page here only means "nothing already-cached is unread" -- not
            // "nothing unread exists anywhere". With the list empty there's no scroll gesture left
            // to trigger maybeLoadOlder()'s own search, so kick it off once explicitly; it already
            // knows how to page past read backlog and fall through to backfill.
            if (snapshot.isEmpty()) maybeLoadOlder()
        }
    }

    private fun maybeLoadOlder() {
        if (loadingOlder || reachedEnd) return
        loadingOlder = true
        controller.loadOlder { added ->
            loadingOlder = false
            if (fragmentView == null) return@loadOlder
            if (added.isEmpty()) {
                reachedEnd = true
                return@loadOlder
            }
            // `added` is newest-first (same convention as every FeedStore callback); this screen
            // wants oldest-first, and these rows are older than everything currently shown, so
            // they belong at the front once re-reversed to chronological order. Always splices its
            // own leading header rather than trying to detect "this run continues into the old
            // leading one and that header is now redundant" -- a single possible duplicate header
            // at a pagination boundary is a acceptable trade for keeping the notify calls below
            // exactly matched to what actually changed in displayItems.
            val chronological = added.asReversed()
            rows.addAll(0, chronological)
            val inserted = buildRunRows(chronological, null)
            displayItems.addAll(0, inserted)
            // notifyItemRangeInserted (not notifyDataSetChanged) so the LayoutManager keeps the
            // content the user is currently looking at pinned in place instead of jumping.
            listView?.adapter?.notifyItemRangeInserted(0, inserted.size)
        }
    }

    /**
     * New messages pushed live while this screen is open. [added] is newest-first, chronologically
     * after everything currently shown, so it's appended at the tail once re-reversed.
     */
    private fun appendLive(added: List<MessageObject>) {
        if (fragmentView == null) return
        val lm = listView?.layoutManager as? LinearLayoutManager
        // Capture "was the user already looking at the bottom" BEFORE mutating the list, so a
        // live push doesn't yank someone reading older history down to the new message.
        val wasAtBottom = lm != null && lm.findLastVisibleItemPosition() >= displayItems.size - 1 - LOAD_MORE_THRESHOLD
        val chronological = added.asReversed()
        rows.addAll(chronological)
        val trailingDialogId = (displayItems.lastOrNull() as? Row.Msg)?.message?.getDialogId()
        val inserted = buildRunRows(chronological, trailingDialogId)
        val start = displayItems.size
        displayItems.addAll(inserted)
        listView?.adapter?.notifyItemRangeInserted(start, inserted.size)
        updateEmptyView()
        if (wasAtBottom) listView?.post { listView?.scrollToPosition(displayItems.size - 1) }
    }

    /** A message was deleted elsewhere while this screen is open. */
    private fun removeLive(dialogId: Long, messageIds: Collection<Int>) {
        if (fragmentView == null || messageIds.isEmpty()) return
        val idSet = messageIds.toHashSet()
        if (rows.removeAll { it.getDialogId() == dialogId && idSet.contains(it.id) }) {
            rebuildDisplayItems()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
        }
    }

    /** A channel's whole history was cleared, or the account left it, while this screen is open. */
    private fun removeDialogLive(dialogId: Long) {
        if (fragmentView == null) return
        if (rows.removeAll { it.getDialogId() == dialogId }) {
            rebuildDisplayItems()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
        }
    }

    /**
     * Splices a [Row.Header] before each new channel run within [chronological] (oldest-first).
     * [precedingDialogId] is the channel of the row immediately before this batch in the final
     * list (null if the batch starts the list), so a run continuing across the splice point
     * doesn't get a redundant header.
     */
    private fun buildRunRows(chronological: List<MessageObject>, precedingDialogId: Long?): ArrayList<Row> {
        val out = ArrayList<Row>(chronological.size + 1)
        var lastDialogId = precedingDialogId
        for (msg in chronological) {
            val dialogId = msg.getDialogId()
            if (dialogId != lastDialogId) {
                out.add(Row.Header(dialogId))
                lastDialogId = dialogId
            }
            out.add(Row.Msg(msg))
        }
        return out
    }

    /** Rebuilds [displayItems] from [rows], splicing a [Row.Header] before each new channel run. */
    private fun rebuildDisplayItems() {
        displayItems.clear()
        displayItems.addAll(buildRunRows(rows, null))
    }

    private fun updateEmptyView() {
        emptyView?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onFragmentDestroy() {
        controller.onLiveMessagesAdded = null
        controller.onLiveMessagesRemoved = null
        controller.onLiveDialogRemoved = null
        controller.onScreenClosed()
        super.onFragmentDestroy()
    }

    private fun showOverflowMenu() {
        val anchor = actionBar.createMenu().getItem(MENU_OVERFLOW) ?: return
        ItemOptions.makeOptions(this, anchor)
            .add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedManageChannels)) {
                presentFragment(FeedExcludedChannelsSettingsActivity())
            }
            .add(R.drawable.msg_markread, LocaleController.getString(R.string.InuFeedMarkAllRead)) {
                // Every eligible channel, not just the ones currently loaded into rows -- a channel
                // with unread posts that haven't been paginated into the feed yet should still clear.
                val marked = controller.unreadTracker.markAllRead(FeedChannelSet.eligibleChannels(currentAccount).toList())
                // Feed only ever shows unread posts (see class doc) -- prune everything that just
                // became read straight out of view instead of leaving it sitting there stale.
                rows.removeAll { !controller.unreadTracker.isUnread(it.getDialogId(), it.id) }
                rebuildDisplayItems()
                listView?.adapter?.notifyDataSetChanged()
                updateEmptyView()
                // The button previously gave no feedback at all, which made a correct no-op (most
                // channels are already read from the regular chat list) look identical to a broken
                // click -- always show what actually happened, in both directions.
                val text = if (marked > 0) {
                    LocaleController.formatString(R.string.InuFeedMarkedAllRead, marked)
                } else {
                    LocaleController.getString(R.string.InuFeedAlreadyRead)
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, text).show()
            }
            .show()
    }

    private fun openChannel(dialogId: Long) {
        val args = Bundle()
        args.putLong("chat_id", -dialogId)
        presentFragment(ChatActivity(args))
    }

    private fun openRow(msg: MessageObject) {
        val args = Bundle()
        args.putLong("chat_id", -msg.getDialogId())
        args.putInt("message_id", msg.id)
        presentFragment(ChatActivity(args))
    }

    private fun hideChannel(dialogId: Long) {
        val current = InuConfig.FEED_EXCLUDED_CHANNELS.value.toMutableSet()
        current.add(dialogId.toString())
        InuConfig.FEED_EXCLUDED_CHANNELS.value = current
        FeedChannelSet.invalidate()
        controller.store.removeDialog(dialogId)
        rows.removeAll { it.getDialogId() == dialogId }
        rebuildDisplayItems()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
    }

    private fun showRowMenu(cell: FeedMessageCell) {
        val msg = cell.messageObject ?: return
        val dialogId = msg.getDialogId()
        ItemOptions.makeOptions(this, cell)
            .add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedOpenChannel)) {
                openChannel(dialogId)
            }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.InuFeedHideChannel)) {
                hideChannel(dialogId)
            }
            .setGravity(if (msg.isOutOwner) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    private fun channelName(dialogId: Long): String {
        val chat = MessagesController.getInstance(currentAccount).getChat(-dialogId)
        return chat?.title ?: "ID $dialogId"
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> = ArrayList()

    private inner class FeedAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true
        override fun getItemCount(): Int = displayItems.size

        override fun getItemViewType(position: Int): Int = when (displayItems[position]) {
            is Row.Header -> VIEW_TYPE_HEADER
            is Row.Msg -> VIEW_TYPE_MESSAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == VIEW_TYPE_HEADER) ChannelHeaderCell(context) else FeedMessageCell(context)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = displayItems[position]) {
                is Row.Header -> (holder.itemView as ChannelHeaderCell).bind(row.dialogId)
                is Row.Msg -> {
                    val cell = holder.itemView as FeedMessageCell
                    cell.bind(row.message)
                    controller.unreadTracker.onRowSeen(row.message.getDialogId(), row.message.id)
                }
            }
        }
    }

    /**
     * Small tappable bar: channel avatar + title, spliced in before each channel's run of posts.
     * No unread indicator -- every row this screen ever shows is unread by construction (see the
     * class doc), so a per-header dot would always be on and carry no information.
     */
    private inner class ChannelHeaderCell(context: Context) : FrameLayout(context) {
        private val avatarImage = BackupImageView(context)
        private val avatarDrawable = AvatarDrawable()
        private val titleView = SimpleTextView(context)
        private var boundDialogId = 0L

        init {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
            avatarDrawable.setTextSize(dp(12f))
            avatarImage.setRoundRadius(dp(11f))
            addView(avatarImage, LayoutHelper.createFrame(22, 22f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))

            titleView.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
            titleView.setTypeface(AndroidUtilities.bold())
            titleView.setTextSize(13)
            titleView.setGravity(Gravity.LEFT or Gravity.CENTER_VERTICAL)
            addView(
                titleView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.LEFT or Gravity.CENTER_VERTICAL, 42f, 0f, 12f, 0f),
            )

            setOnClickListener { openChannel(boundDialogId) }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(34f), MeasureSpec.EXACTLY),
            )
        }

        fun bind(dialogId: Long) {
            boundDialogId = dialogId
            titleView.text = channelName(dialogId)
            val chat: TLRPC.Chat? = MessagesController.getInstance(currentAccount).getChat(-dialogId)
            if (chat != null) {
                avatarDrawable.setInfo(currentAccount, chat)
                avatarImage.setForUserOrChat(chat, avatarDrawable)
            }
        }
    }

    private inner class FeedMessageCell(context: Context) : ChatMessageCell(context, currentAccount) {
        init {
            setFullyDraw(true)
            isChat = false
            // Deliberately inert: canPerformActions() stays false so ChatMessageCell does not
            // consume touches and this row's own click/long-click listeners fire instead. Channel
            // identity is shown by the separate ChannelHeaderCell row above each run, not by
            // ChatMessageCell's own sender-avatar grouping, which is built for multi-user group
            // chats and has no notion of "which channel" for a synthetic cross-channel list.
            setDelegate(object : ChatMessageCellDelegate {})
            setOnClickListener {
                messageObject?.let { openRow(it) }
            }
            setOnLongClickListener {
                showRowMenu(this)
                true
            }
        }

        fun bind(msg: MessageObject) {
            setMessageObject(msg, null, false, false, false)
        }
    }

    companion object {
        private val MENU_OVERFLOW = InuUtils.generateId()
        private const val LOAD_MORE_THRESHOLD = 6
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_HEADER = 1
    }
}
