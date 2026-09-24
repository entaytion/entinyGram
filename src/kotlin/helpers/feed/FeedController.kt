package desu.inugram.helpers.feed

import android.util.SparseIntArray
import androidx.collection.LongSparseArray
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

// entiny: owns one feed scope's timeline and keeps it live while a screen is attached
class FeedController private constructor(
    private val account: Int,
    val scope: FeedScope = FeedScope.Global,
) {

    interface Listener {
        fun onTimelineChanged()
        fun onBackfilled() {}
    }

    val store = FeedStore(account, scope)
    val unreadTracker = FeedUnreadTracker.get(account)
    private val backfill = FeedBackfillCoordinator.get(account)

    private var listener: Listener? = null
    private val backfillListener: (Long) -> Unit = { listener?.onBackfilled() }

    val isActive: Boolean get() = listener != null

    private val updatesObserver = NotificationCenter.NotificationCenterDelegate { id, _, args ->
        val changed = when (id) {
            NotificationCenter.replaceMessagesObjects -> {
                val dialogId = args[0] as? Long ?: return@NotificationCenterDelegate
                @Suppress("UNCHECKED_CAST")
                val objects = args[1] as? ArrayList<MessageObject> ?: return@NotificationCenterDelegate
                store.replace(dialogId, objects)
            }
            NotificationCenter.didUpdateReactions -> {
                val dialogId = args[0] as? Long ?: return@NotificationCenterDelegate
                val messageId = args[1] as? Int ?: return@NotificationCenterDelegate
                val reactions = args[2] as? TLRPC.TL_messageReactions ?: return@NotificationCenterDelegate
                store.updateReactions(dialogId, messageId, reactions)
            }
            NotificationCenter.didUpdateMessagesViews -> applyViews(args)
            else -> false
        }
        if (changed) listener?.onTimelineChanged()
    }

    fun attach(listener: Listener) {
        val wasActive = isActive
        this.listener = listener
        if (!wasActive) {
            FeedChannelSet.pruneStaleExclusions(account)
            unreadTracker.refresh(FeedChannelSet.eligibleChannels(account, scope))
            backfill.listeners.add(backfillListener)
            val nc = NotificationCenter.getInstance(account)
            for (event in OBSERVED) nc.addObserver(updatesObserver, event)
            if (scope is FeedScope.Folder) registerOpenFolder(this)
        }
        store.ensureChannelGeneration()
    }

    fun detach(listener: Listener) {
        if (this.listener !== listener) return
        this.listener = null
        backfill.listeners.remove(backfillListener)
        val nc = NotificationCenter.getInstance(account)
        for (event in OBSERVED) nc.removeObserver(updatesObserver, event)
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
            val boundary = store.oldestForChannel(dialogId) ?: continue
            candidates.add(dialogId to boundary)
        }
        backfill.request(candidates)
    }

    fun onNewMessages(messages: List<MessageObject>) {
        if (isActive && store.mergeLive(messages)) listener?.onTimelineChanged()
    }

    fun onMessagesDeleted(dialogId: Long, messageIds: Collection<Int>) {
        if (isActive && store.remove(dialogId, messageIds)) listener?.onTimelineChanged()
    }

    fun onHistoryCleared(dialogId: Long) {
        if (isActive && store.removeDialog(dialogId)) listener?.onTimelineChanged()
    }

    fun hideChannel(dialogId: Long) {
        if (store.removeDialog(dialogId)) listener?.onTimelineChanged()
    }

    fun markAllRead(): Int = unreadTracker.markAllRead(FeedChannelSet.eligibleChannels(account, scope).toList(), store.newestIdPerChannel())

    private fun applyViews(args: Array<out Any?>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val views = args.getOrNull(0) as? LongSparseArray<SparseIntArray>
        @Suppress("UNCHECKED_CAST")
        val forwards = args.getOrNull(1) as? LongSparseArray<SparseIntArray>
        val dialogs = HashSet<Long>()
        views?.let { for (i in 0 until it.size()) dialogs.add(it.keyAt(i)) }
        forwards?.let { for (i in 0 until it.size()) dialogs.add(it.keyAt(i)) }
        var changed = false
        for (dialogId in dialogs) {
            if (store.updateViews(dialogId, views?.get(dialogId).toMap(), forwards?.get(dialogId).toMap())) changed = true
        }
        return changed
    }

    private fun SparseIntArray?.toMap(): Map<Int, Int> {
        if (this == null) return emptyMap()
        val out = HashMap<Int, Int>(size())
        for (i in 0 until size()) out[keyAt(i)] = valueAt(i)
        return out
    }

    companion object {
        private val OBSERVED = intArrayOf(
            NotificationCenter.replaceMessagesObjects,
            NotificationCenter.didUpdateReactions,
            NotificationCenter.didUpdateMessagesViews,
        )

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
