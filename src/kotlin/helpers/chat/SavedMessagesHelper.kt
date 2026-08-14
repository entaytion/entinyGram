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

    fun copyMediaFile(account: Int, message: TLRPC.Message?): String? {
        if (message == null || message.media == null) return null
        try {
            val fileLoader = FileLoader.getInstance(account) ?: return null
            val path = fileLoader.getPathToMessage(message)
            if (path != null && path.exists() && path.length() > 0) {
                val mediaDir = getSavedMediaDir()
                val targetFile = File(mediaDir, "${message.dialog_id}_${message.id}_${path.name}")
                if (!targetFile.exists()) {
                    path.copyTo(targetFile, overwrite = true)
                }
                return targetFile.absolutePath
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "copyMediaFile error", e)
        }
        return null
    }

    // In-memory cache for deleted message IDs per account (account -> (dialogId -> Set<msgId>))
    private val deletedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    private val deletedMessageDates = LongSparseArray<LongSparseArray<LongSparseArray<Long>>>()
    private val loadedAccounts = HashSet<Int>()

    // In-memory cache for edit history (account -> (dialogId -> (msgId -> List<EditEntry>)))
    private val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditEntry>>>>()

    data class EditEntry(
        val timestamp: Long,
        val text: String,
        val mediaPath: String? = null
    )

    @JvmStatic
    fun isSaveDeletedEnabled(): Boolean = InuConfig.SAVE_DELETED_MESSAGES.value

    @JvmStatic
    fun isSaveEditedEnabled(): Boolean = InuConfig.SAVE_EDITED_MESSAGES.value

    @JvmStatic
    fun shouldSaveForDialog(account: Int, dialogId: Long): Boolean {
        if (!isSaveDeletedEnabled()) return false
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
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            if (loadedAccounts.contains(account)) return@runOnUIThread
            deletedMessageIds.put(account.toLong(), deletedArray)
            deletedMessageDates.put(account.toLong(), dateArray)
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
                deletedMessageDates.remove(account.toLong())
                editHistoryCache.remove(account.toLong())
                loadedAccounts.remove(account)
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
                            val toRemove = ArrayList(list.filter { it?.id != null && it.id in mids })
                            list.removeAll(toRemove)
                        }
                        val channelId = getChannelId(account, dialogId)
                        org.telegram.messenger.NotificationCenter.getInstance(account)
                            .postNotificationName(
                                org.telegram.messenger.NotificationCenter.messagesDeleted,
                                java.util.ArrayList(mids), channelId, false, false, false, 0
                            )
                    }
                }
                deletedMessageIds.remove(account.toLong())
                deletedMessageDates.remove(account.toLong())
                editHistoryCache.remove(account.toLong())
                loadedAccounts.remove(account)
                onDone?.run()
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun markMessageDeleted(account: Int, dialogId: Long, msgId: Int, fromId: Long, text: String?, date: Int, message: TLRPC.Message? = null, forceSave: Boolean = false) {
        if (!forceSave && !shouldSaveForDialog(account, dialogId)) return
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

        val deletionTime = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000L
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

        val mediaPath = copyMediaFile(account, message)
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveDeletedMessage(db, dialogId, msgId, fromId, text ?: "", deletionTime.toInt(), mediaPath)
        }
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
    fun getDeletedDate(account: Int, dialogId: Long, msgId: Int): Long {
        ensureAccountLoaded(account)
        return deletedMessageDates.get(account.toLong())?.get(dialogId)?.get(msgId.toLong()) ?: 0L
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
            LocaleController.formatString("InuDeletedAt", R.string.InuDeletedAt, timeStr)
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
        if (!isSaveEditedEnabled()) return
        val mediaPath = copyMediaFile(account, message)
        val trimmed = oldText.trim()
        if (trimmed.isBlank() && mediaPath.isNullOrBlank()) return
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
        if (list.isNotEmpty() && list.last().text.trim() == trimmed && list.last().mediaPath == mediaPath) {
            return
        }
        if (list.any { it.text.trim() == trimmed && it.mediaPath == mediaPath }) {
            return
        }
        list.add(EditEntry(now, trimmed, mediaPath))

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveEditHistory(db, dialogId, msgId, trimmed, now.toInt(), mediaPath)
        }
    }

    @JvmStatic
    fun recordEditHistory(account: Int, dialogId: Long, msgId: Int, oldText: String, date: Int) {
        recordEditHistory(account, dialogId, msgId, oldText, date, null)
    }

    @JvmStatic
    fun recordEditHistory(dialogId: Long, msgId: Int, oldText: String) {
        recordEditHistory(UserConfig.selectedAccount, dialogId, msgId, oldText, 0, null)
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
            list = ArrayList(loaded.map { EditEntry(it.first, it.second, it.third) })
            dialogMap.put(msgId.toLong(), list)
        }
        return list
    }

    @JvmStatic
    fun getEditHistory(dialogId: Long, msgId: Int): List<EditEntry> {
        return getEditHistory(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun hasEditHistory(account: Int, dialogId: Long, msgId: Int): Boolean {
        return getEditHistory(account, dialogId, msgId).isNotEmpty()
    }

    @JvmStatic
    fun hasEditHistory(dialogId: Long, msgId: Int): Boolean {
        return hasEditHistory(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, dialogId: Long, msgId: Int) {
        val controller = MessagesController.getInstance(UserConfig.selectedAccount)
        var msgObj: MessageObject? = controller.dialogMessagesByIds.get(msgId)
        if (msgObj == null) {
            val dummyMsg = TLRPC.TL_message().apply {
                id = msgId
                dialog_id = dialogId
            }
            msgObj = MessageObject(UserConfig.selectedAccount, dummyMsg, false, true)
        }
        showEditHistoryDialog(context, activity, msgObj)
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, msgObj: MessageObject) {
        if (context == null || !isSaveEditedEnabled()) return
        val dialogId = msgObj.getDialogId()
        val msgId = msgObj.id
        val account = msgObj.currentAccount
        val history = getEditHistory(account, dialogId, msgId)
        val isEdited = (msgObj.messageOwner != null && (msgObj.messageOwner.flags and TLRPC.MESSAGE_FLAG_EDITED) != 0) || (msgObj.messageOwner?.edit_date ?: 0) != 0
        if (history.isEmpty() && !isEdited) {
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
