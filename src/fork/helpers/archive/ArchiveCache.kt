package desu.inugram.helpers.archive

import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.MessageObject
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

    internal fun rememberKept(account: Int, dialogId: Long, msgId: Int, date: Long) {
        synchronized(cacheLock) {
            val dialogs = deletedMessageIds.get(account.toLong())
                ?: LongSparseArray<DeletedIdSet>().also { deletedMessageIds.put(account.toLong(), it) }
            val set = dialogs.get(dialogId) ?: DeletedIdSet().also { dialogs.put(dialogId, it) }
            set.add(msgId, date)
        }
    }

    internal fun forgetKept(account: Int, dialogId: Long, msgId: Int) {
        synchronized(cacheLock) {
            deletedMessageIds.get(account.toLong())?.get(dialogId)?.remove(msgId)
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
                dates[pos] = firstDeletionDate(dates[pos], date)
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
                        mergedDates[k] = firstDeletionDate(dates[i], other.dates[j])
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

        private fun firstDeletionDate(previous: Long, incoming: Long): Long = when {
            previous <= 0 -> incoming
            incoming <= 0 -> previous
            else -> minOf(previous, incoming)
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
        val message: TLRPC.Message?,
    )

    private val shadowMessageCache = LongSparseArray<LinkedHashMap<Pair<Long, Int>, ShadowEntry>>()

    private const val LOAD_PAGE_ROWS = 2000
    private const val LOAD_RETRY_MS = 60_000L
    private val loadFailedAt = HashMap<Int, Long>()

    private class ArchiveIndex(val generation: Long) {
        val deleted = LongSparseArray<DeletedIdSet>()
        val media = LongSparseArray<LongSparseArray<String>>()
        val edits = LongSparseArray<DeletedIdSet>()
        // entiny: rows are buffered per page and merged once, arraycopy-shifting the master set per row is O(n^2)
        val pageDeleted = LongSparseArray<DeletedIdSet>()
        val pageEdits = LongSparseArray<DeletedIdSet>()
        var lastDeletedRow = 0L
        var lastEditRow = 0L
        var deletedDone = false
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

    private fun mergePageSets(page: LongSparseArray<DeletedIdSet>, master: LongSparseArray<DeletedIdSet>) {
        for (i in 0 until page.size()) {
            val dialogId = page.keyAt(i)
            val set = master.get(dialogId) ?: DeletedIdSet().also { master.put(dialogId, it) }
            set.addAll(page.valueAt(i))
        }
        page.clear()
    }

    // entiny: one page per runnable so dialog and message loads can run between pages
    private fun loadIndexPage(account: Int, storage: MessagesStorage, index: ArchiveIndex) {
        if (synchronized(cacheLock) { (cacheGeneration[account] ?: 0L) != index.generation }) return
        try {
            val db = storage.database ?: throw IllegalStateException("database closed")
            val finished: Boolean
            if (!index.deletedDone) {
                val count = ArchiveStore.readDeletedIndexPage(db, index.lastDeletedRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId, date, mediaPath ->
                    index.lastDeletedRow = rowId
                    (index.pageDeleted.get(dialogId) ?: DeletedIdSet().also { index.pageDeleted.put(dialogId, it) }).add(msgId, date)
                    if (mediaPath != null) (index.media.get(dialogId) ?: LongSparseArray<String>().also { index.media.put(dialogId, it) }).put(msgId.toLong(), mediaPath)
                }
                mergePageSets(index.pageDeleted, index.deleted)
                if (count < LOAD_PAGE_ROWS) index.deletedDone = true
                finished = false
            } else {
                val count = ArchiveStore.readEditIndexPage(db, index.lastEditRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId ->
                    index.lastEditRow = rowId
                    (index.pageEdits.get(dialogId) ?: DeletedIdSet().also { index.pageEdits.put(dialogId, it) }).add(msgId, 0L)
                }
                mergePageSets(index.pageEdits, index.edits)
                finished = count < LOAD_PAGE_ROWS
            }
            if (finished) finishIndex(account, index) else storage.storageQueue.postRunnable { loadIndexPage(account, storage, index) }
        } catch (e: Throwable) {
            synchronized(cacheLock) {
                loadingAccounts.remove(account)
                loadFailedAt[account] = android.os.SystemClock.elapsedRealtime()
            }
            android.util.Log.e(TAG, "load archive failed", e)
        }
    }

    private fun mergeSets(all: LongSparseArray<LongSparseArray<DeletedIdSet>>, account: Int, loaded: LongSparseArray<DeletedIdSet>) {
        val existing = all.get(account.toLong())
        if (existing == null) {
            all.put(account.toLong(), loaded)
            return
        }
        for (i in 0 until loaded.size()) {
            val target = existing.get(loaded.keyAt(i))
            if (target == null) existing.put(loaded.keyAt(i), loaded.valueAt(i)) else target.addAll(loaded.valueAt(i))
        }
    }

    private fun finishIndex(account: Int, index: ArchiveIndex) {
        ArchiveMediaHelper.scheduleMediaMigration(account)
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            synchronized(cacheLock) {
                if ((cacheGeneration[account] ?: 0L) != index.generation) return@runOnUIThread
                mergeSets(editHistoryIds, account, index.edits)
                mergeSets(deletedMessageIds, account, index.deleted)
                val paths = deletedMessageMediaPaths.get(account.toLong())
                if (paths == null) {
                    deletedMessageMediaPaths.put(account.toLong(), index.media)
                } else {
                    for (i in 0 until index.media.size()) {
                        val loaded = index.media.valueAt(i)
                        val target = paths.get(index.media.keyAt(i))
                        if (target == null) paths.put(index.media.keyAt(i), loaded) else for (j in 0 until loaded.size()) target.put(loaded.keyAt(j), loaded.valueAt(j))
                    }
                }
                loadedAccounts.add(account)
                loadingAccounts.remove(account)
                loadFailedAt.remove(account)
            }
        }
    }

    private fun resetAccount(account: Int, deleted: Boolean) {
        synchronized(cacheLock) {
            cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
            loadedAccounts.remove(account)
            loadingAccounts.remove(account)
            editHistoryCache.remove(account.toLong())
            editHistoryIds.remove(account.toLong())
            if (deleted) {
                deletedMessageIds.remove(account.toLong())
                deletedMessageMediaPaths.remove(account.toLong())
                shadowMessageCache.remove(account.toLong())
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
            val expired = try {
                ArchiveStore.expiredDeletedMessageIds(db, cutoff)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "archive prune failed", e)
                return@postRunnable
            }
            val editsPruned = try {
                ArchiveStore.pruneEditHistory(db, cutoff)
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "edit history prune failed", e)
                0
            }
            if (expired.isEmpty() && editsPruned == 0) return@postRunnable
            for ((dialogId, mids) in expired) {
                DeletedMessagesHelper.purgeInStorage(account, storage, db, dialogId, mids)
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                resetAccount(account, true)
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
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            try {
                storage.database?.let { ArchiveStore.clearEditHistory(it, dialogIds) }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "clear edit history failed", e)
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                resetAccount(account, false)
                ensureAccountLoaded(account)
                onDone?.run()
            }
        }
    }

    @JvmStatic
    fun clearCache(account: Int, dialogIds: Collection<Long>? = null, deletedOnly: Boolean = false, onDone: Runnable? = null) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database
            if (db != null) {
                val pairs = ArchiveStore.getDeletedMessageIds(db, dialogIds)
                for ((dialogId, mids) in pairs) {
                    DeletedMessagesHelper.purgeInStorage(account, storage, db, dialogId, mids)
                }
                ArchiveStore.clearDeletedMessages(db, dialogIds, !deletedOnly)
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                resetAccount(account, true)
                onDone?.run()
            }
        }
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

    internal fun messageSnapshot(account: Int, dialogId: Long, msgId: Int): TLRPC.Message? {
        val entry = synchronized(cacheLock) {
            val messages = shadowMessageCache.get(account.toLong())
            if (dialogId != 0L) messages?.get(dialogId to msgId) else messages?.entries?.firstOrNull { it.key.second == msgId }?.value
        }
        return entry?.message?.let { InuUtils.cloneTLObject(it, TLRPC.Message::TLdeserialize) }
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
            ArchiveStore.cloneMedia(message.media),
            if (DeletedMessagesHelper.isSaveDeletedEnabled()) InuUtils.cloneTLObject(message, TLRPC.Message::TLdeserialize) else null,
        )
        synchronized(cacheLock) {
            val map = shadowMessageCache.get(account.toLong())
                ?: BoundedLinkedHashMap<Pair<Long, Int>, ShadowEntry>(SHADOW_CACHE_MAX_PER_ACCOUNT)
                    .also { shadowMessageCache.put(account.toLong(), it) }
            map[MessageObject.getDialogId(message) to message.id] = entry
        }
    }

    fun invalidateMediaCache(account: Int) {
        synchronized(cacheLock) {
            deletedMessageMediaPaths.remove(account.toLong())
            editHistoryCache.remove(account.toLong())
            cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
            loadedAccounts.remove(account)
            loadingAccounts.remove(account)
        }
    }
}
