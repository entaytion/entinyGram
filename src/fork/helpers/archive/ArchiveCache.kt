package desu.inugram.helpers.archive

import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLRPC

object ArchiveCache {
    private const val TAG = "ArchiveCache"

    internal fun rememberDeletedMediaPath(account: Int, dialogId: Long, msgId: Int, path: String) {
        synchronized(cacheLock) {
            val paths = deletedMessageMediaPaths.get(account.toLong()) ?: LongSparseArray<LongSparseArray<String>>().also { deletedMessageMediaPaths.put(account.toLong(), it) }
            val dialogPaths = paths.get(dialogId) ?: LongSparseArray<String>().also { paths.put(dialogId, it) }
            dialogPaths.put(msgId.toLong(), path)
        }
    }

    internal val deletedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    internal val deletedMessageDates = LongSparseArray<LongSparseArray<LongSparseArray<Long>>>()
    // entiny: keep in-memory media paths for synchronous fallback in FileLoader without SQLite queries
    internal val deletedMessageMediaPaths = LongSparseArray<LongSparseArray<LongSparseArray<String>>>()
    private val loadedAccounts = HashSet<Int>()
    private val loadingAccounts = HashSet<Int>()
    internal val cacheLock = Any()
    private val cacheGeneration = HashMap<Int, Long>()

    internal val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditHistoryHelper.EditEntry>>>>()

    // entiny: presence index avoids UI-thread SQLite queries during message layout
    internal val editHistoryIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()

    private const val SHADOW_CACHE_MAX_PER_ACCOUNT = 500

    private class BoundedLinkedHashMap<K, V>(private val maxSize: Int) :
        LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    internal data class ShadowEntry(
        val text: String,
        val date: Int,
        val editDate: Int,
        val entities: ArrayList<TLRPC.MessageEntity>?,
        val media: TLRPC.MessageMedia?,
    )

    private val shadowMessageCache = LongSparseArray<LinkedHashMap<Pair<Long, Int>, ShadowEntry>>()

    private const val LOAD_PAGE_ROWS = 2000
    private const val LOAD_RETRY_MS = 60_000L
    private val loadFailedAt = HashMap<Int, Long>()

    private class ArchiveIndex(val generation: Long) {
        val deleted = LongSparseArray<HashSet<Int>>()
        val dates = LongSparseArray<LongSparseArray<Long>>()
        val media = LongSparseArray<LongSparseArray<String>>()
        val edits = LongSparseArray<HashSet<Int>>()
        var lastDeletedRow = 0L
        var lastEditRow = 0L
        var deletedDone = false
        var rows = 0
        var busyMs = 0L
    }

    @JvmStatic
    fun ensureAccountLoaded(account: Int) {
        val generation = synchronized(cacheLock) {
            if (loadedAccounts.contains(account) || loadingAccounts.contains(account)) return
            val failed = loadFailedAt[account]
            if (failed != null && android.os.SystemClock.elapsedRealtime() - failed < LOAD_RETRY_MS) return
            loadingAccounts.add(account)
            cacheGeneration[account] ?: 0L
        }
        val storage = MessagesStorage.getInstance(account)
        if (storage == null) {
            synchronized(cacheLock) { loadingAccounts.remove(account) }
            return
        }
        val index = ArchiveIndex(generation)
        storage.storageQueue.postRunnable { loadIndexPage(account, storage, index) }
    }

