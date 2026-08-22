package desu.inugram.helpers.security

import android.util.SparseArray
import android.widget.Toast
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun newTimeFormat() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * Opt-in per-contact online/offline logger. Never watches everyone by default — logging every
 * status update for hundreds of contacts is noisy and wasteful; users pick who to watch from
 * that person's profile menu (see ProfileHelper.ACTION_TOGGLE_PRESENCE_WATCH).
 */
object PresenceHelper {
    private val watched = SparseArray<MutableSet<Long>>()

    @JvmStatic
    fun load(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val loaded = InuDatabaseHelper.loadWatchedUsers(db)
            synchronized(watched) {
                watched.put(account, HashSet(loaded))
            }
        }
    }

    @JvmStatic
    fun isWatched(account: Int, userId: Long): Boolean {
        synchronized(watched) {
            return watched.get(account)?.contains(userId) == true
        }
    }

    @JvmStatic
    fun getWatchedUsers(account: Int): List<Long> {
        synchronized(watched) {
            return watched.get(account)?.toList().orEmpty()
        }
    }

    @JvmStatic
    fun toggleWatch(account: Int, userId: Long): Boolean {
        val storage = MessagesStorage.getInstance(account) ?: return false
        val nowWatched: Boolean
        synchronized(watched) {
            val set = watched.get(account) ?: HashSet<Long>().also { watched.put(account, it) }
            nowWatched = if (set.remove(userId)) false else { set.add(userId); true }
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            if (nowWatched) InuDatabaseHelper.saveWatch(db, userId) else InuDatabaseHelper.removeWatch(db, userId)
        }
        return nowWatched
    }

    @JvmStatic
    fun onStatusUpdate(update: TL_update.TL_updateUserStatus, account: Int) {
        if (!isWatched(account, update.user_id)) return
        val statusType = statusTypeOf(update.status)
        val nowSeconds = (System.currentTimeMillis() / 1000L).toInt()
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.appendPresenceLog(db, update.user_id, statusType, nowSeconds)
        }
        if (!InuConfig.PRESENCE_LOGGER_NOTIFY.value) return
        val user = MessagesController.getInstance(account).getUser(update.user_id) ?: return
        val name = UserObject.getFirstName(user)
        AndroidUtilities.runOnUIThread {
            // formatted on the UI thread — SimpleDateFormat isn't thread-safe and onStatusUpdate can
            // be invoked concurrently from update-dispatch threads across multiple accounts
            val time = newTimeFormat().format(Date(nowSeconds * 1000L))
            val text = if (statusType == "online") "$name online [$time]" else "$name offline [$time]"
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** Clears the local status-change log for [userId], or every watched user's log if null. */
    @JvmStatic
    fun clearLog(account: Int, userId: Long, onDone: Runnable? = null) = clearLogs(account, listOf(userId), onDone)

    /** Clears logs for [userIds] (or every logged user if null). */
    @JvmStatic
    fun clearLogs(account: Int, userIds: Collection<Long>? = null, onDone: Runnable? = null) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.clearPresenceLogs(db, userIds)
            onDone?.let { AndroidUtilities.runOnUIThread(it) }
        }
    }

    /** Prunes logs older than [InuConfig.PRESENCE_LOGS_TTL] days. No-op when TTL is NEVER. */
    @JvmStatic
    fun pruneIfNeeded(account: Int) {
        val ttlDays = InuConfig.PRESENCE_LOGS_TTL.value
        if (ttlDays == 0) return
        val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.prunePresenceLogs(db, cutoff)
        }
    }

    @JvmStatic
    fun getLogsStats(account: Int, callback: (count: Int, size: Long) -> Unit) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val stat = InuDatabaseHelper.getPresenceLogsStats(db)
            AndroidUtilities.runOnUIThread { callback(stat.count, stat.estimatedSize) }
        }
    }

    @JvmStatic
    fun getLogsStatsByUser(account: Int, callback: (List<InuDatabaseHelper.DialogCacheStat>) -> Unit) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val stats = InuDatabaseHelper.getPresenceLogsStatsByUser(db)
            AndroidUtilities.runOnUIThread { callback(stats) }
        }
    }

    private fun statusTypeOf(status: TLRPC.UserStatus?): String = when (status) {
        is TLRPC.TL_userStatusOnline -> "online"
        is TLRPC.TL_userStatusOffline -> "offline"
        is TLRPC.TL_userStatusRecently -> "recently"
        is TLRPC.TL_userStatusLastWeek -> "last_week"
        is TLRPC.TL_userStatusLastMonth -> "last_month"
        else -> "unknown"
    }
}
