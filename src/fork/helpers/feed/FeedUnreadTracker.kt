package desu.inugram.helpers.feed

import androidx.collection.LongSparseArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

internal class FeedUnreadTracker(
    private val currentAccount: Int,
    private val timeline: ArrayList<MessageObject>,
) {
    private val readInboxMaxByDialog = LongSparseArray<Int>()
    private val pendingMaxReadId = LongSparseArray<Int>()

    private val flushRunnable = Runnable { flush() }

    private var flushScheduled = false

    fun applyReadInboxMax(dialogId: Long, maxReadId: Int) {
        if (maxReadId > readInboxMaxByDialog.get(dialogId, NO_READ_ID)) {
            readInboxMaxByDialog.put(dialogId, maxReadId)
        }
    }

    fun clear() {
        if (flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable)
            flushScheduled = false
        }
        flush()
        readInboxMaxByDialog.clear()
    }

    fun countUnreadBelow(messages: ArrayList<MessageObject?>?, count: Int): Int {
        if (messages == null || readInboxMaxByDialog.isEmpty()) {
            return 0
        }
        val limit = minOf(count, messages.size)
        var unread = 0
        for (i in 0 until limit) {
            val message = messages[i]
            if (FeedMessageUtils.isPostRow(message) && isUnread(message)) {
                unread++
            }
        }
        return unread
    }

    fun findFirstUnreadIndex(messages: ArrayList<MessageObject?>?): Int {
        if (messages != null && !readInboxMaxByDialog.isEmpty()) {
            for (i in messages.size - 1 downTo 0) {
                if (isUnread(messages[i])) {
                    return i
                }
            }
        }
        return -1
    }

    fun getUnreadCount(): Int {
        val unreadDialogs = collectUnreadFeedDialogs()
        var total = 0
        for (i in unreadDialogs.indices) {
            total += unreadDialogs[i].unread_count
        }
        return total
    }

    fun isUnread(message: MessageObject?): Boolean =
        message != null &&
            !message.isSponsored &&
            message.realId > getEffectiveReadInboxMax(message.dialogId)

    fun markAllRead() {
        val messagesController = MessagesController.getInstance(currentAccount)
        val touchedDialogs = HashSet<Long>()

        val unreadDialogs = collectUnreadFeedDialogs()
        for (i in unreadDialogs.indices) {
            val dialog = unreadDialogs[i]
            messagesController.markMentionsAsRead(dialog.id, 0L)
            messagesController.markDialogAsRead(
                dialog.id, dialog.top_message, dialog.top_message,
                dialog.last_message_date, false, 0L, 0, true, 0
            )
            readInboxMaxByDialog.put(dialog.id, dialog.top_message)
            touchedDialogs.add(dialog.id)
        }

        val feedConfig = FeedConfig.getInstance(currentAccount)
        val includeArchived = feedConfig.includeArchived
        for (i in timeline.indices) {
            val message = timeline[i]
            val dialogId = message.dialogId
            if (feedConfig.isHidden(currentAccount, dialogId)) {
                continue
            }
            if (!includeArchived) {
                val dialog = messagesController.dialogs_dict.get(dialogId)
                if (dialog != null && dialog.folder_id == ARCHIVE_FOLDER_ID) {
                    continue
                }
            }
            touchedDialogs.add(dialogId)
            val realId = message.realId
            if (realId > readInboxMaxByDialog.get(dialogId, NO_READ_ID)) {
                readInboxMaxByDialog.put(dialogId, realId)
            }
        }

        for (dialogId in touchedDialogs) {
            pendingMaxReadId.remove(dialogId)
        }
        if (pendingMaxReadId.isEmpty() && flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable)
            flushScheduled = false
        }
    }

    fun onPostSeen(dialogId: Long, messageId: Int) {
        if (dialogId == 0L || messageId <= 0 || messageId <= getEffectiveReadInboxMax(dialogId)) {
            return
        }
        val pending = pendingMaxReadId.get(dialogId)
        if (pending != null && pending >= messageId) {
            return
        }
        pendingMaxReadId.put(dialogId, messageId)
        if (!flushScheduled) {
            flushScheduled = true
            AndroidUtilities.runOnUIThread(flushRunnable, FLUSH_DELAY_MS)
        }
    }

    private fun collectUnreadFeedDialogs(): ArrayList<TLRPC.Dialog> {
        val messagesController = MessagesController.getInstance(currentAccount)
        val feedConfig = FeedConfig.getInstance(currentAccount)
        val includeArchived = feedConfig.includeArchived

        val dialogs = messagesController.dialogs_dict
        val result = ArrayList<TLRPC.Dialog>()
        for (i in 0 until dialogs.size()) {
            val dialog = dialogs.valueAt(i)
            if (dialog == null || dialog.unread_count <= 0) {
                continue
            }
            val dialogId = dialog.id
            if (!DialogObject.isChatDialog(dialogId) || feedConfig.isHidden(currentAccount, dialogId)) {
                continue
            }
            if (!includeArchived && dialog.folder_id == ARCHIVE_FOLDER_ID) {
                continue
            }
            if (FeedController.isEligibleChannel(messagesController.getChat(-dialogId))) {
                result.add(dialog)
            }
        }
        return result
    }

    private fun countTimelineRows(dialogId: Long, fromIdExclusive: Int, toIdInclusive: Int): Int {
        var count = 0
        for (i in timeline.indices) {
            val message = timeline[i]
            if (message.dialogId != dialogId) {
                continue
            }
            val realId = message.realId
            if (realId > fromIdExclusive && realId <= toIdInclusive) {
                count++
            }
        }
        return count
    }

    private fun flush() {
        flushScheduled = false
        if (pendingMaxReadId.isEmpty()) {
            return
        }
        val messagesController = MessagesController.getInstance(currentAccount)
        val currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime()
        for (i in 0 until pendingMaxReadId.size()) {
            val dialogId = pendingMaxReadId.keyAt(i)
            val maxReadId = pendingMaxReadId.valueAt(i)
            val knownMaxReadId = readInboxMaxByDialog.get(dialogId, NO_READ_ID)
            if (maxReadId <= knownMaxReadId) {
                continue
            }
            readInboxMaxByDialog.put(dialogId, maxReadId)
            val countDiff = maxOf(countTimelineRows(dialogId, knownMaxReadId, maxReadId), 1)
            messagesController.markDialogAsRead(dialogId, maxReadId, 0, currentTime, false, 0L, countDiff, true, 0)
        }
        pendingMaxReadId.clear()
    }

    private fun getEffectiveReadInboxMax(dialogId: Long): Int =
        maxOf(readInboxMaxByDialog.get(dialogId, NO_READ_ID), pendingMaxReadId.get(dialogId, NO_READ_ID))

    companion object {
        private const val FLUSH_DELAY_MS = 1000L
        private const val ARCHIVE_FOLDER_ID = 1
        private const val NO_READ_ID = 0
    }
}
