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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.FolderHelper
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.feed.FeedChannelSet
import desu.inugram.helpers.feed.FeedController
import desu.inugram.helpers.feed.FeedScope
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

class FeedActivity @JvmOverloads constructor(
    private val scope: FeedScope = FeedScope.Global,
    private val hasMainTabs: Boolean = false,
) : BaseFragment() {

    private sealed class Row {
        data class Msg(val message: MessageObject) : Row()
        data class Header(val dialogId: Long) : Row()
    }

    private val newestOnTop = InuConfig.FEED_NEWEST_ON_TOP.value
    private val rows = ArrayList<MessageObject>()
    private val displayItems = ArrayList<Row>()

    private var listView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingOlder = false
    private var reachedEnd = false

    private val controller: FeedController by lazy {
        val folder = scope as? FeedScope.Folder
        if (folder != null) FeedController.forFolder(currentAccount, folder.filterId) else FeedController.get(currentAccount)
    }

    private val additionNavigationBarHeight: Int
        get() = if (hasMainTabs && !MainTabsHelper.isHidden) dp(MainTabsHelper.mainTabsHeightWithMargins.toFloat()) else 0

    private var navigationBarHeight = 0

    private fun applyBottomInset() {
        listView?.setPadding(0, dp(8f), 0, dp(8f) + navigationBarHeight + additionNavigationBarHeight)
    }

    // entiny: tab pages receive consumed insets from ViewPagerActivity so bottom tab padding must be applied manually
    private fun installTabInsetsListener(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            navigationBarHeight = AndroidUtilities.getDefaultWindowInsets(insets, false).bottom
            applyBottomInset()
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun createView(context: Context): View {
        if (!hasMainTabs) actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setTitle(LocaleController.getString(R.string.InuFeed))
        FeedChannelSet.folderName(currentAccount, scope)?.let { actionBar.setSubtitle(it) }
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
                stackFromEnd = !newestOnTop
            }
            setVerticalScrollBarEnabled(true)
            clipToPadding = false
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f) + additionNavigationBarHeight)
            adapter = FeedAdapter(context)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    if (newestOnTop) {
                        if (dy <= 0) return
                        val lastVisible = lm.findLastVisibleItemPosition()
                        if (lastVisible != RecyclerView.NO_POSITION && lastVisible >= displayItems.size - 1 - LOAD_MORE_THRESHOLD) {
                            maybeLoadOlder()
                        }
                    } else {
                        if (dy >= 0) return
                        if (lm.findFirstVisibleItemPosition() <= LOAD_MORE_THRESHOLD) maybeLoadOlder()
                    }
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
        if (hasMainTabs) installTabInsetsListener(frameLayout)
        loadInitial()
        return frameLayout
    }

    private fun loadInitial() {
        controller.onLiveMessagesAdded = { added -> appendLive(added) }
        controller.onLiveMessagesRemoved = { dialogId, ids -> removeLive(dialogId, ids) }
        controller.onLiveDialogRemoved = { dialogId -> removeDialogLive(dialogId) }
        controller.onBackfillCompleted = { retryAfterBackfill() }
        controller.onScreenOpened { _ ->
            if (fragmentView == null) return@onScreenOpened
            val ordered = controller.store.snapshot().let { if (newestOnTop) it else it.asReversed() }
            val snapshot = ordered.filter { controller.unreadTracker.isUnread(it.getDialogId(), it.id) }
            rows.clear()
            rows.addAll(snapshot)
            reachedEnd = false
            rebuildDisplayItems()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
            if (snapshot.size < MIN_INITIAL_UNREAD) maybeLoadOlder()
        }
    }

    private fun retryAfterBackfill() {
        if (fragmentView == null) return
        reachedEnd = false
        if (rows.size < MIN_INITIAL_UNREAD) maybeLoadOlder()
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
            if (newestOnTop) {
                rows.addAll(added)
                val trailingDialogId = (displayItems.lastOrNull() as? Row.Msg)?.message?.getDialogId()
                val inserted = buildRunRows(added, trailingDialogId)
                val start = displayItems.size
                displayItems.addAll(inserted)
                listView?.adapter?.notifyItemRangeInserted(start, inserted.size)
            } else {
                val chronological = added.asReversed()
                rows.addAll(0, chronological)
                val inserted = buildRunRows(chronological, null)
                displayItems.addAll(0, inserted)
                listView?.adapter?.notifyItemRangeInserted(0, inserted.size)
            }
        }
    }

    private fun appendLive(added: List<MessageObject>) {
        if (fragmentView == null) return
        if (newestOnTop) {
            rows.addAll(0, added)
            val inserted = buildRunRows(added, null)
            displayItems.addAll(0, inserted)
            listView?.adapter?.notifyItemRangeInserted(0, inserted.size)
            updateEmptyView()
            return
        }
        val chronological = added.asReversed()
        rows.addAll(chronological)
        val trailingDialogId = (displayItems.lastOrNull() as? Row.Msg)?.message?.getDialogId()
        val inserted = buildRunRows(chronological, trailingDialogId)
        val start = displayItems.size
        displayItems.addAll(inserted)
        listView?.adapter?.notifyItemRangeInserted(start, inserted.size)
        updateEmptyView()
    }

    private fun removeLive(dialogId: Long, messageIds: Collection<Int>) {
        if (fragmentView == null || messageIds.isEmpty()) return
        val idSet = messageIds.toHashSet()
        if (rows.removeAll { it.getDialogId() == dialogId && idSet.contains(it.id) }) {
            rebuildDisplayItems()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
        }
    }

    private fun removeDialogLive(dialogId: Long) {
        if (fragmentView == null) return
        if (rows.removeAll { it.getDialogId() == dialogId }) {
            rebuildDisplayItems()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
        }
    }

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
        controller.onBackfillCompleted = null
        controller.onScreenClosed()
        super.onFragmentDestroy()
    }

    private fun showOverflowMenu() {
        val anchor = actionBar.createMenu().getItem(MENU_OVERFLOW) ?: return
        val options = ItemOptions.makeOptions(this, anchor)
        if (hasPickableFolders()) {
            options.add(R.drawable.msg_folders, LocaleController.getString(R.string.InuFeedFolders)) {
                // entiny: delay showing folder picker until popup dismiss animation finishes
                AndroidUtilities.runOnUIThread({ showFolderPicker() }, 100)
            }
        }
        options
            .add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedManageChannels)) {
                presentFragment(FeedExcludedChannelsSettingsActivity())
            }
            .add(R.drawable.msg_markread, LocaleController.getString(R.string.InuFeedMarkAllRead)) {
                val marked = controller.unreadTracker.markAllRead(FeedChannelSet.eligibleChannels(currentAccount, scope).toList())
                rows.removeAll { !controller.unreadTracker.isUnread(it.getDialogId(), it.id) }
                rebuildDisplayItems()
                listView?.adapter?.notifyDataSetChanged()
                updateEmptyView()
                val text = if (marked > 0) {
                    LocaleController.formatString(R.string.InuFeedMarkedAllRead, marked)
                } else {
                    LocaleController.getString(R.string.InuFeedAlreadyRead)
                }
                // entiny: switch to global bulletin factory when hosted inside main tabs
                val factory = if (hasMainTabs) BulletinFactory.global() else BulletinFactory.of(this)
                factory.createSimpleBulletin(R.raw.chats_infotip, text).show()
            }
            .show()
    }

    private fun pickableFolders(): List<MessagesController.DialogFilter> =
        MessagesController.getInstance(currentAccount).dialogFilters?.filter { !it.isDefault }.orEmpty()

    private fun hasPickableFolders(): Boolean = pickableFolders().isNotEmpty()

    private fun showFolderPicker() {
        if (fragmentView == null) return
        val anchor = actionBar.createMenu().getItem(MENU_OVERFLOW) ?: return
        val currentFolder = scope as? FeedScope.Folder
        val options = ItemOptions.makeOptions(this, anchor)
        if (currentFolder != null) {
            options.add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedAllChannels)) {
                presentFragment(FeedActivity(), !hasMainTabs)
            }
        }
        for (filter in pickableFolders()) {
            val info = FolderHelper.getTabInfo(filter)
            val name = info.first.takeIf { it.isNotEmpty() } ?: continue
            val filterId = filter.id
            if (currentFolder != null && currentFolder.filterId == filterId) continue
            options.add(FolderHelper.getTabIcon(info.second), name) {
                presentFragment(FeedActivity(FeedScope.Folder(filterId)), !hasMainTabs)
            }
        }
        options.show()
    }

    private fun openChannel(dialogId: Long) {
        val args = Bundle()
        args.putLong("chat_id", -dialogId)
        presentFragment(ChatActivity(args))
    }

    private fun openRow(msg: MessageObject) {
        // entiny: a row counts as read only on tap -- plain scrolling must not advance the watermark
        controller.unreadTracker.onRowSeen(msg.getDialogId(), msg.id)
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
                }
            }
        }
    }

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

            titleView.setTextColor(Theme.getColor(Theme.key_chats_name))
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
            // entiny: empty delegate keeps canPerformActions false so row click listener handles touches
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
        private const val MIN_INITIAL_UNREAD = 10
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_HEADER = 1
    }
}
