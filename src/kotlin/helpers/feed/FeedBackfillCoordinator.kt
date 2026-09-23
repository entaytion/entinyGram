package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.ConnectionsManager

class FeedBackfillCoordinator private constructor(private val account: Int) {

    // entiny: one listener per open controller; a single callback slot let folder feeds overwrite each other
    val listeners = LinkedHashSet<(dialogId: Long) -> Unit>()

    private fun notifyBackfilled(dialogId: Long) {
        for (listener in ArrayList(listeners)) listener(dialogId)
    }

    private val classGuid = ConnectionsManager.generateClassGuid()
    private val queueLock = Object()
    private val queue = LinkedHashMap<Long, Int>()
    private val inFlight = HashSet<Long>()
    private var observing = false

    // entiny: MessagesController.messagesDidLoad args: dialogId at 0, classGuid at 10
    private val observer = NotificationCenter.NotificationCenterDelegate { id, _, args ->
        if (id != NotificationCenter.messagesDidLoad) return@NotificationCenterDelegate
        val guid = args.getOrNull(10) as? Int ?: return@NotificationCenterDelegate
        if (guid != classGuid) return@NotificationCenterDelegate
        val dialogId = args.getOrNull(0) as? Long ?: return@NotificationCenterDelegate
        if (!inFlight.remove(dialogId)) return@NotificationCenterDelegate
        notifyBackfilled(dialogId)
        pump()
    }

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
            AndroidUtilities.runOnUIThread({
                if (inFlight.remove(dialogId)) {
                    notifyBackfilled(dialogId)
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
        private val instances = HashMap<Int, FeedBackfillCoordinator>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedBackfillCoordinator =
            instances.getOrPut(account) { FeedBackfillCoordinator(account) }

        private const val PAGE_SIZE = 20
        private const val MAX_CONCURRENT = 4
        private const val WATCHDOG_MS = 10_000L
    }
}
