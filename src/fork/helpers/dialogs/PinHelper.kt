package desu.inugram.helpers.dialogs

import android.util.SparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

// entiny: telegram server rejects pins beyond limit so extra pins are stored in inu_local_pins and merged client-side
object PinHelper {
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
            // entiny: pins are read off the storage queue, so the list sorted at startup has none of them yet
            if (loadedMap.isNotEmpty()) AndroidUtilities.runOnUIThread { refresh(account) }
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

    @JvmStatic
    fun reorderLocalPins(controller: MessagesController, account: Int) {
        HiddenDialogsHelper.filter(controller, account)
        val unlimited = InuConfig.UNLIMITED_PINNED_CHATS.value
        for (i in 0 until controller.dialogsByFolder.size()) {
            val folderId = controller.dialogsByFolder.keyAt(i)
            val list = controller.dialogsByFolder.valueAt(i) ?: continue
            val pins = if (unlimited) scopeMap(account, folderId) else null
            if (pins != null) reorder(pins, list) { it.pinned }
            unreadFirstInPinned(controller, list) { it.pinned || pins?.containsKey(it.id) == true }
        }
        for (filter in controller.selectedDialogFilter) {
            if (filter == null) continue
            val pins = if (unlimited) scopeMap(account, filter.id) else null
            if (pins != null) reorder(pins, filter.dialogs) { d -> filter.pinnedDialogs.indexOfKey(d.id) >= 0 }
            unreadFirstInPinned(controller, filter.dialogs) { d -> filter.pinnedDialogs.indexOfKey(d.id) >= 0 || pins?.containsKey(d.id) == true }
        }
    }

    private fun unreadFirstInPinned(controller: MessagesController, list: ArrayList<TLRPC.Dialog>, isPinned: (TLRPC.Dialog) -> Boolean) {
        if (!InuConfig.UNREAD_CHATS_FIRST.value || !InuConfig.UNREAD_CHATS_FIRST_PINNED.value) return
        var start = 0
        while (start < list.size && list[start] is TLRPC.TL_dialogFolder) start++
        var end = start
        while (end < list.size && isPinned(list[end])) end++
        if (end - start < 2) return
        val sorted = list.subList(start, end).sortedBy { if (UnreadFirstHelper.isUnread(controller, it)) 0 else 1 }
        for (i in sorted.indices) list[start + i] = sorted[i]
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
        var insertAt = list.indexOfLast(isReallyPinned) + 1
        if (insertAt == 0) while (insertAt < list.size && list[insertAt] is TLRPC.TL_dialogFolder) insertAt++
        list.addAll(insertAt, ordered)
    }
}
