package desu.inugram.helpers.chat

import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object SavedMessagesHelper {
    private const val TAG = "SavedMessagesHelper"

    fun getSavedMediaDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, "entinyGram/media")
        if (!dir.exists()) {
            try {
                dir.mkdirs()
                File(dir, ".nomedia").createNewFile()
            } catch (e: Throwable) { }
        }
        return dir
    }

    /**
     * Where a message's media *will* live in the archive, plus the copy still to be performed.
     * Resolving the path is a couple of stat calls; the copy itself can be hundreds of megabytes,
     * and both capture points (an incoming deletion, an incoming edit) run on the main thread --
     * so the copy is handed to the storage queue and only the path is decided inline.
     */
    private data class PendingMediaCopy(val source: File, val target: File)

    private fun planMediaCopy(account: Int, message: TLRPC.Message?): PendingMediaCopy? {
        if (message?.media == null) return null
        return try {
            val fileLoader = FileLoader.getInstance(account) ?: return null
            val path = fileLoader.getPathToMessage(message)
            if (path == null || !path.exists() || path.length() <= 0) return null
            PendingMediaCopy(path, File(getSavedMediaDir(), "${message.dialog_id}_${message.id}_${path.name}"))
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "planMediaCopy error", e)
            null
        }
    }

    private fun runMediaCopy(plan: PendingMediaCopy?) {
        if (plan == null) return
        try {
            if (!plan.target.exists()) {
                plan.source.copyTo(plan.target, overwrite = true)
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "copyMediaFile error", e)
        }
    }

    // In-memory cache for deleted message IDs per account (account -> (dialogId -> Set<msgId>))
    private val deletedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    private val deletedMessageDates = LongSparseArray<LongSparseArray<LongSparseArray<Long>>>()
    private val loadedAccounts = HashSet<Int>()
    private val cacheLock = Any()

    // In-memory cache for edit history (account -> (dialogId -> (msgId -> List<EditEntry>)))
    private val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditEntry>>>>()

    // Presence-only index of which messages have stored edit history, loaded alongside the
    // deleted-message cache. hasEditHistory is asked for every un-edited bubble that gets laid
    // out (see ChatHelper.canClickTime); answering it through getEditHistory ran a SQLite query
    // on the UI thread for each one, holding cacheLock while the storage queue needed it.
    private val editHistoryIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()

    // Messages the user is deleting *for good* right now (a saved ghost being deleted a second
    // time). The archive drops them asynchronously, but ChatActivity has to decide whether to keep
    // the bubble on the spot, so the intent is recorded synchronously and cleared once the
    // messagesDeleted broadcast has been dispatched.
    private val purgingMessages = HashSet<Pair<Long, Int>>()

    // MessageObjects built for the edit-history screen. They reuse the real message id, so the
    // deleted-mark/transparency lookups would otherwise stamp every history row with the current
    // message's "deleted" state. Identity-keyed and weak: entries die with the screen.
    private val historyPreviewObjects: MutableSet<MessageObject> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<MessageObject, Boolean>())

    // Independent shadow cache of message text/media presence, populated for every message as
    // it's first seen off the wire (see rememberMessage), regardless of whether its chat is
    // open or it's the dialog's current preview message. Needed because dialogMessagesByIds
    // (stock's per-dialog cache, used as the primary edit-history source) only ever tracks each
    // dialog's newest message -- editing any other message in an unread/unopened chat had no
    // fallback to recover the pre-edit text from, since messages_v2 may not have a row for it
    // yet either (see recordEditHistoryFromShadow, called when both other sources miss).
    private const val SHADOW_CACHE_MAX_PER_ACCOUNT = 500

    private class BoundedLinkedHashMap<K, V>(private val maxSize: Int) :
        LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    private data class ShadowEntry(
        val text: String,
        val hadMedia: Boolean,
        val date: Int,
        val entities: ArrayList<TLRPC.MessageEntity>? = null,
        val media: TLRPC.MessageMedia? = null,
    )

    private val shadowMessageCache = LongSparseArray<LinkedHashMap<Pair<Long, Int>, ShadowEntry>>()

    /**
     * One stored revision. [entities] and [media] are what make a revision render as the message
     * it actually was -- bold/links/spoilers intact, a video as a video instead of an empty photo
     * bubble. Rows written before the schema carried them come back with both null.
     */
    data class EditEntry(
        val timestamp: Long,
        val text: String,
        val mediaPath: String? = null,
        val entities: ArrayList<TLRPC.MessageEntity>? = null,
        val media: TLRPC.MessageMedia? = null,
    )

    @JvmStatic
    fun isSaveDeletedEnabled(): Boolean = InuConfig.SAVE_DELETED_MESSAGES.value

    @JvmStatic
    fun isSaveEditedEnabled(): Boolean = InuConfig.SAVE_EDITED_MESSAGES.value

    @JvmStatic
    fun shouldSaveForDialog(account: Int, dialogId: Long): Boolean {
        if (!isSaveDeletedEnabled()) return false
        if (org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) return false
        val controller = MessagesController.getInstance(account) ?: return true
        if (org.telegram.messenger.DialogObject.isUserDialog(dialogId)) {
            val user = controller.getUser(dialogId)
            if (user != null && user.bot) {
                return InuConfig.SAVE_DELETED_BOTS.value
            }
            return InuConfig.SAVE_DELETED_PRIVATE.value
        } else if (org.telegram.messenger.DialogObject.isChatDialog(dialogId)) {
            val chat = controller.getChat(-dialogId)
            if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat) && !chat.megagroup) {
                return InuConfig.SAVE_DELETED_CHANNELS.value
            }
            return InuConfig.SAVE_DELETED_GROUPS.value
        }
        return true
    }

    @JvmStatic
    fun ensureAccountLoaded(account: Int) {
        synchronized(cacheLock) {
            if (loadedAccounts.contains(account)) return
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        val db = storage.database
        if (db != null) {
            loadFromDb(account, db)
        } else {
            storage.storageQueue.postRunnable {
                val asyncDb = storage.database ?: return@postRunnable
                loadFromDb(account, asyncDb)
            }
        }
    }

    private fun loadFromDb(account: Int, db: org.telegram.SQLite.SQLiteDatabase) {
        synchronized(cacheLock) {
            if (loadedAccounts.contains(account)) return
        }

        // Prune stale entries before loading into memory
        val ttlDays = InuConfig.DELETED_MESSAGES_TTL.value
        if (ttlDays > 0) {
            val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
            InuDatabaseHelper.pruneDeletedMessages(db, cutoff)
            InuDatabaseHelper.pruneEditHistory(db, cutoff)
        }

        // Build the final sparse caches directly while reading the cursor. The old
        // path materialized HashMaps first and copied them into LongSparseArrays,
        // briefly keeping two complete representations of the cache in memory.
        val deletedArray = LongSparseArray<HashSet<Int>>()
        val dateArray = LongSparseArray<LongSparseArray<Long>>()
        InuDatabaseHelper.forEachDeletedMessageInfo(db) { dialogId, msgId, date ->
            var ids = deletedArray.get(dialogId)
            if (ids == null) {
                ids = HashSet()
                deletedArray.put(dialogId, ids)
            }
            ids.add(msgId)
            if (date > 0) {
                var dates = dateArray.get(dialogId)
                if (dates == null) {
                    dates = LongSparseArray()
                    dateArray.put(dialogId, dates)
                }
                dates.put(msgId.toLong(), date)
            }
        }
        val editArray = LongSparseArray<HashSet<Int>>()
        InuDatabaseHelper.forEachEditHistoryKey(db) { dialogId, msgId ->
            var ids = editArray.get(dialogId)
            if (ids == null) {
                ids = HashSet()
                editArray.put(dialogId, ids)
            }
            ids.add(msgId)
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            synchronized(cacheLock) {
                var existingEdits = editHistoryIds.get(account.toLong())
                if (existingEdits == null) {
                    editHistoryIds.put(account.toLong(), editArray)
                } else {
                    for (i in 0 until editArray.size()) {
                        val k = editArray.keyAt(i)
                        val v = editArray.valueAt(i)
                        val target = existingEdits.get(k)
                        if (target == null) existingEdits.put(k, v) else target.addAll(v)
                    }
                }
                var existingDialogs = deletedMessageIds.get(account.toLong())
                if (existingDialogs == null) {
                    deletedMessageIds.put(account.toLong(), deletedArray)
                } else {
                    for (i in 0 until deletedArray.size()) {
                        val k = deletedArray.keyAt(i)
                        val v = deletedArray.valueAt(i)
                        val targetSet = existingDialogs.get(k)
                        if (targetSet == null) {
                            existingDialogs.put(k, v)
                        } else {
                            targetSet.addAll(v)
                        }
                    }
                }

                var existingDates = deletedMessageDates.get(account.toLong())
                if (existingDates == null) {
                    deletedMessageDates.put(account.toLong(), dateArray)
                } else {
                    for (i in 0 until dateArray.size()) {
                        val k = dateArray.keyAt(i)
                        val v = dateArray.valueAt(i)
                        val targetDates = existingDates.get(k)
                        if (targetDates == null) {
                            existingDates.put(k, v)
                        } else {
                            for (j in 0 until v.size()) {
                                targetDates.put(v.keyAt(j), v.valueAt(j))
                            }
                        }
                    }
                }
                loadedAccounts.add(account)
            }
        }
    }

    /**
     * Manually trigger a prune pass for [account] with the current TTL setting.
     * Safe to call from any thread; runs on the storage queue.
     */
    @JvmStatic
    fun pruneIfNeeded(account: Int) {
        val ttlDays = InuConfig.DELETED_MESSAGES_TTL.value
        if (ttlDays == 0) return
        val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.pruneDeletedMessages(db, cutoff)
            InuDatabaseHelper.pruneEditHistory(db, cutoff)
            // Invalidate in-memory cache so it's reloaded fresh on next access
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    deletedMessageIds.remove(account.toLong())
                    deletedMessageDates.remove(account.toLong())
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                    shadowMessageCache.remove(account.toLong())
                    loadedAccounts.remove(account)
                }
            }
        }
    }

    /**
     * Clear deleted message cache for [dialogIds] (or all if null) for [account].
     * Saved-deleted messages are also physically removed from the chat so they disappear,
     * not just downgraded to a "deleted" placeholder.
     */
    private fun getChannelId(account: Int, dialogId: Long): Long {
        if (!org.telegram.messenger.DialogObject.isChatDialog(dialogId)) return 0L
        val controller = org.telegram.messenger.MessagesController.getInstance(account) ?: return 0L
        val chat = controller.getChat(-dialogId)
        return if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat)) chat.id else 0L
    }

    /**
     * Clear deleted message cache for [dialogIds] (or all if null) for [account].
     * Saved-deleted messages are also physically removed from the chat so they disappear,
     * not just downgraded to a "deleted" placeholder.
     */
    @JvmStatic
    fun clearCache(account: Int, dialogIds: Collection<Long>? = null, onDone: Runnable? = null) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database
            val pairs = if (db != null) InuDatabaseHelper.getDeletedMessageIds(db, dialogIds) else emptyMap()
            if (db != null) {
                InuDatabaseHelper.clearDeletedMessages(db, dialogIds)
                for ((dialogId, mids) in pairs) {
                    InuDatabaseHelper.deleteSavedMessages(db, dialogId, mids)
                    val channelId = getChannelId(account, dialogId)
                    storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(mids), null)
                }
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (pairs.isNotEmpty()) {
                    val controller = org.telegram.messenger.MessagesController.getInstance(account)
                    for ((dialogId, mids) in pairs) {
                        for (mid in mids) {
                            controller.dialogMessagesByIds.remove(mid)
                        }
                        val list = controller.dialogMessage.get(dialogId)
                        if (list != null) {
                            val midSet = mids.toHashSet()
                            list.removeAll { it?.id != null && it.id in midSet }
                        }
                        val channelId = getChannelId(account, dialogId)
                        org.telegram.messenger.NotificationCenter.getInstance(account)
                            .postNotificationName(
                                org.telegram.messenger.NotificationCenter.messagesDeleted,
                                java.util.ArrayList(mids), channelId, false, false, false, 0
                            )
                    }
                }
                synchronized(cacheLock) {
                    deletedMessageIds.remove(account.toLong())
                    deletedMessageDates.remove(account.toLong())
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                    shadowMessageCache.remove(account.toLong())
                    loadedAccounts.remove(account)
                }
                onDone?.run()
            }
        }
    }

    /**
     * Permanently removes a single saved-deleted message: drops its `inu_deleted_messages` row
     * (so it stops being shown as a "deleted" placeholder) and the underlying `messages_v2` row
     * (so the bubble disappears from the chat entirely), mirroring [clearCache] but scoped to one message.
     */
    @JvmStatic
    @JvmOverloads
    fun deletePermanently(account: Int, dialogId: Long, msgId: Int, onDone: Runnable? = null) {
        deletePermanently(account, dialogId, listOf(msgId), onDone)
    }

    @JvmStatic
    @JvmOverloads
    fun deletePermanently(account: Int, dialogId: Long, msgIds: List<Int>, onDone: Runnable? = null) {
        if (msgIds.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        // Drop them from the in-memory set first: ChatActivity decides whether a bubble stays
        // visible by asking isMessageDeleted, and the messagesDeleted broadcast below has to
        // find them already gone or the ghost survives its own deletion.
        synchronized(cacheLock) {
            val ids = deletedMessageIds.get(account.toLong())?.get(dialogId)
            val dates = deletedMessageDates.get(account.toLong())?.get(dialogId)
            for (msgId in msgIds) {
                ids?.remove(msgId)
                dates?.remove(msgId.toLong())
            }
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.deleteDeletedMessageEntries(db, dialogId, msgIds)
            InuDatabaseHelper.deleteEditHistory(db, dialogId, msgIds)
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, msgIds)
            val channelId = getChannelId(account, dialogId)
            storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(msgIds), null)
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    val ids = deletedMessageIds.get(account.toLong())?.get(dialogId)
                    val dates = deletedMessageDates.get(account.toLong())?.get(dialogId)
                    val edits = editHistoryCache.get(account.toLong())?.get(dialogId)
                    for (msgId in msgIds) {
                        ids?.remove(msgId)
                        dates?.remove(msgId.toLong())
                        edits?.remove(msgId.toLong())
                    }
                }
                val controller = MessagesController.getInstance(account)
                val idSet = msgIds.toHashSet()
                for (msgId in msgIds) {
                    controller.dialogMessagesByIds.remove(msgId)
                }
                val list = controller.dialogMessage.get(dialogId)
                if (list != null) {
                    list.removeAll { it?.id != null && it.id in idSet }
                }
                org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(
                        org.telegram.messenger.NotificationCenter.messagesDeleted,
                        ArrayList(msgIds), channelId, false, false, false, 0
                    )
                synchronized(cacheLock) {
                    for (msgId in msgIds) purgingMessages.remove(dialogId to msgId)
                }
                onDone?.run()
            }
        }
    }

    /**
     * Removes from [msgIds] every id that is already preserved as a saved deletion, wiping those
     * for good instead. Deleting such a ghost through the stock path never worked: the message is
     * already gone server-side, markMessagesAsDeleted keeps its messages_v2 row while
     * SAVE_DELETED_MESSAGES is on, and markDialogMessageAsDeleted simply re-records it -- so the
     * bubble always came back. Returns true when anything was purged.
     */
    @JvmStatic
    fun extractPreserved(account: Int, dialogId: Long, msgIds: MutableList<Int>?): Boolean {
        if (msgIds.isNullOrEmpty() || !isSaveDeletedEnabled()) return false
        val preserved = msgIds.filter { isMessageDeleted(account, dialogId, it) }
        if (preserved.isEmpty()) return false
        msgIds.removeAll(preserved.toHashSet())
        synchronized(cacheLock) {
            for (id in preserved) purgingMessages.add(dialogId to id)
        }
        deletePermanently(account, dialogId, preserved)
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun markMessageDeleted(account: Int, dialogId: Long, msgId: Int, fromId: Long, text: String?, date: Int, message: TLRPC.Message? = null, forceSave: Boolean = false) {
        // No dialog owns id 0: this is the non-channel TL_updateDeleteMessages path (key == 0)
        // failing to resolve the message through dialogMessagesByIds, which only holds each
        // dialog's preview message. Such a row carries no text or sender either, and message ids
        // restart per dialog, so keeping it would mark every unrelated chat's message of the same
        // id as deleted.
        if (dialogId == 0L) return
        if (!forceSave && !shouldSaveForDialog(account, dialogId)) return
        if (!forceSave && !InuConfig.SAVE_DELETED_OWN.value && fromId == UserConfig.getInstance(account).clientUserId) return
        ensureAccountLoaded(account)
        // Several paths can report the same deletion (the update, then the storage pass). The row
        // is written with INSERT OR REPLACE, so a later report carrying no text must not overwrite
        // the one that had it.
        val alreadyRecorded = isMessageDeleted(account, dialogId, msgId)
        if (alreadyRecorded && text.isNullOrEmpty() && message?.media == null) return
        val deletionTime = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000L
        synchronized(cacheLock) {
            var dialogs = deletedMessageIds.get(account.toLong())
            if (dialogs == null) {
                dialogs = LongSparseArray()
                deletedMessageIds.put(account.toLong(), dialogs)
            }
            var set = dialogs.get(dialogId)
            if (set == null) {
                set = HashSet()
                dialogs.put(dialogId, set)
            }
            set.add(msgId)

            var dateAcc = deletedMessageDates.get(account.toLong())
            if (dateAcc == null) {
                dateAcc = LongSparseArray()
                deletedMessageDates.put(account.toLong(), dateAcc)
            }
            var dateDialog = dateAcc.get(dialogId)
            if (dateDialog == null) {
                dateDialog = LongSparseArray()
                dateAcc.put(dialogId, dateDialog)
            }
            dateDialog.put(msgId.toLong(), deletionTime)
        }

        val mediaCopy = planMediaCopy(account, message)
        val mediaPath = mediaCopy?.target?.absolutePath
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            runMediaCopy(mediaCopy)
            InuDatabaseHelper.saveDeletedMessage(db, dialogId, msgId, fromId, text ?: "", deletionTime.toInt(), mediaPath)
        }
    }

    /**
     * Whether the chat should keep showing this message after its deletion.
     *
     * Recording is asynchronous -- for a private chat the dialog id is not even known until
     * MessagesStorage resolves it against `messages_v2` -- so asking [isMessageDeleted] here loses
     * the race and the bubble disappears before the archive learns about it. This answers from the
     * same rules the recorder uses, synchronously.
     */
    @JvmStatic
    fun isPreservedOrWillBe(account: Int, msg: MessageObject?): Boolean {
        if (msg == null) return false
        val dialogId = msg.getDialogId()
        val msgId = msg.id
        synchronized(cacheLock) {
            if (purgingMessages.contains(dialogId to msgId)) return false
        }
        if (isMessageDeleted(account, dialogId, msgId)) return true
        if (!shouldSaveForDialog(account, dialogId)) return false
        if (!InuConfig.SAVE_DELETED_OWN.value && msg.isOutOwner) return false
        return true
    }

    /**
     * Records a batch of deletions using the dialog ids MessagesStorage resolved from
     * `messages_v2`.
     *
     * The update path cannot do this itself: `TL_updateDeleteMessages` carries no peer, so the
     * controller falls back to `dialogMessagesByIds`, which only ever holds each dialog's newest
     * message. Every deletion in a private chat that was not the last message went unrecorded --
     * and while the bubble used to linger anyway (ChatActivity kept it on a dialog-wide check), it
     * carried no archive row behind it and was gone after a restart.
     */
    @JvmStatic
    fun markMessagesDeletedFromStorage(
        account: Int,
        messagesByDialogs: LongSparseArray<ArrayList<Int>>?,
        resolved: List<TLRPC.Message>?,
    ) {
        if (!isSaveDeletedEnabled() || messagesByDialogs == null) return
        val byId = HashMap<Int, TLRPC.Message>()
        resolved?.forEach { m -> if (m != null) byId[m.id] = m }
        for (i in 0 until messagesByDialogs.size()) {
            val dialogId = messagesByDialogs.keyAt(i)
            val mids = messagesByDialogs.valueAt(i) ?: continue
            for (mid in mids) {
                val message = byId[mid]
                val fromId = message?.from_id?.let { org.telegram.messenger.DialogObject.getPeerDialogId(it) } ?: 0L
                markMessageDeleted(account, dialogId, mid, fromId, message?.message ?: "", 0, message)
            }
        }
    }

    @JvmStatic
    fun isMessageDeleted(account: Int, dialogId: Long, msgId: Int): Boolean {
        if (!isSaveDeletedEnabled()) return false
        ensureAccountLoaded(account)
        return synchronized(cacheLock) {
            val accMap = deletedMessageIds.get(account.toLong()) ?: return@synchronized false
            accMap.get(dialogId)?.contains(msgId) == true
        }
    }

    @JvmStatic
    fun isMessageDeleted(dialogId: Long, msgId: Int): Boolean {
        return isMessageDeleted(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun getDeletedDate(account: Int, dialogId: Long, msgId: Int): Long {
        ensureAccountLoaded(account)
        return synchronized(cacheLock) {
            val accMap = deletedMessageDates.get(account.toLong()) ?: return@synchronized 0L
            accMap.get(dialogId)?.get(msgId.toLong()) ?: 0L
        }
    }

    @JvmStatic
    fun getDeletedDate(dialogId: Long, msgId: Int): Long {
        return getDeletedDate(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun showDeletionTimeBulletin(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, dialogId: Long, msgId: Int) {
        if (context == null) return
        val date = getDeletedDate(dialogId, msgId)
        val timeStr = if (date > 0) {
            LocaleController.formatDateAudio(date, true)
        } else {
            null
        }
        val text = if (timeStr != null) {
            LocaleController.formatString(R.string.InuDeletedAt, timeStr)
        } else {
            LocaleController.getString(R.string.InuSaveDeletedMessages)
        }
        val bulletinFactory = if (activity != null) org.telegram.ui.Components.BulletinFactory.of(activity) else org.telegram.ui.Components.BulletinFactory.global()
        bulletinFactory?.createSimpleBulletin(
            R.drawable.msg_delete,
            text
        )?.show()
    }

    @JvmStatic
    fun recordEditHistory(account: Int, dialogId: Long, msgId: Int, oldText: String, date: Int, message: TLRPC.Message? = null) {
        recordEditHistory(account, dialogId, msgId, oldText, date, message, message?.entities, message?.media)
    }

    private fun recordEditHistory(
        account: Int,
        dialogId: Long,
        msgId: Int,
        oldText: String,
        date: Int,
        message: TLRPC.Message?,
        entities: ArrayList<TLRPC.MessageEntity>?,
        media: TLRPC.MessageMedia?,
    ) {
        if (!isSaveEditedEnabled()) return
        val mediaCopy = planMediaCopy(account, message)
        val mediaPath = mediaCopy?.target?.absolutePath
        val trimmed = oldText.trim()
        if (trimmed.isBlank() && mediaPath.isNullOrBlank()) return
        val now = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000
        // Entity offsets address the untrimmed text. The row stores the trimmed one, so keep
        // formatting only when trimming was a no-op -- which it is for anything Telegram sent,
        // since outgoing messages are trimmed before they leave the client.
        val keptEntities = if (entities.isNullOrEmpty() || trimmed != oldText) null else ArrayList(entities)
        // Detached from the live message: the screen clears one-time TTLs on archived media.
        val keptMedia = InuDatabaseHelper.cloneMedia(media)
        
        synchronized(cacheLock) {
            var accMap = editHistoryCache.get(account.toLong())
            if (accMap == null) {
                accMap = LongSparseArray()
                editHistoryCache.put(account.toLong(), accMap)
            }
            var dialogMap = accMap.get(dialogId)
            if (dialogMap == null) {
                dialogMap = LongSparseArray()
                accMap.put(dialogId, dialogMap)
            }
            var list = dialogMap.get(msgId.toLong())
            if (list == null) {
                list = ArrayList()
                dialogMap.put(msgId.toLong(), list)
            }
            if (list.isNotEmpty() && list.last().text.trim() == trimmed && list.last().mediaPath == mediaPath) return
            if (list.any { it.text.trim() == trimmed && it.mediaPath == mediaPath }) return
            list.add(EditEntry(now, trimmed, mediaPath, keptEntities, keptMedia))

            var idAcc = editHistoryIds.get(account.toLong())
            if (idAcc == null) {
                idAcc = LongSparseArray()
                editHistoryIds.put(account.toLong(), idAcc)
            }
            var idSet = idAcc.get(dialogId)
            if (idSet == null) {
                idSet = HashSet()
                idAcc.put(dialogId, idSet)
            }
            idSet.add(msgId)
        }

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            runMediaCopy(mediaCopy)
            InuDatabaseHelper.saveEditHistory(db, dialogId, msgId, trimmed, now.toInt(), mediaPath, keptEntities, keptMedia)
        }
    }

    @JvmStatic
    fun recordEditHistory(account: Int, dialogId: Long, msgId: Int, oldText: String, date: Int) {
        recordEditHistory(account, dialogId, msgId, oldText, date, null)
    }

    /** Called for every incoming message (see MessagesController's new-message ingestion) so an
     *  edit later has something to diff against even if this message is never opened/read. */
    @JvmStatic
    fun rememberMessageText(account: Int, dialogId: Long, msgId: Int, text: String?, hasMedia: Boolean, date: Int) {
        rememberMessage(account, dialogId, msgId, text, hasMedia, date, null, null)
    }

    /** Full-fidelity variant: keeps formatting and media so a shadow-recovered revision is not
     *  downgraded to plain text just because the chat was never opened. */
    @JvmStatic
    fun rememberMessage(account: Int, dialogId: Long, msgId: Int, text: String?, hasMedia: Boolean, date: Int, entities: ArrayList<TLRPC.MessageEntity>?, media: TLRPC.MessageMedia?) {
        if (!isSaveEditedEnabled()) return
        synchronized(cacheLock) {
            val map = shadowMessageCache.get(account.toLong())
                ?: BoundedLinkedHashMap<Pair<Long, Int>, ShadowEntry>(SHADOW_CACHE_MAX_PER_ACCOUNT)
                    .also { shadowMessageCache.put(account.toLong(), it) }
            map[dialogId to msgId] = ShadowEntry(
                text ?: "",
                hasMedia,
                date,
                if (entities.isNullOrEmpty()) null else ArrayList(entities),
                // Held by reference only -- recordEditHistory detaches it if this entry is ever
                // promoted into the archive, and cloning here would cost a serialization pass on
                // every single incoming message.
                if (media is TLRPC.TL_messageMediaEmpty) null else media,
            )
        }
    }

    /** Last-resort fallback when neither dialogMessagesByIds nor the messages_v2 row had the
     *  pre-edit text (both only reliably cover a dialog's current preview message). */
    @JvmStatic
    fun recordEditHistoryFromShadow(account: Int, dialogId: Long, msgId: Int, newText: String?, newHasMedia: Boolean) {
        if (!isSaveEditedEnabled()) return
        val old = synchronized(cacheLock) {
            shadowMessageCache.get(account.toLong())?.remove(dialogId to msgId)
        } ?: return
        val textChanged = old.text.isNotEmpty() && old.text != (newText ?: "")
        val mediaChanged = old.hadMedia != newHasMedia
        if (textChanged || mediaChanged) {
            recordEditHistory(account, dialogId, msgId, old.text, old.date, null, old.entities, old.media)
        }
    }

    @JvmStatic
    fun recordEditHistory(dialogId: Long, msgId: Int, oldText: String) {
        recordEditHistory(UserConfig.selectedAccount, dialogId, msgId, oldText, 0, null)
    }

    /**
     * Loads the history off the UI thread. The screen is the only place that needs the entries
     * themselves; everything else asks [hasEditHistory], which never touches SQLite.
     */
    @JvmStatic
    fun getEditHistoryAsync(account: Int, dialogId: Long, msgId: Int, onLoaded: (List<EditEntry>) -> Unit) {
        val cached = synchronized(cacheLock) {
            editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())?.toList()
        }
        if (cached != null) {
            onLoaded(cached)
            return
        }
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
                InuDatabaseHelper.loadEditHistory(db, dialogId, msgId)
                    .map { EditEntry(it.date, it.text, it.mediaPath, it.entities, it.media) }
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    var accMap = editHistoryCache.get(account.toLong())
                    if (accMap == null) {
                        accMap = LongSparseArray()
                        editHistoryCache.put(account.toLong(), accMap)
                    }
                    var dialogMap = accMap.get(dialogId)
                    if (dialogMap == null) {
                        dialogMap = LongSparseArray()
                        accMap.put(dialogId, dialogMap)
                    }
                    if (dialogMap.get(msgId.toLong()) == null) {
                        dialogMap.put(msgId.toLong(), ArrayList(loaded))
                    }
                }
                onLoaded(loaded)
            }
        }
    }

    /** Drops a single stored revision (the history screen's per-row delete). */
    @JvmStatic
    @JvmOverloads
    fun deleteEditHistoryEntry(account: Int, dialogId: Long, msgId: Int, timestamp: Long, onDone: Runnable? = null) {
        synchronized(cacheLock) {
            val list = editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())
            list?.removeAll { it.timestamp == timestamp }
            if (list != null && list.isEmpty()) {
                editHistoryIds.get(account.toLong())?.get(dialogId)?.remove(msgId)
            }
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.deleteEditHistoryEntry(db, dialogId, msgId, timestamp)
            org.telegram.messenger.AndroidUtilities.runOnUIThread { onDone?.run() }
        }
    }

    /** Memory-only presence check -- safe to call from measure/layout. */
    @JvmStatic
    fun hasEditHistory(account: Int, dialogId: Long, msgId: Int): Boolean {
        if (!isSaveEditedEnabled()) return false
        ensureAccountLoaded(account)
        return synchronized(cacheLock) {
            if (editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())?.isNotEmpty() == true) {
                return@synchronized true
            }
            editHistoryIds.get(account.toLong())?.get(dialogId)?.contains(msgId) == true
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
    fun hasEditHistory(dialogId: Long, msgId: Int): Boolean {
        return hasEditHistory(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, dialogId: Long, msgId: Int) {
        val account = activity?.currentAccount ?: UserConfig.selectedAccount
        val controller = MessagesController.getInstance(account)
        var msgObj: MessageObject? = controller.dialogMessagesByIds.get(msgId)
        if (msgObj == null) {
            val dummyMsg = TLRPC.TL_message().apply {
                id = msgId
                dialog_id = dialogId
                // Without a peer MessageObject.generateLayout()/checkLayout() bail out, and every
                // row on the history screen renders as a bubble with a timestamp and no text.
                peer_id = controller.getPeer(dialogId)
                date = (System.currentTimeMillis() / 1000L).toInt()
            }
            msgObj = MessageObject(account, dummyMsg, false, true)
        }
        showEditHistoryDialog(context, activity, msgObj)
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, msgObj: MessageObject) {
        if (context == null || !isSaveEditedEnabled()) return
        val dialogId = msgObj.getDialogId()
        val msgId = msgObj.id
        val account = msgObj.currentAccount
        val isEdited = (msgObj.messageOwner != null && (msgObj.messageOwner.flags and TLRPC.MESSAGE_FLAG_EDITED) != 0) || (msgObj.messageOwner?.edit_date ?: 0) != 0
        if (!hasEditHistory(account, dialogId, msgId) && !isEdited) {
            val bulletinFactory = if (activity != null) org.telegram.ui.Components.BulletinFactory.of(activity) else org.telegram.ui.Components.BulletinFactory.global()
            bulletinFactory?.createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.InuNoEditHistory)
            )?.show()
            return
        }

        val frag = desu.inugram.ui.AyuMessageHistoryActivity(msgObj)
        if (activity != null) {
            activity.presentFragment(frag)
        } else if (context is org.telegram.ui.LaunchActivity) {
            context.actionBarLayout?.presentFragment(frag)
        }
    }
}
