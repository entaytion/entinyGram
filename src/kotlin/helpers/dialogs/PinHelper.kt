package desu.inugram.helpers.dialogs

import android.util.SparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

/**
 * "Pin beyond the server limit" overlay, kept entirely client-side. Telegram's server rejects
 * toggleDialogPin once the account's real pin limit is hit, so overflow pins are never sent
 * there — they live in inu_local_pins (via [InuDatabaseHelper]) and get merged into the sorted
 * dialog list after every stock sort ([reorderLocalPins]), right after the really-pinned prefix.
 */
object PinHelper {
    // account -> scope -> (dialogId -> pin_order), scope = filter.id or folderId
    private val cache = SparseArray<MutableMap<Int, LinkedHashMap<Long, Int>>>()

    private fun scopeFor(folderId: Int, filter: MessagesController.DialogFilter?): Int =
        filter?.id ?: folderId

    @JvmStatic
    fun load(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val loadedMap = InuDatabaseHelper.loadLocalPins(db)
            synchronized(cache) {
                cache.put(account, HashMap(loadedMap))
            }
        }
    }

    private fun scopeMap(account: Int, scope: Int): LinkedHashMap<Long, Int>? {
        synchronized(cache) {
            return cache.get(account)?.get(scope)
        }
    }

    @JvmStatic
    fun isLocallyPinned(account: Int, folderId: Int, filter: MessagesController.DialogFilter?, dialogId: Long): Boolean {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return false
        return scopeMap(account, scopeFor(folderId, filter))?.containsKey(dialogId) == true
    }

    @JvmStatic
    fun addLocalPin(account: Int, folderId: Int, filter: MessagesController.DialogFilter?, dialogId: Long) {
        val scope = scopeFor(folderId, filter)
        val maxExtra = InuConfig.UNLIMITED_PINNED_CHATS_COUNT.value.coerceAtLeast(1)
        val storage = MessagesStorage.getInstance(account) ?: return
        var evicted: Long? = null
        var nextOrder = 0
        synchronized(cache) {
            val accountMap = cache.get(account) ?: HashMap<Int, LinkedHashMap<Long, Int>>().also { cache.put(account, it) }
            val scopeMap = accountMap.getOrPut(scope) { LinkedHashMap() }
            if (scopeMap.containsKey(dialogId)) return
            if (scopeMap.size >= maxExtra) {
                evicted = scopeMap.entries.minByOrNull { it.value }?.key
                evicted?.let { scopeMap.remove(it) }
            }
            nextOrder = (scopeMap.values.maxOrNull() ?: 0) + 1
            scopeMap[dialogId] = nextOrder
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            evicted?.let { InuDatabaseHelper.removeLocalPin(db, scope, it) }
            InuDatabaseHelper.saveLocalPin(db, scope, dialogId, nextOrder)
        }
        refresh(account)
    }

    @JvmStatic
    fun removeLocalPin(account: Int, folderId: Int, filter: MessagesController.DialogFilter?, dialogId: Long) {
        val scope = scopeFor(folderId, filter)
        val storage = MessagesStorage.getInstance(account) ?: return
        var removed = false
        synchronized(cache) {
            removed = cache.get(account)?.get(scope)?.remove(dialogId) != null
        }
        if (!removed) return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.removeLocalPin(db, scope, dialogId)
        }
        refresh(account)
    }

    private fun refresh(account: Int) {
        val controller = MessagesController.getInstance(account) ?: return
        controller.sortDialogs(null)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    /** Called at the end of MessagesController.sortDialogs() — pure in-memory, no DB access. */
    @JvmStatic
    fun reorderLocalPins(controller: MessagesController, account: Int) {
        if (!InuConfig.UNLIMITED_PINNED_CHATS.value) return
        for (i in 0 until controller.dialogsByFolder.size()) {
            val folderId = controller.dialogsByFolder.keyAt(i)
            val list = controller.dialogsByFolder.valueAt(i) ?: continue
            reorder(scopeMap(account, folderId), list) { it.pinned }
        }
        for (filter in controller.selectedDialogFilter) {
            if (filter == null) continue
            reorder(scopeMap(account, filter.id), filter.dialogs) { d -> filter.pinnedDialogs.indexOfKey(d.id) >= 0 }
        }
    }

    private fun reorder(pins: LinkedHashMap<Long, Int>?, list: ArrayList<TLRPC.Dialog>, isReallyPinned: (TLRPC.Dialog) -> Boolean) {
        if (pins.isNullOrEmpty()) return
        val matched = HashMap<Long, TLRPC.Dialog>()
        val it = list.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (!isReallyPinned(d) && pins.containsKey(d.id)) {
                matched[d.id] = d
                it.remove()
            }
        }
        if (matched.isEmpty()) return
        val ordered = pins.entries.sortedByDescending { it.value }.mapNotNull { matched[it.key] }
        var insertAt = 0
        while (insertAt < list.size && isReallyPinned(list[insertAt])) insertAt++
        list.addAll(insertAt, ordered)
    }
}
