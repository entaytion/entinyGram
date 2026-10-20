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
import desu.inugram.helpers.archive.ArchiveCache
import desu.inugram.helpers.archive.ArchiveMediaHelper
import desu.inugram.helpers.archive.DeletedMessagesHelper
import desu.inugram.helpers.archive.EditHistoryHelper
import desu.inugram.helpers.archive.EditHistoryHelper.EditEntry
import desu.inugram.helpers.InuDatabaseHelper
import java.io.File
import java.util.ArrayList
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Emoji
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Components.AnimatedEmojiSpan
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

    private class Row(val type: Int, val index: Int, val date: Long)

    private val isDeletedArchive: Boolean
        get() = deletedArchiveAuthor != null || deletedArchiveDialog

    private val showSenders: Boolean
        get() {
            if (!isDeletedArchive || deletedArchiveAuthor != null) return false
            val dialogId = targetMessageObject.getDialogId()
            if (!DialogObject.isChatDialog(dialogId)) return false
            val chat = messagesController.getChat(-dialogId)
            return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat)
        }

    private val historyEntries = ArrayList<EditEntry>()
    private val editEvents = ArrayList<EditEvent>()
    private val rows = ArrayList<Row>()
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
        val cancel = ArchiveMediaHelper.downloadArchiveMedia(currentAccount, message.messageOwner, entry.rowId, isDeletedArchive) { path ->
            mediaDownloads.remove(entry)
            if (!isFinished) {
                if (path == null) {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
                } else {
                    val index = historyEntries.indexOf(entry)
                    if (index >= 0) {
                        historyEntries[index] = entry.copy(mediaPath = path)
                        rebuildRows()
                        listView?.adapter?.notifyDataSetChanged()
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
                val layoutManager = listView?.layoutManager as? LinearLayoutManager
                val anchorPosition = layoutManager?.findFirstVisibleItemPosition() ?: -1
                val anchorTop = layoutManager?.findViewByPosition(anchorPosition)?.top ?: 0
                val anchorIndex = (anchorPosition until rows.size).firstNotNullOfOrNull { p -> rows.getOrNull(p)?.index?.takeIf { it >= 0 } } ?: -1
                historyEntries.addAll(0, page.asReversed())
                loaded = true
                rebuildRows()
                listView?.adapter?.notifyDataSetChanged()
                if (first) {
                    updateEmptyView()
                    scrollToEnd()
                } else if (page.isNotEmpty() && anchorIndex >= 0) {
                    val target = rows.indexOfFirst { it.type != VIEW_DATE && it.index == anchorIndex + page.size }
                    if (target >= 0) layoutManager?.scrollToPositionWithOffset(target, anchorTop)
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
                val rows = InuDatabaseHelper.loadEditHistoryInDialog(db, dialogId)
                val entries = rows.map { (msgId, row) ->
                    EditEntry(row.date, row.text, row.mediaPath, row.entities, row.media, originalMessageId = msgId, rowId = row.rowId)
                }
                val live = try {
                    InuDatabaseHelper.loadMessagesForArchive(db, dialogId, rows.mapTo(HashSet()) { it.first })
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
        EditHistoryHelper.getEditHistoryAsync(currentAccount, dialogId, msgId) { list ->
            historyEntries.clear()
            for (entry in list) {
                historyEntries.add(entry.copy(mediaPath = entry.mediaPath?.takeIf { it.isNotBlank() }))
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
        rebuildRows()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        scrollToEnd()
    }

    private fun scrollToEnd() {
        val count = listView?.adapter?.itemCount ?: 0
        if (count > 0) listView?.scrollToPosition(count - 1)
    }

    private fun rebuildRows() {
        rebuildEvents()
        rows.clear()
        var lastDay = Int.MIN_VALUE
        fun add(type: Int, index: Int, date: Long) {
            val day = dayKey(date)
            if (date > 0 && day != lastDay) {
                rows.add(Row(VIEW_DATE, -1, date))
                lastDay = day
            }
            rows.add(Row(type, index, date))
        }
        if (isDeletedArchive) {
            historyEntries.forEachIndexed { i, e -> add(VIEW_DELETED, i, e.timestamp) }
        } else {
            editEvents.forEachIndexed { i, e -> add(VIEW_COMPARE, i, e.date) }
        }
    }

    private fun dayKey(date: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = date * 1000L
        return calendar.get(java.util.Calendar.YEAR) * 1000 + calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun formatDayHeader(date: Long): String {
        val today = dayKey(System.currentTimeMillis() / 1000L)
        return when (dayKey(date)) {
            today -> LocaleController.getString(R.string.MessageScheduleToday)
            dayKey(System.currentTimeMillis() / 1000L - 86400L) -> LocaleController.getString(R.string.Yesterday).replaceFirstChar { it.titlecase() }
            else -> LocaleController.formatDateChat(date, true)
        }
    }

    private fun styledText(text: CharSequence, entities: ArrayList<TLRPC.MessageEntity>?, view: TextView): CharSequence {
        val builder = SpannableStringBuilder(text)
        if (!entities.isNullOrEmpty()) {
            try {
                MessageObject.addEntitiesToText(builder, entities, false, false, false, true)
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "entities render failed", e)
            }
        }
        var result: CharSequence = Emoji.replaceEmoji(builder, view.paint.fontMetricsInt, false)
        if (!entities.isNullOrEmpty()) result = MessageObject.replaceAnimatedEmoji(result, entities, view.paint.fontMetricsInt)
        return result
    }

    private fun emojiTextView(context: Context, size: Float, color: Int): TextView = AnimatedEmojiSpan.TextViewEmojis(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(Theme.getColor(color))
    }

    private fun formatTime(date: Long): String = LocaleController.getInstance().getFormatterDay().format(date * 1000L)

    private fun changeLabels(a: EditEntry, b: EditEntry): List<String> =
        EditHistoryHelper.changes(a, b).map { LocaleController.getString(it) }

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
                if (EditHistoryHelper.changes(before, after).isEmpty()) continue
                editEvents.add(EditEvent(msgId, before, after, author, if (after.timestamp > 0) after.timestamp else before.timestamp))
            }
            if (!hasCurrent && versions.isNotEmpty()) {
                val last = versions.last()
                editEvents.add(EditEvent(msgId, last, null, author, last.timestamp))
            }
        }
        if (editedArchiveDialog) editEvents.sortBy { it.date }
    }

    private var currentVersionIndex = -1

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
                }
            }
        })
        if (deletedArchiveDialog || editedArchiveDialog) {
            actionBar.createMenu().addItem(MENU_CLEAR_ARCHIVE, R.drawable.inu_tabler_trash_x)
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

        val recycler = RecyclerListView(context).apply {
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
                        rebuildRows()
                        listView?.adapter?.notifyDataSetChanged()
                        updateEmptyView()
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone)).show()
                    }
                }
                if (editedArchiveDialog) {
                    ArchiveCache.clearEditHistory(currentAccount, ids, done)
                } else {
                    ArchiveCache.clearCache(currentAccount, ids, deletedOnly = true, onDone = done)
                }
            }
            .create())
    }

    private fun copyText(text: String) {
        AndroidUtilities.addToClipboard(text)
        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.MessageCopied)).show()
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

    private fun showDeletedMenu(cell: DeletedCardCell) {
        val index = cell.index
        val entry = historyEntries.getOrNull(index) ?: return
        ItemOptions.makeOptions(this, cell)
            .addIf(entry.text.isNotEmpty(), R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                copyText(entry.text)
            }
            .addIf(entry.originalMessageId != 0, R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                deleteArchived(index)
            }
            .show()
    }

    private fun openDeletedMedia(entry: EditEntry) {
        val saved = entry.mediaPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        if (saved != null) {
            AndroidUtilities.openForView(saved, saved.name, null, parentActivity, null, false)
            return
        }
        val message = entry.message ?: return
        if (message.media == null || message.media is TLRPC.TL_messageMediaEmpty) return
        val messageObject = MessageObject(currentAccount, message, false, false)
        EditHistoryHelper.markAsHistoryPreview(messageObject)
        openArchiveMedia(entry, messageObject)
    }

    private fun deleteArchived(position: Int) {
        if (position < 0 || position >= historyEntries.size) return
        val entry = historyEntries.removeAt(position)
        deletedOffset = maxOf(0, deletedOffset - 1)
        rebuildRows()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        DeletedMessagesHelper.deletePermanently(currentAccount, targetMessageObject.getDialogId(), listOf(entry.originalMessageId))
    }

    private fun deleteRevision(entry: EditEntry) {
        val position = historyEntries.indexOfFirst { it === entry }
        if (position < 0 || position == currentVersionIndex) return
        historyEntries.removeAt(position)
        if (currentVersionIndex > position) currentVersionIndex--
        rebuildRows()
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        EditHistoryHelper.deleteEditHistoryEntry(
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

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemViewType != VIEW_DATE

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell: View = when (viewType) {
                VIEW_COMPARE -> EditCompareCell(context)
                VIEW_DELETED -> DeletedCardCell(context)
                else -> DateHeaderCell(context)
            }
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows.getOrNull(position) ?: return
            when (val view = holder.itemView) {
                is DateHeaderCell -> view.bind(formatDayHeader(row.date))
                is EditCompareCell -> editEvents.getOrNull(row.index)?.let(view::bind)
                is DeletedCardCell -> historyEntries.getOrNull(row.index)?.let { view.bind(it, row.index) }
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
        private val sharedMedia = MediaRow(context)
        private val beforeLabel = label(context, R.string.InuEditHistoryBefore, Theme.key_text_RedRegular)
        private val beforeText = emojiTextView(context, 15f, Theme.key_windowBackgroundWhiteBlackText)
        private val beforeMedia = MediaRow(context)
        private val afterLabel = label(context, R.string.InuEditHistoryAfter, Theme.key_windowBackgroundWhiteGreenText)
        private val afterText = emojiTextView(context, 15f, Theme.key_windowBackgroundWhiteBlackText)
        private val afterMedia = MediaRow(context)
        private val changesView = textView(context, 13f, Theme.key_windowBackgroundWhiteBlueText)

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
            card.addView(sharedMedia, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 0f))
            card.addView(beforeLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 2f))
            card.addView(beforeText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            card.addView(beforeMedia, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
            card.addView(afterLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 2f))
            card.addView(afterText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            card.addView(afterMedia, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
            card.addView(changesView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 10f, 4f, 10f, 4f))

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

        private fun setBlock(view: TextView, text: CharSequence?, entities: ArrayList<TLRPC.MessageEntity>? = null) {
            if (text.isNullOrEmpty()) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = styledText(text, entities, view)
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
            dateView.text = if (e.date > 0) LocaleController.formatString(R.string.InuEditedAtTime, formatTime(e.date)) else ""

            val after = e.after
            val (oldText, newText) = if (after != null) diffPair(e.before.text, after.text) else Pair<CharSequence, CharSequence>(e.before.text, "")
            setBlock(beforeText, oldText, e.before.entities)
            // entiny: an unchanged attachment is shown once above both versions, like media above a caption
            val sameMedia = after != null && R.string.InuEditFieldMedia !in EditHistoryHelper.changes(e.before, after)
            if (sameMedia) {
                sharedMedia.bind(after!!.media ?: e.before.media, e.before.mediaPath ?: after.mediaPath)
            } else {
                sharedMedia.bind(null, null)
            }
            beforeMedia.bind(if (sameMedia) null else e.before.media, if (sameMedia) null else e.before.mediaPath)
            if (after == null) {
                setBlock(afterText, LocaleController.getString(R.string.InuEditHistoryCurrentUnknown))
                afterText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                afterMedia.bind(null, null)
                changesView.visibility = View.GONE
            } else {
                setBlock(afterText, newText, after.entities)
                afterText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                afterMedia.bind(if (sameMedia) null else after.media, if (sameMedia) null else after.mediaPath)
                val labels = changeLabels(e.before, after)
                setBlock(changesView, if (labels.isEmpty()) null else "✎ " + LocaleController.formatString(R.string.InuEditHistoryChanged, labels.joinToString(", ")))
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

    private inner class PreviewImageView(context: Context) : BackupImageView(context) {
        var ratio = 1f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = (width / ratio).toInt().coerceIn(AndroidUtilities.dp(120f), AndroidUtilities.dp(360f))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private inner class MediaRow(context: Context) : LinearLayout(context) {
        private val previewView = PreviewImageView(context).apply { setRoundRadius(AndroidUtilities.dp(10f)) }
        private val rowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val imageView = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(6f)) }
        private val infoView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private var file: File? = null

        init {
            orientation = VERTICAL
            addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            rowView.addView(imageView, LayoutHelper.createLinear(40, 40, 0f, 0f, 8f, 0f))
            rowView.addView(infoView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(rowView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
            setOnClickListener {
                file?.let { AndroidUtilities.openForView(it, it.name, null, parentActivity, null, false) }
            }
        }

        fun bind(mediaIn: TLRPC.MessageMedia?, path: String?) {
            val media = mediaIn?.takeIf { it !is TLRPC.TL_messageMediaEmpty }
            val saved = path?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
            file = saved
            isClickable = saved != null
            if (media == null && saved == null) {
                visibility = View.GONE
                return
            }
            visibility = View.VISIBLE
            val document = media?.document
            val photo = media?.photo
            val savedImage = saved != null && saved.extension.lowercase() in IMAGE_EXTENSIONS
            val visual = photo != null || savedImage || (document != null && (MessageObject.isVideoDocument(document) || MessageObject.isGifDocument(document)))
            val thumbs = photo?.sizes ?: document?.thumbs

            val parts = ArrayList<String>()
            if (!visual && media != null) DeletedMessagesHelper.archiveText(null, TLRPC.TL_message().apply { this.media = media }).takeIf { it.isNotEmpty() }?.let(parts::add)
            if (document != null) {
                if (!visual) FileLoader.getDocumentFileName(document)?.takeIf { it.isNotEmpty() }?.let(parts::add)
                if (document.size > 0) parts.add(AndroidUtilities.formatFileSize(document.size))
                val duration = MessageObject.getDocumentDuration(document)
                if (duration > 0) parts.add(AndroidUtilities.formatShortDuration(duration.toInt()))
            }
            if (media is TLRPC.TL_messageMediaWebPage) media.webpage?.let { (it.title ?: it.url)?.takeIf { t -> t.isNotEmpty() }?.let { t -> parts.add("🔗 $t") } }
            if ((media?.ttl_seconds ?: 0) > 0) parts.add("⏱ ${media?.ttl_seconds}s")
            if (media?.spoiler == true) parts.add("▒")
            if (parts.isEmpty() && saved != null && !visual) parts.add(saved.name)
            infoView.text = Emoji.replaceEmoji(parts.joinToString(" · "), infoView.paint.fontMetricsInt, false)

            if (visual) {
                bindPreview(photo, document, thumbs, saved.takeIf { savedImage }, media)
                imageView.imageReceiver.clearImage()
                imageView.visibility = View.GONE
                rowView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
                return
            }
            previewView.imageReceiver.clearImage()
            previewView.visibility = View.GONE
            rowView.visibility = View.VISIBLE
            when {
                !thumbs.isNullOrEmpty() -> {
                    imageView.visibility = View.VISIBLE
                    val small = FileLoader.getClosestPhotoSizeWithSize(thumbs, 90)
                    val stripped = thumbs.firstOrNull { it is TLRPC.TL_photoStrippedSize }
                    imageView.setImage(location(small, photo, document), "80_80", location(stripped, photo, document), "80_80_b", 0, media)
                }
                else -> {
                    imageView.imageReceiver.clearImage()
                    imageView.visibility = View.GONE
                }
            }
        }

        private fun bindPreview(photo: TLRPC.Photo?, document: TLRPC.Document?, thumbs: ArrayList<TLRPC.PhotoSize>?, saved: File?, media: TLRPC.MessageMedia?) {
            previewView.visibility = View.VISIBLE
            val full = thumbs?.let { FileLoader.getClosestPhotoSizeWithSize(it, AndroidUtilities.getPhotoSize()) }
            val stripped = thumbs?.firstOrNull { it is TLRPC.TL_photoStrippedSize }
            val (w, h) = previewSize(full, document, saved)
            previewView.ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f
            previewView.requestLayout()
            // entiny: spoilered media keeps its blur in the archive too
            val filter = if (media?.spoiler == true) "b" else null
            if (saved != null) {
                previewView.setImage(saved.absolutePath, filter, null as android.graphics.drawable.Drawable?)
            } else {
                previewView.setImage(location(full, photo, document), filter, location(stripped, photo, document), "b", 0, media)
            }
        }

        private fun previewSize(size: TLRPC.PhotoSize?, document: TLRPC.Document?, saved: File?): Pair<Int, Int> {
            if (size != null && size.w > 0 && size.h > 0) return size.w to size.h
            document?.attributes?.firstNotNullOfOrNull { it as? TLRPC.TL_documentAttributeVideo }?.let { if (it.w > 0 && it.h > 0) return it.w to it.h }
            if (saved != null) {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(saved.absolutePath, options)
                return options.outWidth to options.outHeight
            }
            return 0 to 0
        }

        private fun location(size: TLRPC.PhotoSize?, photo: TLRPC.Photo?, document: TLRPC.Document?): ImageLocation? = when {
            size == null -> null
            photo != null -> ImageLocation.getForPhoto(size, photo)
            else -> ImageLocation.getForDocument(size, document)
        }
    }

    private inner class DateHeaderCell(context: Context) : FrameLayout(context) {
        private val textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_chat_serviceText))
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(5f))
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_chat_serviceBackground))
        }

        init {
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_HORIZONTAL, 0f, 6f, 0f, 6f))
        }

        fun bind(text: String) {
            textView.text = text
        }
    }

    private inner class DeletedCardCell(context: Context) : FrameLayout(context) {
        var index = -1
            private set
        private var entry: EditEntry? = null

        private val avatarDrawable = AvatarDrawable()
        private val avatarView = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(18f)) }
        private val nameView = text(context, 15f, Theme.key_windowBackgroundWhiteBlackText).apply {
            typeface = AndroidUtilities.bold()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val deletedView = text(context, 13f, Theme.key_text_RedRegular)
        private val replyView = text(context, 13f, Theme.key_windowBackgroundWhiteGrayText).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val bodyView = emojiTextView(context, 15f, Theme.key_windowBackgroundWhiteBlackText)
        private val mediaView = MediaRow(context)
        private val buttonsView = text(context, 13f, Theme.key_windowBackgroundWhiteGrayText)
        private val sentView = text(context, 12f, Theme.key_windowBackgroundWhiteGrayText)

        init {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f))
                background = Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), Theme.getColor(Theme.key_windowBackgroundWhite))
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(avatarView, LayoutHelper.createLinear(36, 36, 0f, 0f, 10f, 0f))
            val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            titles.addView(deletedView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            card.addView(replyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))
            card.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))
            card.addView(mediaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))
            card.addView(buttonsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))
            card.addView(sentView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 10f, 4f, 10f, 4f))

            card.setOnClickListener { entry?.let { openDeletedMedia(it) } }
            card.setOnLongClickListener {
                showDeletedMenu(this)
                true
            }
        }

        private fun text(context: Context, size: Float, color: Int) = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            setTextColor(Theme.getColor(color))
        }

        private fun setText(view: TextView, value: CharSequence?, entities: ArrayList<TLRPC.MessageEntity>? = null) {
            if (value.isNullOrEmpty()) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = styledText(value, entities, view)
            }
        }

        fun bind(e: EditEntry, position: Int) {
            entry = e
            index = position
            val message = e.message
            val senderId = e.fromId.takeIf { it != 0L } ?: authorOf(message)
            val author = if (senderId != 0L) messagesController.getUserOrChat(senderId) else null
            nameView.text = when (author) {
                is TLRPC.User -> UserObject.getUserName(author)
                is TLRPC.Chat -> author.title ?: ""
                else -> ""
            }
            nameView.visibility = if (nameView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (author != null) {
                avatarDrawable.setInfo(currentAccount, author)
                avatarView.setForUserOrChat(author, avatarDrawable)
                avatarView.visibility = View.VISIBLE
            } else {
                avatarView.visibility = View.GONE
            }
            deletedView.text = if (e.timestamp > 0) LocaleController.formatString(R.string.InuDeletedAtTime, formatTime(e.timestamp)) else LocaleController.getString(R.string.InuDeletedMessage)

            val reply = message?.replyMessage
            setText(replyView, reply?.let { "↩ " + DeletedMessagesHelper.archiveText(it.message, it) })

            if (message != null) setText(bodyView, message.message, message.entities) else setText(bodyView, e.text)
            mediaView.bind(message?.media, e.mediaPath)
            val buttons = (message?.reply_markup as? TLRPC.TL_replyInlineMarkup)?.rows?.flatMap { it.buttons }?.mapNotNull { it.text?.takeIf { t -> t.isNotEmpty() } }
            setText(buttonsView, if (buttons.isNullOrEmpty()) null else "⌨ " + buttons.joinToString(" · "))

            val sent = message?.date ?: 0
            setText(sentView, if (sent > 0) LocaleController.formatString(R.string.InuSentAtTime, LocaleController.formatDateAudio(sent.toLong(), true)) else null)
        }
    }

    companion object {
        private const val TAG = "AyuMessageHistory"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val TOKEN = Regex("\\s+|[\\p{L}\\p{N}_]+|.", RegexOption.DOT_MATCHES_ALL)
        private const val DIFF_LIMIT = 1_500_000L
        private const val PAGE_SIZE = 200
        private const val VIEW_COMPARE = 1
        private const val VIEW_DELETED = 2
        private const val VIEW_DATE = 3
        private const val MENU_CLEAR_ARCHIVE = 3

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

    }
}
