package desu.inugram.helpers.feed

import org.telegram.messenger.MessagesController

/**
 * Per-channel read watermark for the Feed screen. Seeded from Telegram's own per-dialog read
 * state (`dialogs.read_inbox_max_id`) so a channel already read outside Feed starts read here too,
 * but [onRowSeen] itself is purely local bookkeeping -- it does NOT call `markDialogAsRead`.
 *
 * Two reasons, not one:
 * - Correctness bug: `MessagesController.markDialogAsRead` treats `countDiff == 0` as "mark this
 *   dialog FULLY read" (`dialog.unread_count = 0` unconditionally -- see its own source), not "zero
 *   messages newly read". Scrolling past a single post in Feed has no accurate countDiff to offer
 *   (unlike the real chat screen, which tracks it precisely against its own visible-item list), so
 *   passing 0 there was quietly zeroing a channel's entire unread count off one scrolled-past post
 *   -- which is exactly what made "Mark all read" look like a no-op afterwards: by the time it ran,
 *   `dialog.unread_count` was already (wrongly) 0 for anything already scrolled past, so
 *   [markAllRead]'s own already-read skip discarded it before the real API call ever fired.
 * - Product judgement: silently telling the server a channel's posts were "read" just because they
 *   scrolled through an aggregated feed is questionable on its own merits, independent of the bug
 *   above. The server-side read state only changes here on an explicit [markAllRead], or naturally
 *   when the user opens the channel itself from Feed (stock `ChatActivity` handles that already).
 *
 * `markDialogAsRead` already composes with `GhostHelper.shouldSuppressLocalRead`, so Ghost Mode
 * users get correct behavior for free on the one path that still calls it.
 */
class FeedUnreadTracker(private val account: Int) {

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
}
