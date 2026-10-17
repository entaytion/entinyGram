package desu.inugram.helpers.dialogs

import android.util.SparseArray
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLRPC

// entiny: dialogs the server refuses to delete (deleted accounts) stay hidden locally so they do not return on restart
object HiddenDialogsHelper {
    private val cache = SparseArray<MutableSet<Long>>()

    @JvmStatic
    fun load(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val loaded = InuDatabaseHelper.loadHiddenDialogs(db)
            synchronized(cache) { cache.put(account, HashSet(loaded)) }
            if (loaded.isNotEmpty()) AndroidUtilities.runOnUIThread {
                MessagesController.getInstance(account)?.sortDialogs(null)
            }
        }
    }

    private fun isHidden(account: Int, dialogId: Long): Boolean = synchronized(cache) {
        cache.get(account)?.contains(dialogId) == true
    }

    @JvmStatic
    fun hide(account: Int, dialogId: Long) {
        val storage = MessagesStorage.getInstance(account) ?: return
        synchronized(cache) {
            val set = cache.get(account) ?: HashSet<Long>().also { cache.put(account, it) }
            if (!set.add(dialogId)) return
        }
        storage.storageQueue.postRunnable {
            storage.database?.let { InuDatabaseHelper.saveHiddenDialog(it, dialogId) }
        }
        AndroidUtilities.runOnUIThread { MessagesController.getInstance(account)?.sortDialogs(null) }
    }

    @JvmStatic
    fun unhide(account: Int, dialogId: Long) {
        val storage = MessagesStorage.getInstance(account) ?: return
        var removed = false
        var loaded = true
        synchronized(cache) {
            val set = cache.get(account)
            if (set == null) loaded = false else removed = set.remove(dialogId)
        }
        if (loaded && !removed) return
        storage.storageQueue.postRunnable {
            storage.database?.let { InuDatabaseHelper.removeHiddenDialog(it, dialogId) }
        }
        if (removed) AndroidUtilities.runOnUIThread { MessagesController.getInstance(account)?.sortDialogs(null) }
    }

    @JvmStatic
    fun filter(controller: MessagesController, account: Int) {
        val hidden = synchronized(cache) { cache.get(account)?.takeIf { it.isNotEmpty() }?.toSet() } ?: return
        val pred = { d: TLRPC.Dialog -> hidden.contains(d.id) }
        controller.allDialogs.removeAll(pred)
        for (i in 0 until controller.dialogsByFolder.size()) {
            controller.dialogsByFolder.valueAt(i)?.removeAll(pred)
        }
        for (filter in controller.selectedDialogFilter) {
            filter?.dialogs?.removeAll(pred)
        }
    }
}
