package desu.inugram.helpers.chat

import android.app.Activity
import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SparseArray
import android.view.Gravity
import android.widget.FrameLayout
import androidx.collection.LongSparseArray
import androidx.core.content.res.ResourcesCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper

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

    // entiny: resolve archive paths on main thread but defer heavy media copies to storage queue
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

    private val deletedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    private val deletedMessageDates = LongSparseArray<LongSparseArray<LongSparseArray<Long>>>()
    // entiny: keep in-memory media paths for synchronous fallback in FileLoader without SQLite queries
    private val deletedMessageMediaPaths = LongSparseArray<LongSparseArray<LongSparseArray<String>>>()
    private val loadedAccounts = HashSet<Int>()
    private val cacheLock = Any()

    private val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditEntry>>>>()

    // entiny: presence index avoids UI-thread SQLite queries during message layout
    private val editHistoryIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()

    // entiny: synchronous record of permanent deletions so ChatActivity drops bubbles immediately
    private val purgingMessages = HashSet<Pair<Long, Int>>()

    // entiny: bypasses global toggle when user explicitly checks keep-local in delete dialog
    private val pendingKeepLocal = HashSet<Pair<Long, Int>>()

    // entiny: user-initiated deletes must never be archived; timestamped because several readers poll it
    private val pendingPermanentDelete = HashMap<Pair<Long, Int>, Long>()
    private const val PERMANENT_PENDING_TTL_MS = 120_000L

    // entiny: history preview objects avoid stamping history rows with current message's deleted state
    private val historyPreviewObjects: MutableSet<MessageObject> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<MessageObject, Boolean>())

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

        val ttlDays = InuConfig.DELETED_MESSAGES_TTL.value
        if (ttlDays > 0) {
            val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
            InuDatabaseHelper.pruneDeletedMessages(db, cutoff)
            InuDatabaseHelper.pruneEditHistory(db, cutoff)
        }

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
        val mediaPathArray = LongSparseArray<LongSparseArray<String>>()
        InuDatabaseHelper.forEachDeletedMessageMedia(db) { dialogId, msgId, mediaPath ->
            var paths = mediaPathArray.get(dialogId)
            if (paths == null) {
                paths = LongSparseArray()
                mediaPathArray.put(dialogId, paths)
            }
            paths.put(msgId.toLong(), mediaPath)
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

                var existingMediaPaths = deletedMessageMediaPaths.get(account.toLong())
                if (existingMediaPaths == null) {
                    deletedMessageMediaPaths.put(account.toLong(), mediaPathArray)
                } else {
                    for (i in 0 until mediaPathArray.size()) {
                        val k = mediaPathArray.keyAt(i)
                        val v = mediaPathArray.valueAt(i)
                        val targetPaths = existingMediaPaths.get(k)
                        if (targetPaths == null) {
                            existingMediaPaths.put(k, v)
                        } else {
                            for (j in 0 until v.size()) {
                                targetPaths.put(v.keyAt(j), v.valueAt(j))
                            }
                        }
                    }
                }
                loadedAccounts.add(account)
            }
        }
    }

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

    private fun getChannelId(account: Int, dialogId: Long): Long {
        if (!org.telegram.messenger.DialogObject.isChatDialog(dialogId)) return 0L
        val controller = org.telegram.messenger.MessagesController.getInstance(account) ?: return 0L
        val chat = controller.getChat(-dialogId)
        return if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat)) chat.id else 0L
    }

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

    @JvmStatic
    @JvmOverloads
    fun deletePermanently(account: Int, dialogId: Long, msgIds: List<Int>, onDone: Runnable? = null) {
        dropGhostsFromChat(account, dialogId, msgIds, true, onDone)
    }

    // entiny: purgeArchive=false hides the ghost bubble but keeps the saved copy (archive row, media, edit history)
    private fun dropGhostsFromChat(account: Int, dialogId: Long, msgIds: List<Int>, purgeArchive: Boolean, onDone: Runnable? = null) {
        if (msgIds.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        // entiny: drop from in-memory set before broadcasting deletion so ghost bubbles don't survive
        if (purgeArchive) {
            synchronized(cacheLock) {
                val ids = deletedMessageIds.get(account.toLong())?.get(dialogId)
                val dates = deletedMessageDates.get(account.toLong())?.get(dialogId)
                for (msgId in msgIds) {
                    ids?.remove(msgId)
                    dates?.remove(msgId.toLong())
                }
            }
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            if (purgeArchive) {
                InuDatabaseHelper.deleteDeletedMessageEntries(db, dialogId, msgIds)
                InuDatabaseHelper.deleteEditHistory(db, dialogId, msgIds)
            }
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, msgIds)
            val channelId = getChannelId(account, dialogId)
            storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(msgIds), null)
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (purgeArchive) {
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

    @JvmStatic
    @JvmOverloads
    fun extractPreserved(
        account: Int,
        dialogId: Long,
        msgIds: MutableList<Int>?,
        permanent: Boolean,
        fragment: BaseFragment? = null,
    ): Boolean {
        if (msgIds.isNullOrEmpty() || !isSaveDeletedEnabled()) return false
        val preserved = msgIds.filter { isMessageDeleted(account, dialogId, it) }
        if (preserved.isEmpty()) {
            val stale = findStaleArchiveEntries(account, msgIds)
            if (stale.isEmpty()) return false
            if (permanent) {
                for ((did, mids) in stale) deletePermanently(account, did, mids, null)
                showPurgedBulletin(fragment)
            }
            return true
        }
        msgIds.removeAll(preserved.toHashSet())
        synchronized(cacheLock) {
            for (id in preserved) purgingMessages.add(dialogId to id)
        }
        if (permanent) {
            deletePermanently(account, dialogId, preserved) { showPurgedBulletin(fragment) }
        } else {
            dropGhostsFromChat(account, dialogId, preserved, false, null)
        }
        return true
    }

    // entiny: archive rows stored under a wrong dialog key (storage-guess / saved-subfolder mismatch) stay purgeable
    private fun findStaleArchiveEntries(account: Int, msgIds: List<Int>): Map<Long, List<Int>> {
        val stale = HashMap<Long, ArrayList<Int>>()
        synchronized(cacheLock) {
            val dialogs = deletedMessageIds.get(account.toLong()) ?: return@synchronized
            for (mid in msgIds) {
                for (i in 0 until dialogs.size()) {
                    if (dialogs.valueAt(i)?.contains(mid) == true) {
                        stale.getOrPut(dialogs.keyAt(i)) { ArrayList() }.add(mid)
                    }
                }
            }
        }
        return stale
    }

    private fun showPurgedBulletin(fragment: BaseFragment?) {
        val activity = fragment?.parentActivity ?: return
        val drawable = ResourcesCompat.getDrawable(activity.resources, R.drawable.inu_tabler_trash_x, null)?.mutate() ?: return
        BulletinFactory.of(fragment).createSimpleBulletin(
            drawable,
            LocaleController.getString(R.string.InuDeletePermanentlyDone),
        ).show()
    }

    @JvmStatic
    fun hasPreservedSelection(
        account: Int,
        selectedMessage: MessageObject?,
        selectedMessages: Array<SparseArray<MessageObject>>?,
        selectedGroup: MessageObject.GroupedMessages?,
    ): Boolean {
        if (!isSaveDeletedEnabled()) return false
        val targets = ArrayList<Pair<Long, Int>>()
        if (selectedMessage != null) {
            if (selectedGroup != null) {
                for (msg in selectedGroup.messages) targets.add(msg.dialogId to msg.id)
            } else {
                targets.add(selectedMessage.dialogId to selectedMessage.id)
            }
        } else if (selectedMessages != null) {
            for (sparse in selectedMessages) {
                if (sparse == null) continue
                for (i in 0 until sparse.size()) {
                    val msg = sparse.valueAt(i) ?: continue
                    targets.add(msg.dialogId to msg.id)
                }
            }
        }
        return targets.any { isMessageDeleted(account, it.first, it.second) }
    }

    @JvmStatic
    fun decorateDeleteAlert(
        builder: AlertDialog.Builder,
        box: FrameLayout?,
        activity: Activity,
        resourcesProvider: Theme.ResourcesProvider?,
        account: Int,
        selectedMessage: MessageObject?,
        selectedMessages: Array<SparseArray<MessageObject>>?,
        selectedGroup: MessageObject.GroupedMessages?,
        state: BooleanArray,
    ) {
        if (!hasPreservedSelection(account, selectedMessage, selectedMessages, selectedGroup)) return
        var container = box
        if (container == null) {
            container = FrameLayout(activity)
            builder.setView(container)
            builder.setCustomViewOffset(9)
        }
        val cell = CheckBoxCell(activity, 1, resourcesProvider)
        cell.background = Theme.getSelectorDrawable(false)
        cell.setText(LocaleController.getString(R.string.InuDeletePermanently), "", false, false)
        cell.setPadding(
            AndroidUtilities.dp(if (LocaleController.isRTL) 16f else 8f), 0,
            AndroidUtilities.dp(if (LocaleController.isRTL) 8f else 16f), 0,
        )
        container.addView(
            cell,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT.toFloat(), 48f,
                Gravity.TOP or Gravity.LEFT,
                0f, 48f * container.childCount, 0f, 0f,
            ),
        )
        cell.setOnClickListener { v ->
            state[0] = !state[0]
            (v as CheckBoxCell).setChecked(state[0], true)
        }
        // entiny: manual delete of a ghost defaults to a full purge, matching the old menu item
        state[0] = true
        cell.setChecked(true, false)
    }

    @JvmStatic
    fun requestKeepLocal(dialogId: Long, msgIds: Collection<Int>) {
        if (msgIds.isEmpty()) return
        synchronized(cacheLock) {
            for (id in msgIds) pendingKeepLocal.add(dialogId to id)
        }
    }

    @JvmStatic
    fun hasPendingKeepLocal(dialogId: Long, msgIds: Collection<Int>): Boolean {
        if (msgIds.isEmpty()) return false
        synchronized(cacheLock) {
            return msgIds.any { dialogId to it in pendingKeepLocal }
        }
    }

    @JvmStatic
    fun consumeKeepLocalRequest(dialogId: Long, msgId: Int): Boolean {
        synchronized(cacheLock) {
            return pendingKeepLocal.remove(dialogId to msgId)
        }
    }

    @JvmStatic
    fun requestPermanentDelete(dialogId: Long, msgIds: Collection<Int>) {
        if (msgIds.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            for (id in msgIds) pendingPermanentDelete[dialogId to id] = now
        }
    }

    @JvmStatic
    fun isPermanentDeleteRequested(dialogId: Long, msgId: Int): Boolean {
        val key = dialogId to msgId
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val ts = pendingPermanentDelete[key] ?: return false
            if (now - ts > PERMANENT_PENDING_TTL_MS) {
                pendingPermanentDelete.remove(key)
                return false
            }
            return true
        }
    }

    // entiny: mids are account-unique; cancel-upload keys may be recorded under a different dialog than the archive hook
    @JvmStatic
    fun isPermanentDeleteRequestedForMid(msgId: Int): Boolean {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val iterator = pendingPermanentDelete.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > PERMANENT_PENDING_TTL_MS) {
                    iterator.remove()
                } else if (entry.key.second == msgId) {
                    return true
                }
            }
            return false
        }
    }

    // entiny: with save-deleted on, stock skips messages_v2 cleanup for every row; delete the ones that
    // were not archived (own delete, off-category, permanent request) so their bubbles don't resurrect on reload
    @JvmStatic
    fun deleteNonPreservedFromStorage(
        account: Int,
        db: org.telegram.SQLite.SQLiteDatabase,
        dialogId: Long,
        mids: List<Int>?,
    ) {
        if (mids.isNullOrEmpty()) return
        val doomed = mids.filter {
            !isMessageDeleted(account, dialogId, it) || isPermanentDeleteRequested(dialogId, it) || isPermanentDeleteRequestedForMid(it)
        }
        if (doomed.isNotEmpty()) {
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, doomed)
        }
    }

    // entiny: a deletion is only worth archiving when we actually hold something to preserve
    private fun hasPreservableData(text: String?, message: TLRPC.Message?): Boolean {
        if (!text.isNullOrBlank()) return true
        val media = message?.media ?: return false
        return media !is TLRPC.TL_messageMediaEmpty
    }

    @JvmStatic
    @JvmOverloads
    fun markMessageDeleted(account: Int, dialogId: Long, msgId: Int, fromId: Long, text: String?, date: Int, message: TLRPC.Message? = null, forceSave: Boolean = false) {
        // entiny: reject dialog id 0 to prevent marking matching IDs in unrelated chats as deleted
        if (dialogId == 0L) return
        if (!forceSave && !shouldSaveForDialog(account, dialogId)) return
        if (!forceSave && !InuConfig.SAVE_DELETED_OWN.value && (fromId == UserConfig.getInstance(account).clientUserId || message?.out == true)) return
        if (!forceSave && isPermanentDeleteRequested(dialogId, msgId)) return
        if (!forceSave && isPermanentDeleteRequestedForMid(msgId)) return
        // entiny: prevent empty text from subsequent delete reports overwriting preserved text
        val alreadyRecorded = isMessageDeleted(account, dialogId, msgId)
        if (alreadyRecorded && text.isNullOrEmpty() && message?.media == null) return
        // entiny: deletions for messages never loaded locally carry no data; recording them
        // created phantom ghosts that littered chats with deleted-media placeholders
        if (!forceSave && !alreadyRecorded && !hasPreservableData(text, message)) return
        ensureAccountLoaded(account)
        val deletionTime = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000L
        val mediaCopy = planMediaCopy(account, message)
        val mediaPath = mediaCopy?.target?.absolutePath
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

            if (mediaPath != null) {
                var mediaAcc = deletedMessageMediaPaths.get(account.toLong())
                if (mediaAcc == null) {
                    mediaAcc = LongSparseArray()
                    deletedMessageMediaPaths.put(account.toLong(), mediaAcc)
                }
                var mediaDialog = mediaAcc.get(dialogId)
                if (mediaDialog == null) {
                    mediaDialog = LongSparseArray()
                    mediaAcc.put(dialogId, mediaDialog)
                }
                mediaDialog.put(msgId.toLong(), mediaPath)
            }
        }

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            runMediaCopy(mediaCopy)
            InuDatabaseHelper.saveDeletedMessage(db, dialogId, msgId, fromId, text ?: "", deletionTime.toInt(), mediaPath)
        }
    }

    @JvmStatic
    fun isPreservedOrWillBe(account: Int, msg: MessageObject?): Boolean {
        if (msg == null) return false
        val dialogId = msg.getDialogId()
        val msgId = msg.id
        synchronized(cacheLock) {
            if (purgingMessages.contains(dialogId to msgId)) return false
        }
        if (isPermanentDeleteRequested(dialogId, msgId)) return false
        if (isMessageDeleted(account, dialogId, msgId)) return true
        // entiny: nothing to archive means nothing to preserve — drop the bubble instead of
        // leaving an in-memory ghost that survives until cache clear
        if (!hasPreservableData(msg.messageOwner?.message, msg.messageOwner)) return false
        if (!shouldSaveForDialog(account, dialogId)) return false
        if (!InuConfig.SAVE_DELETED_OWN.value && msg.isOutOwner) return false
        return true
    }

    // entiny: record deletions with dialog ids resolved by MessagesStorage from messages_v2
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
    fun getArchivedMediaPath(account: Int, dialogId: Long, msgId: Int): File? {
        if (!isSaveDeletedEnabled()) return null
        ensureAccountLoaded(account)
        val path = synchronized(cacheLock) {
            deletedMessageMediaPaths.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())
        } ?: return null
        val file = File(path)
        return if (file.exists()) file else null
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
        // entiny: preserve formatting entities only when text trimming did not shift entity offsets
        val keptEntities = if (entities.isNullOrEmpty() || trimmed != oldText) null else ArrayList(entities)
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

    @JvmStatic
    fun rememberMessageText(account: Int, dialogId: Long, msgId: Int, text: String?, hasMedia: Boolean, date: Int) {
        rememberMessage(account, dialogId, msgId, text, hasMedia, date, null, null)
    }

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
                if (media is TLRPC.TL_messageMediaEmpty) null else media,
            )
        }
    }

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
    fun recordEditHistoryIfChanged(account: Int, dialogId: Long, msgId: Int, oldMessage: TLRPC.Message?, newMessage: TLRPC.Message) {
        if (oldMessage == null || !isSaveEditedEnabled()) return
        val textChanged = oldMessage.message != null && oldMessage.message != newMessage.message
        // entiny: compare the actual attachment, "has media" is set on every ordinary update
        val mediaChanged = MessageObject.getFileName(oldMessage) != MessageObject.getFileName(newMessage)
        if (textChanged || mediaChanged) {
            recordEditHistory(account, dialogId, msgId, oldMessage.message ?: "", oldMessage.date, oldMessage)
        }
    }

    @JvmStatic
    fun handleMessageEdited(account: Int, newMessage: TLRPC.Message, cached: MessageObject?) {
        if (!isSaveEditedEnabled()) return
        val old = cached?.messageOwner
        if (old != null) {
            recordEditHistoryIfChanged(account, newMessage.dialog_id, newMessage.id, old, newMessage)
        } else {
            // entiny: dialogMessagesByIds holds only the dialog preview, so fall back to the shadow cache
            recordEditHistoryFromShadow(account, newMessage.dialog_id, newMessage.id, newMessage.message, newMessage.media != null)
        }
    }

    @JvmStatic
    fun recordEditHistory(dialogId: Long, msgId: Int, oldText: String) {
        recordEditHistory(UserConfig.selectedAccount, dialogId, msgId, oldText, 0, null)
    }

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
                // entiny: require peer so MessageObject layout generation does not bail out without text
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
