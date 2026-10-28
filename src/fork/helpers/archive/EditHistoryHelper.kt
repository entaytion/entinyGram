package desu.inugram.helpers.archive

import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory

object EditHistoryHelper {
    private const val TAG = "EditHistoryHelper"

    // entiny: history preview objects avoid stamping history rows with current message's deleted state
    private val historyPreviewObjects: MutableSet<MessageObject> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<MessageObject, Boolean>())

    data class EditEntry(
        val timestamp: Long,
        val text: String,
        val mediaPath: String? = null,
        val entities: ArrayList<TLRPC.MessageEntity>? = null,
        val media: TLRPC.MessageMedia? = null,
        val originalMessageId: Int = 0,
        val fromId: Long = 0L,
        val message: TLRPC.Message? = null,
        val rowId: Long = 0L,
    )

    @JvmStatic
    fun isSaveEditedEnabled(): Boolean = InuConfig.SAVE_EDITED_MESSAGES.value

    @JvmStatic
    fun isEdited(msg: MessageObject?): Boolean {
        val owner = msg?.messageOwner ?: return false
        return (owner.flags and TLRPC.MESSAGE_FLAG_EDITED) != 0 || owner.edit_date != 0
    }

    // entiny: a revision is identified by the date of that version, so every source of the same edit lands on one row
    private fun revisionOf(message: TLRPC.Message): EditEntry =
        EditEntry(revisionDate(message.edit_date, message.date), message.message ?: "", null, message.entities, message.media)

    private fun revisionDate(editDate: Int, date: Int): Long = (if (editDate != 0) editDate else date).toLong()

    @JvmStatic
    fun onStoredEdit(account: Int, oldMessage: TLRPC.Message?, newMessage: TLRPC.Message) {
        if (!isSaveEditedEnabled()) return
        val dialogId = MessageObject.getDialogId(newMessage)
        val old = oldMessage?.let { revisionOf(it) }
            ?: ArchiveCache.shadow(account, dialogId, newMessage.id)?.let {
                EditEntry(revisionDate(it.editDate, it.date), it.text, null, it.entities, it.media)
            }
        ArchiveCache.updateShadow(account, newMessage)
        if (old == null || newMessage.edit_date <= old.timestamp) return
        val changed = changes(old, revisionOf(newMessage))
        if (changed.isEmpty()) return
        record(account, dialogId, newMessage.id, old, oldMessage, R.string.InuEditFieldMedia in changed)
    }

    @JvmStatic
    fun recordOwnEdit(account: Int, obj: MessageObject?, newText: String?, newEntities: ArrayList<TLRPC.MessageEntity>?, newMedia: TLRPC.MessageMedia?) {
        if (!isSaveEditedEnabled()) return
        val owner = obj?.messageOwner ?: return
        val old = revisionOf(owner)
        val edited = EditEntry(0L, newText ?: old.text, null, newEntities ?: owner.entities, newMedia ?: owner.media)
        if (changes(old, edited).isEmpty()) return
        record(account, obj.dialogId, obj.id, old, owner, false)
    }

    private fun record(account: Int, dialogId: Long, msgId: Int, revision: EditEntry, source: TLRPC.Message?, copyMedia: Boolean) {
        if (!ArchiveScope.allows(account, dialogId, source, ArchiveScope.Kind.EDITED)) return
        if (revision.text.isEmpty() && !hasMedia(revision.media)) return
        val kept = revision.copy(
            entities = if (revision.entities.isNullOrEmpty()) null else ArrayList(revision.entities),
            media = ArchiveStore.cloneMedia(revision.media),
        )
        synchronized(ArchiveCache.cacheLock) {
            val list = cachedRevisions(account, dialogId, msgId)
            if (list.any { it.timestamp == kept.timestamp }) return
            list.add(kept)
            val ids = ArchiveCache.editHistoryIds.get(account.toLong())
                ?: LongSparseArray<ArchiveCache.DeletedIdSet>().also { ArchiveCache.editHistoryIds.put(account.toLong(), it) }
            (ids.get(dialogId) ?: ArchiveCache.DeletedIdSet().also { ids.put(dialogId, it) }).add(msgId, 0L)
        }
        val mediaCopy = if (copyMedia) ArchiveMediaHelper.planNewMediaCopy(account, source) else null
        val storage = MessagesStorage.getInstance(account) ?: return
        val persist = Runnable {
            val db = storage.database ?: return@Runnable
            try {
                val date = kept.timestamp.toInt()
                val inserted = ArchiveStore.saveEditHistory(db, dialogId, msgId, kept.text, date, null, kept.entities, kept.media)
                synchronized(ArchiveCache.cacheLock) {
                    ArchiveCache.editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong())
                }
                if (!inserted || mediaCopy == null) return@Runnable
                val rowId = ArchiveMediaHelper.latestEditRowId(db, dialogId, msgId, date)
                if (rowId > 0) ArchiveMediaHelper.copyThenStore(account, "e:$account:$rowId", mediaCopy) { copyDb, copied ->
                    val stored = ArchiveMediaHelper.updateMediaPath(copyDb, "inu_edit_history", "rowid = $rowId", copied)
                    if (stored) synchronized(ArchiveCache.cacheLock) { ArchiveCache.editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong()) }
                    stored
                }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "edit history save failed", e)
            }
        }
        if (Thread.currentThread() === storage.storageQueue) persist.run() else storage.storageQueue.postRunnable(persist)
    }

    private fun cachedRevisions(account: Int, dialogId: Long, msgId: Int): ArrayList<EditEntry> {
        val accMap = ArchiveCache.editHistoryCache.get(account.toLong())
            ?: LongSparseArray<LongSparseArray<ArrayList<EditEntry>>>().also { ArchiveCache.editHistoryCache.put(account.toLong(), it) }
        val dialogMap = accMap.get(dialogId) ?: LongSparseArray<ArrayList<EditEntry>>().also { accMap.put(dialogId, it) }
        return dialogMap.get(msgId.toLong()) ?: ArrayList<EditEntry>().also { dialogMap.put(msgId.toLong(), it) }
    }

    private fun hasMedia(media: TLRPC.MessageMedia?): Boolean = media != null && media !is TLRPC.TL_messageMediaEmpty

    // entiny: the one definition of "what changed", shared by recording and the history screen
    @JvmStatic
    fun changes(a: EditEntry, b: EditEntry): List<Int> {
        val changed = ArrayList<Int>()
        val withMedia = listOf(a.media, b.media).any { hasMedia(it) && it !is TLRPC.TL_messageMediaWebPage }
        if (a.text != b.text) changed.add(if (withMedia) R.string.InuEditFieldCaption else R.string.InuEditFieldText)
        val (formatA, emojiA) = splitEntities(a.entities)
        val (formatB, emojiB) = splitEntities(b.entities)
        if (!sameEntities(formatA, formatB)) changed.add(R.string.InuEditFieldFormatting)
        if (!sameEntities(emojiA, emojiB)) changed.add(R.string.InuEditFieldEmoji)
        if (mediaKey(a) != mediaKey(b)) changed.add(R.string.InuEditFieldMedia)
        if ((a.media as? TLRPC.TL_messageMediaWebPage)?.webpage?.url != (b.media as? TLRPC.TL_messageMediaWebPage)?.webpage?.url) changed.add(R.string.InuEditFieldLinkPreview)
        if (pollKey(a) != pollKey(b)) changed.add(R.string.InuEditFieldPoll)
        if ((a.media?.ttl_seconds ?: 0) != (b.media?.ttl_seconds ?: 0)) changed.add(R.string.InuEditFieldTimer)
        if ((a.media?.spoiler == true) != (b.media?.spoiler == true)) changed.add(R.string.InuEditFieldSpoiler)
        return changed
    }

    private fun splitEntities(entities: List<TLRPC.MessageEntity>?): Pair<List<TLRPC.MessageEntity>, List<TLRPC.MessageEntity>> =
        entities.orEmpty().partition { it !is TLRPC.TL_messageEntityCustomEmoji }

    private fun sameEntities(a: List<TLRPC.MessageEntity>, b: List<TLRPC.MessageEntity>): Boolean =
        a.size == b.size && a.indices.all { serialized(a[it]).contentEquals(serialized(b[it])) }

    // entiny: photo/document identity is the id, their file_reference/access_hash rotate on re-fetch
    private fun mediaKey(entry: EditEntry): String? {
        val media = entry.media
        return when {
            media == null || media is TLRPC.TL_messageMediaEmpty || media is TLRPC.TL_messageMediaWebPage -> entry.mediaPath?.let { "f$it" }
            media.photo != null -> "p${media.photo.id}"
            media.document != null -> "d${media.document.id}"
            else -> media.javaClass.simpleName
        }
    }

    private fun pollKey(entry: EditEntry): String? {
        val poll = (entry.media as? TLRPC.TL_messageMediaPoll)?.poll ?: return null
        return (poll.question?.text ?: "") + "\n" + poll.answers.joinToString("\n") { it.text?.text ?: "" }
    }

    private fun serialized(value: org.telegram.tgnet.TLObject): ByteArray {
        val buffer = org.telegram.tgnet.NativeByteBuffer(value.objectSize)
        return try {
            value.serializeToStream(buffer)
            buffer.position(0)
            buffer.readData(value.objectSize, true)
        } finally {
            buffer.reuse()
        }
    }

    @JvmStatic
    fun getEditHistoryAsync(account: Int, dialogId: Long, msgId: Int, onLoaded: (List<EditEntry>) -> Unit) {
        val storage = MessagesStorage.getInstance(account)
        if (storage == null) {
            onLoaded(emptyList())
            return
        }
        storage.storageQueue.postRunnable {
            val db = storage.database
            val loaded: List<EditEntry> = if (db == null) {
                emptyList()
            } else {
                ArchiveStore.loadEditHistory(db, dialogId, msgId)
                    .map { EditEntry(it.date, it.text, it.mediaPath, it.entities, it.media, rowId = it.rowId) }
            }
            AndroidUtilities.runOnUIThread {
                synchronized(ArchiveCache.cacheLock) {
                    val list = cachedRevisions(account, dialogId, msgId)
                    if (list.isEmpty()) list.addAll(loaded)
                }
                onLoaded(loaded)
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun deleteEditHistoryEntry(account: Int, dialogId: Long, msgId: Int, rowId: Long, onDone: Runnable? = null) {
        if (rowId <= 0L) return
        synchronized(ArchiveCache.cacheLock) {
            val list = ArchiveCache.editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())
            list?.removeAll { it.rowId == rowId }
            if (list != null && list.isEmpty()) {
                ArchiveCache.editHistoryIds.get(account.toLong())?.get(dialogId)?.remove(msgId)
            }
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            ArchiveStore.deleteEditHistoryRevision(db, dialogId, msgId, rowId)
            AndroidUtilities.runOnUIThread { onDone?.run() }
        }
    }

    @JvmStatic
    fun hasEditHistory(account: Int, dialogId: Long, msgId: Int): Boolean {
        if (!isSaveEditedEnabled()) return false
        ArchiveCache.ensureAccountLoaded(account)
        return synchronized(ArchiveCache.cacheLock) {
            if (ArchiveCache.editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())?.isNotEmpty() == true) {
                return@synchronized true
            }
            ArchiveCache.editHistoryIds.get(account.toLong())?.get(dialogId)?.contains(msgId) == true
        }
    }

    @JvmStatic
    fun markAsHistoryPreview(msg: MessageObject) {
        synchronized(historyPreviewObjects) { historyPreviewObjects.add(msg) }
    }

    @JvmStatic
    fun isHistoryPreview(msg: MessageObject?): Boolean {
        if (msg == null) return false
        return synchronized(historyPreviewObjects) { historyPreviewObjects.contains(msg) }
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, msgObj: MessageObject) {
        if (context == null || !isSaveEditedEnabled()) return
        if (!hasEditHistory(msgObj.currentAccount, msgObj.getDialogId(), msgObj.id) && !isEdited(msgObj)) {
            val bulletinFactory = if (activity != null) BulletinFactory.of(activity) else BulletinFactory.global()
            bulletinFactory?.createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuNoEditHistory))?.show()
            return
        }
        val frag = desu.inugram.ui.EditHistoryActivity.forMessage(msgObj)
        if (activity != null) {
            activity.presentFragment(frag)
        } else if (context is org.telegram.ui.LaunchActivity) {
            context.actionBarLayout?.presentFragment(frag)
        }
    }
}
