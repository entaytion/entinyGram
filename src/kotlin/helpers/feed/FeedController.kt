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

    /**
     * A gap-fill round just landed new history in local storage for some channel. Nothing was
     * merged in automatically -- the screen decides whether it's still worth re-querying (e.g. it
     * gave up with "all read" before the backfill it itself triggered had a chance to land).
     */
    var onBackfillCompleted: (() -> Unit)? = null

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
            backfill.onChannelBackfilled = { _ -> onBackfillCompleted?.invoke() }
        }
        // The global controller is the shared per-account singleton (see [get]) -- InuHooks
        // already knows to reach it. A folder-scoped one is fresh per screen (see [forFolder]) and
        // otherwise invisible to the outside world, so it has to opt itself into live delivery for
        // as long as its screen is open, and opt back out in onScreenClosed.
        if (scope is FeedScope.Folder) registerOpenFolder(this)
        store.loadInitial(onReady)
    }

    fun onScreenClosed() {
        openCount = (openCount - 1).coerceAtLeast(0)
        if (scope is FeedScope.Folder) unregisterOpenFolder(this)
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

        // account -> folder-scoped controllers whose screen is currently open. Unlike [instances]
        // this is a live "who wants pushes right now" registry, not a cache: entries come and go
        // with FeedActivity.onScreenOpened/onScreenClosed, since forFolder() controllers are
        // otherwise thrown away with their screen (see [forFolder]).
        private val openFolderControllers = HashMap<Int, MutableSet<FeedController>>()

        /** The cached global-scope controller for [account] -- the one `InuHooks` pushes live into. */
        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedController = instances.getOrPut(account) { FeedController(account) }

        /**
         * A folder-scoped controller, owned by the [desu.inugram.ui.feed.FeedActivity] instance that
         * asked for it and GC'd with it. Deliberately NOT cached in [instances] -- a folder is an
         * arbitrarily narrow slice of the account, so caching one per filter would mean keeping a
         * separate loaded window warm for every folder the user has ever opened Feed on, most of
         * which would go stale the moment they're not looking. It self-registers into
         * [openFolderControllers] for the duration its screen is open instead (see [onScreenOpened]),
         * which is enough for [allActiveFor] to reach it live.
         */
        @JvmStatic
        fun forFolder(account: Int, filterId: Int): FeedController =
            FeedController(account, FeedScope.Folder(filterId))

        /** Every controller for [account] that should receive a live push right now: the cached
         * global controller once it's ever been opened, plus every folder-scoped screen open now.
         * Empty for an account that has never opened Feed -- callers on a hot path (every incoming
         * message) get an early exit for free by just iterating this instead of calling [get]. */
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
