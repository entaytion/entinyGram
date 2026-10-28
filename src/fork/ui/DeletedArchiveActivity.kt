package desu.inugram.ui

import desu.inugram.helpers.archive.ArchiveStore
import android.view.Gravity
import androidx.recyclerview.widget.LinearLayoutManager
import desu.inugram.helpers.archive.ArchiveCache
import desu.inugram.helpers.archive.DeletedMessagesHelper
import desu.inugram.helpers.archive.EditHistoryHelper.EditEntry
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.ItemOptions

class DeletedArchiveActivity(dialogId: Long, account: Int) : ArchiveChatFragment(dialogId, account) {

    private val entries = ArrayList<EditEntry>()
    private var loadingPage = false
    private var hasMore = false
    private var offset = 0
    private val groups = HashMap<Long, MessageObject.GroupedMessages>()

    override val subtitle: String get() = LocaleController.getString(R.string.InuDeletedArchive)
    override val emptyText: Int get() = R.string.InuDeletedArchiveEmpty
    override val clearable: Boolean get() = true
    override val isDeletedArchive: Boolean get() = true
    override val overlayActionBar: Boolean get() = true

    private val showSenders: Boolean
        get() {
            if (!DialogObject.isChatDialog(dialogId)) return false
            val chat = messagesController.getChat(-dialogId)
            return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat)
        }

    override fun hasContent(): Boolean = entries.isNotEmpty()

    override fun onCleared() {
        entries.clear()
        hasMore = false
    }

    override fun clearArchive(ids: List<Long>, done: Runnable) {
        ArchiveCache.clearCache(currentAccount, ids, deletedOnly = true, onDone = done)
    }

    override fun onNearTop() {
        if (hasMore && !loadingPage) load()
    }

    override fun onTimePressed(message: MessageObject) {
        val context = parentActivity ?: return
        DeletedMessagesHelper.showDeletionTimeBulletin(context, null, message)
    }

    override fun onMediaDownloaded(entry: EditEntry, path: String) {
        val index = entries.indexOf(entry)
        if (index < 0) return
        entries[index] = entry.copy(mediaPath = path)
        refresh()
    }

    private fun resolveReplies(db: org.telegram.SQLite.SQLiteDatabase, messages: List<TLRPC.Message>) {
        val byDialog = HashMap<Long, HashSet<Int>>()
        for (m in messages) {
            val replyId = m.reply_to?.reply_to_msg_id ?: 0
            if (replyId != 0 && m.replyMessage == null) {
                byDialog.getOrPut(MessageObject.getReplyToDialogId(m)) { HashSet() }.add(replyId)
            }
        }
        val found = HashMap<Pair<Long, Int>, TLRPC.Message>()
        for ((did, ids) in byDialog) {
            try {
                for ((id, m) in ArchiveStore.loadMessagesForArchive(db, did, ids)) found[did to id] = m
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive reply load failed", e)
            }
        }
        for (m in messages) {
            val replyId = m.reply_to?.reply_to_msg_id ?: 0
            if (replyId != 0 && m.replyMessage == null) m.replyMessage = found[MessageObject.getReplyToDialogId(m) to replyId]
        }
    }

    override fun load() {
        if (loadingPage) return
        loadingPage = true
        val pageOffset = offset
        val storage = MessagesStorage.getInstance(currentAccount)
        storage.storageQueue.postRunnable {
            val db = storage.database
            val page = if (db == null) emptyList() else ArchiveStore.deletedMessagesInDialog(db, dialogId, PAGE_SIZE, pageOffset).map {
                EditEntry(it.date.toLong(), it.text, it.mediaPath, originalMessageId = it.msgId, fromId = it.fromId, message = it.message)
            }
            val messages = page.mapNotNull { it.message }
            if (db != null) resolveReplies(db, messages)
            val peers = loadPeers(storage, messages + messages.mapNotNull { it.replyMessage }, page.map { it.fromId })
            AndroidUtilities.runOnUIThread {
                loadingPage = false
                if (isFinished) return@runOnUIThread
                applyPage(page, peers)
            }
        }
    }

    private fun applyPage(page: List<EditEntry>, peers: Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>>) {
        applyPeers(peers)
        for (entry in page) {
            val path = entry.mediaPath?.takeIf { it.isNotBlank() } ?: continue
            ArchiveCache.rememberDeletedMediaPath(currentAccount, dialogId, entry.originalMessageId, path)
        }
        offset += page.size
        hasMore = page.size == PAGE_SIZE
        val first = !loaded
        val layoutManager = listView?.layoutManager as? LinearLayoutManager
        val anchorPosition = layoutManager?.findFirstVisibleItemPosition() ?: -1
        val anchorTop = layoutManager?.findViewByPosition(anchorPosition)?.top ?: 0
        val anchorEntry = (anchorPosition until rows.size).firstNotNullOfOrNull { position ->
            rows.getOrNull(position)?.index?.takeIf { it >= 0 }?.let(entries::getOrNull)
        }
        entries.addAll(page)
        entries.sortWith(compareBy<EditEntry> { sentDate(it) }.thenBy { it.originalMessageId })
        loaded = true
        refresh()
        if (first) {
            scrollToEnd()
        } else if (page.isNotEmpty() && anchorEntry != null) {
            val target = rows.indexOfFirst { it.type == VIEW_MESSAGE && entries.getOrNull(it.index) === anchorEntry }
            if (target >= 0) layoutManager?.scrollToPositionWithOffset(target, anchorTop)
        }
    }

    override fun buildRows() {
        groups.clear()
        resetRows()
        entries.forEachIndexed { index, entry ->
            val previous = entries.getOrNull(index - 1)
            val next = entries.getOrNull(index + 1)
            addRow(
                VIEW_MESSAGE,
                index,
                sentDate(entry),
                next?.let { connected(entry, it) } == true,
                previous?.let { connected(it, entry) } == true,
            )
        }
    }

    private fun sentDate(entry: EditEntry): Long = entry.message?.date?.takeIf { it > 0 }?.toLong() ?: entry.timestamp

    private fun connected(first: EditEntry, second: EditEntry): Boolean {
        val groupedId = first.message?.grouped_id ?: 0L
        if (groupedId != 0L && groupedId == second.message?.grouped_id) return true
        if (first.message?.reply_markup is TLRPC.TL_replyInlineMarkup || second.message?.reply_markup is TLRPC.TL_replyInlineMarkup) return false
        return senderKey(first) == senderKey(second) &&
                dayKey(sentDate(first)) == dayKey(sentDate(second)) &&
                kotlin.math.abs(sentDate(first) - sentDate(second)) <= RUN_SECONDS
    }

    private fun senderKey(entry: EditEntry): Long {
        val message = entry.message
        if (message != null) {
            message.from_id?.let { DialogObject.getPeerDialogId(it) }?.takeIf { it != 0L }?.let { return it }
            if (message.out) return userConfig.clientUserId
        }
        entry.fromId.takeIf { it != 0L }?.let { return it }
        return if (DialogObject.isUserDialog(dialogId)) dialogId else 0L
    }

    override fun spanSizeAt(position: Int): Int {
        val row = rows.getOrNull(position)?.takeIf { it.type == VIEW_MESSAGE } ?: return FULL_SPAN
        val message = entries.getOrNull(row.index)?.let(::messageOf) ?: return FULL_SPAN
        return groupOf(message)?.positions?.get(message)?.spanSize ?: FULL_SPAN
    }

    override fun bindMessage(cell: ArchiveMessageCell, row: Row) {
        val entry = entries.getOrNull(row.index) ?: return
        val message = messageOf(entry)
        cell.show(entry, row, message, groupOf(message), showSenders)
    }

    private fun groupOf(message: MessageObject): MessageObject.GroupedMessages? {
        val groupedId = message.messageOwner?.grouped_id ?: 0L
        if (groupedId == 0L) return null
        groups[groupedId]?.let { return it }
        val members = entries.filter { it.message?.grouped_id == groupedId }
        if (members.size < 2) return null
        return MessageObject.GroupedMessages().apply {
            this.groupId = groupedId
            members.forEach { messages.add(messageOf(it)) }
            calculate()
            groups[groupedId] = this
        }
    }

    override fun showMenu(cell: ArchiveMessageCell) {
        val entry = cell.entry ?: return
        val message = cell.messageObject ?: return
        val index = cell.row?.index ?: return
        ItemOptions.makeOptions(this, cell)
            .add(R.drawable.msg_openin, LocaleController.getString(R.string.InuShowInChat)) { showInChat(message) }
            .addIf(entry.text.isNotEmpty(), R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) { copyText(entry.text) }
            .addIf(entry.originalMessageId != 0, R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) { deleteArchived(index) }
            .setGravity(if (message.isOutOwner) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    private fun deleteArchived(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        val groupedId = entry.message?.grouped_id ?: 0L
        val ids = if (groupedId != 0L) {
            entries.filter { it.message?.grouped_id == groupedId }.map { it.originalMessageId }
        } else {
            listOf(entry.originalMessageId)
        }
        val idSet = ids.toHashSet()
        entries.removeAll { it.originalMessageId in idSet }
        offset = maxOf(0, offset - ids.size)
        refresh()
        DeletedMessagesHelper.purge(currentAccount, dialogId, ids)
    }

    companion object {
        private const val TAG = "DeletedArchive"
        private const val PAGE_SIZE = 200
        private const val RUN_SECONDS = 5 * 60
    }
}
