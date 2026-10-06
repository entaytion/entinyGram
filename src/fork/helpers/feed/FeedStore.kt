package desu.inugram.helpers.feed

import org.telegram.messenger.MessageObject

class FeedStore {

    private var count = 0
    private var endReached = false

    private val messages = ArrayList<MessageObject>()
    private val identityMap = FeedMessageIdentityMap()
    private val hiddenDialogIds = HashSet<Long>()
    private val oldestCursor = FeedTimelineLoader.Cursor()
    private val newestCursor = FeedTimelineLoader.Cursor()

    private fun findMergeIndex(message: MessageObject, from: Int): Int {
        var index = from
        while (index < messages.size) {
            val current = messages[index]
            if (compareTimeline(
                    current.messageOwner.date, current.dialogId, current.realId,
                    message.messageOwner.date, message.dialogId, message.realId
                ) < 0
            ) {
                break
            }
            index++
        }
        while (index > 0 && index < messages.size) {
            val previous = messages[index - 1]
            val current = messages[index]
            if (previous.groupId == 0L ||
                previous.groupId != current.groupId ||
                previous.dialogId != current.dialogId
            ) {
                break
            }
            index++
        }
        return index
    }

    private fun onRowsRemoved() {
        if (!rebuildPagingCursorsFromLoadedRows()) {
            endReached = false
        }
        updateCount()
    }

    private fun purgeRow(message: MessageObject, removedIds: ArrayList<Int>, seenIds: HashSet<Int>) {
        identityMap.purge(message)
        if (seenIds.add(message.id)) {
            removedIds.add(message.id)
        }
    }

    private fun rebuildPagingCursorsFromLoadedRows(): Boolean {
        val oldestCursorWasSet = !oldestCursor.isEmpty()

        var newestDate = 0
        var newestUid = 0L
        var newestMid = 0
        var oldestDate = 0
        var oldestUid = 0L
        var oldestMid = 0

        for (i in messages.indices) {
            val message = messages[i]
            if (!isPagingRow(message)) {
                continue
            }
            val date = message.messageOwner.date
            val uid = message.dialogId
            val mid = message.realId

            if (newestDate == 0 || compareTimeline(date, uid, mid, newestDate, newestUid, newestMid) > 0) {
                newestDate = date
                newestUid = uid
                newestMid = mid
            }
            if (oldestDate == 0 || compareTimeline(date, uid, mid, oldestDate, oldestUid, oldestMid) < 0) {
                oldestDate = date
                oldestUid = uid
                oldestMid = mid
            }
        }

        if (newestDate == 0) {
            oldestCursor.set(0, 0L, 0)
            newestCursor.set(0, 0L, 0)
            return false
        }

        if (oldestCursorWasSet &&
            compareTimeline(oldestDate, oldestUid, oldestMid, oldestCursor.date, oldestCursor.uid, oldestCursor.mid) > 0
        ) {
            endReached = false
        }

        newestCursor.set(newestDate, newestUid, newestMid)
        oldestCursor.set(oldestDate, oldestUid, oldestMid)
        return true
    }

    private fun updateCount() {
        var visibleCount = 0
        if (messages.isNotEmpty()) {
            visibleCount = getVisibleCount() + (if (endReached) 0 else LOADING_PLACEHOLDER_ROWS)
        }
        count = visibleCount
    }

    fun appendMessages(incoming: ArrayList<MessageObject>, toStart: Boolean): ArrayList<MessageObject> {
        val accepted = ArrayList<MessageObject>(incoming.size)
        for (message in incoming) {
            if (identityMap.register(message)) {
                accepted.add(message)
            }
        }
        if (toStart) {
            val reversed = ArrayList(accepted)
            reversed.reverse()
            messages.addAll(0, reversed)
        } else {
            messages.addAll(accepted)
        }
        updateCount()
        return accepted
    }

