package desu.inugram.helpers.dialogs

import android.util.SparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter

// entiny: telegram server rejects folder chat limit overflow so extra chats are merged purely client-side
object FolderMembershipHelper {
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
