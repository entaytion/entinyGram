package desu.inugram.helpers.feed

import android.util.SparseArray
import android.util.SparseIntArray
import androidx.collection.LongSparseArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity

class FeedController private constructor(
    @JvmField val currentAccount: Int,
) : NotificationCenter.NotificationCenterDelegate {

    val store = FeedStore()
    private val unreadTracker = FeedUnreadTracker(currentAccount, store.getMessages())
    private val loader = FeedTimelineLoader(currentAccount)
    private val backfill = FeedBackfillCoordinator(currentAccount) { onBackfillRoundFinished() }

    private val initialLoadWaiters = ArrayList<IntArray>()
    private val closedRefreshGuid = ConnectionsManager.generateClassGuid()
    private val closedRefreshRunnable = Runnable { runClosedRefresh() }
    private var closedRefreshScheduled = false

    private var attemptRounds = 0
    private var cachedIncludedChannelCount = 0
    private var configGeneration = 0
    private var sessionGeneration = 0
    private var heldGuid = 0
    private var heldLoadIndex = 0
    private var uiActiveClients = 0
    private var resumedUiClients = 0

    private var hasChannels = false
    private var hasIncludedChannels = false
    private var initialUnreadScrollPending = true
    private var loading = false
    private var loadingNewer = false
    private var newerPagingBoundsDirty = false
    private var olderPagingBoundsDirty = false

    var drawerScrollPosition: SavedScrollPosition? = null
        private set

    fun interface ChannelsCallback {
        fun onChannels(channels: ArrayList<TLRPC.Chat>, includedCount: Int)
    }

    class SavedScrollPosition internal constructor(
        @JvmField val dialogId: Long,
        @JvmField val messageId: Int,
        @JvmField val offsetTop: Int,
    )

    init {
        AndroidUtilities.runOnUIThread { subscribe(currentAccount) }
    }

    private fun subscribe(account: Int) {
        val notificationCenter = NotificationCenter.getInstance(account)
        notificationCenter.addObserver(this, NotificationCenter.messagesDidLoad)
        notificationCenter.addObserver(this, NotificationCenter.loadingMessagesFailed)
        notificationCenter.addObserver(this, NotificationCenter.messagesDeleted)
        notificationCenter.addObserver(this, NotificationCenter.historyCleared)
        notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages)
        FeedChannelRegistry.getInstance(account).addListener { added, removed -> onFeedChannelsChanged(added, removed) }
    }

    private fun isUiActive(): Boolean = uiActiveClients > 0

    private fun applyEnumeration(enumeration: FeedTimelineLoader.ChannelEnumeration) {
        hasChannels = enumeration.hasChannels
        hasIncludedChannels = enumeration.included.isNotEmpty()
        cachedIncludedChannelCount = enumeration.included.size
        for (snapshot in enumeration.included) {
            var readInboxMax = snapshot.readInboxMax
            if (readInboxMax <= 0 && snapshot.unreadCount <= 0) {
                readInboxMax = snapshot.topMessage
            }
            unreadTracker.applyReadInboxMax(snapshot.dialogId, readInboxMax)
        }
    }

    private fun createMessageObjects(
        messages: ArrayList<TLRPC.Message>,
        users: ArrayList<TLRPC.User>,
        chats: ArrayList<TLRPC.Chat>,
    ): ArrayList<MessageObject> {
        val usersMap = HashMap<Long, TLRPC.User>()
        val chatsMap = HashMap<Long, TLRPC.Chat>()
        for (user in users) {
            usersMap[user.id] = user
        }
        for (chat in chats) {
            chatsMap[chat.id] = chat
        }
        val result = ArrayList<MessageObject>(messages.size)
        for (message in messages) {
            result.add(
                MessageObject(
                    currentAccount, message, null, usersMap, chatsMap, null, null,
                    true, true, 0L, false, false, false, FEED_SEARCH_TYPE
                )
            )
        }
        return result
    }

    private fun ensureCurrentConfig() {
        if (configGeneration != FeedConfig.getInstance(currentAccount).generation) {
            applyConfigChange { postNeedReload(it) }
        }
    }

    private fun postNeedReload(truncated: Boolean) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.feedNeedReload, truncated)
    }

    private fun flushInitialLoadWaiters() {
        if (initialLoadWaiters.isEmpty()) {
            return
        }
        val waiters = ArrayList(initialLoadWaiters)
        initialLoadWaiters.clear()
        val visibleMessages = store.getVisibleMessages()
        for (waiter in waiters) {
            postFeedResults(waiter[0], waiter[1], visibleMessages, 0)
            postFeedCount(waiter[0])
        }
    }

    private fun onBackfillRoundFinished() {
        if (loading) {
            runAttempt()
        }
    }

    private fun onFeedChannelsChanged(added: HashSet<Long>, removed: HashSet<Long>) {
        loader.invalidateChannelCache()
        for (dialogId in removed) {
            deleteHistory(dialogId, Int.MAX_VALUE)
        }
        if (added.isEmpty()) {
            postNeedReload(false)
        } else {
            reconcileChannelSet { postNeedReload(it) }
        }
    }

    private fun onFeedRowsRemoved() {
        if (loading) {
            olderPagingBoundsDirty = true
        }
        if (loadingNewer) {
            newerPagingBoundsDirty = true
        }
    }

    private fun postFeedCount(classGuid: Int) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(
            NotificationCenter.hashtagSearchUpdated,
            classGuid, store.getCount(), store.isEndReached(), 0, 0, 0
        )
    }

    private fun postFeedResults(
        classGuid: Int,
        loadIndex: Int,
        messages: ArrayList<MessageObject>,
        loadType: Int,
        hasMore: Boolean = false,
    ) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(
            NotificationCenter.messagesDidLoad,
            0L, messages.size, messages, false, 0, 0, 0, 0, loadType, true,
            classGuid, loadIndex, 0, 0, ChatActivity.MODE_SEARCH, hasMore
        )
    }

    private fun postNewerMessagesLoaded(classGuid: Int, loadIndex: Int, messages: ArrayList<MessageObject>?, hasMore: Boolean) {
        val ordered = ArrayList<MessageObject>()
        var loadType = 0
        if (messages != null && messages.isNotEmpty()) {
            ordered.addAll(messages)
            ordered.reverse()
            loadType = LOAD_TYPE_NEWER
        }
        postFeedResults(classGuid, loadIndex, ordered, loadType, hasMore)
    }

    private fun pruneStaleExclusions(config: FeedConfig, messagesController: MessagesController) {
        var stale: HashSet<Long>? = null
        for (dialogId in config.excludedSnapshot) {
            val chat = messagesController.getChat(-dialogId)
            if (chat != null && !isEligibleChannel(chat)) {
                if (stale == null) {
                    stale = HashSet()
                }
                stale.add(dialogId)
            }
        }
        if (stale != null) {
            config.removeExcluded(stale)
            markConfigApplied()
        }
    }

    private fun reconcileChannelSet(callback: Utilities.Callback<Boolean>?) {
        val generation = sessionGeneration
        val config = FeedConfig.getInstance(currentAccount)
        if (store.isEmpty()) {
            loadChannels { _, _ -> callback?.run(false) }
            return
        }
        val loadedDialogIds = store.getLoadedDialogIds()
        val hiddenDialogIds = store.getHiddenSnapshot()
        val newest = FeedTimelineLoader.Cursor()
        val oldest = FeedTimelineLoader.Cursor()
        newest.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid)
        oldest.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid)
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable {
            val enumeration = loader.enumerateChannels(config, generation, true)
            val freshDialogIds = ArrayList<Long>()
            for (snapshot in enumeration.included) {
                if (!loadedDialogIds.contains(snapshot.dialogId) || hiddenDialogIds.contains(snapshot.dialogId)) {
                    freshDialogIds.add(snapshot.dialogId)
                }
            }
            val page = if (freshDialogIds.isEmpty()) null else loader.loadChannelWindow(freshDialogIds, newest, oldest)
            val messageObjects = if (page != null) createMessageObjects(page.messages, page.users, page.chats) else null
            val hasFreshDialogs = freshDialogIds.isNotEmpty()
            AndroidUtilities.runOnUIThread {
                applyReconciledChannelSet(generation, callback, enumeration, page, messageObjects, hasFreshDialogs)
            }
        }
    }

    private fun applyReconciledChannelSet(
        generation: Int,
        callback: Utilities.Callback<Boolean>?,
        enumeration: FeedTimelineLoader.ChannelEnumeration,
        page: FeedTimelineLoader.WindowPage?,
        messageObjects: ArrayList<MessageObject>?,
        hasFreshDialogs: Boolean,
    ) {
        if (generation != sessionGeneration) {
            callback?.run(false)
            return
        }
        applyEnumeration(enumeration)
        val includedDialogIds = HashSet<Long>()
        for (snapshot in enumeration.included) {
            includedDialogIds.add(snapshot.dialogId)
        }
        store.applyIncludedDialogs(includedDialogIds)
        val truncated = page != null && page.truncated
        if (page != null && !truncated && messageObjects != null && messageObjects.isNotEmpty()) {
            val messagesController = MessagesController.getInstance(currentAccount)
            messagesController.putUsers(page.users, true)
            messagesController.putChats(page.chats, true)
            store.mergeRows(messageObjects)
        }
        if (hasFreshDialogs) {
            store.setEndReached(false)
            if (loading) {
                olderPagingBoundsDirty = true
            }
            if (loadingNewer) {
                newerPagingBoundsDirty = true
            }
        }
        callback?.run(truncated)
    }

    private fun runAttempt() {
        val classGuid = heldGuid
        val loadIndex = heldLoadIndex
        val generation = sessionGeneration
        val firstPage = store.getOldestCursor().isEmpty()
        val oldest = FeedTimelineLoader.Cursor()
        oldest.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid)
        val exhausted = backfill.getExhaustedSnapshot()
        val config = FeedConfig.getInstance(currentAccount)
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable {
            val enumeration = loader.enumerateChannels(config, generation, false)
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread { applyEmptyOlderPage(generation, enumeration, classGuid, loadIndex) }
                return@postRunnable
            }
            val page = loader.loadOlderPage(enumeration.included, oldest, exhausted)
            val messageObjects = createMessageObjects(page.messages, page.users, page.chats)
            AndroidUtilities.runOnUIThread {
                applyOlderPage(generation, enumeration, page, firstPage, messageObjects, classGuid, loadIndex)
            }
        }
    }

    private fun applyEmptyOlderPage(
        generation: Int,
        enumeration: FeedTimelineLoader.ChannelEnumeration,
        classGuid: Int,
        loadIndex: Int,
    ) {
        if (generation != sessionGeneration) {
            return
        }
        applyEnumeration(enumeration)
        olderPagingBoundsDirty = false
        unreadTracker.clear()
        loading = false
        store.setEndReached(true)
        postFeedResults(classGuid, loadIndex, ArrayList(), LOAD_TYPE_OLDER)
        postFeedCount(classGuid)
        flushInitialLoadWaiters()
    }

    private fun applyOlderPage(
        generation: Int,
        enumeration: FeedTimelineLoader.ChannelEnumeration,
        page: FeedTimelineLoader.OlderPage,
        firstPage: Boolean,
        messageObjects: ArrayList<MessageObject>,
        classGuid: Int,
        loadIndex: Int,
    ) {
        if (generation != sessionGeneration) {
            return
        }
        if (olderPagingBoundsDirty) {
            olderPagingBoundsDirty = false
            attemptRounds = 0
            runAttempt()
            return
        }
        applyEnumeration(enumeration)
        val messagesController = MessagesController.getInstance(currentAccount)
        pruneStaleExclusions(FeedConfig.getInstance(currentAccount), messagesController)
        store.getOldestCursor().set(page.last.date, page.last.uid, page.last.mid)
        if (firstPage && !page.first.isEmpty()) {
            store.getNewestCursor().set(page.first.date, page.first.uid, page.first.mid)
        }
        messagesController.putUsers(page.users, true)
        messagesController.putChats(page.chats, true)
        val appended = store.appendMessages(messageObjects, false)
        if (appended.isEmpty() && page.lastChunkRowCount == FULL_CHUNK_ROW_COUNT) {
            runAttempt()
            return
        }
        val endReached = !page.hasIncomplete && page.lastChunkRowCount < FULL_CHUNK_ROW_COUNT
        if (appended.isEmpty() && !endReached && page.backfillCandidates.isNotEmpty() && attemptRounds < MAX_BACKFILL_ROUNDS) {
            attemptRounds++
            backfill.startRound(page.backfillCandidates)
            return
        }
        loading = false
        store.setEndReached(endReached)
        postFeedResults(classGuid, loadIndex, appended, LOAD_TYPE_OLDER)
        postFeedCount(classGuid)
        flushInitialLoadWaiters()
    }

    private fun runLoadNewer(classGuid: Int, loadIndex: Int) {
        val generation = sessionGeneration
        val newest = FeedTimelineLoader.Cursor()
        newest.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid)
        val config = FeedConfig.getInstance(currentAccount)
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable {
            val enumeration = loader.enumerateChannels(config, generation, false)
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread { applyEmptyNewerPage(generation, classGuid, loadIndex) }
                return@postRunnable
            }
            val page = loader.loadNewerPage(enumeration.included, newest)
            val messageObjects = createMessageObjects(page.messages, page.users, page.chats)
            AndroidUtilities.runOnUIThread {
                applyNewerPage(generation, classGuid, loadIndex, enumeration, page, messageObjects)
            }
        }
    }

    private fun applyEmptyNewerPage(generation: Int, classGuid: Int, loadIndex: Int) {
        if (generation != sessionGeneration) {
            return
        }
        newerPagingBoundsDirty = false
        loadingNewer = false
        postNewerMessagesLoaded(classGuid, loadIndex, null, false)
        postFeedCount(classGuid)
    }

    private fun applyNewerPage(
        generation: Int,
        classGuid: Int,
        loadIndex: Int,
        enumeration: FeedTimelineLoader.ChannelEnumeration,
        page: FeedTimelineLoader.NewerPage,
        messageObjects: ArrayList<MessageObject>,
    ) {
        if (generation != sessionGeneration) {
            return
        }
        if (newerPagingBoundsDirty) {
            newerPagingBoundsDirty = false
            if (!store.getNewestCursor().isEmpty()) {
                runLoadNewer(classGuid, loadIndex)
                return
            }
            loadingNewer = false
            postNewerMessagesLoaded(classGuid, loadIndex, null, false)
            postFeedCount(classGuid)
            return
        }
        loadingNewer = false
        applyEnumeration(enumeration)
        store.getNewestCursor().set(page.first.date, page.first.uid, page.first.mid)
        if (page.messages.isEmpty()) {
            postNewerMessagesLoaded(classGuid, loadIndex, null, page.hasMore)
            if (!page.hasMore) {
                postFeedCount(classGuid)
            }
            return
        }
        val messagesController = MessagesController.getInstance(currentAccount)
        messagesController.putUsers(page.users, true)
        messagesController.putChats(page.chats, true)
        postNewerMessagesLoaded(classGuid, loadIndex, store.appendMessages(messageObjects, true), page.hasMore)
        if (!page.hasMore) {
            postFeedCount(classGuid)
        }
        trimForInactiveCache()
    }

    private fun runClosedRefresh() {
        closedRefreshScheduled = false
        if (isUiActive() || loadingNewer || store.isEmpty() || store.getNewestCursor().isEmpty()) {
            return
        }
        loadNewer(closedRefreshGuid, 0)
    }

    private fun scheduleClosedRefresh() {
        if (closedRefreshScheduled) {
            return
        }
        closedRefreshScheduled = true
        AndroidUtilities.runOnUIThread(closedRefreshRunnable, CLOSED_REFRESH_DELAY)
    }

    private fun updateCounters(counters: LongSparseArray<SparseIntArray>?, views: Boolean, updated: ArrayList<MessageObject>) {
        if (counters == null) {
            return
        }
        for (a in 0 until counters.size()) {
            val dialogId = counters.keyAt(a)
            val values = counters.valueAt(a)
            for (b in 0 until values.size()) {
                val messageObject = getMessage(dialogId, values.keyAt(b)) ?: continue
                val value = values.valueAt(b)
                val owner = messageObject.messageOwner
                if (views) {
                    if (value > owner.views) {
                        owner.views = value
                        addUpdated(updated, messageObject)
                    }
                } else if (value > owner.forwards) {
                    owner.forwards = value
                    addUpdated(updated, messageObject)
                }
            }
        }
    }

    private fun updateReplies(
        repliesArray: LongSparseArray<SparseArray<TLRPC.MessageReplies>>?,
        added: Boolean,
        updated: ArrayList<MessageObject>,
    ) {
        if (repliesArray == null) {
            return
        }
        for (a in 0 until repliesArray.size()) {
            val dialogId = repliesArray.keyAt(a)
            val values = repliesArray.valueAt(a)
            for (b in 0 until values.size()) {
                val messageObject = getMessage(dialogId, values.keyAt(b))
                val update = values.valueAt(b)
                if (messageObject == null || update == null) {
                    continue
                }
                val owner = messageObject.messageOwner
                if (added) {
                    if (owner.replies == null) {
                        owner.replies = TLRPC.TL_messageReplies()
                    }
                    owner.replies.replies += update.replies
                    for (c in 0 until update.recent_repliers.size) {
                        owner.replies.recent_repliers.remove(update.recent_repliers[c])
                    }
                    owner.replies.recent_repliers.addAll(0, update.recent_repliers)
                    while (owner.replies.recent_repliers.size > MAX_RECENT_REPLIERS) {
                        owner.replies.recent_repliers.removeAt(0)
                    }
                } else if (owner.replies == null ||
                    update.replies_pts > owner.replies.replies_pts ||
                    update.read_max_id > owner.replies.read_max_id ||
                    update.max_id > owner.replies.max_id
                ) {
                    owner.replies = update
                }
                messageObject.animateComments = true
                addUpdated(updated, messageObject)
            }
        }
    }

    fun applyConfigChange(callback: Utilities.Callback<Boolean>?) {
        configGeneration = FeedConfig.getInstance(currentAccount).generation
        reconcileChannelSet(callback)
    }

    fun cancelLoads() {
        sessionGeneration++
        loading = false
        loadingNewer = false
        olderPagingBoundsDirty = false
        newerPagingBoundsDirty = false
        attemptRounds = 0
        initialLoadWaiters.clear()
        backfill.cancel()
    }

    fun clear() {
        sessionGeneration++
        configGeneration = FeedConfig.getInstance(currentAccount).generation
        unreadTracker.clear()
        drawerScrollPosition = null
        store.clear()
        loading = false
        loadingNewer = false
        olderPagingBoundsDirty = false
        newerPagingBoundsDirty = false
        attemptRounds = 0
        initialLoadWaiters.clear()
        backfill.cancel()
        backfill.clearExhausted()
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable)
            closedRefreshScheduled = false
        }
    }

    fun consumeInitialUnreadScroll(): Boolean {
        val pending = initialUnreadScrollPending
        initialUnreadScrollPending = false
        return pending
    }

    fun countUnreadBelow(messages: ArrayList<MessageObject?>?, index: Int): Int =
        unreadTracker.countUnreadBelow(messages, index)

    fun deleteHistory(dialogId: Long, maxId: Int): ArrayList<Int> {
        val rowsRemoved = BooleanArray(1)
        val deleted = store.deleteHistory(dialogId, maxId, rowsRemoved)
        if (rowsRemoved[0]) {
            onFeedRowsRemoved()
        }
        return deleted
    }

    fun deleteMessages(dialogId: Long, messageIds: ArrayList<Int>?): ArrayList<Int> {
        val rowsRemoved = BooleanArray(1)
        val deleted = store.deleteMessages(dialogId, messageIds, rowsRemoved)
        if (rowsRemoved[0]) {
            onFeedRowsRemoved()
        }
        return deleted
    }

    @Suppress("UNCHECKED_CAST")
    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.messagesDidLoad) {
            backfill.onMessagesDidLoad(args)
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            backfill.onLoadingMessagesFailed(args)
        } else if (id == NotificationCenter.messagesDeleted) {
            if (isUiActive() || args[2] as Boolean) {
                return
            }
            var dialogId = args[1] as Long
            if (dialogId == 0L) {
                return
            }
            if (dialogId > 0) {
                dialogId = -dialogId
            }
            deleteMessages(dialogId, args[0] as ArrayList<Int>)
        } else if (id == NotificationCenter.historyCleared) {
            if (isUiActive()) {
                return
            }
            val dialogId = args[0] as Long
            if (DialogObject.isChatDialog(dialogId)) {
                deleteHistory(dialogId, args[1] as Int)
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (isUiActive() || args[2] as Boolean || store.isEmpty() || store.getNewestCursor().isEmpty() ||
                !isIncludedChannelPost(args[0] as Long)
            ) {
                return
            }
            scheduleClosedRefresh()
        }
    }

    fun findFirstUnreadIndex(messages: ArrayList<MessageObject?>?): Int = unreadTracker.findFirstUnreadIndex(messages)

    val includedChannelCount: Int
        get() = cachedIncludedChannelCount

    fun getMessage(dialogId: Long, messageId: Int): MessageObject? = store.getMessage(dialogId, messageId)

    val messages: ArrayList<MessageObject>
        get() = store.getMessages()

    fun getUnreadCount(): Int = unreadTracker.getUnreadCount()

    fun hasChannels(): Boolean = hasChannels

    fun hasIncludedChannels(): Boolean = hasIncludedChannels

    fun hasMessagesForDialog(dialogId: Long): Boolean = store.hasMessagesForDialog(dialogId)

    fun hasNoSyntheticIds(): Boolean = store.hasNoSyntheticIds()

    fun isIncludedChannelPost(dialogId: Long): Boolean {
        if (!DialogObject.isChatDialog(dialogId) || FeedConfig.getInstance(currentAccount).isHidden(currentAccount, dialogId)) {
            return false
        }
        return isEligibleChannel(MessagesController.getInstance(currentAccount).getChat(-dialogId))
    }

    fun loadChannels(callback: ChannelsCallback?) {
        val config = FeedConfig.getInstance(currentAccount)
        val generation = sessionGeneration
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable {
            val enumeration = loader.enumerateChannels(config, generation, false)
            AndroidUtilities.runOnUIThread {
                if (generation != sessionGeneration) {
                    return@runOnUIThread
                }
                applyEnumeration(enumeration)
                MessagesController.getInstance(currentAccount).putChats(enumeration.channels, true)
                callback?.onChannels(enumeration.channels, enumeration.included.size)
            }
        }
    }

    fun loadInitial(classGuid: Int, loadIndex: Int): Boolean {
        ensureCurrentConfig()
        if (store.isEmpty()) {
            if (!loadMore(classGuid, loadIndex)) {
                initialLoadWaiters.add(intArrayOf(classGuid, loadIndex))
            }
            return false
        }
        val visibleMessages = store.getVisibleMessages()
        for (message in visibleMessages) {
            message.viewsReloaded = false
        }
        if (visibleMessages.isEmpty() && !store.isEndReached()) {
            if (!loadMore(classGuid, loadIndex)) {
                initialLoadWaiters.add(intArrayOf(classGuid, loadIndex))
            }
            return false
        }
        val generation = sessionGeneration
        val config = FeedConfig.getInstance(currentAccount)
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable {
            val enumeration = loader.enumerateChannels(config, generation, true)
            AndroidUtilities.runOnUIThread {
                applyEnumeration(enumeration)
                postFeedResults(classGuid, loadIndex, visibleMessages, 0)
                postFeedCount(classGuid)
            }
        }
        return true
    }

    fun loadMore(classGuid: Int, loadIndex: Int): Boolean {
        ensureCurrentConfig()
        if (loading || (store.isEndReached() && !store.getOldestCursor().isEmpty())) {
            return false
        }
        loading = true
        heldGuid = classGuid
        heldLoadIndex = loadIndex
        attemptRounds = 0
        runAttempt()
        return true
    }

    fun loadNewer(classGuid: Int, loadIndex: Int): Boolean {
        ensureCurrentConfig()
        if (loadingNewer || store.getNewestCursor().isEmpty()) {
            return false
        }
        loadingNewer = true
        runLoadNewer(classGuid, loadIndex)
        return true
    }

    fun markAllRead() {
        unreadTracker.markAllRead()
    }

    fun markConfigApplied() {
        configGeneration = FeedConfig.getInstance(currentAccount).generation
    }

    fun onPostSeen(dialogId: Long, messageId: Int) {
        unreadTracker.onPostSeen(dialogId, messageId)
    }

    fun refreshReadState(callback: Runnable?) {
        val generation = sessionGeneration
        val config = FeedConfig.getInstance(currentAccount)
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable {
            val enumeration = loader.enumerateChannels(config, generation, true)
            AndroidUtilities.runOnUIThread {
                if (generation != sessionGeneration) {
                    return@runOnUIThread
                }
                applyEnumeration(enumeration)
                callback?.run()
            }
        }
    }

    fun replaceMessage(oldMessage: MessageObject?, newMessage: MessageObject?) {
        store.replaceMessage(oldMessage, newMessage)
    }

    fun resolveRealDialogId(syntheticMessageId: Int): Long = store.resolveRealDialogId(syntheticMessageId)

    fun resolveRealMessageId(dialogId: Long, syntheticMessageId: Int): Int =
        store.resolveRealMessageId(dialogId, syntheticMessageId)

    fun saveDrawerScrollPosition(dialogId: Long, messageId: Int, offsetTop: Int) {
        if (dialogId == 0L || messageId <= 0) {
            return
        }
        drawerScrollPosition = SavedScrollPosition(dialogId, messageId, offsetTop)
    }

    fun setUiActive(active: Boolean) {
        if (!active) {
            if (uiActiveClients == 0) {
                return
            }
            uiActiveClients--
            if (uiActiveClients == 0) {
                cancelLoads()
                trimForInactiveCache()
            }
            return
        }
        uiActiveClients++
        if (uiActiveClients > 1) {
            return
        }
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable)
            closedRefreshScheduled = false
        }
        if (loadingNewer) {
            cancelLoads()
        }
    }

    fun setUiResumed(resumed: Boolean) {
        if (resumed) {
            resumedUiClients++
        } else if (resumedUiClients > 0) {
            resumedUiClients--
        }
    }

    fun trimForInactiveCache() {
        if (isUiActive() || store.isEmpty()) {
            return
        }
        store.trim(getInactiveCacheCap())
    }

    fun updateViews(
        views: LongSparseArray<SparseIntArray>?,
        forwards: LongSparseArray<SparseIntArray>?,
        replies: LongSparseArray<SparseArray<TLRPC.MessageReplies>>?,
        addedReplies: Boolean,
    ): ArrayList<MessageObject> {
        val updated = ArrayList<MessageObject>()
        updateCounters(views, true, updated)
        updateCounters(forwards, false, updated)
        updateReplies(replies, addedReplies, updated)
        return updated
    }

    companion object {
        private const val FULL_CHUNK_ROW_COUNT = 30
        private const val MAX_BACKFILL_ROUNDS = 3
        private const val MAX_RECENT_REPLIERS = 3
        private const val CLOSED_REFRESH_DELAY = 1000L

        private const val INACTIVE_CACHE_CAP_LOW = 300
        private const val INACTIVE_CACHE_CAP_AVERAGE = 600
        private const val INACTIVE_CACHE_CAP_HIGH = 1000

        private const val LOAD_TYPE_NEWER = 1
        private const val LOAD_TYPE_OLDER = 2

        private const val FEED_SEARCH_TYPE = 4

        private val instances = arrayOfNulls<FeedController>(UserConfig.MAX_ACCOUNT_COUNT)
        private val lockObjects = Array(UserConfig.MAX_ACCOUNT_COUNT) { Any() }

        @JvmStatic
        fun getInstance(account: Int): FeedController {
            var localInstance = instances[account]
            if (localInstance != null) {
                return localInstance
            }
            synchronized(lockObjects[account]) {
                localInstance = instances[account]
                if (localInstance == null) {
                    localInstance = FeedController(account)
                    instances[account] = localInstance
                }
            }
            return localInstance!!
        }

        @JvmStatic
        fun peekInstance(account: Int): FeedController? = instances[account]

        @JvmStatic
        fun isEligibleChannel(chat: TLRPC.Chat?): Boolean =
            chat != null &&
                ChatObject.isChannelAndNotMegaGroup(chat) &&
                !ChatObject.isCommunity(chat) &&
                !ChatObject.isNotInChat(chat)

        private fun getInactiveCacheCap(): Int {
            val performanceClass = SharedConfig.getDevicePerformanceClass()
            if (performanceClass == SharedConfig.PERFORMANCE_CLASS_LOW) {
                return INACTIVE_CACHE_CAP_LOW
            }
            if (performanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
                return INACTIVE_CACHE_CAP_HIGH
            }
            return INACTIVE_CACHE_CAP_AVERAGE
        }

        private fun addUpdated(updated: ArrayList<MessageObject>, messageObject: MessageObject) {
            if (!updated.contains(messageObject)) {
                updated.add(messageObject)
            }
        }
    }
}
