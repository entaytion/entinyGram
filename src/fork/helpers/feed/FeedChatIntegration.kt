package desu.inugram.helpers.feed

import android.graphics.RectF
import androidx.collection.LongSparseArray
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

class FeedChatIntegration(
    private val currentAccount: Int,
    private val host: Host,
    private val restoreDrawerScrollPosition: Boolean,
) {
    private var channelsChangedCallback: Runnable? = null
    private var destroyed = false
    private var initialScrollApplied = false
    private var pagedownShownByScroll = false
    private var pendingDividerScroll = false
    private var pendingHideDialogId = 0L
    private var pendingInitialScrollRestore: ScrollAnchor? = null
    private var reactionsRefreshScheduled = false
    private var readyToMarkAsRead = false
    private var scrollPreservedNewerToUnread = false
    private var settleAtNewestScheduled = false
    private var totalScrollDy = 0
    private var unreadDivider: MessageObject? = null
    private var viewportActive = false
    private var preserveScrollLoadIndex = -1
    private var lastPagedownCount = -1
    private var seenNewestRow: MessageObject? = null
    private var seenOldestRow: MessageObject? = null

    private val settleAtNewestRunnable = Runnable { settleAtNewestNow() }

    private val reactionsRequestGuid = ConnectionsManager.generateClassGuid()
    private val reactionsLastCheckTimes = LongSparseArray<Long>()
    private val pendingReactionIds = LongSparseArray<ArrayList<Int>>()
    private val reactionsRefreshRunnable = Runnable { flushReactionsRefresh() }

    interface Host {
        fun canScrollToNewer(): Boolean

        fun captureScrollAnchor(): ScrollAnchor?

        fun deleteRows(ids: ArrayList<Int>)

        fun getDistanceToNewerPx(): Int

        fun getFragment(): BaseFragment

        fun getLastVisibleMessageIndex(): Int

        fun getMessages(): ArrayList<MessageObject?>

        fun getNewestVisibleMessageIndex(): Int

        fun invalidateVisiblePart()

        fun isFirstLoadComplete(): Boolean

        fun isListReady(): Boolean

        fun isListScrollIdle(): Boolean

        fun isPagedownButtonVisible(): Boolean

        fun isScrollAnimationRunning(): Boolean

        fun materializeRow(message: MessageObject)

        fun nextStableId(): Int

        fun notifyAllMessagesChanged()

        fun notifyMessageInserted(index: Int)

        fun notifyMessageRemoved(index: Int)

        fun onFeedListChanged()

        fun reloadFeed()

        fun requestOlderFeedPage()

        fun restoreScrollAnchor(anchor: ScrollAnchor?)

        fun scrollToMessage(index: Int, offset: Int)

        fun scrollToMessageAnimated(index: Int, offset: Int)

        fun setPagedownButtonVisible(visible: Boolean)

        fun setPagedownCount(count: Int)

        fun showEmptyFeedProgress()

        fun showEmptyFeedState()

        fun stableIdForDateHeader(dateKey: Int): Int
    }

    class ScrollAnchor(
        @JvmField val row: MessageObject?,
        @JvmField val offsetTop: Int,
    )

    private fun applyUnreadDivider(scrollToDivider: Boolean, incrementalNotify: Boolean) {
        if (!host.isListReady()) {
            return
        }
        val rows = host.getMessages()
        if (rows.isEmpty()) {
            unreadDivider = null
            readyToMarkAsRead = false
            return
        }
        val previousIndex = if (unreadDivider == null) -1 else rows.indexOf(unreadDivider)
        var divider = if (previousIndex >= 0) unreadDivider else null
        if (previousIndex >= 0) {
            rows.removeAt(previousIndex)
        }
        unreadDivider = null

        val firstUnreadIndex = FeedController.getInstance(currentAccount).findFirstUnreadIndex(rows)
        if (firstUnreadIndex < 0) {
            pendingDividerScroll = false
            if (previousIndex >= 0) {
                if (!scrollToDivider || incrementalNotify) {
                    host.notifyMessageRemoved(previousIndex)
                    host.invalidateVisiblePart()
                } else {
                    host.notifyAllMessagesChanged()
                }
            }
            readyToMarkAsRead = true
            return
        }

        val insertIndex = findDividerInsertIndex(rows, firstUnreadIndex)
        if (divider == null) {
            divider = FeedMessageUtils.createUnreadDivider(currentAccount, host.nextStableId())
        }
        rows.add(insertIndex, divider)
        unreadDivider = divider

        if (!scrollToDivider) {
            readyToMarkAsRead = true
            if (previousIndex < 0) {
                host.notifyMessageInserted(insertIndex)
                host.invalidateVisiblePart()
            } else if (previousIndex != insertIndex) {
                host.notifyMessageRemoved(previousIndex)
                host.notifyMessageInserted(insertIndex)
                host.invalidateVisiblePart()
            }
            return
        }

        readyToMarkAsRead = false
        if (!incrementalNotify) {
            host.notifyAllMessagesChanged()
        } else if (previousIndex < 0) {
            host.notifyMessageInserted(insertIndex)
        } else if (previousIndex != insertIndex) {
            host.notifyMessageRemoved(previousIndex)
            host.notifyMessageInserted(insertIndex)
        }
        pendingDividerScroll = true
        requestPendingInitialPosition()
        host.invalidateVisiblePart()
    }

    private fun cancelPendingReactionsRefresh() {
        AndroidUtilities.cancelRunOnUIThread(reactionsRefreshRunnable)
        reactionsRefreshScheduled = false
        pendingReactionIds.clear()
    }

    private fun createDateHeader(message: MessageObject): MessageObject =
        FeedMessageUtils.createDateHeader(currentAccount, message, host.stableIdForDateHeader(message.dateKeyInt))

    private fun flushReactionsRefresh() {
        reactionsRefreshScheduled = false
        if (destroyed || !viewportActive) {
            pendingReactionIds.clear()
            return
        }
        for (i in 0 until pendingReactionIds.size()) {
            val request = TLRPC.TL_messages_getMessagesReactions()
            request.peer = MessagesController.getInstance(currentAccount).getInputPeer(pendingReactionIds.keyAt(i))
            request.id.addAll(pendingReactionIds.valueAt(i))
            val requestToken = ConnectionsManager.getInstance(currentAccount)
                .sendRequest(request, RequestDelegate { response, _ -> onReactionsLoaded(response) })
            ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(requestToken, reactionsRequestGuid)
        }
        pendingReactionIds.clear()
    }

    private fun onReactionsLoaded(response: TLObject?) {
        if (response !is TLRPC.Updates) {
            return
        }
        for (i in response.updates.indices) {
            val update = response.updates[i]
            if (update is TL_update.TL_updateMessageReactions) {
                update.updateUnreadState = false
            }
        }
        MessagesController.getInstance(currentAccount).processUpdates(response, false)
    }

    private fun hasMaterializedPostRows(): Boolean {
        val rows = host.getMessages()
        for (i in rows.indices) {
            if (FeedMessageUtils.isPostRow(rows[i])) {
                return true
            }
        }
        return false
    }

    private fun hasPendingInitialPosition(): Boolean =
        pendingInitialScrollRestore != null || pendingDividerScroll

    private fun maybeScrollToDivider() {
        if (!pendingDividerScroll) {
            return
        }
        if (!host.isListReady() || unreadDivider == null) {
            pendingDividerScroll = false
            readyToMarkAsRead = true
            return
        }
        val lastVisibleIndex = host.getLastVisibleMessageIndex()
        if (lastVisibleIndex == Int.MIN_VALUE) {
            return
        }
        val dividerIndex = host.getMessages().indexOf(unreadDivider)
        if (dividerIndex >= 0 && dividerIndex > lastVisibleIndex) {
            host.scrollToMessage(dividerIndex, AndroidUtilities.dp(UNREAD_DIVIDER_SCROLL_OFFSET_DP.toFloat()))
        }
        pendingDividerScroll = false
        readyToMarkAsRead = true
    }

    private fun normalizeDateHeaders(rows: ArrayList<MessageObject?>): Boolean {
        var changed = false
        var pendingRun: MessageObject? = null
        var index = 0
        while (index < rows.size) {
            val row = rows[index]
            if (row == null || row.type == MessageObject.TYPE_LOADING || row.isSponsored) {
                index++
                continue
            }
            if (row.isDateObject) {
                if (pendingRun == null) {
                    rows.removeAt(index)
                    host.notifyMessageRemoved(index)
                    changed = true
                    continue
                }
                if (pendingRun.dateKeyInt != row.dateKeyInt) {
                    rows.add(index, createDateHeader(pendingRun))
                    host.notifyMessageInserted(index)
                    changed = true
                    pendingRun = null
                    index++
                    continue
                }
                pendingRun = null
                index++
                continue
            }
            if (pendingRun != null && pendingRun.dateKeyInt != row.dateKeyInt) {
                rows.add(index, createDateHeader(pendingRun))
                host.notifyMessageInserted(index)
                changed = true
                pendingRun = null
                index++
                continue
            }
            pendingRun = row
            index++
        }
        if (pendingRun != null) {
            rows.add(createDateHeader(pendingRun))
            host.notifyMessageInserted(rows.size - 1)
            changed = true
        }
        return changed
    }

    private fun requestPendingInitialPosition() {
        if (host.getFragment().isPaused() || !host.isListReady()) {
            return
        }
        val restore = pendingInitialScrollRestore
        if (restore != null) {
            host.restoreScrollAnchor(restore)
            return
        }
        if (!pendingDividerScroll) {
            return
        }
        val dividerIndex = if (unreadDivider == null) -1 else host.getMessages().indexOf(unreadDivider)
        if (dividerIndex >= 0) {
            host.scrollToMessage(dividerIndex, AndroidUtilities.dp(UNREAD_DIVIDER_SCROLL_OFFSET_DP.toFloat()))
        } else {
            pendingDividerScroll = false
            readyToMarkAsRead = true
        }
    }

    private fun requestReactionsRefresh(message: MessageObject) {
        if (destroyed || !viewportActive || message.messageOwner == null) {
            return
        }
        val realId = message.realId
        val dialogId = message.dialogId
        if (realId <= 0 || dialogId == 0L) {
            return
        }
        if (message.messageOwner.action != null && !message.canSetReaction()) {
            return
        }
        val now = System.currentTimeMillis()
        val syntheticId = message.id.toLong()
        if (now - reactionsLastCheckTimes.get(syntheticId, 0L) <= REACTIONS_RECHECK_INTERVAL) {
            return
        }
        reactionsLastCheckTimes.put(syntheticId, now)
        var ids = pendingReactionIds.get(dialogId)
        if (ids == null) {
            ids = ArrayList()
            pendingReactionIds.put(dialogId, ids)
        }
        ids.add(realId)
        if (reactionsRefreshScheduled) {
            return
        }
        reactionsRefreshScheduled = true
        AndroidUtilities.runOnUIThread(reactionsRefreshRunnable)
    }

    private fun resetMetadataRefresh() {
        cancelPendingReactionsRefresh()
        reactionsLastCheckTimes.clear()
        ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(reactionsRequestGuid)
    }

    private fun settleAtNewestNow() {
        settleAtNewestScheduled = false
        if (destroyed || !viewportActive || !host.isListReady() || host.isScrollAnimationRunning() || host.canScrollToNewer()) {
            return
        }
        settleUnreadDivider()
    }

    private fun undoHideChannel() {
        val dialogId = pendingHideDialogId
        if (dialogId == 0L) {
            return
        }
        pendingHideDialogId = 0L
        val feedController = FeedController.getInstance(currentAccount)
        FeedConfig.getInstance(currentAccount).setExcluded(dialogId, false)
        feedController.markConfigApplied()
        feedController.store.setHidden(dialogId, false)
        reconcileWithStore()
        onFeedExclusionsChanged()
        notifyChannelsChanged()
    }

    private fun updatePagedownCounter() {
        if (!host.isListReady() || host.isScrollAnimationRunning()) {
            return
        }
        val rawNewestIndex = host.getNewestVisibleMessageIndex()
        val unreadBelow = if (rawNewestIndex == Int.MIN_VALUE) {
            0
        } else {
            val newestVisibleIndex = maxOf(0, rawNewestIndex)
            val oldestVisibleIndex = host.getLastVisibleMessageIndex()
            if (canMarkVisibleAsRead() && oldestVisibleIndex >= 0) {
                markScrolledPastAsSeen(host.getMessages(), newestVisibleIndex, oldestVisibleIndex)
            }
            FeedController.getInstance(currentAccount).countUnreadBelow(host.getMessages(), newestVisibleIndex)
        }
        if (unreadBelow != lastPagedownCount) {
            lastPagedownCount = unreadBelow
            host.setPagedownCount(unreadBelow)
        }
        if (unreadBelow > 0) {
            pagedownShownByScroll = false
            host.setPagedownButtonVisible(true)
        } else if (!host.canScrollToNewer()) {
            pagedownShownByScroll = false
            host.setPagedownButtonVisible(false)
        }
    }

    private fun markScrolledPastAsSeen(rows: ArrayList<MessageObject?>, newestIndex: Int, oldestIndex: Int) {
        val prevNewest = seenNewestRow
        val prevOldest = seenOldestRow
        seenNewestRow = rows.getOrNull(newestIndex)
        seenOldestRow = rows.getOrNull(oldestIndex)
        if (prevNewest == null || prevOldest == null) {
            return
        }
        val prevNewestIndex = rows.indexOf(prevNewest)
        val prevOldestIndex = rows.indexOf(prevOldest)
        if (prevNewestIndex < 0 || prevOldestIndex < 0) {
            return
        }
        val feedController = FeedController.getInstance(currentAccount)
        val from = maxOf(0, minOf(newestIndex, oldestIndex, prevNewestIndex, prevOldestIndex))
        val to = minOf(rows.size - 1, maxOf(newestIndex, oldestIndex, prevNewestIndex, prevOldestIndex))
        for (i in from..to) {
            val row = rows[i]
            if (FeedMessageUtils.isPostRow(row)) {
                feedController.onPostSeen(row!!.dialogId, row.realId)
            }
        }
    }

    fun afterPreservedNewerMessagesInserted(): Boolean {
        val scrollToUnread = scrollPreservedNewerToUnread
        applyUnreadDivider(scrollToUnread, true)
        scrollPreservedNewerToUnread = false
        return scrollToUnread
    }

    fun applyUnreadDivider(scrollToDivider: Boolean) {
        applyUnreadDivider(scrollToDivider, false)
    }

    fun beforePreservedNewerMessagesInserted() {
        scrollPreservedNewerToUnread = host.isListScrollIdle() &&
            !host.isScrollAnimationRunning() &&
            host.getDistanceToNewerPx() <= NEAR_NEWEST_THRESHOLD
    }

    fun canMarkVisibleAsRead(): Boolean =
        InuConfig.FEED_MARK_READ_ON_SCROLL.value &&
            viewportActive &&
            !host.getFragment().isPaused() &&
            initialScrollApplied &&
            readyToMarkAsRead &&
            !pendingDividerScroll &&
            pendingInitialScrollRestore == null &&
            !BaseFragment.hasSheets(host.getFragment())

    fun collectLocalRowIds(dialogId: Long, realIds: ArrayList<Int>?, maxId: Int): ArrayList<Int> {
        val result = ArrayList<Int>()
        val realIdSet = if (realIds != null) HashSet(realIds) else null
        val rows = host.getMessages()
        for (i in rows.indices) {
            val row = rows[i]
            if (!FeedMessageUtils.isPostRow(row) || row!!.dialogId != dialogId) {
                continue
            }
            val realId = row.realId
            val matches = if (realIdSet != null) realIdSet.contains(realId) else realId > 0 && realId <= maxId
            if (matches) {
                result.add(row.id)
            }
        }
        return result
    }

    fun consumePreserveScrollLoad(loadIndex: Int): Boolean {
        if (preserveScrollLoadIndex != loadIndex) {
            return false
        }
        preserveScrollLoadIndex = -1
        return true
    }

    fun destroy() {
        destroyed = true
        resetMetadataRefresh()
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable)
            settleAtNewestScheduled = false
        }
        pendingInitialScrollRestore = null
    }

    fun hideChannelWithUndo(dialogId: Long, title: CharSequence?) {
        val feedConfig = FeedConfig.getInstance(currentAccount)
        val feedController = FeedController.getInstance(currentAccount)
        feedConfig.setExcluded(dialogId, true)
        feedController.markConfigApplied()
        feedController.store.setHidden(dialogId, true)
        pendingHideDialogId = dialogId
        reconcileWithStore()
        onFeedExclusionsChanged()
        notifyChannelsChanged()
        BulletinFactory.of(host.getFragment())
            .createUndoBulletin(
                AndroidUtilities.replaceTags(LocaleController.formatString(R.string.InuFeedChannelHidden, title)),
                Runnable { undoHideChannel() },
                Runnable {
                    if (pendingHideDialogId == dialogId) {
                        pendingHideDialogId = 0L
                    }
                }
            )
            .show()
    }

    fun loadReplyMessages(rows: ArrayList<MessageObject?>?, chatMode: Int, classGuid: Int) {
        if (rows == null || rows.isEmpty()) {
            return
        }
        val byDialog = LongSparseArray<ArrayList<MessageObject>>()
        for (i in rows.indices) {
            val row = rows[i]
            if (row == null || row.isDateObject) {
                continue
            }
            val dialogId = row.dialogId
            if (dialogId == 0L) {
                continue
            }
            var group = byDialog.get(dialogId)
            if (group == null) {
                group = ArrayList()
                byDialog.put(dialogId, group)
            }
            group.add(row)
        }
        for (i in 0 until byDialog.size()) {
            MediaDataController.getInstance(currentAccount)
                .loadReplyMessagesForMessages(byDialog.valueAt(i), byDialog.keyAt(i), chatMode, 0L, null, classGuid, null)
        }
    }

    fun markAllRead() {
        FeedController.getInstance(currentAccount).markAllRead()
        applyUnreadDivider(false)
        requestPendingInitialPosition()
        host.invalidateVisiblePart()
    }

    fun notifyChannelsChanged() {
        channelsChangedCallback?.run()
    }

    fun onFeedExclusionsChanged() {
        lastPagedownCount = -1
        updatePagedownCounter()
        NotificationCenter.getInstance(currentAccount).postNotificationName(
            NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE
        )
    }

    fun onHostResumed() {
        if (host.getMessages().isNotEmpty()) {
            onMessagesLoaded()
        }
        requestPendingInitialPosition()
    }

    fun onMessagesDeleted() {
        val divider = unreadDivider ?: return
        val rows = host.getMessages()
        val dividerIndex = rows.indexOf(divider)
        for (i in 0 until dividerIndex) {
            if (FeedMessageUtils.isPostRow(rows[i])) {
                return
            }
        }
        unreadDivider = null
        if (dividerIndex < 0 || !host.isListReady()) {
            return
        }
        rows.removeAt(dividerIndex)
        host.notifyMessageRemoved(dividerIndex)
    }

    fun onMessagesLoaded() {
        if (host.getFragment().isPaused() || !host.isListReady() || !hasMaterializedPostRows()) {
            return
        }
        if (initialScrollApplied) {
            return
        }
        initialScrollApplied = true

        val feedController = FeedController.getInstance(currentAccount)
        val initialUnreadScroll = feedController.consumeInitialUnreadScroll()
        val savedPosition = if (restoreDrawerScrollPosition) feedController.drawerScrollPosition else null
        val savedRow = if (savedPosition != null) feedController.getMessage(savedPosition.dialogId, savedPosition.messageId) else null

        if (savedRow == null || !host.getMessages().contains(savedRow)) {
            applyUnreadDivider(initialUnreadScroll || host.getDistanceToNewerPx() <= NEAR_NEWEST_THRESHOLD)
            return
        }
        applyUnreadDivider(false)
        pendingInitialScrollRestore = ScrollAnchor(savedRow, savedPosition!!.offsetTop)
        requestPendingInitialPosition()
    }

    fun onPostCellVisible(message: MessageObject?, fullyVisible: Boolean, tallerThanViewport: Boolean) {
        if (message == null || message.isSponsored) {
            return
        }
        requestReactionsRefresh(message)
        if (canMarkVisibleAsRead() && (fullyVisible || tallerThanViewport)) {
            FeedController.getInstance(currentAccount).onPostSeen(message.dialogId, message.realId)
        }
    }

    fun onPreserveScrollLoadStarted(loadIndex: Int) {
        preserveScrollLoadIndex = loadIndex
    }

    fun onReadStateRefreshed() {
        val anchor = host.captureScrollAnchor()
        val hadPendingPosition = hasPendingInitialPosition()
        applyUnreadDivider(false)
        requestPendingInitialPosition()
        if (!hadPendingPosition || !hasPendingInitialPosition()) {
            host.restoreScrollAnchor(anchor)
        }
        lastPagedownCount = -1
        updatePagedownCounter()
        host.invalidateVisiblePart()
    }

    fun onScrollAnimationFinished() {
        if (destroyed || !viewportActive || settleAtNewestScheduled) {
            return
        }
        settleAtNewestScheduled = true
        AndroidUtilities.runOnUIThread(settleAtNewestRunnable)
    }

    fun onScrolled(dy: Int) {
        if (!viewportActive || !host.isListReady() || host.isScrollAnimationRunning()) {
            return
        }
        if (!host.canScrollToNewer()) {
            totalScrollDy = 0
            pagedownShownByScroll = false
            host.setPagedownButtonVisible(false)
            if (dy > 0 && !settleAtNewestScheduled) {
                settleAtNewestScheduled = true
                AndroidUtilities.runOnUIThread(settleAtNewestRunnable)
            }
            return
        }
        if (lastPagedownCount > 0) {
            return
        }
        val pagedownVisible = host.isPagedownButtonVisible()
        if (dy > 0) {
            if (pagedownVisible) {
                return
            }
            totalScrollDy += dy
            if (totalScrollDy > PAGEDOWN_SCROLL_THRESHOLD) {
                totalScrollDy = 0
                pagedownShownByScroll = true
                host.setPagedownButtonVisible(true)
            }
            return
        }
        if (dy < 0 && pagedownShownByScroll && pagedownVisible) {
            totalScrollDy += dy
            if (totalScrollDy < -PAGEDOWN_SCROLL_THRESHOLD) {
                totalScrollDy = 0
                host.setPagedownButtonVisible(false)
            }
        }
    }

    fun addTabsGlassPosition(positions: MutableList<RectF>, index: Int, width: Int, height: Int): Int {
        if (MainTabsHelper.isHidden) return 0
        val rect = if (index < positions.size) positions[index] else RectF().also { positions.add(it) }
        val tabs = AndroidUtilities.dp(MainTabsHelper.mainTabsHeightWithMargins.toFloat())
        rect.set(0f, (height - tabs).toFloat(), width.toFloat(), height.toFloat())
        rect.inset(0f, -AndroidUtilities.dp(48f).toFloat())
        return 1
    }

    fun onVisiblePartInvalidated() {
        if (!viewportActive || host.getFragment().isPaused()) {
            return
        }
        val restore = pendingInitialScrollRestore
        if (restore != null) {
            host.restoreScrollAnchor(restore)
            pendingInitialScrollRestore = null
        }
        maybeScrollToDivider()
        updatePagedownCounter()
    }

    fun reconcileWithStore() {
        if (!host.isListReady()) {
            return
        }
        val store = FeedController.getInstance(currentAccount).store
        val rows = host.getMessages()

        var postRowCount = 0
        for (i in rows.indices) {
            if (FeedMessageUtils.isPostRow(rows[i])) {
                postRowCount++
            }
        }
        val visibleMessages = store.getVisibleMessages()

        if (postRowCount == 0) {
            if (visibleMessages.isNotEmpty() && host.isFirstLoadComplete()) {
                host.reloadFeed()
            } else if (!store.isEmpty() && !store.isEndReached()) {
                host.requestOlderFeedPage()
            }
            return
        }
        if (store.isEmpty()) {
            host.reloadFeed()
            return
        }

        val visibleSet = HashSet<MessageObject>(visibleMessages)
        var staleRowIds: ArrayList<Int>? = null
        var hiddenRows: ArrayList<MessageObject>? = null
        for (i in rows.indices) {
            val row = rows[i]
            if (!FeedMessageUtils.isPostRow(row) || visibleSet.contains(row!!)) {
                continue
            }
            if (store.getMessage(row.dialogId, row.id) === row) {
                if (hiddenRows == null) {
                    hiddenRows = ArrayList()
                }
                hiddenRows.add(row)
            } else {
                if (staleRowIds == null) {
                    staleRowIds = ArrayList()
                }
                staleRowIds.add(row.id)
            }
        }

        val removedCount = (staleRowIds?.size ?: 0) + (hiddenRows?.size ?: 0)
        if (removedCount == 0 && postRowCount == visibleMessages.size) {
            return
        }

        val anchor = host.captureScrollAnchor()
        val hadPendingPosition = hasPendingInitialPosition()

        if (staleRowIds != null) {
            host.deleteRows(staleRowIds)
        }
        if (hiddenRows != null) {
            for (i in hiddenRows.indices) {
                val index = rows.indexOf(hiddenRows[i])
                if (index >= 0) {
                    rows.removeAt(index)
                    host.notifyMessageRemoved(index)
                }
            }
        }

        val presentRows = HashSet<MessageObject>()
        for (i in rows.indices) {
            val row = rows[i]
            if (FeedMessageUtils.isPostRow(row)) {
                presentRows.add(row!!)
            }
        }

        var insertedAny = false
        var cursor = 0
        for (i in visibleMessages.indices) {
            val message = visibleMessages[i]
            if (presentRows.contains(message)) {
                while (cursor < rows.size && rows[cursor] !== message) {
                    cursor++
                }
                if (cursor < rows.size) {
                    cursor++
                }
                continue
            }
            host.materializeRow(message)
            val insertIndex = getInsertIndex(rows, message, visibleMessages, i, cursor)
            rows.add(insertIndex, message)
            host.notifyMessageInserted(insertIndex)
            cursor = insertIndex + 1
            insertedAny = true
        }

        val listChanged = removedCount > 0 || insertedAny
        if (!normalizeDateHeaders(rows) && !listChanged) {
            return
        }

        host.onFeedListChanged()
        applyUnreadDivider(false)
        requestPendingInitialPosition()
        onFeedExclusionsChanged()
        if (!hadPendingPosition || !hasPendingInitialPosition()) {
            host.restoreScrollAnchor(anchor)
        }
        host.invalidateVisiblePart()

        if (store.getVisibleCount() == 0) {
            if (store.isEndReached()) {
                host.showEmptyFeedState()
            } else {
                host.showEmptyFeedProgress()
                host.requestOlderFeedPage()
            }
        }
    }

    fun resetUiState() {
        resetMetadataRefresh()
        initialScrollApplied = false
        readyToMarkAsRead = false
        pendingDividerScroll = false
        pendingInitialScrollRestore = null
        scrollPreservedNewerToUnread = false
        preserveScrollLoadIndex = -1
        unreadDivider = null
        lastPagedownCount = -1
        pagedownShownByScroll = false
        totalScrollDy = 0
        pendingHideDialogId = 0L
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable)
            settleAtNewestScheduled = false
        }
    }

    fun saveDrawerScrollPosition() {
        val anchor = host.captureScrollAnchor()
        val row = anchor?.row ?: return
        FeedController.getInstance(currentAccount)
            .saveDrawerScrollPosition(row.dialogId, row.realId, anchor.offsetTop)
    }

    fun scrollToUnreadDividerIfAbove(): Boolean {
        if (!host.isListReady()) {
            return false
        }
        var rows = host.getMessages()
        if (unreadDivider == null || !rows.contains(unreadDivider)) {
            applyUnreadDivider(false)
            requestPendingInitialPosition()
            rows = host.getMessages()
        }
        val dividerIndex = rows.indexOf(unreadDivider)
        if (dividerIndex < 0) {
            return false
        }
        val newestVisibleIndex = host.getNewestVisibleMessageIndex()
        if (newestVisibleIndex == Int.MIN_VALUE || dividerIndex >= newestVisibleIndex) {
            return false
        }
        host.scrollToMessageAnimated(dividerIndex, AndroidUtilities.dp(UNREAD_DIVIDER_SCROLL_OFFSET_DP.toFloat()))
        host.invalidateVisiblePart()
        return true
    }

    fun setChannelsChangedCallback(callback: Runnable?) {
        channelsChangedCallback = callback
    }

    fun setViewportActive(active: Boolean) {
        if (viewportActive == active) {
            return
        }
        viewportActive = active
        if (active) {
            onHostResumed()
            if (host.getMessages().isNotEmpty()) {
                onVisiblePartInvalidated()
            }
            return
        }
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable)
            settleAtNewestScheduled = false
        }
        seenNewestRow = null
        seenOldestRow = null
        cancelPendingReactionsRefresh()
    }

    fun settleUnreadDivider() {
        if (!canMarkVisibleAsRead() || !host.isListReady()) {
            return
        }
        val lastVisibleIndex = host.getLastVisibleMessageIndex()
        if (lastVisibleIndex == Int.MIN_VALUE) {
            return
        }
        val rows = host.getMessages()
        val lastIndexToMark = if (host.canScrollToNewer()) {
            minOf(lastVisibleIndex, rows.size - 1)
        } else {
            rows.size - 1
        }
        if (lastIndexToMark >= 0) {
            val feedController = FeedController.getInstance(currentAccount)
            for (i in 0..lastIndexToMark) {
                val row = rows[i]
                if (row != null && !row.isDateObject && row.type != MessageObject.TYPE_LOADING && !row.isSponsored) {
                    feedController.onPostSeen(row.dialogId, row.realId)
                }
            }
        }
        applyUnreadDivider(false)
        requestPendingInitialPosition()
        updatePagedownCounter()
    }

    companion object {
        private val PAGEDOWN_SCROLL_THRESHOLD = AndroidUtilities.dp(100.0f)
        private val NEAR_NEWEST_THRESHOLD = AndroidUtilities.dp(160.0f)

        private const val UNREAD_DIVIDER_SCROLL_OFFSET_DP = 48
        private const val REACTIONS_RECHECK_INTERVAL = 15000L

        private fun findDividerInsertIndex(rows: ArrayList<MessageObject?>, firstUnreadIndex: Int): Int {
            val firstUnread = rows[firstUnreadIndex]!!
            val groupId = firstUnread.groupId
            if (groupId == 0L) {
                return firstUnreadIndex + 1
            }
            val dialogId = firstUnread.dialogId
            var insertIndex = firstUnreadIndex + 1
            for (i in rows.indices) {
                val row = rows[i]
                if (row != null && row.groupId == groupId && row.dialogId == dialogId) {
                    insertIndex = maxOf(insertIndex, i + 1)
                }
            }
            return insertIndex
        }

        private fun getInsertIndex(
            rows: ArrayList<MessageObject?>,
            row: MessageObject,
            visibleMessages: ArrayList<MessageObject>,
            visibleIndex: Int,
            cursor: Int,
        ): Int {
            val index = minOf(cursor, rows.size)
            if (visibleIndex <= 0 || index >= rows.size) {
                return index
            }
            val previousVisible = visibleMessages[visibleIndex - 1]
            val rowAtIndex = rows[index]
            if (rowAtIndex == null || !rowAtIndex.isDateObject) {
                return index
            }
            val previousDateKey = previousVisible.dateKeyInt
            if (previousDateKey == row.dateKeyInt || rowAtIndex.dateKeyInt != previousDateKey) {
                return index
            }
            return index + 1
        }

        @JvmStatic
        fun mergeDeletedIds(target: ArrayList<Int>, source: ArrayList<Int>) {
            for (i in source.indices) {
                if (!target.contains(source[i])) {
                    target.add(source[i])
                }
            }
        }
    }
}
