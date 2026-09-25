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
            knownMax[dialogId] = serverReadMax(MessagesController.getInstance(account), dialogId)
        }
        return maxOf(knownMax[dialogId] ?: 0, pendingMax[dialogId] ?: 0)
    }

    // entiny: re-sync from stock dialogs so reads done outside the feed shrink the unread zone
    fun refresh(dialogIds: LongArray) {
        val controller = MessagesController.getInstance(account)
        for (dialogId in dialogIds) {
            val readMax = serverReadMax(controller, dialogId)
            if (readMax > (knownMax[dialogId] ?: 0)) knownMax[dialogId] = readMax
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

    // entiny: channels outside the loaded dialogs page aren't in dialogs_dict, so fall back to the feed's newest ids
    fun markAllRead(dialogIds: Collection<Long>, newestLoaded: Map<Long, Int>): Int {
        val controller = MessagesController.getInstance(account)
        var marked = 0
        for (dialogId in dialogIds) {
            val dialog = controller.dialogs_dict?.get(dialogId)
            val top = maxOf(dialog?.top_message ?: 0, newestLoaded[dialogId] ?: 0)
            if (top <= 0) continue
            // entiny: judge by what Telegram itself considers read, not by our local seen marks
            if (top <= serverReadMax(controller, dialogId) && (dialog?.unread_count ?: 0) <= 0 && dialog?.unread_mark != true) continue
            knownMax[dialogId] = top
            pendingMax.remove(dialogId)
            controller.markDialogAsRead(dialogId, top, top, dialog?.last_message_date ?: 0, false, 0L, 0, true, 0)
            marked++
        }
        return marked
    }

    private fun serverReadMax(controller: MessagesController, dialogId: Long): Int =
        maxOf(controller.dialogs_dict?.get(dialogId)?.read_inbox_max_id ?: 0, controller.dialogs_read_inbox_max[dialogId] ?: 0)

    companion object {
        private const val FLUSH_DELAY_MS = 1000L

        private val instances = HashMap<Int, FeedUnreadTracker>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedUnreadTracker = instances.getOrPut(account) { FeedUnreadTracker(account) }

        @JvmStatic
        fun getUnreadCount(account: Int): Int {
            val controller = MessagesController.getInstance(account) ?: return 0
            var count = 0
            for (dialogId in FeedChannelSet.eligibleChannels(account)) {
                val dialog = controller.dialogs_dict?.get(dialogId) ?: continue
                count += dialog.unread_count
            }
            return count
        }
    }
}
