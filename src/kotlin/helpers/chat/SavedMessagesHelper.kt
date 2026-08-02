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

    @JvmStatic
    fun ensureAccountLoaded(account: Int) {
        if (loadedAccounts.contains(account)) return
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
        if (loadedAccounts.contains(account)) return

        // Prune stale entries before loading into memory
        val ttlDays = InuConfig.DELETED_MESSAGES_TTL.value
        if (ttlDays > 0) {
            val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
            InuDatabaseHelper.pruneDeletedMessages(db, cutoff)
            InuDatabaseHelper.pruneEditHistory(db, cutoff)
        }

        val deletedMap = InuDatabaseHelper.loadDeletedMessageIds(db)
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            if (loadedAccounts.contains(account)) return@runOnUIThread
            var dialogArray = deletedMessageIds.get(account.toLong())
            if (dialogArray == null) {
                dialogArray = LongSparseArray()
                deletedMessageIds.put(account.toLong(), dialogArray)
            }
            for ((dialogId, set) in deletedMap) {
                dialogArray.put(dialogId, set)
            }
            loadedAccounts.add(account)
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
                deletedMessageIds.remove(account.toLong())
                editHistoryCache.remove(account.toLong())
                loadedAccounts.remove(account)
            }
        }
    }

    /**
     * Clear deleted message cache for [dialogIds] (or all if null) for [account].
     */
    @JvmStatic
    fun clearCache(account: Int, dialogIds: Collection<Long>? = null, onDone: Runnable? = null) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database
            if (db != null) {
                InuDatabaseHelper.clearDeletedMessages(db, dialogIds)
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                deletedMessageIds.remove(account.toLong())
                editHistoryCache.remove(account.toLong())
                loadedAccounts.remove(account)
                onDone?.run()
            }
        }
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

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, dialogId: Long, msgId: Int) {
        if (context == null) return
        val history = getEditHistory(dialogId, msgId)
        if (history.isEmpty()) {
            val bulletinFactory = if (activity != null) org.telegram.ui.Components.BulletinFactory.of(activity) else org.telegram.ui.Components.BulletinFactory.global()
            bulletinFactory?.createSimpleBulletin(
                R.drawable.group_edit,
                LocaleController.getString(R.string.InuNoEditHistory)
            )?.show()
            return
        }

        val builder = org.telegram.ui.ActionBar.BottomSheet.Builder(context)
        builder.setTitle(LocaleController.getString(R.string.InuEditHistory))

        val linear = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(org.telegram.messenger.AndroidUtilities.dp(18f), org.telegram.messenger.AndroidUtilities.dp(8f), org.telegram.messenger.AndroidUtilities.dp(18f), org.telegram.messenger.AndroidUtilities.dp(20f))
        }

        history.forEachIndexed { index, entry ->
            val itemLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, org.telegram.messenger.AndroidUtilities.dp(10f), 0, org.telegram.messenger.AndroidUtilities.dp(10f))
            }

            val dateStr = LocaleController.formatDateTime(entry.timestamp, false)
            val headerText = LocaleController.formatString(R.string.InuEditedAt, dateStr)

            val headerView = android.widget.TextView(context).apply {
                text = headerText
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
                setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayText))
                setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(4f))
            }

            val contentView = android.widget.TextView(context).apply {
                text = entry.text
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
                setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText))
                setTextIsSelectable(true)
            }

            itemLayout.addView(headerView)
            itemLayout.addView(contentView)

            if (index > 0) {
                val divider = android.view.View(context).apply {
                    setBackgroundColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_divider))
                }
                linear.addView(divider, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, 1, 0f, 4f, 0f, 4f))
            }

            linear.addView(itemLayout)
        }

        val scrollView = android.widget.ScrollView(context).apply {
            addView(linear)
        }

        builder.setCustomView(scrollView)
        val sheet = builder.create()
        if (activity != null) {
            activity.showDialog(sheet)
        } else {
            sheet.show()
        }
    }
}

