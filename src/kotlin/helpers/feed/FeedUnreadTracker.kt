package desu.inugram.helpers.feed

import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController

class FeedUnreadTracker private constructor(private val account: Int) {

    private val knownMax = HashMap<Long, Int>()
    private val pendingMax = HashMap<Long, Int>()
    private var flushScheduled = false
    private val flushRunnable = Runnable { flushNow() }

    private fun effectiveMax(dialogId: Long): Int {
        if (!knownMax.containsKey(dialogId)) {
            knownMax[dialogId] = maxOf(serverReadMax(MessagesController.getInstance(account), dialogId), storedMax(dialogId))
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

    // entiny: seen posts update only the feed's local read cursor
    fun onRowSeen(dialogId: Long, messageId: Int): Boolean {
        if (messageId <= effectiveMax(dialogId)) return false
        if ((pendingMax[dialogId] ?: 0) < messageId) pendingMax[dialogId] = messageId
        if (!flushScheduled) {
            flushScheduled = true
            AndroidUtilities.runOnUIThread(flushRunnable, FLUSH_DELAY_MS)
        }
        return true
    }

    fun flush() {
        AndroidUtilities.cancelRunOnUIThread(flushRunnable)
        flushNow()
    }

    private fun flushNow() {
        flushScheduled = false
        if (pendingMax.isEmpty()) return
        val entries = ArrayList(pendingMax.entries)
        pendingMax.clear()
        for ((dialogId, maxReadId) in entries) {
            if (maxReadId <= (knownMax[dialogId] ?: 0)) continue
            knownMax[dialogId] = maxReadId
            storeMax(dialogId, maxReadId)
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
            if (top <= effectiveMax(dialogId)) continue
            knownMax[dialogId] = top
            pendingMax.remove(dialogId)
            storeMax(dialogId, top)
            marked++
        }
        return marked
    }

    private fun serverReadMax(controller: MessagesController, dialogId: Long): Int =
        maxOf(controller.dialogs_dict?.get(dialogId)?.read_inbox_max_id ?: 0, controller.dialogs_read_inbox_max[dialogId] ?: 0)

    private fun storedMax(dialogId: Long): Int = preferences().getInt(dialogId.toString(), 0)

    private fun storeMax(dialogId: Long, maxReadId: Int) {
        preferences().edit().putInt(dialogId.toString(), maxReadId).apply()
    }

    private fun preferences() = ApplicationLoader.applicationContext
        .getSharedPreferences("inu_feed_read_$account", android.content.Context.MODE_PRIVATE)

    companion object {
        private const val FLUSH_DELAY_MS = 1000L

        private val instances = HashMap<Int, FeedUnreadTracker>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedUnreadTracker = instances.getOrPut(account) { FeedUnreadTracker(account) }

    }
}
