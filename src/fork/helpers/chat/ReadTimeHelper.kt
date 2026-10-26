package desu.inugram.helpers.chat

import android.util.SparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage

object ReadTimeHelper {
    private const val MERGE_WINDOW_SEC = 60

    private class Boundary(val maxId: Int, val readAt: Int)

    private val cache = SparseArray<HashMap<Long, MutableList<Boundary>>>()
    private val loadedDialogs = SparseArray<HashSet<Long>>()
    private val loadingDialogs = SparseArray<HashSet<Long>>()

    @JvmStatic
    fun onReadOutbox(account: Int, userId: Long, maxId: Int, date: Int) {
        if (!InuConfig.SAVE_READ_TIME.value || userId <= 0L || date <= 0) return
        val storage = MessagesStorage.getInstance(account) ?: return
        ensureDialogLoaded(account, userId)
        var replacedMaxId = 0
        var readAt = date
        synchronized(cache) {
            val dialogs = cache.get(account) ?: HashMap<Long, MutableList<Boundary>>().also { cache.put(account, it) }
            val list = dialogs.getOrPut(userId) { ArrayList() }
            val last = list.lastOrNull()
            if (last != null) {
                if (last.maxId >= maxId) return
                if (date - last.readAt < MERGE_WINDOW_SEC) {
                    list.removeAt(list.size - 1)
                    replacedMaxId = last.maxId
                    readAt = last.readAt
                }
            }
            list.add(Boundary(maxId, readAt))
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            if (replacedMaxId != 0) InuDatabaseHelper.deleteReadTime(db, userId, replacedMaxId)
            InuDatabaseHelper.saveReadTime(db, userId, maxId, readAt)
        }
    }

    @JvmStatic
    fun readAt(msg: MessageObject?): Int? {
        if (msg == null || !InuConfig.SAVE_READ_TIME.value || !msg.isOutOwner()) return null
        val dialogId = msg.dialogId
        if (dialogId <= 0L) return null
        ensureDialogLoaded(msg.currentAccount, dialogId)
        return synchronized(cache) {
            val list = cache.get(msg.currentAccount)?.get(dialogId) ?: return@synchronized null
            var lo = 0
            var hi = list.size - 1
            var found: Int? = null
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].maxId >= msg.id) {
                    found = list[mid].readAt
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            found
        }
    }

    private fun ensureDialogLoaded(account: Int, dialogId: Long) {
        synchronized(cache) {
            if (loadedDialogs.get(account)?.contains(dialogId) == true) return
            val loading = loadingDialogs.get(account) ?: HashSet<Long>().also { loadingDialogs.put(account, it) }
            if (!loading.add(dialogId)) return
        }
        val storage = MessagesStorage.getInstance(account)
        if (storage == null) {
            synchronized(cache) { loadingDialogs.get(account)?.remove(dialogId) }
            return
        }
        storage.storageQueue.postRunnable {
            val rows = storage.database?.let { InuDatabaseHelper.loadReadTimesForDialog(it, dialogId) } ?: emptyList()
            synchronized(cache) {
                val dialogs = cache.get(account) ?: HashMap<Long, MutableList<Boundary>>().also { cache.put(account, it) }
                val list = dialogs.getOrPut(dialogId) { ArrayList() }
                for ((maxId, readAt) in rows) {
                    if (list.none { it.maxId == maxId }) list.add(Boundary(maxId, readAt))
                }
                list.sortBy { it.maxId }
                (loadedDialogs.get(account) ?: HashSet<Long>().also { loadedDialogs.put(account, it) }).add(dialogId)
                loadingDialogs.get(account)?.remove(dialogId)
            }
        }
    }
}
