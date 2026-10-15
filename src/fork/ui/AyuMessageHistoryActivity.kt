package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.SavedMessagesHelper
import desu.inugram.helpers.chat.SavedMessagesHelper.EditEntry
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import java.io.File
import java.util.ArrayList
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Emoji
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout

class AyuMessageHistoryActivity(
    private val targetMessageObject: MessageObject,
    private val deletedArchiveAuthor: Long? = null,
    private val deletedArchiveDialog: Boolean = false,
    private val editedArchiveDialog: Boolean = false,
) : BaseFragment() {

    private class EditEvent(val msgId: Int, val before: EditEntry, val after: EditEntry?, val authorId: Long, val date: Long)

    private val isDeletedArchive: Boolean
        get() = deletedArchiveAuthor != null || deletedArchiveDialog

    private val compareMode: Boolean
        get() = !isDeletedArchive && !InuConfig.EDIT_HISTORY_COMPACT.value

    private val showSenders: Boolean
        get() {
            if (!isDeletedArchive || deletedArchiveAuthor != null) return false
            val dialogId = targetMessageObject.getDialogId()
            if (!DialogObject.isChatDialog(dialogId)) return false
            val chat = messagesController.getChat(-dialogId)
            return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat)
        }

    private val historyEntries = ArrayList<EditEntry>()
    private val messageObjects = ArrayList<MessageObject?>()
    private val previousEntries = ArrayList<EditEntry?>()
    private val editEvents = ArrayList<EditEvent>()
    private val liveMessages = HashMap<Int, TLRPC.Message>()
    private var listView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loaded = false
    private var clearing = false
    private var loadingPage = false
    private var hasMoreDeleted = false
    private var deletedOffset = 0
    private val mediaDownloads = HashMap<EditEntry, () -> Unit>()

    override fun onFragmentDestroy() {
        mediaDownloads.values.toList().forEach { it() }
        mediaDownloads.clear()
        super.onFragmentDestroy()
    }

    private fun openArchiveMedia(entry: EditEntry, message: MessageObject) {
        if (mediaDownloads.containsKey(entry)) return
        mediaDownloads[entry] = {}
        val cancel = SavedMessagesHelper.downloadArchiveMedia(currentAccount, message.messageOwner, entry.rowId, isDeletedArchive) { path ->
            mediaDownloads.remove(entry)
            if (!isFinished) {
                if (path == null) {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
                } else {
                    val index = historyEntries.indexOf(entry)
                    if (index >= 0) {
                        historyEntries[index] = entry.copy(mediaPath = path)
                        rebuildMessageObjects()
                        if (!compareMode) listView?.adapter?.notifyItemChanged(index)
                    }
                    AndroidUtilities.openForView(File(path), File(path).name, message.mimeType, parentActivity, null, false)
                }
            }
        }
        if (mediaDownloads.containsKey(entry)) mediaDownloads[entry] = cancel
    }

    init {
        setCurrentAccount(targetMessageObject.currentAccount)
    }

    // entiny: checkLayout and generateLayout return early if peer_id is null so synthesized messages need a fallback peer
    private val peer: TLRPC.Peer?
        get() = targetMessageObject.messageOwner?.peer_id
            ?: messagesController?.getPeer(targetMessageObject.getDialogId())

    private fun loadPeers(storage: MessagesStorage, messages: Collection<TLRPC.Message>, peerIds: Collection<Long>): Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>> {
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

    private fun applyPeers(peers: Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>>) {
        if (peers.first.isNotEmpty()) messagesController.putUsers(peers.first, true)
        if (peers.second.isNotEmpty()) messagesController.putChats(peers.second, true)
    }

    private fun resolveReplies(db: org.telegram.SQLite.SQLiteDatabase, messages: List<TLRPC.Message>) {
        val byDialog = HashMap<Long, HashSet<Int>>()
        for (m in messages) {
            val replyId = m.reply_to?.reply_to_msg_id ?: 0
            if (replyId != 0 && m.replyMessage == null) byDialog.getOrPut(MessageObject.getReplyToDialogId(m)) { HashSet() }.add(replyId)
        }
        val found = HashMap<Pair<Long, Int>, TLRPC.Message>()
        for ((did, ids) in byDialog) {
            try {
                for ((id, m) in InuDatabaseHelper.loadMessagesForArchive(db, did, ids)) found[did to id] = m
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive reply load failed", e)
            }
        }
        for (m in messages) {
            val replyId = m.reply_to?.reply_to_msg_id ?: 0
            if (replyId != 0 && m.replyMessage == null) m.replyMessage = found[MessageObject.getReplyToDialogId(m) to replyId]
        }
    }

    private fun loadDeletedPage() {
        if (loadingPage) return
        loadingPage = true
        val dialogId = targetMessageObject.getDialogId()
        val offset = deletedOffset
        val storage = MessagesStorage.getInstance(currentAccount)
        storage.storageQueue.postRunnable {
            val db = storage.database
            val page = if (db == null) emptyList<EditEntry>() else deletedArchiveAuthor?.let { author ->
                InuDatabaseHelper.deletedByAuthorInDialog(db, author, dialogId, PAGE_SIZE, offset).map {
                    EditEntry(it.date.toLong(), it.text, it.mediaPath, originalMessageId = it.msgId, fromId = author, message = it.message)
                }
            } ?: InuDatabaseHelper.deletedMessagesInDialog(db, dialogId, PAGE_SIZE, offset).map {
                EditEntry(it.date.toLong(), it.text, it.mediaPath, originalMessageId = it.msgId, fromId = it.fromId, message = it.message)
            }
            val messages = page.mapNotNull { it.message }
            if (db != null) resolveReplies(db, messages)
            val peers = loadPeers(storage, messages + messages.mapNotNull { it.replyMessage }, page.map { it.fromId })
            AndroidUtilities.runOnUIThread {
                loadingPage = false
                if (isFinished) return@runOnUIThread
                applyPeers(peers)
                deletedOffset += page.size
                hasMoreDeleted = page.size == PAGE_SIZE
                val first = !loaded
                historyEntries.addAll(0, page.asReversed())
                loaded = true
                rebuildMessageObjects()
                if (first) {
                    listView?.adapter?.notifyDataSetChanged()
                    updateEmptyView()
                    if (historyEntries.isNotEmpty()) listView?.scrollToPosition(historyEntries.size - 1)
                } else if (page.isNotEmpty()) {
                    listView?.adapter?.notifyItemRangeInserted(0, page.size)
                }
            }
        }
    }

    private fun loadHistory() {
        val dialogId = targetMessageObject.getDialogId()
        if (isDeletedArchive) {
            loadDeletedPage()
            return
        }
        if (editedArchiveDialog) {
            val storage = MessagesStorage.getInstance(currentAccount) ?: return
            storage.storageQueue.postRunnable {
                val db = storage.database ?: return@postRunnable
                val previous = HashMap<Int, InuDatabaseHelper.EditHistoryRow>()
                val entries = InuDatabaseHelper.loadEditHistoryInDialog(db, dialogId).filter { (msgId, row) ->
                    val last = previous.put(msgId, row)
                    last == null || last.text != row.text || last.mediaPath != row.mediaPath ||
                        !SavedMessagesHelper.sameContent(last.entities, last.media, row.entities, row.media)
                }.map { (msgId, row) ->
                    EditEntry(row.date, row.text, row.mediaPath, row.entities, row.media, originalMessageId = msgId, rowId = row.rowId)
                }
                val live = try {
                    InuDatabaseHelper.loadMessagesForArchive(db, dialogId, previous.keys)
                } catch (e: Throwable) {
                    android.util.Log.d(TAG, "live messages load failed", e)
                    emptyMap()
                }
                val peers = loadPeers(storage, live.values, listOf(dialogId))
                AndroidUtilities.runOnUIThread {
                    if (isFinished) return@runOnUIThread
                    applyPeers(peers)
                    historyEntries.clear()
                    historyEntries.addAll(entries)
                    liveMessages.clear()
                    liveMessages.putAll(live)
                    onHistoryLoaded()
                }
            }
            return
        }
        val msgId = targetMessageObject.id
        SavedMessagesHelper.getEditHistoryAsync(currentAccount, dialogId, msgId) { list ->
            historyEntries.clear()
            var last: EditEntry? = null
            for (entry in list) {
                val media = entry.mediaPath?.takeIf { it.isNotBlank() }
                if (entry.text.isEmpty() && media == null && entry.media == null) continue
                val revision = EditEntry(entry.timestamp, entry.text, media, entry.entities, entry.media, rowId = entry.rowId)
                if (last != null && sameVersion(last, revision) && last.mediaPath == revision.mediaPath) continue
                historyEntries.add(revision)
                last = revision
            }

            // entiny: use raw message rather than messageText because entity offsets address the unformatted raw string
            val owner = targetMessageObject.messageOwner
            val currentText = owner?.message ?: ""
            val currentDate = when {
                (owner?.edit_date ?: 0) != 0 -> owner!!.edit_date.toLong()
                (owner?.date ?: 0) != 0 -> owner!!.date.toLong()
                else -> System.currentTimeMillis() / 1000
            }
            currentVersionIndex = -1
            if (currentText.isNotEmpty() || owner?.media != null) {
                currentVersionIndex = historyEntries.size
                historyEntries.add(EditEntry(currentDate, currentText, null, owner?.entities, owner?.media))
            }
            onHistoryLoaded()
        }
    }

    private fun onHistoryLoaded() {
        loaded = true
        rebuildMessageObjects()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        scrollToEnd()
    }

    private fun scrollToEnd() {
        val count = listView?.adapter?.itemCount ?: 0
        if (count > 0) listView?.scrollToPosition(count - 1)
    }

    private fun rebuildMessageObjects() {
        messageObjects.clear()
        previousEntries.clear()
        val previous = HashMap<Int, EditEntry>()
        for (entry in historyEntries) {
            messageObjects.add(null)
            previousEntries.add(previous.put(if (editedArchiveDialog) entry.originalMessageId else targetMessageObject.id, entry))
        }
        rebuildEvents()
    }

    private fun mediaKey(entry: EditEntry): String? {
        val media = entry.media
        return when {
            media == null || media is TLRPC.TL_messageMediaEmpty || media is TLRPC.TL_messageMediaWebPage -> entry.mediaPath?.let { "f$it" }
            media.photo != null -> "p${media.photo.id}"
            media.document != null -> "d${media.document.id}"
            else -> media.javaClass.simpleName
        }
    }

    private fun sameVersion(a: EditEntry, b: EditEntry): Boolean =
        a.text == b.text && mediaKey(a) == mediaKey(b) && SavedMessagesHelper.sameContent(a.entities, null, b.entities, null)

    private fun authorOf(message: TLRPC.Message?): Long {
        val dialogId = targetMessageObject.getDialogId()
        if (message == null) return if (DialogObject.isUserDialog(dialogId)) 0L else dialogId
        message.from_id?.let { DialogObject.getPeerDialogId(it) }?.takeIf { it != 0L }?.let { return it }
        if (DialogObject.isUserDialog(dialogId)) return if (message.out) userConfig.clientUserId else dialogId
        return dialogId
    }

    private fun liveEntry(msgId: Int, message: TLRPC.Message): EditEntry? {
        val media = message.media?.takeIf { it !is TLRPC.TL_messageMediaEmpty }
        if (message.message.isNullOrEmpty() && media == null) return null
        val date = if (message.edit_date > 0) message.edit_date else message.date
        return EditEntry(date.toLong(), message.message ?: "", null, message.entities, media, originalMessageId = msgId)
    }

    private fun rebuildEvents() {
        editEvents.clear()
        if (isDeletedArchive) return
        val groups = LinkedHashMap<Int, ArrayList<EditEntry>>()
        for (entry in historyEntries) {
            groups.getOrPut(if (editedArchiveDialog) entry.originalMessageId else targetMessageObject.id) { ArrayList() }.add(entry)
        }
        for ((msgId, list) in groups) {
            val liveMessage = if (editedArchiveDialog) liveMessages[msgId] else targetMessageObject.messageOwner
            val versions = ArrayList(list)
            val live = if (editedArchiveDialog) liveMessage?.let { liveEntry(msgId, it) } else null
            if (live != null) versions.add(live)
            val hasCurrent = if (editedArchiveDialog) live != null else currentVersionIndex >= 0
            val author = authorOf(liveMessage)
            for (i in 0 until versions.size - 1) {
                val before = versions[i]
                val after = versions[i + 1]
                if (sameVersion(before, after)) continue
                editEvents.add(EditEvent(msgId, before, after, author, if (after.timestamp > 0) after.timestamp else before.timestamp))
            }
            if (!hasCurrent && versions.isNotEmpty()) {
                val last = versions.last()
                editEvents.add(EditEvent(msgId, last, null, author, last.timestamp))
            }
        }
        if (editedArchiveDialog) editEvents.sortBy { it.date }
    }

    private fun isStoredRevision(position: Int): Boolean =
        !isDeletedArchive && position >= 0 && position < historyEntries.size && position != currentVersionIndex

    private var currentVersionIndex = -1
    private var diffItem: ActionBarMenuSubItem? = null
    private var compactItem: ActionBarMenuSubItem? = null

    private fun refreshMode() {
        compactItem?.setChecked(InuConfig.EDIT_HISTORY_COMPACT.value)
        diffItem?.setChecked(InuConfig.SHOW_EDIT_HISTORY_DIFF.value)
        diffItem?.visibility = if (InuConfig.EDIT_HISTORY_COMPACT.value) View.VISIBLE else View.GONE
    }

    override fun createView(context: Context): View {
        val dialogId = targetMessageObject.getDialogId()
        val peerObject = if (deletedArchiveAuthor != null) messagesController.getUser(deletedArchiveAuthor) else messagesController.getUserOrChat(dialogId)
        val name = when (peerObject) {
            is TLRPC.User -> UserObject.getUserName(peerObject)
            is TLRPC.Chat -> peerObject.title ?: ""
            else -> LocaleController.getString(R.string.InuEditHistory)
        }

        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(name)
        actionBar.setSubtitle(when {
            isDeletedArchive -> LocaleController.getString(R.string.InuDeletedArchive)
            editedArchiveDialog -> LocaleController.getString(R.string.InuEditedArchive)
            else -> "#${targetMessageObject.id}"
        })
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_CLEAR_ARCHIVE -> confirmClearArchive()
                    MENU_TOGGLE_DIFF -> {
                        InuConfig.SHOW_EDIT_HISTORY_DIFF.toggle()
                        refreshMode()
                        rebuildMessageObjects()
                        listView?.adapter?.notifyDataSetChanged()
                    }
                    MENU_TOGGLE_COMPACT -> {
                        InuConfig.EDIT_HISTORY_COMPACT.toggle()
                        refreshMode()
                        rebuildMessageObjects()
                        listView?.adapter?.notifyDataSetChanged()
                        updateEmptyView()
                        scrollToEnd()
                    }
                }
            }
        })
        if (deletedArchiveDialog || editedArchiveDialog) {
            actionBar.createMenu().addItem(MENU_CLEAR_ARCHIVE, R.drawable.inu_tabler_trash_x)
        }
        if (!isDeletedArchive) {
            val other = actionBar.createMenu().addItem(MENU_MAIN, R.drawable.ic_ab_other)
            compactItem = other.addSubItem(MENU_TOGGLE_COMPACT, R.drawable.msg_list, LocaleController.getString(R.string.InuEditHistoryCompact), true)
            diffItem = other.addSubItem(MENU_TOGGLE_DIFF, R.drawable.msg_customize, LocaleController.getString(R.string.InuEditHistoryDiff), true)
            refreshMode()
        }

        val frameLayout = object : SizeNotifierFrameLayout(context) {
            override fun isActionBarVisible(): Boolean = false
            override fun isStatusBarVisible(): Boolean = false
            override fun useRootView(): Boolean = false
        }
        frameLayout.setOccupyStatusBar(false)
        // entiny: set solid color fallback because setBackgroundImage(null) no-ops when theme has no cached wallpaper
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion())

        // entiny: stock ChatActivity draws message avatars from its list's drawChild, the cell never draws them itself
        val recycler = object : RecyclerListView(context) {
            override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
                val result = super.drawChild(canvas, child, drawingTime)
                if (child is ChatMessageCell && child.isAvatarVisible) {
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
                return result
            }
        }.apply {
            setItemAnimator(null)
            setLayoutAnimation(null)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setVerticalScrollBarEnabled(true)
            clipToPadding = false
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
            adapter = HistoryAdapter(context)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!isDeletedArchive || !hasMoreDeleted || loadingPage) return
                    val first = (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                    if (first in 0..5) loadDeletedPage()
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
        empty.text = LocaleController.getString(if (isDeletedArchive) R.string.InuDeletedArchiveEmpty else R.string.InuNoEditHistory)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        empty.typeface = AndroidUtilities.bold()
        empty.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        empty.gravity = Gravity.CENTER
        empty.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(6f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        empty.visibility = View.GONE
        emptyView = empty
        frameLayout.addView(empty, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = frameLayout
        if (!loaded) {
            loadHistory()
        } else {
            updateEmptyView()
        }
        return frameLayout
    }

    private fun updateEmptyView() {
        val count = listView?.adapter?.itemCount ?: 0
        emptyView?.visibility = if (loaded && count == 0) View.VISIBLE else View.GONE
    }

    private fun confirmClearArchive() {
        if (clearing || !loaded || historyEntries.isEmpty()) return
        val context = parentActivity ?: return
        showDialog(AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuCacheClearAll))
            .setMessage(LocaleController.getString(if (editedArchiveDialog) R.string.InuEditedArchive else R.string.InuDeletedArchive))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                if (clearing) return@setPositiveButton
                clearing = true
                val ids = listOf(targetMessageObject.getDialogId())
                val done = Runnable {
                    clearing = false
                    if (!isFinished) {
                        historyEntries.clear()
                        hasMoreDeleted = false
                        rebuildMessageObjects()
                        listView?.adapter?.notifyDataSetChanged()
                        updateEmptyView()
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone)).show()
                    }
                }
                if (editedArchiveDialog) {
                    SavedMessagesHelper.clearEditHistory(currentAccount, ids, done)
                } else {
                    SavedMessagesHelper.clearCache(currentAccount, ids, deletedOnly = true, onDone = done)
                }
            }
            .create())
    }

    private fun copyText(text: String) {
        AndroidUtilities.addToClipboard(text)
        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.MessageCopied)).show()
    }

    private fun showRowMenu(cell: AyuHistoryMessageCell) {
        val entry = cell.currentEntry() ?: return
        val position = cell.currentPosition()
        ItemOptions.makeOptions(this, cell)
            .addIf(!TextUtils.isEmpty(entry.text), R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                copyText(entry.text)
            }
            .addIf(isStoredRevision(position), R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                deleteRevision(entry)
            }
            .addIf(isDeletedArchive && entry.originalMessageId != 0, R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                deleteArchived(position)
            }
            .setGravity(if (cell.messageObject?.isOutOwner == true) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    private fun showEventMenu(cell: EditCompareCell) {
        val event = cell.event ?: return
        val savedMedia = event.before.mediaPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        ItemOptions.makeOptions(this, cell)
            .addIf(event.before.text.isNotEmpty(), R.drawable.msg_copy, LocaleController.getString(R.string.InuEditHistoryCopyBefore)) {
                copyText(event.before.text)
            }
            .addIf(!event.after?.text.isNullOrEmpty(), R.drawable.msg_copy, LocaleController.getString(R.string.InuEditHistoryCopyAfter)) {
                copyText(event.after?.text ?: "")
            }
            .addIf(savedMedia != null, R.drawable.msg_openin, LocaleController.getString(R.string.OpenInExternalApp)) {
                savedMedia?.let { AndroidUtilities.openForView(it, it.name, null, parentActivity, null, false) }
            }
            .addIf(event.before.rowId > 0, R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                deleteRevision(event.before)
            }
            .show()
    }

    private fun deleteArchived(position: Int) {
        if (position < 0 || position >= historyEntries.size) return
        val entry = historyEntries.removeAt(position)
        deletedOffset = maxOf(0, deletedOffset - 1)
        rebuildMessageObjects()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        SavedMessagesHelper.deletePermanently(currentAccount, targetMessageObject.getDialogId(), listOf(entry.originalMessageId))
    }

    private fun deleteRevision(entry: EditEntry) {
        val position = historyEntries.indexOfFirst { it === entry }
        if (position < 0 || position == currentVersionIndex) return
        historyEntries.removeAt(position)
        if (currentVersionIndex > position) currentVersionIndex--
        rebuildMessageObjects()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        SavedMessagesHelper.deleteEditHistoryEntry(
            currentAccount,
            targetMessageObject.getDialogId(),
            if (editedArchiveDialog) entry.originalMessageId else targetMessageObject.id,
            entry.rowId,
        )
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> {
        return ArrayList()
    }

    private inner class HistoryAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemCount(): Int = if (compareMode) editEvents.size else historyEntries.size

        override fun getItemViewType(position: Int): Int = if (compareMode) VIEW_COMPARE else VIEW_BUBBLE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == VIEW_COMPARE) {
                val cell = EditCompareCell(context)
                cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return RecyclerListView.Holder(cell)
            }
            return RecyclerListView.Holder(AyuHistoryMessageCell(context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            if (view is EditCompareCell) {
                editEvents.getOrNull(position)?.let(view::bind)
                return
            }
            val cell = view as AyuHistoryMessageCell
            val entry = historyEntries[position]
            var syntheticMsg = messageObjects.getOrNull(position)
            if (syntheticMsg == null) {
                val prevEntry = previousEntries.getOrNull(position)
                syntheticMsg = createMessageObjectForEntry(entry, prevEntry, position == currentVersionIndex)
                if (position < messageObjects.size) {
                    messageObjects[position] = syntheticMsg
                }
            }
            cell.setEditEntry(entry, syntheticMsg, position)
        }

        private fun createMessageObjectForEntry(entry: EditEntry, prevEntry: EditEntry?, isLive: Boolean): MessageObject {
            val diff: CharSequence? = if (!isDeletedArchive && InuConfig.SHOW_EDIT_HISTORY_DIFF.value && prevEntry != null) {
                computeDiff(prevEntry.text, entry.text)
            } else {
                null
            }

            val owner = targetMessageObject.messageOwner
            val archived = if (isDeletedArchive) entry.message?.let { InuUtils.cloneTLObject(it, TLRPC.Message::TLdeserialize) } else null
            val msg = archived?.apply {
                id = if (entry.originalMessageId != 0) entry.originalMessageId else id
                dialog_id = targetMessageObject.getDialogId()
                if (peer_id == null) peer_id = this@AyuMessageHistoryActivity.peer
                if (from_id == null && entry.fromId != 0L) from_id = messagesController.getPeer(entry.fromId)
                edit_hide = true
                reply_markup = null
                media?.ttl_seconds = 0
                if (entry.mediaPath == null) attachPath = null
                replyMessage = entry.message?.replyMessage
                // entiny: unresolved replies would render as an endless "Loading" header
                if (replyMessage == null && (reply_to?.reply_to_msg_id ?: 0) != 0) {
                    reply_to = null
                    flags = flags and 8.inv()
                }
            } ?: TLRPC.TL_message().apply {
                id = if ((isDeletedArchive || editedArchiveDialog) && entry.originalMessageId != 0) entry.originalMessageId else targetMessageObject.id
                dialog_id = targetMessageObject.getDialogId()
                date = entry.timestamp.toInt()
                message = entry.text
                from_id = if (isDeletedArchive && entry.fromId != 0L) messagesController.getPeer(entry.fromId) else owner?.from_id
                peer_id = this@AyuMessageHistoryActivity.peer
                out = if (isDeletedArchive && entry.fromId != 0L) entry.fromId == userConfig.clientUserId else owner?.out == true
                post = owner?.post == true
                edit_hide = true
            }

            val entities = if (diff == null && archived == null) entry.entities else null
            if (!entities.isNullOrEmpty()) {
                msg.entities = ArrayList(entities)
                msg.flags = msg.flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
            }

            // entiny: only stored revisions carry media so text revisions stay TYPE_TEXT and build their text layout
            val savedFile = entry.mediaPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
            val storedMedia = if (archived != null) null else entry.media
            if (archived != null) {
                if (savedFile != null && archived.media != null) {
                    msg.attachPath = savedFile.absolutePath
                    FileLoader.getInstance(currentAccount).getPathToMessage(msg)
                }
            } else if (storedMedia != null && storedMedia !is TLRPC.TL_messageMediaEmpty) {
                msg.media = storedMedia
                if (!isLive) {
                    // entiny: clear ttl on archived copies so one-time media does not render as an expired placeholder
                    storedMedia.ttl_seconds = 0
                }
                msg.flags = msg.flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
                if (savedFile != null) {
                    msg.attachPath = savedFile.absolutePath
                }
            } else if (savedFile != null && savedFile.extension.lowercase() in IMAGE_EXTENSIONS) {
                msg.media = TLRPC.TL_messageMediaPhoto().apply { photo = TLRPC.TL_photo() }
                msg.flags = msg.flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
                msg.attachPath = savedFile.absolutePath
            }

            if (archived == null && targetMessageObject.replyMessageObject != null) {
                msg.replyMessage = targetMessageObject.replyMessageObject.messageOwner
                msg.reply_to = owner?.reply_to
            }

            val msgObj = MessageObject(currentAccount, msg, false, true)
            // entiny: mark before layout so the cell never starts auto-downloads for archived copies
            SavedMessagesHelper.markAsHistoryPreview(msgObj)
            msgObj.forceAvatar = !msgObj.isOutOwner
            if (savedFile != null) {
                msgObj.attachPathExists = true
                msgObj.mediaExists = true
            }
            if (diff != null) {
                msgObj.messageText = diff
            }
            msgObj.checkLayout()
            if (diff != null) {
                msgObj.generateLayout(null)
                if (msgObj.caption != null) msgObj.caption = diff
            }
            return msgObj
        }
    }

    private inner class AyuHistoryMessageCell(context: Context) : ChatMessageCell(context, currentAccount) {
        private var entry: EditEntry? = null
        private var entryPosition = -1

        init {
            setFullyDraw(true)
            isChat = false
            // entiny: empty delegate keeps canPerformActions false so row click listener handles touches
            setDelegate(object : ChatMessageCellDelegate {})

            setOnClickListener {
                val currentEntry = entry ?: return@setOnClickListener
                val msgObj = messageObject

                val path: String? = currentEntry.mediaPath
                if (!path.isNullOrEmpty()) {
                    val file = File(path)
                    if (file.isFile && file.length() > 0) {
                        AndroidUtilities.openForView(file, file.name, null, parentActivity, null, false)
                        return@setOnClickListener
                    }
                }

                if (msgObj != null && msgObj.messageOwner?.media != null) {
                    openArchiveMedia(currentEntry, msgObj)
                } else if (!TextUtils.isEmpty(currentEntry.text)) {
                    copyText(currentEntry.text)
                }
            }

            setOnLongClickListener {
                showRowMenu(this)
                true
            }
        }

        fun setEditEntry(editEntry: EditEntry, msgObj: MessageObject, position: Int) {
            this.entry = editEntry
            this.entryPosition = position
            isChat = showSenders
            setMessageObject(msgObj, null, false, false, false)
        }

        fun currentEntry(): EditEntry? = entry

        fun currentPosition(): Int = entryPosition

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val currentEntry = entry ?: return
            // entiny: legacy fallback points photo image at local file when revision predates media blob
            if (currentEntry.media != null || currentEntry.message?.media != null) return
            val path: String? = currentEntry.mediaPath
            if (!path.isNullOrEmpty()) {
                val file = File(path)
                if (file.exists()) {
                    getPhotoImage()?.setImage(file.absolutePath, null, null, null, 0)
                }
            }
        }
    }

    private inner class EditCompareCell(context: Context) : FrameLayout(context) {
        var event: EditEvent? = null
            private set

        private val avatarDrawable = AvatarDrawable()
        private val avatarView = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(18f)) }
        private val nameView = textView(context, 15f, Theme.key_windowBackgroundWhiteBlackText).apply {
            typeface = AndroidUtilities.bold()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val dateView = textView(context, 13f, Theme.key_windowBackgroundWhiteGrayText)
        private val beforeLabel = label(context, R.string.InuEditHistoryBefore, Theme.key_text_RedRegular)
        private val beforeText = textView(context, 15f, Theme.key_windowBackgroundWhiteBlackText)
        private val beforeMedia = textView(context, 13f, Theme.key_windowBackgroundWhiteGrayText)
        private val afterLabel = label(context, R.string.InuEditHistoryAfter, Theme.key_windowBackgroundWhiteGreenText)
        private val afterText = textView(context, 15f, Theme.key_windowBackgroundWhiteBlackText)
        private val afterMedia = textView(context, 13f, Theme.key_windowBackgroundWhiteGrayText)

        init {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
                background = Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), Theme.getColor(Theme.key_windowBackgroundWhite))
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(avatarView, LayoutHelper.createLinear(36, 36, 0f, 0f, 10f, 0f))
            val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            titles.addView(dateView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            card.addView(beforeLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 2f))
            card.addView(beforeText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            card.addView(beforeMedia, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
            card.addView(afterLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 2f))
            card.addView(afterText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            card.addView(afterMedia, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 10f, 4f, 10f, 4f))

            card.setOnClickListener {
                val path = event?.before?.mediaPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
                if (path != null) AndroidUtilities.openForView(path, path.name, null, parentActivity, null, false)
            }
            card.setOnLongClickListener {
                showEventMenu(this)
                true
            }
        }

        private fun textView(context: Context, size: Float, color: Int) = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            setTextColor(Theme.getColor(color))
        }

        private fun label(context: Context, textRes: Int, color: Int) = textView(context, 12f, color).apply {
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(textRes).uppercase()
        }

        private fun mediaLine(entry: EditEntry?, changed: Boolean): String {
            if (entry == null) return ""
            val media = entry.media
            var line = if (media != null) SavedMessagesHelper.archiveText(null, TLRPC.TL_message().apply { this.media = media }) else ""
            val path = entry.mediaPath
            if (line.isEmpty() && !path.isNullOrBlank()) line = File(path).name
            if (line.isNotEmpty()) line = "📎 $line"
            if (changed) line = if (line.isEmpty()) LocaleController.getString(R.string.InuEditHistoryMediaChanged) else line + " · " + LocaleController.getString(R.string.InuEditHistoryMediaChanged)
            return line
        }

        private fun setBlock(view: TextView, text: CharSequence?) {
            if (text.isNullOrEmpty()) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = Emoji.replaceEmoji(text, view.paint.fontMetricsInt, false)
            }
        }

        fun bind(e: EditEvent) {
            event = e
            val author = if (e.authorId != 0L) messagesController.getUserOrChat(e.authorId) else null
            when (author) {
                is TLRPC.User -> nameView.text = UserObject.getUserName(author)
                is TLRPC.Chat -> nameView.text = author.title ?: ""
                else -> nameView.text = ""
            }
            nameView.visibility = if (nameView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (author != null) {
                avatarDrawable.setInfo(currentAccount, author)
                avatarView.setForUserOrChat(author, avatarDrawable)
                avatarView.visibility = View.VISIBLE
            } else {
                avatarView.visibility = View.GONE
            }
            dateView.text = if (e.date > 0) LocaleController.formatDateAudio(e.date, true) else ""

            val after = e.after
            val (oldText, newText) = if (after != null) diffPair(e.before.text, after.text) else Pair<CharSequence, CharSequence>(e.before.text, "")
            setBlock(beforeText, oldText)
            val mediaChanged = after != null && mediaKey(e.before) != mediaKey(after)
            setBlock(beforeMedia, mediaLine(e.before, false))
            if (after == null) {
                setBlock(afterText, LocaleController.getString(R.string.InuEditHistoryCurrentUnknown))
                afterText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                setBlock(afterMedia, null)
            } else {
                setBlock(afterText, newText)
                afterText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                setBlock(afterMedia, mediaLine(after, mediaChanged))
            }
        }

        private fun diffPair(old: String, new: String): Pair<CharSequence, CharSequence> {
            if (old == new) return old to new
            val a = TOKEN.findAll(old).map { it.value }.toList()
            val b = TOKEN.findAll(new).map { it.value }.toList()
            if (a.size.toLong() * b.size > DIFF_LIMIT) return old to new
            val dp = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.size - 1 downTo 0) {
                for (j in b.size - 1 downTo 0) {
                    dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            val red = Theme.getColor(Theme.key_text_RedRegular)
            val green = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText)
            val before = SpannableStringBuilder()
            val after = SpannableStringBuilder()
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                if (i < a.size && j < b.size && a[i] == b[j]) {
                    before.append(a[i++])
                    after.append(b[j++])
                } else if (j >= b.size || (i < a.size && dp[i + 1][j] >= dp[i][j + 1])) {
                    mark(before, a[i++], red, true)
                } else {
                    mark(after, b[j++], green, false)
                }
            }
            return before to after
        }

        private fun mark(builder: SpannableStringBuilder, token: String, color: Int, removed: Boolean) {
            val start = builder.length
            builder.append(token)
            if (token.isBlank()) return
            val end = builder.length
            builder.setSpan(BackgroundColorSpan(ColorUtils.setAlphaComponent(color, 0x38)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (removed) {
                builder.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    companion object {
        private const val TAG = "AyuMessageHistory"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val TOKEN = Regex("\\s+|[\\p{L}\\p{N}_]+|.", RegexOption.DOT_MATCHES_ALL)
        private const val DIFF_LIMIT = 1_500_000L
        private const val PAGE_SIZE = 200
        private const val VIEW_BUBBLE = 0
        private const val VIEW_COMPARE = 1
        private const val MENU_MAIN = 1
        private const val MENU_TOGGLE_DIFF = 2
        private const val MENU_CLEAR_ARCHIVE = 3
        private const val MENU_TOGGLE_COMPACT = 4

        fun forDeletedMessagesInDialog(account: Int, dialogId: Long): AyuMessageHistoryActivity {
            val controller = org.telegram.messenger.MessagesController.getInstance(account)
            val message = TLRPC.TL_message().apply {
                id = 0
                dialog_id = dialogId
                peer_id = controller.getPeer(dialogId)
                message = ""
            }
            return AyuMessageHistoryActivity(MessageObject(account, message, false, true), deletedArchiveDialog = true)
        }

        fun forEditedMessagesInDialog(account: Int, dialogId: Long): AyuMessageHistoryActivity {
            val message = TLRPC.TL_message().apply {
                id = 0
                dialog_id = dialogId
                peer_id = org.telegram.messenger.MessagesController.getInstance(account).getPeer(dialogId)
                message = ""
            }
            return AyuMessageHistoryActivity(MessageObject(account, message, false, true), editedArchiveDialog = true)
        }

        private fun computeDiff(oldText: String, newText: String): CharSequence {
            if (oldText == newText) return newText
            val oldWords = oldText.split(Regex("(?<=\\s)|(?=\\s)"))
            val newWords = newText.split(Regex("(?<=\\s)|(?=\\s)"))
            if (oldWords.size.toLong() * newWords.size > DIFF_LIMIT) return newText
            val lcs = getLcs(oldWords, newWords)

            val builder = SpannableStringBuilder()
            var i = 0
            var j = 0
            var k = 0

            while (i < oldWords.size || j < newWords.size) {
                if (k < lcs.size && i < oldWords.size && j < newWords.size && oldWords[i] == lcs[k] && newWords[j] == lcs[k]) {
                    builder.append(newWords[j])
                    i++
                    j++
                    k++
                } else {
                    val delStart = builder.length
                    val delBuffer = StringBuilder()
                    while (i < oldWords.size && (k >= lcs.size || oldWords[i] != lcs[k])) {
                        delBuffer.append(oldWords[i])
                        i++
                    }
                    if (delBuffer.isNotEmpty()) {
                        builder.append(delBuffer)
                        val delEnd = builder.length
                        builder.setSpan(ForegroundColorSpan(0xFFE53935.toInt()), delStart, delEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(StrikethroughSpan(), delStart, delEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    val insBuffer = StringBuilder()
                    while (j < newWords.size && (k >= lcs.size || newWords[j] != lcs[k])) {
                        insBuffer.append(newWords[j])
                        j++
                    }
                    if (insBuffer.isNotEmpty()) {
                        if (delBuffer.isNotEmpty() && !delBuffer.last().isWhitespace() && !insBuffer.first().isWhitespace()) {
                            builder.append(" ")
                        }
                        val insStart = builder.length
                        builder.append(insBuffer)
                        val insEnd = builder.length
                        builder.setSpan(ForegroundColorSpan(0xFF4CAF50.toInt()), insStart, insEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            return builder
        }

        private fun getLcs(a: List<String>, b: List<String>): List<String> {
            val m = a.size
            val n = b.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 0 until m) {
                for (j in 0 until n) {
                    if (a[i] == b[j]) {
                        dp[i + 1][j + 1] = dp[i][j] + 1
                    } else {
                        dp[i + 1][j + 1] = maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }
            val result = ArrayList<String>()
            var i = m
            var j = n
            while (i > 0 && j > 0) {
                if (a[i - 1] == b[j - 1]) {
                    result.add(a[i - 1])
                    i--
                    j--
                } else if (dp[i - 1][j] > dp[i][j - 1]) {
                    i--
                } else {
                    j--
                }
            }
            result.reverse()
            return result
        }
    }
}
