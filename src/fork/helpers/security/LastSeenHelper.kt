package desu.inugram.helpers.security

import android.util.SparseArray
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

object LastSeenHelper {
    private const val WRITE_THROTTLE_SEC = 60
    private const val MIN_VALID_DATE = 1397411401

    private val cache = SparseArray<LongSparseArray<Int>>()
    private val loaded = HashSet<Int>()

    @JvmStatic
    fun onUserStatus(account: Int, user: TLRPC.User) {
        onUserStatus(account, user.id, user.status)
    }

    @JvmStatic
    fun onUserStatus(account: Int, userId: Long, status: TLRPC.UserStatus?) {
        if (!InuConfig.SAVE_LAST_SEEN.value) return
        status ?: return
        val now = ConnectionsManager.getInstance(account).currentTime
        val offline = status is TLRPC.TL_userStatusOffline
        val seen = when {
            status is TLRPC.TL_userStatusOnline || status.expires > now -> now
            offline && status.expires > 0 -> status.expires
            else -> return
        }
        store(account, userId, seen, offline)
    }

    @JvmStatic
    fun onUserActivity(account: Int, userId: Long, date: Int) {
        if (!InuConfig.SAVE_LAST_SEEN.value || userId <= 0L) return
        if (userId == UserConfig.getInstance(account).clientUserId) return
        val now = ConnectionsManager.getInstance(account).currentTime
        if (date < MIN_VALID_DATE || date > now) return
        val user = MessagesController.getInstance(account).getUser(userId) ?: return
        if (user.bot || !isHiddenStatus(user.status)) return
        store(account, userId, date, false)
    }

    @JvmStatic
    fun onMessage(account: Int, message: TLRPC.Message?) {
        message ?: return
        if (message.out || message.from_scheduled || message.from_id !is TLRPC.TL_peerUser) return
        onUserActivity(account, message.from_id.user_id, message.date)
    }

    @JvmStatic
    fun onUpdate(account: Int, update: Any?) {
        if (!InuConfig.SAVE_LAST_SEEN.value) return
        val now = ConnectionsManager.getInstance(account).currentTime
        when (update) {
            is TL_update.TL_updateUserTyping -> onUserActivity(account, update.user_id, now)
            is TL_update.TL_updateEditMessage -> update.message?.let {
                if (it.edit_date > 0 && !it.out && it.from_id is TLRPC.TL_peerUser) {
                    onUserActivity(account, it.from_id.user_id, it.edit_date)
                }
            }
        }
    }

    private fun isHiddenStatus(status: TLRPC.UserStatus?): Boolean =
        status == null || status is TLRPC.TL_userStatusEmpty || status is TLRPC.TL_userStatusRecently ||
            status is TLRPC.TL_userStatusLastWeek || status is TLRPC.TL_userStatusLastMonth

    private fun store(account: Int, userId: Long, seen: Int, offline: Boolean) {
        val storage = MessagesStorage.getInstance(account) ?: return
        ensureLoaded(account, storage)
        synchronized(cache) {
            val users = cache.get(account) ?: LongSparseArray<Int>().also { cache.put(account, it) }
            val prev = users.get(userId)
            if (prev != null && (seen <= prev || !offline && seen - prev < WRITE_THROTTLE_SEC)) return
            users.put(userId, seen)
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveLastSeen(db, userId, seen)
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
