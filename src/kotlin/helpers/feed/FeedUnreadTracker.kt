package desu.inugram.helpers.feed

import org.telegram.messenger.MessagesController

class FeedUnreadTracker private constructor(private val account: Int) {

    private val watermark = HashMap<Long, Int>()

    fun seed(dialogId: Long) {
        val dialog = MessagesController.getInstance(account).dialogs_dict?.get(dialogId) ?: return
        val current = watermark[dialogId] ?: -1
        if (dialog.read_inbox_max_id > current) {
            watermark[dialogId] = dialog.read_inbox_max_id
        }
    }

    fun isUnread(dialogId: Long, messageId: Int): Boolean {
        val mark = watermark[dialogId] ?: run { seed(dialogId); watermark[dialogId] ?: 0 }
        return messageId > mark
    }

    fun onRowSeen(dialogId: Long, messageId: Int) {
        val mark = watermark[dialogId] ?: run { seed(dialogId); watermark[dialogId] ?: 0 }
        if (messageId <= mark) return
        watermark[dialogId] = messageId
    }

    fun markAllRead(dialogIds: Collection<Long>): Int {
        val controller = MessagesController.getInstance(account)
        var marked = 0
        for (dialogId in dialogIds) {
            val dialog = controller.dialogs_dict?.get(dialogId) ?: continue
            if (dialog.unread_count <= 0) continue
            watermark[dialogId] = dialog.top_message
            controller.markDialogAsRead(dialogId, dialog.top_message, dialog.top_message, 0, false, 0, dialog.unread_count, true, 0)
            marked++
        }
        return marked
    }

    companion object {
        private val instances = HashMap<Int, FeedUnreadTracker>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedUnreadTracker = instances.getOrPut(account) { FeedUnreadTracker(account) }
    }
}
