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
 *
 * [scope] decides which channels this controller's [store] merges. The global-scope controller is
 * the cached per-account singleton ([get]); folder-scoped ones come from [forFolder] and are
 * deliberately NOT cached -- see its doc for what that costs and why it's the right v1 trade.
 * [unreadTracker] and [backfill] are shared per-account regardless of scope.
 */
class FeedController private constructor(
    private val account: Int,
    val scope: FeedScope = FeedScope.Global,
) {

    val store = FeedStore(account, scope)
    val unreadTracker = FeedUnreadTracker.get(account)
    val backfill = FeedBackfillCoordinator.get(account)

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
        val channels = FeedChannelSet.eligibleChannels(account, scope)
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

        /** The cached global-scope controller for [account] -- the one `InuHooks` pushes live into. */
        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedController = instances.getOrPut(account) { FeedController(account) }

        /**
         * A folder-scoped controller, owned by the [desu.inugram.ui.feed.FeedActivity] instance that
         * asked for it and GC'd with it. Deliberately NOT cached in [instances]: `InuHooks`'s
         * new-message/deleted/history-cleared routing keeps talking only to the global controller,
         * so a folder-scoped screen gets NO live push while it's open.
         *
         * That's a deliberate v1 trade, not an oversight. Every load path reads local `messages_v2`,
         * which stock message ingestion keeps current regardless of Feed, so an open folder feed is
         * only ever stale by "posts that arrived since you opened it" and picks them up on the next
         * pagination or reopen. Wiring live push per scope would mean teaching `InuHooks` about a
         * registry of open scoped controllers -- explicitly out of scope here.
         */
        @JvmStatic
        fun forFolder(account: Int, filterId: Int): FeedController =
            FeedController(account, FeedScope.Folder(filterId))

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
