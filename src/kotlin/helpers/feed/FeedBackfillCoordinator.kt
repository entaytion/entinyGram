package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.ConnectionsManager

/**
 * On-demand gap-filling for channels whose local message cache doesn't reach as far back as the
 * Feed screen wants to scroll. Never a bulk "sync everything" pass on enable -- only triggered
 * when [FeedStore.loadOlder] comes up short, and throttled to a handful of channels per round so
 * a pile of dead/slow channels can't stall the feed. Uses the exact same entry point the real chat
 * screen uses (`MessagesController.loadMessages`, `LOAD_BACKWARD`); the network response is simply
 * written into `messages_v2` as a side effect, same as any other history load, so the caller just
 * re-queries [FeedStore] locally once a round finishes instead of parsing the response itself.
 */
class FeedBackfillCoordinator(private val account: Int) {

    /** Invoked on the UI thread once a channel's backfill round finishes (success or watchdog). */
    var onChannelBackfilled: ((dialogId: Long) -> Unit)? = null

    private val classGuid = ConnectionsManager.generateClassGuid()
    private val queueLock = Object()
    private val queue = LinkedHashMap<Long, Int>() // dialogId -> earliest locally-known message id
    private val inFlight = HashSet<Long>()
    private var observing = false

    // args shape confirmed against MessagesController's own
    // `postNotificationName(NotificationCenter.messagesDidLoad, dialogId, count, objects, isCache,
    // first_unread, deliveredLastMessageId, unread_count, last_date, load_type, isEnd, classGuid,
    // loadIndex, max_id, mentionsCount, mode)` -- dialogId at 0, classGuid at 10.
    private val observer = NotificationCenter.NotificationCenterDelegate { id, _, args ->
        if (id != NotificationCenter.messagesDidLoad) return@NotificationCenterDelegate
        val guid = args.getOrNull(10) as? Int ?: return@NotificationCenterDelegate
        if (guid != classGuid) return@NotificationCenterDelegate
        val dialogId = args.getOrNull(0) as? Long ?: return@NotificationCenterDelegate
        if (!inFlight.remove(dialogId)) return@NotificationCenterDelegate
        onChannelBackfilled?.invoke(dialogId)
        pump()
    }

    /** Queues channels for backfill; [candidates] is (dialogId, earliest locally-known message id). */
    fun request(candidates: List<Pair<Long, Int>>) {
        if (candidates.isEmpty()) return
        synchronized(queueLock) {
            for ((dialogId, boundaryId) in candidates) {
                if (inFlight.contains(dialogId)) continue
                queue[dialogId] = boundaryId
            }
        }
        ensureObserving()
        pump()
    }

    private fun ensureObserving() {
        if (observing) return
        observing = true
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.messagesDidLoad)
    }

    private fun pump() {
        val batch: List<Pair<Long, Int>>
        synchronized(queueLock) {
            if (queue.isEmpty() || inFlight.size >= MAX_CONCURRENT) return
            val room = MAX_CONCURRENT - inFlight.size
            batch = queue.entries.take(room).map { it.key to it.value }
            for ((dialogId, _) in batch) queue.remove(dialogId)
        }
        if (batch.isEmpty()) return
        val controller = MessagesController.getInstance(account)
        for ((dialogId, boundaryId) in batch) {
            inFlight.add(dialogId)
            controller.loadMessages(
                dialogId, 0L, false, PAGE_SIZE, boundaryId, 0, false, 0, classGuid,
                MessagesController.LOAD_BACKWARD, 0, 0, 0L, 0, 0, false,
            )
            // A channel that never answers must not stall the round forever.
            AndroidUtilities.runOnUIThread({
                if (inFlight.remove(dialogId)) {
                    onChannelBackfilled?.invoke(dialogId)
                    pump()
                }
            }, WATCHDOG_MS)
        }
    }

    fun cancelAll() {
        synchronized(queueLock) { queue.clear() }
        inFlight.clear()
        if (observing) {
            NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.messagesDidLoad)
            observing = false
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MAX_CONCURRENT = 4
        private const val WATCHDOG_MS = 10_000L
    }
}
