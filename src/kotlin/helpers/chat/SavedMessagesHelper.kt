package desu.inugram.helpers.chat

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object SavedMessagesHelper {
    private const val TAG = "SavedMessagesHelper"

    // In-memory cache for deleted message IDs per account (account -> (dialogId -> Set<msgId>))
    private val deletedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    private val loadedAccounts = HashSet<Int>()

    // In-memory cache for edit history (account -> (dialogId -> (msgId -> List<EditEntry>)))
    private val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditEntry>>>>()

    data class EditEntry(
        val timestamp: Long,
        val text: String
    )

    @JvmStatic
    fun isSaveDeletedEnabled(): Boolean = InuConfig.SAVE_DELETED_MESSAGES.value

    @JvmStatic
    fun isSaveEditedEnabled(): Boolean = InuConfig.SAVE_EDITED_MESSAGES.value

    private fun ensureAccountLoaded(account: Int) {
        if (loadedAccounts.contains(account)) return
        val storage = MessagesStorage.getInstance(account) ?: return
        val db = storage.database ?: return
        
        val deletedMap = InuDatabaseHelper.loadDeletedMessageIds(db)
        val dialogArray = LongSparseArray<HashSet<Int>>()
        for ((dialogId, set) in deletedMap) {
            dialogArray.put(dialogId, set)
        }
        deletedMessageIds.put(account.toLong(), dialogArray)
        loadedAccounts.add(account)
    }

    @JvmStatic
    fun markMessageDeleted(account: Int, dialogId: Long, msgId: Int, fromId: Long, text: String?, date: Int) {
        if (!isSaveDeletedEnabled()) return
        ensureAccountLoaded(account)
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

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveDeletedMessage(db, dialogId, msgId, fromId, text ?: "", date)
        }
    }

    @JvmStatic
    fun markMessageDeleted(dialogId: Long, msgId: Int) {
        markMessageDeleted(UserConfig.selectedAccount, dialogId, msgId, 0L, "", 0)
    }

    @JvmStatic
    fun isMessageDeleted(account: Int, dialogId: Long, msgId: Int): Boolean {
        if (!isSaveDeletedEnabled()) return false
        ensureAccountLoaded(account)
        return deletedMessageIds.get(account.toLong())?.get(dialogId)?.contains(msgId) == true
    }

    @JvmStatic
    fun isMessageDeleted(dialogId: Long, msgId: Int): Boolean {
        return isMessageDeleted(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun recordEditHistory(account: Int, dialogId: Long, msgId: Int, oldText: String, date: Int) {
        if (!isSaveEditedEnabled() || oldText.isBlank()) return
        val now = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000
        
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
        list.add(EditEntry(now, oldText))

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveEditHistory(db, dialogId, msgId, oldText, now.toInt())
        }
    }

    @JvmStatic
    fun recordEditHistory(dialogId: Long, msgId: Int, oldText: String) {
        recordEditHistory(UserConfig.selectedAccount, dialogId, msgId, oldText, 0)
    }

    @JvmStatic
    fun getEditHistory(account: Int, dialogId: Long, msgId: Int): List<EditEntry> {
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
            val storage = MessagesStorage.getInstance(account) ?: return emptyList()
            val db = storage.database ?: return emptyList()
            val loaded = InuDatabaseHelper.loadEditHistory(db, dialogId, msgId)
            list = ArrayList(loaded.map { EditEntry(it.first, it.second) })
            dialogMap.put(msgId.toLong(), list)
        }
        return list
    }

    @JvmStatic
    fun getEditHistory(dialogId: Long, msgId: Int): List<EditEntry> {
        return getEditHistory(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun hasEditHistory(dialogId: Long, msgId: Int): Boolean {
        return getEditHistory(dialogId, msgId).isNotEmpty()
    }
}
