package desu.inugram.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.archive.ArchiveCache
import desu.inugram.helpers.archive.EditHistoryHelper
import desu.inugram.helpers.archive.EditHistoryHelper.EditEntry
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.ItemOptions

// entiny: one message's history when `message` is set, every edited message of the dialog otherwise
class EditHistoryActivity(
    dialogId: Long,
    account: Int,
    private val message: MessageObject? = null,
) : ArchiveChatFragment(dialogId, account) {

    private class VersionItem(
        val entry: EditEntry,
        val source: EditEntry,
        val label: CharSequence,
        val date: Long,
    )

    private val entries = ArrayList<EditEntry>()
    private val versions = ArrayList<VersionItem>()
    private val liveMessages = HashMap<Int, TLRPC.Message>()
    private var currentVersionIndex = -1

    private val wholeDialog: Boolean get() = message == null

    override val subtitle: String
        get() = if (message == null) LocaleController.getString(R.string.InuEditedArchive) else "#${message.id}"
    override val emptyText: Int get() = R.string.InuNoEditHistory
    override val clearable: Boolean get() = wholeDialog
    override val isDeletedArchive: Boolean get() = false

    override fun hasContent(): Boolean = entries.isNotEmpty()

    override fun onCleared() {
        entries.clear()
    }

    override fun clearArchive(ids: List<Long>, done: Runnable) {
        ArchiveCache.clearEditHistory(currentAccount, ids, done)
    }

    override fun onMediaDownloaded(entry: EditEntry, path: String) {
        val index = entries.indexOf(entry)
        if (index < 0) return
        entries[index] = entry.copy(mediaPath = path)
        refresh()
    }

    override fun labelOf(row: Row): CharSequence = versions[row.index].label

    override fun load() {
        if (wholeDialog) loadDialog() else loadMessage()
    }

    private fun loadDialog() {
        val storage = MessagesStorage.getInstance(currentAccount) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val stored = InuDatabaseHelper.loadEditHistoryInDialog(db, dialogId)
            val loadedEntries = stored.map { (msgId, row) ->
                EditEntry(row.date, row.text, row.mediaPath, row.entities, row.media, originalMessageId = msgId, rowId = row.rowId)
            }
            val live = try {
                InuDatabaseHelper.loadMessagesForArchive(db, dialogId, stored.mapTo(HashSet()) { it.first })
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "live messages load failed", e)
                emptyMap()
            }
            val peers = loadPeers(storage, live.values, listOf(dialogId))
            AndroidUtilities.runOnUIThread {
                if (isFinished) return@runOnUIThread
                applyPeers(peers)
                entries.clear()
                entries.addAll(loadedEntries)
                liveMessages.clear()
                liveMessages.putAll(live)
                onLoaded()
            }
        }
    }

    private fun loadMessage() {
        val target = message ?: return
        EditHistoryHelper.getEditHistoryAsync(currentAccount, dialogId, target.id) { list ->
            entries.clear()
            for (entry in list) entries.add(entry.copy(mediaPath = entry.mediaPath?.takeIf { it.isNotBlank() }))
            // entiny: raw message rather than messageText because entity offsets address the unformatted string
            val owner = target.messageOwner
            val currentText = owner?.message ?: ""
            val currentDate = when {
                (owner?.edit_date ?: 0) != 0 -> owner!!.edit_date.toLong()
                (owner?.date ?: 0) != 0 -> owner!!.date.toLong()
                else -> System.currentTimeMillis() / 1000
            }
            currentVersionIndex = -1
            if (currentText.isNotEmpty() || owner?.media != null) {
                currentVersionIndex = entries.size
                entries.add(EditEntry(currentDate, currentText, null, owner?.entities, owner?.media))
            }
            onLoaded()
        }
    }

    private fun onLoaded() {
        loaded = true
        refresh()
        scrollToEnd()
    }

    override fun buildRows() {
        buildVersions()
        resetRows()
        versions.forEachIndexed { i, item ->
            addRow(VIEW_LABEL, i, item.date)
            addRow(VIEW_MESSAGE, i, item.date)
        }
    }

    private fun buildVersions() {
        versions.clear()
        val groups = LinkedHashMap<Int, ArrayList<EditEntry>>()
        for (entry in entries) {
            groups.getOrPut(message?.id ?: entry.originalMessageId) { ArrayList() }.add(entry)
        }
        for ((msgId, list) in groups) {
            val liveMessage = if (wholeDialog) liveMessages[msgId] else message?.messageOwner
            val all = ArrayList(list)
            if (wholeDialog) liveMessage?.let { liveEntry(msgId, it) }?.let { all.add(it) }
            val author = authorOf(liveMessage)
            val shown = ArrayList<EditEntry>()
            for (entry in all) {
                if (shown.isNotEmpty() && EditHistoryHelper.changes(shown.last(), entry).isEmpty()) continue
                shown.add(entry)
            }
            shown.forEachIndexed { i, entry ->
                val previous = shown.getOrNull(i - 1)
                versions.add(
                    VersionItem(withDiff(entry, previous, shown.getOrNull(i + 1), author, msgId), entry, versionLabel(entry, previous), entry.timestamp)
                )
            }
        }
        if (wholeDialog) versions.sortBy { it.date }
    }

    private fun liveEntry(msgId: Int, live: TLRPC.Message): EditEntry? {
        val media = live.media?.takeIf { it !is TLRPC.TL_messageMediaEmpty }
        if (live.message.isNullOrEmpty() && media == null) return null
        val date = if (live.edit_date > 0) live.edit_date else live.date
        return EditEntry(date.toLong(), live.message ?: "", null, live.entities, media, originalMessageId = msgId)
    }

    private fun authorOf(live: TLRPC.Message?): Long {
        if (live == null) return if (DialogObject.isUserDialog(dialogId)) 0L else dialogId
        live.from_id?.let { DialogObject.getPeerDialogId(it) }?.takeIf { it != 0L }?.let { return it }
        if (DialogObject.isUserDialog(dialogId)) return if (live.out) userConfig.clientUserId else dialogId
        return dialogId
    }

    private fun versionLabel(entry: EditEntry, previous: EditEntry?): CharSequence {
        val time = LocaleController.formatString(R.string.InuEditedAtTime, formatTime(entry.timestamp))
        val labels = previous?.let { prev -> EditHistoryHelper.changes(prev, entry).map { LocaleController.getString(it) } }.orEmpty()
        val head = if (labels.isEmpty()) time else time + " · " + LocaleController.formatString(R.string.InuEditHistoryChanged, labels.joinToString(", "))
        val builder = SpannableStringBuilder(head)
        if (previous != null && InuConfig.SHOW_EDIT_HISTORY_DIFF.value) {
            val (removed, added) = diffRanges(previous.text, entry.text)
            appendChange(builder, 0xFFE5534B.toInt(), removed.map { previous.text.substring(it.first, it.last + 1) })
            appendChange(builder, 0xFF4CAF50.toInt(), added.map { entry.text.substring(it.first, it.last + 1) })
        }
        return builder
    }

    private fun appendChange(builder: SpannableStringBuilder, color: Int, words: List<String>) {
        if (words.isEmpty()) return
        val text = words.joinToString(" ").let { if (it.length > SUMMARY_LIMIT) it.take(SUMMARY_LIMIT) + "…" else it }
        builder.append('\n')
        val start = builder.length
        builder.append("■")
        builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(" ").append(text)
    }

    // entiny: the diff rides on strike/underline entities so versions stay ordinary chat bubbles
    private fun withDiff(entry: EditEntry, previous: EditEntry?, next: EditEntry?, authorId: Long, msgId: Int): EditEntry {
        var entities = entry.entities
        if (InuConfig.SHOW_EDIT_HISTORY_DIFF.value) {
            val added = previous?.let { diffRanges(it.text, entry.text).second }.orEmpty()
            val removed = next?.let { diffRanges(entry.text, it.text).first }.orEmpty()
            if (added.isNotEmpty() || removed.isNotEmpty()) {
                val merged = ArrayList<TLRPC.MessageEntity>(entities.orEmpty())
                for (r in added) merged.add(TLRPC.TL_messageEntityUnderline().apply { offset = r.first; length = r.last - r.first + 1 })
                for (r in removed) merged.add(TLRPC.TL_messageEntityStrike().apply { offset = r.first; length = r.last - r.first + 1 })
                entities = merged
            }
        }
        return entry.copy(entities = entities, fromId = authorId, originalMessageId = msgId)
    }

    // entiny: word-level LCS, returns char ranges removed from old and added to new
    private fun diffRanges(old: String, new: String): Pair<List<IntRange>, List<IntRange>> {
        if (old == new) return emptyList<IntRange>() to emptyList()
        val a = TOKEN.findAll(old).toList()
        val b = TOKEN.findAll(new).toList()
        if (a.size.toLong() * b.size > DIFF_LIMIT) return emptyList<IntRange>() to emptyList()
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            dp[i][j] = if (a[i].value == b[j].value) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val removed = ArrayList<IntRange>()
        val added = ArrayList<IntRange>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size && j < b.size && a[i].value == b[j].value) {
                i++
                j++
            } else if (j >= b.size || (i < a.size && dp[i + 1][j] >= dp[i][j + 1])) {
                if (a[i].value.isNotBlank()) removed.add(a[i].range)
                i++
            } else {
                if (b[j].value.isNotBlank()) added.add(b[j].range)
                j++
            }
        }
        return removed to added
    }

    override fun bindMessage(cell: ArchiveMessageCell, row: Row) {
        val item = versions.getOrNull(row.index) ?: return
        cell.show(item.entry, row, messageOf(item.entry), null, false)
    }

    override fun showMenu(cell: ArchiveMessageCell) {
        val item = cell.row?.index?.let(versions::getOrNull) ?: return
        val source = item.source
        val savedMedia = source.mediaPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        ItemOptions.makeOptions(this, cell)
            .addIf(source.text.isNotEmpty(), R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) { copyText(source.text) }
            .addIf(savedMedia != null, R.drawable.msg_openin, LocaleController.getString(R.string.OpenInExternalApp)) {
                savedMedia?.let { AndroidUtilities.openForView(it, it.name, null, parentActivity, null, false) }
            }
            .addIf(source.rowId > 0, R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) { deleteRevision(source) }
            .setGravity(if (cell.messageObject?.isOutOwner == true) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    private fun deleteRevision(entry: EditEntry) {
        val position = entries.indexOfFirst { it === entry }
        if (position < 0 || position == currentVersionIndex) return
        entries.removeAt(position)
        if (currentVersionIndex > position) currentVersionIndex--
        refresh()
        EditHistoryHelper.deleteEditHistoryEntry(currentAccount, dialogId, message?.id ?: entry.originalMessageId, entry.rowId)
    }

    companion object {
        private const val TAG = "EditHistory"
        private val TOKEN = Regex("\\s+|[\\p{L}\\p{N}_]+|.", RegexOption.DOT_MATCHES_ALL)
        private const val DIFF_LIMIT = 1_500_000L
        private const val SUMMARY_LIMIT = 60

        fun forMessage(msg: MessageObject) = EditHistoryActivity(msg.dialogId, msg.currentAccount, msg)
    }
}
