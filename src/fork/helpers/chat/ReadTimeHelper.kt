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
    private val loaded = HashSet<Int>()

    @JvmStatic
    fun onReadOutbox(account: Int, userId: Long, maxId: Int, date: Int) {
        if (!InuConfig.SAVE_READ_TIME.value || userId <= 0L || date <= 0) return
        val storage = MessagesStorage.getInstance(account) ?: return
        ensureLoaded(account)
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
        ensureLoaded(msg.currentAccount)
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

    private fun ensureLoaded(account: Int) {
        synchronized(cache) {
            if (!loaded.add(account)) return
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val rows = InuDatabaseHelper.loadReadTimes(db)
            synchronized(cache) {
                val dialogs = cache.get(account) ?: HashMap<Long, MutableList<Boundary>>().also { cache.put(account, it) }
                for ((dialogId, maxId, readAt) in rows) {
                    dialogs.getOrPut(dialogId) { ArrayList() }.add(0, Boundary(maxId, readAt))
                }
                for (list in dialogs.values) {
                    list.sortBy { it.maxId }
                }
            }
        }
    }
}
