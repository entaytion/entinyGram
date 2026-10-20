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
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.helpers.chat.BookmarksHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.ShareAlert
import org.telegram.ui.Components.SizeNotifierFrameLayout

// entiny: inspired by NagramX bookmarks
class BookmarksActivity(private val dialogId: Long) : BaseFragment() {

    private val messages = ArrayList<MessageObject>()
    private var listView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loaded = false

    override fun onFragmentCreate(): Boolean {
        loadMessages()
        return super.onFragmentCreate()
    }

    private fun loadMessages() {
        val account = currentAccount
        val ids = BookmarksHelper.ids(account, dialogId)
        val storage = MessagesStorage.getInstance(account)
        // entiny: getMessage blocks on the storage queue itself, so it must not be called from that queue
        Utilities.globalQueue.postRunnable {
            val found = ArrayList<TLRPC.Message>(ids.size)
            val userIds = ArrayList<Long>()
            val chatIds = ArrayList<Long>()
            for (id in ids) {
                val message = storage.getMessage(dialogId, id.toLong())
                if (message != null) {
                    MessagesStorage.addUsersAndChatsFromMessage(message, userIds, chatIds, null)
                    found.add(message)
                }
            }
            android.util.Log.d("BookmarksActivity", "dialog=$dialogId ids=${ids.size} found=${found.size}")
            val missingUsers = ArrayList(userIds.filter { it > 0 && messagesController.getUser(it) == null }.distinct())
            val missingChats = chatIds.filter { it > 0 && messagesController.getChat(it) == null }.distinct()
            storage.storageQueue.postRunnable {
                val users = ArrayList<TLRPC.User>()
                val chats = ArrayList<TLRPC.Chat>()
                try {
                    if (missingUsers.isNotEmpty()) storage.getUsersInternal(missingUsers, users)
                    if (missingChats.isNotEmpty()) storage.getChatsInternal(missingChats.joinToString(","), chats)
                } catch (e: Throwable) {
                    android.util.Log.d("BookmarksActivity", "peers load failed", e)
                }
                AndroidUtilities.runOnUIThread { showMessages(ids, found, users, chats) }
            }
        }
    }

    private fun showMessages(ids: IntArray, found: List<TLRPC.Message>, users: ArrayList<TLRPC.User>, chats: ArrayList<TLRPC.Chat>) {
        if (isFinished) return
        messagesController.putUsers(users, true)
        messagesController.putChats(chats, true)
        val byId = found.associateBy { it.id }
        val built = ArrayList<MessageObject>(ids.size)
        for (id in ids) {
            try {
                val obj = MessageObject(currentAccount, byId[id] ?: placeholder(id), false, true)
                obj.messageOwner.media?.ttl_seconds = 0
                obj.forceAvatar = true
                built.add(obj)
            } catch (e: Throwable) {
                android.util.Log.d("BookmarksActivity", "message $id build failed", e)
            }
        }
        built.sortBy { it.id }
        android.util.Log.d("BookmarksActivity", "built=${built.size}")
        messages.clear()
        messages.addAll(built)
        loaded = true
        listView?.adapter?.notifyDataSetChanged()
        updateState()
        listView?.scrollToPosition(maxOf(0, messages.size - 1))
    }

    private fun placeholder(id: Int): TLRPC.Message {
        val message = TLRPC.TL_message()
        message.id = id
        message.dialog_id = dialogId
        message.date = ConnectionsManager.getInstance(currentAccount).currentTime
        message.out = true
        message.from_id = TLRPC.TL_peerUser().also { it.user_id = userConfig.getClientUserId() }
        message.peer_id = messagesController.getPeer(dialogId)
        message.message = LocaleController.getString(R.string.InuShowInChat)
        return message
    }

    override fun createView(context: Context): View {
        val peerObject = messagesController.getUserOrChat(dialogId)
        val name = when (peerObject) {
            is TLRPC.User -> UserObject.getUserName(peerObject)
            is TLRPC.Chat -> peerObject.title ?: ""
            else -> LocaleController.getString(R.string.InuBookmarks)
        }
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(name)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val frameLayout = object : SizeNotifierFrameLayout(context) {
            override fun isActionBarVisible(): Boolean = false
            override fun isStatusBarVisible(): Boolean = false
            override fun useRootView(): Boolean = false
        }
        frameLayout.setOccupyStatusBar(false)
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion())

