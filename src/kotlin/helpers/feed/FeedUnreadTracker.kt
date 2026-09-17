package desu.inugram.helpers.feed

import org.telegram.messenger.MessagesController

/**
 * Per-channel read watermark for Feed (seeded from dialog.read_inbox_max_id, purely local bookkeeping).
 * Does NOT call markDialogAsRead (bug fix: countDiff==0 wrongly zeros unread_count). One instance per account, shared across all scopes.
 */
class FeedUnreadTracker private constructor(private val account: Int) {

    private val watermark = HashMap<Long, Int>() // dialogId -> highest real_id already known read

    /** Seeds (or refreshes) the watermark for [dialogId] from the dialog's own read state. */
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

    /**
     * Call when a feed row becomes visible. Purely local -- advances this screen's own watermark
     * without telling the server anything; see the class doc for why. [markAllRead] is the only
     * path that actually calls `markDialogAsRead`. The now-read post stays visible for the rest of
     * this viewing session (removing it out from under an active scroll would be jarring); it just
     * won't be there the next time Feed loads -- see [FeedActivity]'s doc.
     */
    fun onRowSeen(dialogId: Long, messageId: Int) {
        val mark = watermark[dialogId] ?: run { seed(dialogId); watermark[dialogId] ?: 0 }
        if (messageId <= mark) return
        watermark[dialogId] = messageId
    }

    /**
     * Marks every dialog in [dialogIds] fully read, used by the Feed screen's overflow menu.
     * Pass every eligible channel (not just the ones currently loaded into the feed window) so a
     * channel with unread posts that haven't been paginated in yet still gets cleared. The only
     * place in this class that touches the real server-side read state.
     *
     * Returns how many dialogs actually had something to mark -- most channels a normal user has
     * open are already read from using the regular chat list, so "nothing changed" is frequently
     * the CORRECT outcome, not a bug; the caller uses this count to tell the user which happened
     * instead of the button silently doing nothing either way.
     */
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

        /** The one tracker for [account]; every [FeedScope] shares it. */
        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedUnreadTracker = instances.getOrPut(account) { FeedUnreadTracker(account) }
    }
}
