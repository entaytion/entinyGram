package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
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
    private val backfill = FeedBackfillCoordinator.get(account)

    private var listener: Listener? = null
    private val backfillListener: (Long, List<TLRPC.Message>) -> Unit = { dialogId, messages ->
        if (store.mergeSynced(dialogId, messages) > 0) {
            scheduleTimelineChanged()
            listener?.onBackfilled()
        }
    }
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
            unreadTracker.refresh(FeedChannelSet.eligibleChannels(account, scope))
            backfill.listeners.add(backfillListener)
            backfill.request(FeedChannelSet.eligibleChannels(account, scope).map { it to 0 })
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
        backfill.listeners.remove(backfillListener)
        if (scope is FeedScope.Folder) unregisterOpenFolder(this)
        unreadTracker.flush()
        store.trim()
    }

    fun loadOlder(onResult: (added: Int) -> Unit) {
        store.loadOlder { added ->
            if (added == 0) requestBackfill()
            onResult(added)
        }
    }

    private fun requestBackfill() {
        val candidates = ArrayList<Pair<Long, Int>>()
        for (dialogId in FeedChannelSet.eligibleChannels(account, scope)) {
            candidates.add(dialogId to (store.oldestForChannel(dialogId) ?: 0))
        }
        backfill.request(candidates)
    }

    fun onNewMessages(messages: List<MessageObject>) {
        if (isActive && store.mergeLive(messages)) scheduleTimelineChanged(true)
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

    fun markRowsSeen(messages: Collection<MessageObject>) {
        var changed = false
        for (msg in messages) {
            if (unreadTracker.onRowSeen(msg.getDialogId(), msg.id)) changed = true
        }
        if (changed) scheduleTimelineChanged()
    }

    companion object {
        private const val TIMELINE_UPDATE_DELAY_MS = 75L

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
        private fun registerOpenFolder(controller: FeedController) {
            openFolderControllers.getOrPut(controller.account) { HashSet() }.add(controller)
        }

        @Synchronized
        private fun unregisterOpenFolder(controller: FeedController) {
            openFolderControllers[controller.account]?.remove(controller)
        }
    }
}