        val recycler = object : RecyclerListView(context) {
            override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
                val result = super.drawChild(canvas, child, drawingTime)
                if (child is ChatMessageCell) {
                    // flush any deferred message object now that the cell is attached
                    child.freezeCell(false)
                    val msg = child.messageObject
                    if (msg != null) msg.forceAvatar = true
                    if (msg != null) {
                        child.isAvatarVisible = true
                        child.setAvatar(msg)
                    }
                    if (child.isAvatarVisible) {
                        val receiver = child.avatarImage ?: return result
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
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setVerticalScrollBarEnabled(true)
            clipToPadding = false
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
            adapter = BookmarksAdapter(context)
        }
        listView = recycler
        recycler.setOnItemClickListener { _, position, _, _ ->
            messages.getOrNull(position)?.let { showInChat(it) }
        }
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
        empty.text = LocaleController.getString(R.string.InuBookmarksEmpty)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        empty.typeface = AndroidUtilities.bold()
        empty.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        empty.gravity = Gravity.CENTER
        empty.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(6f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        empty.visibility = View.GONE
        emptyView = empty
        frameLayout.addView(empty, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = frameLayout
        updateState()
        return frameLayout
    }

    private fun updateState() {
        actionBar?.setSubtitle("${LocaleController.getString(R.string.InuBookmarks)} (${messages.size}/${BookmarksHelper.MAX_PER_CHAT})")
        emptyView?.visibility = if (loaded && messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showInChat(message: MessageObject) {
        val args = Bundle()
        if (dialogId < 0) args.putLong("chat_id", -dialogId) else args.putLong("user_id", dialogId)
        args.putInt("message_id", message.id)
        presentFragment(ChatActivity(args))
    }

    private fun removeBookmark(message: MessageObject) {
        BookmarksHelper.remove(currentAccount, dialogId, message.id)
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) return
        messages.removeAt(index)
        listView?.adapter?.notifyItemRemoved(index)
        updateState()
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuBookmarkRemoved)).show()
    }

    private fun showMenu(cell: BookmarkCell) {
        val message = cell.messageObject ?: return
        val text = message.messageText?.toString().orEmpty().let { if (message.caption != null) message.caption.toString() else it }
        val link = firstLink(message)
        ItemOptions.makeOptions(this, cell)
            .add(R.drawable.msg_openin, LocaleController.getString(R.string.InuShowInChat)) { showInChat(message) }
            .add(R.drawable.msg_share, LocaleController.getString(R.string.ShareFile)) {
                val ctx = parentActivity ?: return@add
                showDialog(ShareAlert.createShareAlert(ctx, message, null, false, null, false))
            }
            .addIf(message.isPhoto || message.isVideo || message.isDocument || message.isMusic || message.isVoice, R.drawable.msg_download, LocaleController.getString(R.string.SaveToDownloads)) {
                val ctx = parentActivity ?: return@addIf
                MediaController.saveFilesFromMessages(ctx, accountInstance, arrayListOf(message), null)
            }
            .addIf(link != null, R.drawable.msg_link, LocaleController.getString(R.string.OpenLink)) {
                parentActivity?.let { Browser.openUrl(it, link) }
            }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.InuBookmarkRemove), true) { removeBookmark(message) }
            .addIf(text.isNotEmpty(), R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                AndroidUtilities.addToClipboard(text)
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.MessageCopied)).show()
            }
            .setGravity(if (message.isOutOwner) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    private fun firstLink(message: MessageObject): String? {
        val entities = message.messageOwner?.entities ?: return null
        val text = message.messageOwner?.message.orEmpty()
        for (e in entities) {
            if (e is TLRPC.TL_messageEntityTextUrl) return e.url
            if (e is TLRPC.TL_messageEntityUrl && e.offset >= 0 && e.offset + e.length <= text.length) return text.substring(e.offset, e.offset + e.length)
        }
        return null
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> = ArrayList()

    private inner class BookmarksAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemCount(): Int = messages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            RecyclerListView.Holder(BookmarkCell(context))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val message = messages.getOrNull(position) ?: return
            (holder.itemView as BookmarkCell).bind(message)
        }
    }

    private inner class BookmarkCell(context: Context) : ChatMessageCell(context, currentAccount) {
        init {
            setFullyDraw(true)
            // entiny: empty delegate keeps canPerformActions false so the row long click handles touches
            setDelegate(object : ChatMessageCellDelegate {
                override fun didPressUrl(cell: ChatMessageCell, url: CharacterStyle?, longPress: Boolean) {
                    if (longPress) return
                    val link = (url as? URLSpan)?.url ?: return
                    parentActivity?.let { Browser.openUrl(it, link) }
                }
            })
            setOnLongClickListener {
                showMenu(this)
                true
            }
        }

        fun bind(message: MessageObject) {
            isChat = dialogId < 0
            setMessageObject(message, null, false, false, false)
            // entiny: force avatar visibility/rendering in standalone list
            isAvatarVisible = true
            setAvatar(message)
        }
    }
}
