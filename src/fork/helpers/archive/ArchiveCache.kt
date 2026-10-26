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

    internal val deletedMessageIds = LongSparseArray<LongSparseArray<DeletedIdSet>>()
    // entiny: keep in-memory media paths for synchronous fallback in FileLoader without SQLite queries
    internal val deletedMessageMediaPaths = LongSparseArray<LongSparseArray<LongSparseArray<String>>>()
    private val loadedAccounts = HashSet<Int>()
    private val loadingAccounts = HashSet<Int>()
    internal val cacheLock = Any()
    private val cacheGeneration = HashMap<Int, Long>()

    internal val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditHistoryHelper.EditEntry>>>>()

    // entiny: presence index avoids UI-thread SQLite queries during message layout
    internal val editHistoryIds = LongSparseArray<LongSparseArray<DeletedIdSet>>()

    /** sorted ids with deletion dates: ~12 bytes per entry instead of a boxed HashSet plus two sparse maps */
    internal class DeletedIdSet {
        private var ids = IntArray(16)
        private var dates = LongArray(16)
        private var count = 0

        fun size(): Int = count

        private fun indexOf(id: Int): Int {
            var lo = 0
            var hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = ids[mid]
                when {
                    v < id -> lo = mid + 1
                    v > id -> hi = mid - 1
                    else -> return mid
                }
            }
            return -(lo + 1)
        }

        fun contains(id: Int): Boolean = indexOf(id) >= 0

        fun dateOf(id: Int): Long {
            val pos = indexOf(id)
            return if (pos >= 0) dates[pos] else 0L
        }

        fun add(id: Int, date: Long): Boolean {
            val pos = indexOf(id)
            if (pos >= 0) {
                if (date > 0) dates[pos] = date
                return false
            }
            val at = -(pos + 1)
            ensure(count + 1)
            if (at < count) {
                System.arraycopy(ids, at, ids, at + 1, count - at)
                System.arraycopy(dates, at, dates, at + 1, count - at)
            }
            ids[at] = id
            dates[at] = date
            count++
            return true
        }

        fun remove(id: Int): Boolean {
            val pos = indexOf(id)
            if (pos < 0) return false
            if (pos < count - 1) {
                System.arraycopy(ids, pos + 1, ids, pos, count - pos - 1)
                System.arraycopy(dates, pos + 1, dates, pos, count - pos - 1)
            }
            count--
            return true
        }

        fun addAll(other: DeletedIdSet) {
            if (other.count == 0) return
            if (count == 0) {
                ids = other.ids.copyOf(other.count)
                dates = other.dates.copyOf(other.count)
                count = other.count
                return
            }
            val mergedIds = IntArray(count + other.count)
            val mergedDates = LongArray(count + other.count)
            var i = 0
            var j = 0
            var k = 0
            while (i < count && j < other.count) {
                val a = ids[i]
                val b = other.ids[j]
                when {
                    a < b -> {
                        mergedIds[k] = a
                        mergedDates[k] = dates[i]
                        i++
                    }
                    a > b -> {
                        mergedIds[k] = b
                        mergedDates[k] = other.dates[j]
                        j++
                    }
                    else -> {
                        mergedIds[k] = a
                        mergedDates[k] = if (dates[i] > 0) dates[i] else other.dates[j]
                        i++
                        j++
                    }
                }
                k++
            }
            while (i < count) {
                mergedIds[k] = ids[i]
                mergedDates[k] = dates[i]
                i++
                k++
            }
            while (j < other.count) {
                mergedIds[k] = other.ids[j]
                mergedDates[k] = other.dates[j]
                j++
                k++
            }
            ids = mergedIds
            dates = mergedDates
            count = k
        }

        private fun ensure(capacity: Int) {
            if (capacity <= ids.size) return
            val grown = maxOf(capacity, ids.size + (ids.size shr 1))
            ids = ids.copyOf(grown)
            dates = dates.copyOf(grown)
        }
    }

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
        val deleted = LongSparseArray<DeletedIdSet>()
        val media = LongSparseArray<LongSparseArray<String>>()
        val edits = LongSparseArray<DeletedIdSet>()
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
                    (index.deleted.get(dialogId) ?: DeletedIdSet().also { index.deleted.put(dialogId, it) }).add(msgId, date)
                    if (mediaPath != null) (index.media.get(dialogId) ?: LongSparseArray<String>().also { index.media.put(dialogId, it) }).put(msgId.toLong(), mediaPath)
                }
                index.rows += count
                if (count < LOAD_PAGE_ROWS) index.deletedDone = true
                finished = false
            } else {
                val count = InuDatabaseHelper.readEditIndexPage(db, index.lastEditRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId ->
                    index.lastEditRow = rowId
                    (index.edits.get(dialogId) ?: DeletedIdSet().also { index.edits.put(dialogId, it) }).add(msgId, 0L)
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