    // entiny: one page per runnable so dialog and message loads can run between pages
    private fun loadIndexPage(account: Int, storage: MessagesStorage, index: ArchiveIndex) {
        if (synchronized(cacheLock) { (cacheGeneration[account] ?: 0L) != index.generation }) return
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val db = storage.database ?: throw IllegalStateException("database closed")
            val finished: Boolean
            if (!index.deletedDone) {
                val count = InuDatabaseHelper.readDeletedIndexPage(db, index.lastDeletedRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId, date, mediaPath ->
                    index.lastDeletedRow = rowId
                    (index.deleted.get(dialogId) ?: HashSet<Int>().also { index.deleted.put(dialogId, it) }).add(msgId)
                    if (date > 0) (index.dates.get(dialogId) ?: LongSparseArray<Long>().also { index.dates.put(dialogId, it) }).put(msgId.toLong(), date)
                    if (mediaPath != null) (index.media.get(dialogId) ?: LongSparseArray<String>().also { index.media.put(dialogId, it) }).put(msgId.toLong(), mediaPath)
                }
                index.rows += count
                if (count < LOAD_PAGE_ROWS) index.deletedDone = true
                finished = false
            } else {
                val count = InuDatabaseHelper.readEditIndexPage(db, index.lastEditRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId ->
                    index.lastEditRow = rowId
                    (index.edits.get(dialogId) ?: HashSet<Int>().also { index.edits.put(dialogId, it) }).add(msgId)
                }
                index.rows += count
                finished = count < LOAD_PAGE_ROWS
            }
            index.busyMs += android.os.SystemClock.elapsedRealtime() - started
            if (finished) finishIndex(account, index) else storage.storageQueue.postRunnable { loadIndexPage(account, storage, index) }
        } catch (e: Throwable) {
            synchronized(cacheLock) {
                loadingAccounts.remove(account)
                loadFailedAt[account] = android.os.SystemClock.elapsedRealtime()
            }
            android.util.Log.e(TAG, "load archive failed", e)
        }
    }

    private fun finishIndex(account: Int, index: ArchiveIndex) {
        val generation = index.generation
        val deletedArray = index.deleted
        val dateArray = index.dates
        val editArray = index.edits
        val mediaPathArray = index.media
        android.util.Log.d(TAG, "archive index: ${index.rows} rows, ${index.busyMs} ms on storage queue")
        ArchiveMediaHelper.scheduleMediaMigration(account)
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            synchronized(cacheLock) {
                if ((cacheGeneration[account] ?: 0L) != generation) return@runOnUIThread
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
                loadingAccounts.remove(account)
                loadFailedAt.remove(account)
            }
        }
    }

    @JvmStatic
    fun schedulePrune(account: Int) {
        if (InuConfig.DELETED_MESSAGES_TTL.value == 0) return
        MessagesStorage.getInstance(account)?.storageQueue?.postRunnable({ pruneIfNeeded(account) }, ArchiveMediaHelper.STARTUP_DELAY_MS)
    }

    @JvmStatic
    fun pruneIfNeeded(account: Int) {
        val ttlDays = InuConfig.DELETED_MESSAGES_TTL.value
        if (ttlDays == 0) return
        val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val started = android.os.SystemClock.elapsedRealtime()
            val result = try {
                val pruned = InuDatabaseHelper.pruneDeletedMessages(db, cutoff)
                for ((dialogId, mids) in pruned) {
                    storage.updateDialogsWithDeletedMessages(dialogId, getChannelId(account, dialogId), ArrayList(mids), null)
                }
                pruned to InuDatabaseHelper.pruneEditHistory(db, cutoff)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "archive prune failed", e)
                null
            } ?: return@postRunnable
            val (expired, editsPruned) = result
            android.util.Log.d(TAG, "archive prune: ${expired.values.sumOf { it.size }} deleted, $editsPruned edits in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            if (expired.isEmpty() && editsPruned == 0) return@postRunnable
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
                    loadingAccounts.remove(account)
                    deletedMessageIds.remove(account.toLong())
                    deletedMessageMediaPaths.remove(account.toLong())
                    deletedMessageDates.remove(account.toLong())
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                    shadowMessageCache.remove(account.toLong())
                    loadedAccounts.remove(account)
                }
                val controller = MessagesController.getInstance(account)
                for ((dialogId, mids) in expired) {
                    val idSet = mids.toHashSet()
                    controller.dialogMessage.get(dialogId)?.removeAll { it != null && it.id in idSet }
                    for (mid in mids) {
                        if (controller.dialogMessagesByIds.get(mid)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(mid)
                    }
                    org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                        org.telegram.messenger.NotificationCenter.messagesDeleted,
                        ArrayList(mids), getChannelId(account, dialogId), false, false, false, 0
                    )
                }
                ensureAccountLoaded(account)
            }
        }
    }

    internal fun getChannelId(account: Int, dialogId: Long): Long {
        if (!org.telegram.messenger.DialogObject.isChatDialog(dialogId)) return 0L
        val controller = org.telegram.messenger.MessagesController.getInstance(account) ?: return 0L
        val chat = controller.getChat(-dialogId)
        return if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat)) chat.id else 0L
    }

    @JvmStatic
    fun clearEditHistory(account: Int, onDone: Runnable? = null) {
        clearEditHistory(account, null, onDone)
    }

    @JvmStatic
    fun clearEditHistory(account: Int, dialogIds: Collection<Long>?, onDone: Runnable?) {
        ArchiveMediaHelper.resetArchiveUsage(account)
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            try {
                storage.database?.let { InuDatabaseHelper.clearEditHistory(it, dialogIds) }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "clear edit history failed", e)
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
                    loadedAccounts.remove(account)
                    loadingAccounts.remove(account)
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                }
                ensureAccountLoaded(account)
                onDone?.run()
            }
        }
    }

    @JvmStatic
    fun clearCache(account: Int, dialogIds: Collection<Long>? = null, deletedOnly: Boolean = false, onDone: Runnable? = null) {
        ArchiveMediaHelper.resetArchiveUsage(account)
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database
            val pairs = if (db != null) InuDatabaseHelper.getDeletedMessageIds(db, dialogIds) else emptyMap()
            if (db != null) {
                InuDatabaseHelper.clearDeletedMessages(db, dialogIds, !deletedOnly)
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
                            if (controller.dialogMessagesByIds.get(mid)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(mid)
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
                    cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
                    loadingAccounts.remove(account)
                    deletedMessageIds.remove(account.toLong())
                    deletedMessageMediaPaths.remove(account.toLong())
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

    internal fun findShadowDialog(account: Int, msgId: Int): Long? = synchronized(cacheLock) {
        shadowMessageCache.get(account.toLong())?.keys?.lastOrNull { it.second == msgId && it.first > 0 }?.first
    }

    @JvmStatic
    fun rememberMessage(account: Int, message: TLRPC.Message?) {
        if (message == null) return
        if (!EditHistoryHelper.isSaveEditedEnabled() && !DeletedMessagesHelper.isSaveDeletedEnabled()) return
        putShadow(account, message)
    }

    internal fun shadow(account: Int, dialogId: Long, msgId: Int): ShadowEntry? = synchronized(cacheLock) {
        shadowMessageCache.get(account.toLong())?.get(dialogId to msgId)
    }

    internal fun updateShadow(account: Int, message: TLRPC.Message) {
        if (shadow(account, MessageObject.getDialogId(message), message.id) != null) putShadow(account, message)
    }

    private fun putShadow(account: Int, message: TLRPC.Message) {
        val entry = ShadowEntry(
            message.message ?: "",
            message.date,
            message.edit_date,
            if (message.entities.isNullOrEmpty()) null else ArrayList(message.entities),
            InuDatabaseHelper.cloneMedia(message.media),
        )
        synchronized(cacheLock) {
            val map = shadowMessageCache.get(account.toLong())
                ?: BoundedLinkedHashMap<Pair<Long, Int>, ShadowEntry>(SHADOW_CACHE_MAX_PER_ACCOUNT)
                    .also { shadowMessageCache.put(account.toLong(), it) }
            map[MessageObject.getDialogId(message) to message.id] = entry
        }
    }

    fun invalidateMediaCache(account: Int) {
        ArchiveMediaHelper.resetArchiveUsage(account)
        synchronized(cacheLock) {
            deletedMessageMediaPaths.remove(account.toLong())
            editHistoryCache.remove(account.toLong())
            cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
            loadedAccounts.remove(account)
            loadingAccounts.remove(account)
        }
    }
}
