package desu.inugram.helpers.feed

import org.telegram.messenger.MessageObject

class FeedController private constructor(
    private val account: Int,
    val scope: FeedScope = FeedScope.Global,
) {

    val store = FeedStore(account, scope)
    val unreadTracker = FeedUnreadTracker.get(account)
    val backfill = FeedBackfillCoordinator.get(account)

    var onLiveMessagesAdded: ((List<MessageObject>) -> Unit)? = null
    var onLiveMessagesRemoved: ((dialogId: Long, messageIds: Collection<Int>) -> Unit)? = null
    var onLiveDialogRemoved: ((dialogId: Long) -> Unit)? = null

    var onBackfillCompleted: (() -> Unit)? = null

    @Volatile
    var isActive = false
        private set

    private var openCount = 0

    fun onScreenOpened(onReady: (List<MessageObject>) -> Unit) {
        openCount++
        if (!isActive) {
            isActive = true
            FeedChannelSet.pruneStaleExclusions(account)
            backfill.onChannelBackfilled = { _ -> onBackfillCompleted?.invoke() }
        }
        if (scope is FeedScope.Folder) registerOpenFolder(this)
        store.loadInitial(onReady)
    }

    fun onScreenClosed() {
        openCount = (openCount - 1).coerceAtLeast(0)
        if (scope is FeedScope.Folder) unregisterOpenFolder(this)
    }

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

        private val openFolderControllers = HashMap<Int, MutableSet<FeedController>>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedController = instances.getOrPut(account) { FeedController(account) }

        @JvmStatic
        fun forFolder(account: Int, filterId: Int): FeedController =
            FeedController(account, FeedScope.Folder(filterId))

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