    fun applyIncludedDialogs(includedDialogIds: HashSet<Long>): Boolean {
        val loadedDialogIds = getLoadedDialogIds()
        var changed = false
        for (dialogId in loadedDialogIds) {
            if (!includedDialogIds.contains(dialogId)) {
                changed = changed or hiddenDialogIds.add(dialogId)
            }
        }
        val iterator = hiddenDialogIds.iterator()
        while (iterator.hasNext()) {
            val dialogId = iterator.next()
            if (includedDialogIds.contains(dialogId) || !loadedDialogIds.contains(dialogId)) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            updateCount()
        }
        return changed
    }

    fun clear() {
        messages.clear()
        identityMap.clear()
        hiddenDialogIds.clear()
        endReached = false
        count = 0
        oldestCursor.set(0, 0L, 0)
        newestCursor.set(0, 0L, 0)
    }

    fun deleteHistory(dialogId: Long, maxId: Int, changed: BooleanArray): ArrayList<Int> {
        val removedIds = ArrayList<Int>()
        val seenIds = HashSet<Int>()
        var removed = false
        for (i in messages.size - 1 downTo 0) {
            val message = messages[i]
            if (message.dialogId == dialogId && message.realId > 0 && message.realId <= maxId) {
                messages.removeAt(i)
                purgeRow(message, removedIds, seenIds)
                removed = true
            }
        }
        if (removed) {
            if (!hasMessagesForDialog(dialogId)) {
                hiddenDialogIds.remove(dialogId)
            }
            onRowsRemoved()
        }
        changed[0] = removed
        return removedIds
    }

    fun deleteMessages(dialogId: Long, realIds: ArrayList<Int>?, changed: BooleanArray): ArrayList<Int> {
        val removedIds = ArrayList<Int>()
        if (realIds == null) {
            return removedIds
        }
        val targetIds = HashSet(realIds)
        val seenIds = HashSet<Int>()
        var removed = false
        for (i in realIds.indices) {
            val message = identityMap.getByRealId(dialogId, realIds[i])
            if (message != null) {
                removed = removed or messages.remove(message)
                purgeRow(message, removedIds, seenIds)
            }
        }
        for (i in messages.size - 1 downTo 0) {
            val message = messages[i]
            if (message.dialogId == dialogId && targetIds.contains(message.realId)) {
                messages.removeAt(i)
                purgeRow(message, removedIds, seenIds)
                removed = true
            }
        }
        if (removed) {
            onRowsRemoved()
        }
        changed[0] = removed
        return removedIds
    }

    fun getCount(): Int = count

    fun getHiddenSnapshot(): HashSet<Long> = HashSet(hiddenDialogIds)

    fun getLoadedDialogIds(): HashSet<Long> {
        val dialogIds = HashSet<Long>()
        for (i in messages.indices) {
            dialogIds.add(messages[i].dialogId)
        }
        return dialogIds
    }

    fun getMessage(dialogId: Long, id: Int): MessageObject? = identityMap.getByAnyId(dialogId, id)

    fun getMessages(): ArrayList<MessageObject> = messages

    internal fun getNewestCursor(): FeedTimelineLoader.Cursor = newestCursor

    internal fun getOldestCursor(): FeedTimelineLoader.Cursor = oldestCursor

    fun getVisibleCount(): Int {
        if (hiddenDialogIds.isEmpty()) {
            return messages.size
        }
        var visible = 0
        for (i in messages.indices) {
            val message = messages[i]
            if (!hiddenDialogIds.contains(message.dialogId)) {
                visible++
            }
        }
        return visible
    }

    fun getVisibleMessages(): ArrayList<MessageObject> {
        if (hiddenDialogIds.isEmpty()) {
            return ArrayList(messages)
        }
        val visible = ArrayList<MessageObject>(messages.size)
        for (i in messages.indices) {
            val message = messages[i]
            if (!hiddenDialogIds.contains(message.dialogId)) {
                visible.add(message)
            }
        }
        return visible
    }

    fun hasMessagesForDialog(dialogId: Long): Boolean {
        for (i in messages.indices) {
            val message = messages[i]
            if (message.dialogId == dialogId) {
                return true
            }
        }
        return false
    }

