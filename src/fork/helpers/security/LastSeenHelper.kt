package desu.inugram.helpers.security

import android.util.SparseArray
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLRPC

object LastSeenHelper {
    private const val WRITE_THROTTLE_SEC = 60

    private val cache = SparseArray<LongSparseArray<Int>>()
    private val loaded = HashSet<Int>()

    @JvmStatic
    fun onUserStatus(account: Int, user: TLRPC.User) {
        if (!InuConfig.SAVE_LAST_SEEN.value) return
        val status = user.status ?: return
        val now = (System.currentTimeMillis() / 1000L).toInt()
        val seen = when {
            status is TLRPC.TL_userStatusOnline || status.expires > now -> now
            status is TLRPC.TL_userStatusOffline && status.expires > 0 -> status.expires
            else -> return
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        ensureLoaded(account, storage)
        synchronized(cache) {
            val users = cache.get(account) ?: LongSparseArray<Int>().also { cache.put(account, it) }
            val prev = users.get(user.id)
            if (prev != null && (seen <= prev || seen - prev < WRITE_THROTTLE_SEC)) return
            users.put(user.id, seen)
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveLastSeen(db, user.id, seen)
        }
    }

    @JvmStatic
    fun format(account: Int, userId: Long, madeShorter: BooleanArray?): String? {
        if (!InuConfig.SAVE_LAST_SEEN.value) return null
        val seen = synchronized(cache) { cache.get(account)?.get(userId) } ?: return null
        return LocaleController.formatDateOnline(seen.toLong(), madeShorter)
    }

    private fun ensureLoaded(account: Int, storage: MessagesStorage) {
        synchronized(cache) {
            if (!loaded.add(account)) return
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val rows = InuDatabaseHelper.loadLastSeen(db)
            synchronized(cache) {
                val users = cache.get(account) ?: LongSparseArray<Int>().also { cache.put(account, it) }
                for ((userId, seen) in rows) {
                    val current = users.get(userId)
                    if (current == null || seen > current) users.put(userId, seen)
                }
            }
        }
    }
}
