package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager

class FeedUnreadTracker private constructor(private val account: Int) {

    private val knownMax = HashMap<Long, Int>()
    private val pendingMax = HashMap<Long, Int>()
    private var flushScheduled = false
    private val flushRunnable = Runnable { flushNow() }

    private fun effectiveMax(dialogId: Long): Int {
        if (!knownMax.containsKey(dialogId)) {
            val dialog = MessagesController.getInstance(account).dialogs_dict?.get(dialogId)
            knownMax[dialogId] = dialog?.read_inbox_max_id ?: 0
        }
        return maxOf(knownMax[dialogId] ?: 0, pendingMax[dialogId] ?: 0)
    }

    // entiny: re-sync from stock dialogs so reads done outside the feed shrink the unread zone
    fun refresh(dialogIds: LongArray) {
        val controller = MessagesController.getInstance(account)
        for (dialogId in dialogIds) {
            val dialog = controller.dialogs_dict?.get(dialogId) ?: continue
            if (dialog.read_inbox_max_id > (knownMax[dialogId] ?: 0)) {
                knownMax[dialogId] = dialog.read_inbox_max_id
            }
        }
    }

    fun isUnread(dialogId: Long, messageId: Int): Boolean = messageId > effectiveMax(dialogId)

    // entiny: seen posts are flushed to the server in delayed batches, like exteraless -- badges sync everywhere
    fun onRowSeen(dialogId: Long, messageId: Int) {
        if (messageId <= effectiveMax(dialogId)) return
        if ((pendingMax[dialogId] ?: 0) < messageId) pendingMax[dialogId] = messageId
        if (!flushScheduled) {
            flushScheduled = true
            AndroidUtilities.runOnUIThread(flushRunnable, FLUSH_DELAY_MS)
        }
    }

    fun flush() {
        AndroidUtilities.cancelRunOnUIThread(flushRunnable)
        flushNow()
    }

    private fun flushNow() {
        flushScheduled = false
        if (pendingMax.isEmpty()) return
        val controller = MessagesController.getInstance(account)
        val now = ConnectionsManager.getInstance(account).currentTime
        val entries = ArrayList(pendingMax.entries)
        pendingMax.clear()
        for ((dialogId, maxReadId) in entries) {
            if (maxReadId <= (knownMax[dialogId] ?: 0)) continue
            knownMax[dialogId] = maxReadId
            controller.markDialogAsRead(dialogId, maxReadId, 0, now, false, 0L, 1, true, 0)
        }
    }

    fun markAllRead(dialogIds: Collection<Long>): Int {
        val controller = MessagesController.getInstance(account)
        var marked = 0
        for (dialogId in dialogIds) {
            val dialog = controller.dialogs_dict?.get(dialogId) ?: continue
            if (dialog.unread_count <= 0) continue
            knownMax[dialogId] = dialog.top_message
            pendingMax.remove(dialogId)
            controller.markDialogAsRead(dialogId, dialog.top_message, dialog.top_message, 0, false, 0L, dialog.unread_count, true, 0)
            marked++
        }
        return marked
    }

    companion object {
        private const val FLUSH_DELAY_MS = 1000L

        private val instances = HashMap<Int, FeedUnreadTracker>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedUnreadTracker = instances.getOrPut(account) { FeedUnreadTracker(account) }
    }
}
