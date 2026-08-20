package desu.inugram.helpers.dialogs

import android.util.SparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter

/**
 * "Include more chats in a folder than the server limit" overlay, entirely client-side —
 * mirrors [PinHelper]'s local-pin design for the same reason: `messages.updateDialogFilter`
 * enforces `dialogFiltersChatsLimitDefault/Premium` on `include_peers` server-side, so overflow
 * chats are never added to the real `filter.alwaysShow`/sent to the server. They're merged in
 * purely at the [org.telegram.messenger.MessagesController.DialogFilter.includesDialog] chokepoint.
 */
object FolderMembershipHelper {
    // account -> filterId -> overlay dialog ids
    private val cache = SparseArray<MutableMap<Int, MutableSet<Long>>>()

    @JvmStatic
    fun load(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val loaded = InuDatabaseHelper.loadLocalFolderChats(db)
            synchronized(cache) {
                cache.put(account, HashMap(loaded.mapValues { HashSet(it.value) }))
            }
        }
    }

    @JvmStatic
    fun isLocallyIncluded(account: Int, filterId: Int, dialogId: Long): Boolean {
        if (!InuConfig.UNLIMITED_FOLDER_CHATS.value) return false
        synchronized(cache) {
            return cache.get(account)?.get(filterId)?.contains(dialogId) == true
        }
    }

    @JvmStatic
    fun getLocal(account: Int, filterId: Int): Set<Long> {
        synchronized(cache) {
            return cache.get(account)?.get(filterId)?.toSet().orEmpty()
        }
    }

    @JvmStatic
    fun addLocal(account: Int, filterId: Int, dialogId: Long) {
        val storage = MessagesStorage.getInstance(account) ?: return
        synchronized(cache) {
            val accountMap = cache.get(account) ?: HashMap<Int, MutableSet<Long>>().also { cache.put(account, it) }
            accountMap.getOrPut(filterId) { HashSet() }.add(dialogId)
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveLocalFolderChat(db, filterId, dialogId)
        }
        refresh(account)
    }

    @JvmStatic
    fun removeLocal(account: Int, filterId: Int, dialogId: Long) {
        val storage = MessagesStorage.getInstance(account) ?: return
        var removed = false
        synchronized(cache) {
            removed = cache.get(account)?.get(filterId)?.remove(dialogId) == true
        }
        if (!removed) return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.removeLocalFolderChat(db, filterId, dialogId)
        }
        refresh(account)
    }

    private fun refresh(account: Int) {
        val controller = MessagesController.getInstance(account) ?: return
        controller.sortDialogs(null)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
    }
}
