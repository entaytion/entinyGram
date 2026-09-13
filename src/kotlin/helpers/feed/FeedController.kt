package desu.inugram.helpers.feed

import org.telegram.messenger.MessageObject

/**
 * Per-account Feed orchestrator. Lazily created and lazily activated: nothing here runs (no
 * `NotificationCenter` work, no DB reads) until [onScreenOpened] fires for the first time this
 * session, which is what keeps a never-opened Feed stock-identical in cost (rule #4) without
 * needing a separate master on/off toggle.
 *
 * New-message/deleted/history-cleared plumbing lives in [InuHooks] (`onNewMessage`,
 * `onMessagesDeleted`, `onHistoryCleared`) rather than a dedicated `NotificationCenter` observer
 * here -- those hooks already fire for every message/account, so Feed just taps into the existing
 * funnel instead of registering a second, redundant one.
 */
class FeedController private constructor(private val account: Int) {

    val store = FeedStore(account)
    val unreadTracker = FeedUnreadTracker(account)
    val backfill = FeedBackfillCoordinator(account)

    /**
     * Live-open [FeedActivity] hooks, so a screen that's already on screen updates in place
     * instead of only picking up changes the next time it's opened. All nullable: the controller
     * outlives any single screen instance (it's per-account, not per-fragment), so these are set
     * on [onScreenOpened] and cleared on [onScreenClosed] rather than passed through the
     * constructor.
     */
    var onLiveMessagesAdded: ((List<MessageObject>) -> Unit)? = null
    var onLiveMessagesRemoved: ((dialogId: Long, messageIds: Collection<Int>) -> Unit)? = null
    var onLiveDialogRemoved: ((dialogId: Long) -> Unit)? = null

    @Volatile
    var isActive = false
        private set

    private var openCount = 0

    /** Feed screen entered the foreground; loads the first page. */
    fun onScreenOpened(onReady: (List<MessageObject>) -> Unit) {
        openCount++
        if (!isActive) {
            isActive = true
            FeedChannelSet.pruneStaleExclusions(account)
            backfill.onChannelBackfilled = { dialogId ->
                // A gap-fill round landed in messages_v2 for `dialogId`; nothing to do here beyond
                // letting the next loadOlder() pick it up -- the caller re-requests on demand.
            }
        }
        store.loadInitial(onReady)
    }

    fun onScreenClosed() {
        openCount = (openCount - 1).coerceAtLeast(0)
    }

    /**
     * Extends the loaded window older, filtered to still-unread posts -- Feed only ever shows
     * things the user hasn't read yet, not a full archive (see [FeedActivity]'s doc). When a whole
     * fetched page turns out to be already-read, keeps paging automatically instead of surfacing
     * an empty result, so scrolling doesn't look stuck just because there's a read backlog behind
     * it; when the local cache comes up genuinely empty, opportunistically requests backfill for
     * whichever already-represented channels might have a gap there.
     */
    fun loadOlder(onResult: (List<MessageObject>) -> Unit) {
        store.loadOlder { added ->
            if (added.isEmpty()) {
                onResult(added)
                requestBackfill()
                return@loadOlder
            }
            val unread = added.filter { unreadTracker.isUnread(it.getDialogId(), it.id) }
            if (unread.isNotEmpty()) onResult(unread) else loadOlder(onResult)
        }
    }

    fun loadNewer(onResult: (List<MessageObject>) -> Unit) {
        store.loadNewer(onResult)
    }

    private fun requestBackfill() {
        val channels = FeedChannelSet.eligibleChannels(account)
        val candidates = ArrayList<Pair<Long, Int>>()
        for (dialogId in channels) {
            val boundary = store.oldestForChannel(dialogId) ?: continue
            candidates.add(dialogId to boundary)
        }
        backfill.request(candidates)
    }

    /** New messages pushed live for an eligible channel; folds straight into the store. */
    fun onNewMessages(messages: List<MessageObject>) {
        if (!isActive) return
        val added = store.mergeLive(messages)
        val unread = added.filter { unreadTracker.isUnread(it.getDialogId(), it.id) }
        if (unread.isNotEmpty()) onLiveMessagesAdded?.invoke(unread)
    }

    fun onMessagesDeleted(dialogId: Long, messageIds: Collection<Int>) {
        if (!isActive) return
        store.removeMessages(dialogId, messageIds)
        onLiveMessagesRemoved?.invoke(dialogId, messageIds)
    }

    fun onHistoryCleared(dialogId: Long) {
        if (!isActive) return
        store.removeDialog(dialogId)
        onLiveDialogRemoved?.invoke(dialogId)
    }

    fun onChannelSetChanged() {
        FeedChannelSet.invalidate()
    }

    companion object {
        private val instances = HashMap<Int, FeedController>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedController = instances.getOrPut(account) { FeedController(account) }

        /**
         * True once Feed has been opened at least once this session for [account]. Callers on a
         * hot path (every incoming message) should check this instead of [get], which would
         * otherwise lazily instantiate a controller for an account that never opened Feed.
         */
        @JvmStatic
        @Synchronized
        fun isActiveFor(account: Int): Boolean = instances[account]?.isActive == true
    }
}
