package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.style.CharacterStyle
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManagerFixed
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.helpers.archive.ArchiveMediaHelper
import desu.inugram.helpers.archive.EditHistoryHelper
import desu.inugram.helpers.archive.EditHistoryHelper.EditEntry
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatMessageSharedResources
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.telegram.ui.PhotoViewer
import org.telegram.ui.ProfileActivity

// entiny: shared chat-like scaffold of the archive screens; each subclass owns one kind of archive
abstract class ArchiveChatFragment(
    protected val dialogId: Long,
    account: Int,
) : BaseFragment() {

    protected class Row(
        val type: Int,
        val index: Int,
        val date: Long,
        val pinnedBottom: Boolean = false,
        val pinnedTop: Boolean = false,
    )

    protected val rows = ArrayList<Row>()
    protected var listView: RecyclerListView? = null
    protected var loaded = false
    protected var clearing = false
    private var emptyView: View? = null
    private var lastDay = Int.MIN_VALUE
    private var sharedResources: ChatMessageSharedResources? = null
    private val mediaDownloads = HashMap<EditEntry, () -> Unit>()
    private val objects = java.util.IdentityHashMap<EditEntry, MessageObject>()

    protected abstract val subtitle: String
    protected abstract val emptyText: Int
    protected abstract val clearable: Boolean
    protected abstract val isDeletedArchive: Boolean
    protected open val overlayActionBar: Boolean = false

    protected abstract fun load()
    protected abstract fun buildRows()
    protected abstract fun hasContent(): Boolean
    protected abstract fun clearArchive(ids: List<Long>, done: Runnable)
    protected abstract fun onCleared()
    protected abstract fun bindMessage(cell: ArchiveMessageCell, row: Row)
    protected abstract fun showMenu(cell: ArchiveMessageCell)
    protected abstract fun onMediaDownloaded(entry: EditEntry, path: String)
    protected open fun labelOf(row: Row): CharSequence = ""
    protected open fun onNearTop() {}
    protected open fun spanSizeAt(position: Int): Int = FULL_SPAN
    protected open fun onTimePressed(message: MessageObject) {}

    init {
        setCurrentAccount(account)
    }

    override fun onFragmentDestroy() {
        mediaDownloads.values.toList().forEach { it() }
        mediaDownloads.clear()
        super.onFragmentDestroy()
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> = ArrayList()

    protected fun resetRows() {
        objects.clear()
        rows.clear()
        lastDay = Int.MIN_VALUE
    }

    protected fun addRow(type: Int, index: Int, date: Long, pinnedBottom: Boolean = false, pinnedTop: Boolean = false) {
        val day = dayKey(date)
        if (date > 0 && day != lastDay) {
            rows.add(Row(VIEW_DATE, -1, date))
            lastDay = day
        }
        rows.add(Row(type, index, date, pinnedBottom, pinnedTop))
    }

    protected fun refresh() {
        buildRows()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
    }

    protected fun scrollToEnd() {
        val count = listView?.adapter?.itemCount ?: 0
        if (count > 0) listView?.scrollToPosition(count - 1)
    }

    protected fun updateEmptyView() {
        val count = listView?.adapter?.itemCount ?: 0
        emptyView?.visibility = if (loaded && count == 0) View.VISIBLE else View.GONE
    }

    protected fun dayKey(date: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = date * 1000L
        return calendar.get(java.util.Calendar.YEAR) * 1000 + calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }

    protected fun formatTime(date: Long): String = LocaleController.getInstance().getFormatterDay().format(date * 1000L)

    private fun formatDayHeader(date: Long): String {
        val now = System.currentTimeMillis() / 1000L
        return when (dayKey(date)) {
            dayKey(now) -> LocaleController.getString(R.string.MessageScheduleToday)
            dayKey(now - 86400L) -> LocaleController.getString(R.string.Yesterday).replaceFirstChar { it.titlecase() }
            else -> LocaleController.formatDateChat(date, true)
        }
    }

    protected fun copyText(text: String) {
        AndroidUtilities.addToClipboard(text)
        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.MessageCopied)).show()
    }

    protected fun showInChat(message: MessageObject, messageId: Int = message.id) {
        val args = Bundle()
        if (dialogId < 0) args.putLong("chat_id", -dialogId) else args.putLong("user_id", dialogId)
        args.putInt("message_id", messageId)
        presentFragment(ChatActivity(args))
    }

    protected fun messageOf(entry: EditEntry): MessageObject = objects.getOrPut(entry) { buildMessage(entry) }

    private fun buildMessage(entry: EditEntry): MessageObject {
        val message = entry.message ?: TLRPC.TL_message().apply {
            id = entry.originalMessageId
            this.dialog_id = dialogId
            peer_id = messagesController.getPeer(dialogId)
            val senderId = entry.fromId.takeIf { it != 0L } ?: if (DialogObject.isUserDialog(dialogId)) dialogId else 0L
            if (senderId != 0L) from_id = messagesController.getPeer(senderId)
            out = senderId == userConfig.clientUserId
            message = entry.text
            entities = entry.entities ?: ArrayList()
            media = entry.media ?: TLRPC.TL_messageMediaEmpty()
            date = entry.timestamp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        message.attachPath = entry.mediaPath ?: message.attachPath
        return MessageObject(currentAccount, message, true, false)
    }

    protected fun loadPeers(
        storage: MessagesStorage,
        messages: Collection<TLRPC.Message>,
        peerIds: Collection<Long>,
    ): Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>> {
        val users = ArrayList<Long>()
        val chats = ArrayList<Long>()
        for (m in messages) MessagesStorage.addUsersAndChatsFromMessage(m, users, chats, null)
        for (id in peerIds) if (id > 0) users.add(id) else if (id < 0) chats.add(-id)
        val userIds = users.filterTo(HashSet()) { it > 0 && messagesController.getUser(it) == null }
        val chatIds = chats.filterTo(HashSet()) { it > 0 && messagesController.getChat(it) == null }
        val loadedUsers = ArrayList<TLRPC.User>()
        val loadedChats = ArrayList<TLRPC.Chat>()
        try {
            if (userIds.isNotEmpty()) storage.getUsersInternal(userIds, loadedUsers)
            if (chatIds.isNotEmpty()) storage.getChatsInternal(chatIds.joinToString(","), loadedChats)
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "archive peers load failed", e)
        }
        return loadedUsers to loadedChats
    }

    protected fun applyPeers(peers: Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>>) {
        if (peers.first.isNotEmpty()) messagesController.putUsers(peers.first, true)
        if (peers.second.isNotEmpty()) messagesController.putChats(peers.second, true)
    }

    // entiny: the viewer hides the source photo and restores it on close only for a place it was given
    private val photoProvider = object : PhotoViewer.EmptyPhotoViewerProvider() {
        override fun getPlaceForPhoto(
            messageObject: MessageObject?,
            fileLocation: TLRPC.FileLocation?,
            index: Int,
            needPreview: Boolean,
            closing: Boolean,
        ): PhotoViewer.PlaceProviderObject? {
            val list = listView ?: return null
            for (i in 0 until list.childCount) {
                val cell = list.getChildAt(i) as? ArchiveMessageCell ?: continue
                if (cell.messageObject?.id != messageObject?.id) continue
                val receiver = cell.photoImage ?: continue
                val coords = IntArray(2)
                cell.getLocationInWindow(coords)
                return PhotoViewer.PlaceProviderObject().apply {
                    viewX = coords[0]
                    viewY = coords[1]
                    parentView = list
                    imageReceiver = receiver
                    thumb = receiver.bitmapSafe
                    radius = receiver.getRoundRadius(true)
                }
            }
            return null
        }
    }

    protected fun openNativeMedia(message: MessageObject) {
        PhotoViewer.getInstance().setParentActivity(this)
        PhotoViewer.getInstance().openPhoto(message, null, dialogId, 0L, 0L, photoProvider)
    }

    protected fun openMedia(entry: EditEntry, messageObject: MessageObject) {
        val message = messageObject.messageOwner
        val saved = entry.mediaPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        val visual = messageObject.isPhoto || messageObject.isVideo || messageObject.isGif
        val localFile = if (visual) FileLoader.getInstance(currentAccount).getPathToMessage(message) else null
        if (localFile?.isFile == true) {
            message.attachPath = localFile.absolutePath
            openNativeMedia(messageObject)
            return
        }
        if (saved != null) {
            AndroidUtilities.openForView(saved, saved.name, messageObject.mimeType, parentActivity, null, false)
            return
        }
        if (message.media == null || message.media is TLRPC.TL_messageMediaEmpty) return
        EditHistoryHelper.markAsHistoryPreview(messageObject)
        downloadMedia(entry, messageObject)
    }

    private fun downloadMedia(entry: EditEntry, message: MessageObject) {
        if (mediaDownloads.containsKey(entry)) return
        mediaDownloads[entry] = {}
        val cancel = ArchiveMediaHelper.downloadArchiveMedia(
            currentAccount,
            message.messageOwner,
            entry.rowId,
            isDeletedArchive,
        ) { path ->
            mediaDownloads.remove(entry)
            if (isFinished) return@downloadArchiveMedia
            if (path == null) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
                return@downloadArchiveMedia
            }
            onMediaDownloaded(entry, path)
            message.messageOwner.attachPath = path
            if (message.isPhoto || message.isVideo || message.isGif) {
                openNativeMedia(message)
            } else {
                AndroidUtilities.openForView(File(path), File(path).name, message.mimeType, parentActivity, null, false)
            }
        }
        if (mediaDownloads.containsKey(entry)) mediaDownloads[entry] = cancel
    }

    private fun confirmClearArchive() {
        if (clearing || !loaded || !hasContent()) return
        val context = parentActivity ?: return
        showDialog(
            org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuCacheClearAll))
                .setMessage(subtitle)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                    if (clearing) return@setPositiveButton
                    clearing = true
                    clearArchive(listOf(dialogId)) {
                        clearing = false
                        if (!isFinished) {
                            onCleared()
                            refresh()
                            BulletinFactory.of(this).createSimpleBulletin(
                                R.raw.ic_delete,
                                LocaleController.getString(R.string.InuCacheClearAllDone),
                            ).show()
                        }
                    }
                }
                .create()
        )
    }

    override fun createView(context: Context): View {
        val peerObject = messagesController.getUserOrChat(dialogId)
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(
            when (peerObject) {
                is TLRPC.User -> UserObject.getUserName(peerObject)
                is TLRPC.Chat -> peerObject.title ?: ""
                else -> LocaleController.getString(R.string.InuEditHistory)
            }
        )
        actionBar.setSubtitle(subtitle)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_CLEAR_ARCHIVE -> confirmClearArchive()
                }
            }
        })
        if (clearable) actionBar.createMenu().addItem(MENU_CLEAR_ARCHIVE, R.drawable.inu_tabler_trash_x)
        if (overlayActionBar) {
            actionBar.setAddToContainer(false)
            actionBar.setCastShadows(false)
        }

        val frameLayout = object : SizeNotifierFrameLayout(context) {
            override fun isActionBarVisible(): Boolean = false
            override fun isStatusBarVisible(): Boolean = false
            override fun useRootView(): Boolean = false
        }
        frameLayout.setOccupyStatusBar(false)
        // entiny: solid color fallback because setBackgroundImage(null) no-ops when theme has no cached wallpaper
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        frameLayout.setBackgroundImage(
            if (overlayActionBar) Theme.getCachedWallpaperNonBlocking() else Theme.getCachedWallpaper(),
            Theme.isWallpaperMotion(),
        )

        val recycler = object : RecyclerListView(context) {
            override fun dispatchDraw(canvas: Canvas) {
                for (index in 0 until childCount) {
                    (getChildAt(index) as? ArchiveMessageCell)?.setParentViewSize(measuredWidth, measuredHeight)
                }
                super.dispatchDraw(canvas)
            }

            override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
                val result = super.drawChild(canvas, child, drawingTime)
                if (child is ArchiveMessageCell) {
                    child.freezeCell(false)
                    if (child.isAvatarVisible) {
                        val receiver = child.avatarImage ?: return result
                        // entiny: a run of one sender shows the avatar once, under its last message
                        if (child.drawPinnedBottom()) {
                            val position = getChildAdapterPosition(child)
                            if (position >= 0 && findViewHolderForAdapterPosition(position + 1) != null) {
                                receiver.setVisible(false, false)
                                return result
                            }
                        }
                        val top = child.top + child.paddingTop
                        var y = child.top + child.paddingTop + child.layoutHeight
                        if (y > child.bottom) y = child.bottom
                        if (y - AndroidUtilities.dp(48f) < top) y = top + AndroidUtilities.dp(48f)
                        receiver.setImageY((y - AndroidUtilities.dp(44f)).toFloat())
                        receiver.setAlpha(1f)
                        receiver.setVisible(true, false)
                        receiver.draw(canvas)
                    }
                }
                return result
            }
        }.apply {
            setItemAnimator(null)
            setLayoutAnimation(null)
            // entiny: albums share a row through spans, like the chat does
            layoutManager = GridLayoutManagerFixed(context, FULL_SPAN, LinearLayoutManager.VERTICAL, false).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = spanSizeAt(position)
                }
            }
            setVerticalScrollBarEnabled(!overlayActionBar)
            clipToPadding = false
            val topPadding =
                if (overlayActionBar) AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight()
                else AndroidUtilities.dp(8f)
            setPadding(0, topPadding, 0, AndroidUtilities.dp(8f))
            adapter = HistoryAdapter(context)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val first =
                        (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                    if (first in 0..5) onNearTop()
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
        empty.text = LocaleController.getString(emptyText)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        empty.typeface = AndroidUtilities.bold()
        empty.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        empty.gravity = Gravity.CENTER
        empty.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(6f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        empty.visibility = View.GONE
        emptyView = empty
        frameLayout.addView(empty, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        if (overlayActionBar) {
            frameLayout.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        }

        fragmentView = frameLayout
        if (!loaded) load() else updateEmptyView()
        return frameLayout
    }

    private inner class HistoryAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemViewType == VIEW_MESSAGE

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell: View = if (viewType == VIEW_MESSAGE) ArchiveMessageCell(context) else HeaderCell(context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows.getOrNull(position) ?: return
            when (val view = holder.itemView) {
                is HeaderCell -> view.bind(if (row.type == VIEW_LABEL) labelOf(row) else formatDayHeader(row.date))
                is ArchiveMessageCell -> bindMessage(view, row)
            }
        }
    }

    private inner class HeaderCell(context: Context) : FrameLayout(context) {
        private val textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_chat_serviceText))
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(5f))
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_chat_serviceBackground))
        }

        init {
            addView(
                textView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_HORIZONTAL, 0f, 6f, 0f, 6f)
            )
        }

        fun bind(text: CharSequence) {
            textView.text = text
        }
    }

    protected inner class ArchiveMessageCell(context: Context) : ChatMessageCell(
        context,
        currentAccount,
        false,
        sharedResources ?: ChatMessageSharedResources(context).also { sharedResources = it },
        resourceProvider,
    ) {
        var entry: EditEntry? = null
        var row: Row? = null
        private var group: MessageObject.GroupedMessages? = null

        init {
            setFullyDraw(true)
            setDelegate(object : ChatMessageCellDelegate {
                override fun didLongPress(cell: ChatMessageCell, x: Float, y: Float) {
                    showMenu(this@ArchiveMessageCell)
                }

                override fun didPressImage(cell: ChatMessageCell, x: Float, y: Float, fullPreview: Boolean) {
                    val current = entry ?: return
                    val message = cell.messageObject ?: return
                    openMedia(current, message)
                }

                override fun didPressUrl(cell: ChatMessageCell, url: CharacterStyle?, longPress: Boolean) {
                    if (longPress) return
                    val link = (url as? URLSpan)?.url ?: return
                    parentActivity?.let { Browser.openUrl(it, link) }
                }

                override fun didPressReplyMessage(cell: ChatMessageCell, id: Int, x: Float, y: Float, longpress: Boolean) {
                    val message = cell.messageObject ?: return
                    showInChat(message, id)
                }

                override fun canPerformActions(): Boolean = true

                override fun didPressUserAvatar(cell: ChatMessageCell, user: TLRPC.User?, touchX: Float, touchY: Float, asForward: Boolean) {
                    val id = user?.id ?: cell.messageObject?.messageOwner?.from_id?.user_id?.takeIf { it != 0L } ?: return
                    presentFragment(ProfileActivity(Bundle().apply { putLong("user_id", id) }))
                }

                override fun didPressChannelAvatar(cell: ChatMessageCell, chat: TLRPC.Chat?, postId: Int, touchX: Float, touchY: Float, asForward: Boolean) {
                    val id = chat?.id ?: return
                    presentFragment(ProfileActivity(Bundle().apply { putLong("chat_id", id) }))
                }

                override fun forceUpdate(cell: ChatMessageCell, anchorScroll: Boolean) {
                    val message = cell.primaryMessageObject ?: return
                    val current = row ?: return
                    message.forceUpdate = true
                    cell.setMessageObject(cell.messageObject, group, current.pinnedBottom, current.pinnedTop, true, true)
                }

                override fun didPressTime(cell: ChatMessageCell) {
                    val message = cell.messageObject ?: return
                    onTimePressed(message)
                }
            })
        }

        fun show(e: EditEntry, r: Row, message: MessageObject, group: MessageObject.GroupedMessages?, isChatBubble: Boolean) {
            entry = e
            row = r
            this.group = group
            isChat = isChatBubble
            setMessageObject(message, group, r.pinnedBottom, r.pinnedTop, true, true)
            if (isAvatarVisible) setAvatar(message)
        }
    }

    companion object {
        private const val TAG = "ArchiveChatFragment"
        const val FULL_SPAN = 1000
        const val VIEW_MESSAGE = 1
        const val VIEW_DATE = 3
        const val VIEW_LABEL = 4
        private const val MENU_CLEAR_ARCHIVE = 3
    }
}
