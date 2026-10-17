package desu.inugram.helpers.chat

import android.util.SparseArray
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.MessagesStorage

// entiny: inspired by NagramX bookmarks
object BookmarksHelper {
    const val MAX_PER_CHAT = 30

    enum class Result { ADDED, REMOVED, LIMIT }

    private val cache = SparseArray<MutableMap<Long, LinkedHashSet<Int>>>()

    @JvmStatic
    fun load(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val map = HashMap<Long, LinkedHashSet<Int>>()
            for ((dialogId, msgId) in InuDatabaseHelper.loadBookmarks(db)) {
                map.getOrPut(dialogId) { LinkedHashSet() }.add(msgId)
            }
            synchronized(cache) { cache.put(account, map) }
        }
    }

    @JvmStatic
    fun isBookmarked(account: Int, dialogId: Long, msgId: Int): Boolean = synchronized(cache) {
        cache.get(account)?.get(dialogId)?.contains(msgId) == true
    }

    @JvmStatic
    fun ids(account: Int, dialogId: Long): IntArray = synchronized(cache) {
        cache.get(account)?.get(dialogId)?.toIntArray() ?: IntArray(0)
    }

    @JvmStatic
    fun counts(account: Int): Map<Long, Int> = synchronized(cache) {
        cache.get(account)?.filterValues { it.isNotEmpty() }?.mapValues { it.value.size } ?: emptyMap()
    }

    @JvmStatic
    fun toggle(account: Int, dialogId: Long, msgIds: Collection<Int>): Result {
        val storage = MessagesStorage.getInstance(account) ?: return Result.REMOVED
        val ids = msgIds.filter { it != 0 }.distinct()
        if (ids.isEmpty()) return Result.REMOVED
        val adding: Boolean
        synchronized(cache) {
            val map = cache.get(account) ?: HashMap<Long, LinkedHashSet<Int>>().also { cache.put(account, it) }
            val set = map.getOrPut(dialogId) { LinkedHashSet() }
            adding = !set.containsAll(ids)
            if (adding) {
                if (set.size + ids.count { it !in set } > MAX_PER_CHAT) return Result.LIMIT
                set.addAll(ids)
            } else {
                set.removeAll(ids.toSet())
            }
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            if (adding) InuDatabaseHelper.addBookmarks(db, dialogId, ids) else InuDatabaseHelper.removeBookmarks(db, dialogId, ids)
        }
        return if (adding) Result.ADDED else Result.REMOVED
    }

    @JvmStatic
    fun remove(account: Int, dialogId: Long, msgId: Int) {
        if (isBookmarked(account, dialogId, msgId)) toggle(account, dialogId, listOf(msgId))
    }

    @JvmStatic
    fun clearAll(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        synchronized(cache) { cache.get(account)?.clear() }
        storage.storageQueue.postRunnable { storage.database?.let { InuDatabaseHelper.clearBookmarks(it) } }
    }

    @JvmStatic
    fun timeAdditionsHash(account: Int, dialogId: Long, msgId: Int): Int = if (isBookmarked(account, dialogId, msgId)) 1 else 0
}