    fun hasNoSyntheticIds(): Boolean = identityMap.isEmpty()

    fun isEmpty(): Boolean = messages.isEmpty()

    fun isEndReached(): Boolean = endReached

    fun mergeRows(incoming: ArrayList<MessageObject>): ArrayList<MessageObject> {
        val accepted = ArrayList<MessageObject>(incoming.size)
        for (message in incoming) {
            if (identityMap.register(message)) {
                accepted.add(message)
            }
        }
        var index = 0
        var mergeIndex = 0
        while (index < accepted.size) {
            val first = accepted[index]
            val groupId = first.groupId
            var groupEnd = index + 1
            while (groupId != 0L && groupEnd < accepted.size &&
                accepted[groupEnd].groupId == groupId &&
                accepted[groupEnd].dialogId == first.dialogId
            ) {
                groupEnd++
            }
            mergeIndex = findMergeIndex(first, mergeIndex)
            while (index < groupEnd) {
                messages.add(mergeIndex, accepted[index])
                index++
                mergeIndex++
            }
        }
        updateCount()
        return accepted
    }

    fun replaceMessage(oldMessage: MessageObject?, newMessage: MessageObject?) {
        if (oldMessage == null || newMessage == null) {
            return
        }
        val index = messages.indexOf(oldMessage)
        if (index >= 0) {
            messages[index] = newMessage
        }
        identityMap.replace(newMessage)
    }

    fun resolveRealDialogId(generatedId: Int): Long = identityMap.resolveRealDialogId(generatedId)

    fun resolveRealMessageId(dialogId: Long, id: Int): Int = identityMap.resolveRealMessageId(dialogId, id)

    fun setEndReached(endReached: Boolean) {
        this.endReached = endReached
        updateCount()
    }

    fun setHidden(dialogId: Long, hidden: Boolean): Boolean {
        val changed = if (hidden) hiddenDialogIds.add(dialogId) else hiddenDialogIds.remove(dialogId)
        if (changed) {
            updateCount()
        }
        return changed
    }

    fun trim(limit: Int): Boolean {
        if (messages.size <= limit) {
            return false
        }
        val boundary = messages[limit - 1]
        val boundaryDate = boundary.messageOwner.date
        val boundaryUid = boundary.dialogId
        val boundaryMid = boundary.realId

        var removed = false
        for (i in messages.size - 1 downTo 0) {
            val message = messages[i]
            if (compareTimeline(
                    message.messageOwner.date, message.dialogId, message.realId,
                    boundaryDate, boundaryUid, boundaryMid
                ) < 0
            ) {
                messages.removeAt(i)
                identityMap.releaseRow(message)
                removed = true
            }
        }
        if (!removed) {
            return false
        }
        if (messages.isEmpty()) {
            oldestCursor.set(0, 0L, 0)
        } else {
            var oldestDate = 0
            var oldestUid = 0L
            var oldestMid = 0
            for (i in messages.indices) {
                val message = messages[i]
                if (oldestDate == 0 ||
                    compareTimeline(
                        message.messageOwner.date, message.dialogId, message.realId,
                        oldestDate, oldestUid, oldestMid
                    ) < 0
                ) {
                    oldestDate = message.messageOwner.date
                    oldestUid = message.dialogId
                    oldestMid = message.realId
                }
            }
            oldestCursor.set(oldestDate, oldestUid, oldestMid)
        }
        endReached = false
        updateCount()
        return true
    }

    companion object {
        private const val LOADING_PLACEHOLDER_ROWS = 3

        @JvmStatic
        fun compareTimeline(date: Int, uid: Long, mid: Int, otherDate: Int, otherUid: Long, otherMid: Int): Int {
            if (date != otherDate) {
                return date.compareTo(otherDate)
            }
            if (uid != otherUid) {
                return uid.compareTo(otherUid)
            }
            return mid.compareTo(otherMid)
        }

        private fun isPagingRow(message: MessageObject?): Boolean =
            message != null && !message.isDateObject && message.messageOwner != null && message.realId > 0
    }
}
