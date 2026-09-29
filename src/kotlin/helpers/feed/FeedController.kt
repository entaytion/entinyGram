package desu.inugram.helpers.feed

import android.os.SystemClock
import android.util.Log
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update

// entiny: owns one feed scope's timeline and keeps it live while a screen is attached
class FeedController private constructor(
    private val account: Int,
    val scope: FeedScope = FeedScope.Global,
) {

    interface Listener {
        fun onTimelineChanged(hasNewPosts: Boolean = false)
        fun onBackfilled() {}
    }

    val store = FeedStore(account, scope)
    val unreadTracker = FeedUnreadTracker.get(account)

    private var listener: Listener? = null
    private val backfillInFlight = HashSet<Long>()
    private val backfillPending = LinkedHashMap<Long, Int>()
    private var backfillActive = 0
    private var backfillPausedUntil = 0L
    private val backfillPumpRunnable = Runnable { pumpBackfill() }
    private var timelineUpdateScheduled = false
    private var pendingNewPosts = false
    private val timelineUpdateRunnable = Runnable {
        timelineUpdateScheduled = false
        val hasNewPosts = pendingNewPosts
        pendingNewPosts = false
        listener?.onTimelineChanged(hasNewPosts)
    }

    val isActive: Boolean get() = listener != null

    fun attach(listener: Listener) {
        val wasActive = isActive
        this.listener = listener
        if (!wasActive) {
            FeedChannelSet.pruneStaleExclusions(account)
            store.pruneCache()
            unreadTracker.refresh(FeedChannelSet.eligibleChannels(account, scope))
            store.channelsWithNewerPosts { requestBackfill(it) }
            if (scope is FeedScope.Folder) registerOpenFolder(this)
        }
        val hadRows = store.size > 0
        val channelsReset = store.ensureChannelGeneration()
        // entiny: live pushes only reach an attached (isActive) controller, so posts that arrived
        // while this screen was closed are otherwise lost forever -- catch up on reattach instead.
        if (hadRows && !channelsReset) {
            store.loadNewer { added -> if (added > 0) this.listener?.onTimelineChanged(true) }
        }
    }

    fun detach(listener: Listener) {
        if (this.listener !== listener) return
        this.listener = null
        AndroidUtilities.cancelRunOnUIThread(timelineUpdateRunnable)
        timelineUpdateScheduled = false
        pendingNewPosts = false
        backfillPending.clear()
        AndroidUtilities.cancelRunOnUIThread(backfillPumpRunnable)
        if (scope is FeedScope.Folder) unregisterOpenFolder(this)
        unreadTracker.flush()
        store.trim(DETACH_KEEP_ROWS)
        if (scope is FeedScope.Folder) releaseFolder(this)
    }

    fun loadOlder(onResult: (added: Int) -> Unit) {
        store.loadOlder { added ->
            if (added == 0) {
                requestBackfill(FeedChannelSet.eligibleChannels(account, scope).map { it to (store.oldestForChannel(it) ?: 0) })
            }
            onResult(added)
        }
    }

    // region backfill

    // entiny: history requests are queued and capped so a big channel list can't flood the connection
    private fun requestBackfill(candidates: List<Pair<Long, Int>>) {
        if (!isActive) return
        for ((dialogId, offsetId) in candidates) {
            if (dialogId !in backfillInFlight) backfillPending[dialogId] = offsetId
        }
        pumpBackfill()
    }

    private fun pumpBackfill() {
        val pause = backfillPausedUntil - SystemClock.elapsedRealtime()
        if (pause > 0) {
            AndroidUtilities.cancelRunOnUIThread(backfillPumpRunnable)
            AndroidUtilities.runOnUIThread(backfillPumpRunnable, pause)
            return
        }
        while (backfillActive < MAX_CONCURRENT_BACKFILL && backfillPending.isNotEmpty()) {
            val (dialogId, offsetId) = backfillPending.entries.first().let { it.key to it.value }
            backfillPending.remove(dialogId)
            if (!backfillInFlight.add(dialogId)) continue
            backfillActive++
            fetchHistory(dialogId, offsetId)
        }
    }

    private fun fetchHistory(dialogId: Long, offsetId: Int) {
        val peer = MessagesController.getInstance(account).getInputPeer(dialogId)
        if (peer == null) {
            finishBackfill(dialogId, emptyList())
            return
        }
        val request = TLRPC.TL_messages_getHistory().apply {
            this.peer = peer
            offset_id = offsetId
            limit = BACKFILL_PAGE_SIZE
        }
        ConnectionsManager.getInstance(account).sendRequest(request) { response, error ->
            val floodSeconds = error?.text?.takeIf { it.startsWith("FLOOD_WAIT_") }?.removePrefix("FLOOD_WAIT_")?.toIntOrNull()
            if (floodSeconds != null) {
                AndroidUtilities.runOnUIThread { retryAfterFlood(dialogId, offsetId, floodSeconds) }
                return@sendRequest
            }
            val messages = if (error == null && response is TLRPC.messages_Messages) {
                response.messages.filterNot { it is TLRPC.TL_messageEmpty }
            } else {
                if (error != null) Log.d(TAG, "history request failed for $dialogId: ${error.text}")
                emptyList()
            }
            AndroidUtilities.runOnUIThread { finishBackfill(dialogId, messages) }
        }
    }

    // entiny: Telegram asked us to slow down -- pause the whole queue and retry this channel once it passes
    private fun retryAfterFlood(dialogId: Long, offsetId: Int, seconds: Int) {
        backfillInFlight.remove(dialogId)
        backfillActive = (backfillActive - 1).coerceAtLeast(0)
        if (seconds <= MAX_FLOOD_RETRY_SECONDS && isActive) {
            backfillPending.putIfAbsent(dialogId, offsetId)
            backfillPausedUntil = maxOf(backfillPausedUntil, SystemClock.elapsedRealtime() + seconds * 1000L)
        }
        pumpBackfill()
    }

    private fun finishBackfill(dialogId: Long, messages: List<TLRPC.Message>) {
        backfillInFlight.remove(dialogId)
        backfillActive = (backfillActive - 1).coerceAtLeast(0)
        if (messages.isNotEmpty() && isActive) {
            val headBefore = store.head()
            store.mergeSynced(dialogId, messages) { added ->
                if (added > 0) {
                    // entiny: a new head means posts arrived while the screen was closed -- surface them like live ones
                    scheduleTimelineChanged(headBefore != null && store.head() !== headBefore)
                    listener?.onBackfilled()
                }
            }
        }
        pumpBackfill()
    }

    // endregion

    fun onNewMessages(messages: List<MessageObject>) {
        if (isActive) store.mergeLive(messages, { isActive }) { scheduleTimelineChanged(true) }
    }

    fun onMessagesDeleted(dialogId: Long, messageIds: Collection<Int>) {
        if (isActive && store.remove(dialogId, messageIds)) scheduleTimelineChanged()
    }

    fun onHistoryCleared(dialogId: Long) {
        if (isActive && store.removeDialog(dialogId)) scheduleTimelineChanged()
    }

    fun hideChannel(dialogId: Long) {
        if (store.removeDialog(dialogId)) scheduleTimelineChanged()
    }

    // entiny: feed-only reaction refresh -- applying the snapshot through processUpdates would
    // replay stale counts into ChatActivity and revert reactions the user just tapped
    fun applyReactionSnapshot(updates: TLRPC.Updates) {
        AndroidUtilities.runOnUIThread {
            if (!isActive) return@runOnUIThread
            var changed = false
            for (baseUpdate in updates.updates) {
                val update = baseUpdate as? TL_update.TL_updateMessageReactions ?: continue
                if (store.updateReactions(MessageObject.getPeerId(update.peer), update.msg_id, update.reactions)) changed = true
            }
            if (changed) scheduleTimelineChanged()
        }
    }

    private fun scheduleTimelineChanged(hasNewPosts: Boolean = false) {
        pendingNewPosts = pendingNewPosts || hasNewPosts
        if (timelineUpdateScheduled) return
        timelineUpdateScheduled = true
        AndroidUtilities.runOnUIThread(timelineUpdateRunnable, TIMELINE_UPDATE_DELAY_MS)
    }

    fun markAllRead(): Int = unreadTracker.markAllRead(FeedChannelSet.eligibleChannels(account, scope).toList(), store.newestIdPerChannel())

    // entiny: read state isn't part of any row, so seeing posts never rebuilds the timeline
    fun markRowsSeen(messages: Collection<MessageObject>): Boolean {
        var changed = false
        for (msg in messages) {
            if (unreadTracker.onRowSeen(msg.getDialogId(), msg.id)) changed = true
        }
        return changed
    }

    companion object {
        private const val TAG = "FeedController"
        private const val TIMELINE_UPDATE_DELAY_MS = 75L
        private const val BACKFILL_PAGE_SIZE = 30
        private const val MAX_CONCURRENT_BACKFILL = 4
        private const val MAX_FLOOD_RETRY_SECONDS = 30
        private const val DETACH_KEEP_ROWS = 150

        private val instances = HashMap<Int, FeedController>()
        private val folderInstances = HashMap<Int, HashMap<Int, FeedController>>()
        private val openFolderControllers = HashMap<Int, MutableSet<FeedController>>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedController = instances.getOrPut(account) { FeedController(account) }

        // entiny: folder controllers are cached so a folder feed keeps its loaded rows between visits
        @JvmStatic
        @Synchronized
        fun forFolder(account: Int, filterId: Int): FeedController =
            folderInstances.getOrPut(account) { HashMap() }
                .getOrPut(filterId) { FeedController(account, FeedScope.Folder(filterId)) }

        @JvmStatic
        @Synchronized
        fun allActiveFor(account: Int): List<FeedController> {
            val result = ArrayList<FeedController>()
            instances[account]?.let { if (it.isActive) result.add(it) }
            openFolderControllers[account]?.let { result.addAll(it) }
            return result
        }

        @Synchronized
        fun releaseInactive() {
            instances.values.removeAll { !it.isActive }
            for (folders in folderInstances.values) folders.values.removeAll { !it.isActive }
        }

        // entiny: a closed folder feed is rebuilt from the disk cache, so its rows should not stay pinned in memory
        @Synchronized
        private fun releaseFolder(controller: FeedController) {
            folderInstances[controller.account]?.values?.remove(controller)
        }

        @Synchronized
        private fun registerOpenFolder(controller: FeedController) {
            openFolderControllers.getOrPut(controller.account) { HashSet() }.add(controller)
        }

        @Synchronized
        private fun unregisterOpenFolder(controller: FeedController) {
            openFolderControllers[controller.account]?.remove(controller)
        }
    }
}
