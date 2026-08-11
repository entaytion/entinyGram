package desu.inugram.helpers.security

import android.util.SparseArray
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage

/**
 * Local preservation of self-destruct content, gated by per-category TOS toggles.
 *
 * Every method returns stock behavior (false / identity) when the corresponding
 * toggles are off, so default-off stays exactly stock.
 */
object SelfDestructHelper {

    /**
     * Guard for `MessagesStorage.emptyMessagesMedia` (the media-wipe choke point).
     * Encrypted dialogs: secret self-destructing media. Regular dialogs: view-once media.
     * Legacy [InuConfig.SAVE_SELF_DESTRUCT] (file-level preservation) is OR-ed in for
     * secret media so enabling it keeps the media row as well.
     */
    @JvmStatic
    fun shouldPreserveMedia(dialogId: Long): Boolean {
        return if (DialogObject.isEncryptedDialog(dialogId)) {
            InuConfig.SAVE_SECRET_CHAT_CONTENT.value ||
                InuConfig.SAVE_SELF_DESTRUCT_MEDIA.value ||
                InuConfig.SAVE_SELF_DESTRUCT.value
        } else {
            InuConfig.SAVE_VIEW_ONCE_MEDIA.value
        }
    }

    /**
     * Guard for the full row-deletion branch of `MessagesController.checkDeletingTask`
     * (`enc_tasks_v4` tasks with media = 0).
     * Encrypted dialogs: self-destructing text (scheduled by `createTaskForSecretChat`).
     * Regular dialogs: auto-delete (ttl_period) chats.
     */
    @JvmStatic
    fun shouldPreserveMessage(dialogId: Long, ttlPeriod: Int): Boolean {
        return if (DialogObject.isEncryptedDialog(dialogId)) {
            InuConfig.SAVE_SECRET_CHAT_CONTENT.value || InuConfig.SAVE_SELF_DESTRUCT_TEXT.value
        } else {
            InuConfig.SAVE_TIMED_MESSAGES.value && ttlPeriod != 0
        }
    }

    // Preserved self-destruct messages (account -> dialogId -> Set<msgId>). The message
    // rows themselves stay in the stock DB (the deletion is skipped), so only the id pair
    // is recorded here — it drives the "deleted" icon next to the timestamp.
    private val preservedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    private val preservedLoadedAccounts = HashSet<Int>()

    @JvmStatic
    fun markPreserved(account: Int, dialogId: Long, mids: ArrayList<Int>) {
        if (mids.isEmpty()) return
        ensurePreservedLoaded(account)
        var dialogs = preservedMessageIds.get(account.toLong())
        if (dialogs == null) {
            dialogs = LongSparseArray()
            preservedMessageIds.put(account.toLong(), dialogs)
        }
        var set = dialogs.get(dialogId)
        if (set == null) {
            set = HashSet()
            dialogs.put(dialogId, set)
        }
        val toSave = ArrayList<Int>()
        for (i in 0 until mids.size) {
            val id = mids[i]
            if (set.add(id)) toSave.add(id)
        }
        if (toSave.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            for (id in toSave) {
                InuDatabaseHelper.savePreservedMessage(db, dialogId, id)
            }
        }
    }

    @JvmStatic
    fun markPreserved(account: Int, dialogId: Long, msgId: Int) {
        val single = ArrayList<Int>(1)
        single.add(msgId)
        markPreserved(account, dialogId, single)
    }

    @JvmStatic
    fun isPreserved(account: Int, dialogId: Long, msgId: Int): Boolean {
        ensurePreservedLoaded(account)
        return preservedMessageIds.get(account.toLong())?.get(dialogId)?.contains(msgId) == true
    }

    @JvmStatic
    fun ensurePreservedLoaded(account: Int) {
        if (preservedLoadedAccounts.contains(account)) return
        val storage = MessagesStorage.getInstance(account) ?: return
        val db = storage.database
        if (db != null) {
            loadPreservedFromDb(account, db)
        } else {
            storage.storageQueue.postRunnable {
                val asyncDb = storage.database ?: return@postRunnable
                loadPreservedFromDb(account, asyncDb)
            }
        }
    }

    private fun loadPreservedFromDb(account: Int, db: org.telegram.SQLite.SQLiteDatabase) {
        if (preservedLoadedAccounts.contains(account)) return
        val loaded = InuDatabaseHelper.loadPreservedMessageIds(db)
        AndroidUtilities.runOnUIThread {
            if (preservedLoadedAccounts.contains(account)) return@runOnUIThread
            var dialogs = preservedMessageIds.get(account.toLong())
            if (dialogs == null) {
                dialogs = LongSparseArray()
                preservedMessageIds.put(account.toLong(), dialogs)
            }
            for ((dialogId, set) in loaded) {
                val existing = dialogs.get(dialogId)
                if (existing == null) dialogs.put(dialogId, set) else existing.addAll(set)
            }
            preservedLoadedAccounts.add(account)
        }
    }

    /**
     * Filters server-pushed deletions (`TL_updateDeleteMessages` accumulated under key 0)
     * for auto-delete chats: mids whose in-memory message carries `ttl_period` are kept
     * locally. Returns a new list, or the original when the toggle is off or nothing can
     * be resolved.
     */
    @JvmStatic
    fun filterTimedDeletions(account: Int, mids: ArrayList<Int>, dialogMessagesByIds: SparseArray<MessageObject>?): ArrayList<Int> {
        if (!InuConfig.SAVE_TIMED_MESSAGES.value || mids.isEmpty() || dialogMessagesByIds == null) {
            return mids
        }
        val result = ArrayList<Int>(mids.size)
        for (i in 0 until mids.size) {
            val id = mids[i]
            val obj = dialogMessagesByIds.get(id)
            if (obj != null && obj.messageOwner.ttl_period != 0) {
                markPreserved(account, obj.getDialogId(), id)
                continue
            }
            result.add(id)
        }
        return result
    }
}
