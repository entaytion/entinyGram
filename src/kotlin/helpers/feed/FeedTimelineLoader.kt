package desu.inugram.helpers.feed

import android.text.TextUtils
import androidx.collection.LongSparseArray
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

internal class FeedTimelineLoader(private val currentAccount: Int) {

    private var channelSetCache: ChannelSet? = null

    private enum class Direction(val operator: String) {
        OLDER("<"),
        NEWER(">"),
    }

    class ChannelEnumeration {
        var hasChannels = false
        val included = ArrayList<ChannelSnapshot>()
        val channels = ArrayList<TLRPC.Chat>()
    }

    class ChannelSet(val sessionGen: Int, val configGen: Int) {
        var hasChannels = false
        val includedRows = ArrayList<LongArray>()
        val channels = ArrayList<TLRPC.Chat>()
    }

    class ChannelSnapshot(val dialogId: Long, val readInboxMax: Int, val unreadCount: Int, val topMessage: Int) {
        var depthDate = 0
        var depthMid = 0
        var hasCached = false
        var hasHole = false
        var holeEnd = 0
        var incomplete = false
        var localStartReached = false
    }

    class Cursor {
        var date = 0
        var mid = 0
        var uid = 0L

        fun isEmpty(): Boolean = date == 0

        fun set(date: Int, uid: Long, mid: Int) {
            this.date = date
            this.uid = uid
            this.mid = mid
        }
    }

    class NewerPage {
        var hasMore = false
        val messages = ArrayList<TLRPC.Message>()
        val users = ArrayList<TLRPC.User>()
        val chats = ArrayList<TLRPC.Chat>()
        val first = Cursor()
    }

    class OlderPage {
        var hasIncomplete = false
        var lastChunkRowCount = 0
        val messages = ArrayList<TLRPC.Message>()
        val users = ArrayList<TLRPC.User>()
        val chats = ArrayList<TLRPC.Chat>()
        val backfillCandidates = ArrayList<LongArray>()
        val last = Cursor()
        val first = Cursor()
    }

    class WindowPage {
        var truncated = false
        val messages = ArrayList<TLRPC.Message>()
        val users = ArrayList<TLRPC.User>()
        val chats = ArrayList<TLRPC.Chat>()
    }

    private fun buildChannelSet(feedConfig: FeedConfig, sessionGen: Int, configGen: Int): ChannelSet {
        val channelSet = ChannelSet(sessionGen, configGen)
        try {
            val messagesStorage = MessagesStorage.getInstance(currentAccount)
            val sql = StringBuilder("SELECT did, inbox_max, unread_count, last_mid FROM dialogs WHERE did < 0")
            if (!feedConfig.includeArchived) {
                sql.append(" AND folder_id != 1")
            }
            sql.append(" ORDER BY date DESC")

            val rows = ArrayList<LongArray>()
            val chatIds = ArrayList<Long>()
            val sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString())
            try {
                while (sqlCursor.next()) {
                    val dialogId = sqlCursor.longValue(0)
                    rows.add(longArrayOf(dialogId, sqlCursor.intValue(1).toLong(), sqlCursor.intValue(2).toLong(), sqlCursor.intValue(3).toLong()))
                    chatIds.add(-dialogId)
                }
            } finally {
                sqlCursor.dispose()
            }
            if (chatIds.isEmpty()) {
                return channelSet
            }

            val chats = ArrayList<TLRPC.Chat?>()
            messagesStorage.getChatsInternal(TextUtils.join(",", chatIds), chats)
            val chatsById = LongSparseArray<TLRPC.Chat>(chats.size)
            for (i in chats.indices) {
                val chat = chats[i]
                if (chat != null) {
                    chatsById.put(chat.id, chat)
                }
            }
            for (i in rows.indices) {
                val row = rows[i]
                val chat = chatsById.get(-row[0])
                if (!FeedController.isEligibleChannel(chat)) {
                    continue
                }
                channelSet.hasChannels = true
                channelSet.channels.add(chat!!)
                if (!feedConfig.isExcluded(row[0])) {
                    channelSet.includedRows.add(row)
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return channelSet
    }

    private fun completeTrailingAlbum(
        messagesStorage: MessagesStorage,
        page: OlderPage,
        usersToLoad: ArrayList<Long>,
        chatsToLoad: ArrayList<Long>,
    ) {
        if (page.messages.isEmpty()) {
            return
        }
        val tail = page.messages[page.messages.size - 1]
        if (tail.grouped_id == 0L) {
            return
        }
        val sqlCursor = messagesStorage.getDatabase().queryFinalized(
            "SELECT data, mid, date, uid FROM messages_v2 WHERE uid = " + tail.dialog_id + " AND mid > 0 AND mid < " + tail.id +
                " ORDER BY date DESC, mid DESC LIMIT " + ALBUM_TAIL_LOOKUP
        )
        try {
            while (sqlCursor.next()) {
                val message = readMessage(sqlCursor)
                if (message == null || message.grouped_id != tail.grouped_id) {
                    break
                }
                page.messages.add(message)
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
            }
        } finally {
            sqlCursor.dispose()
        }
    }

    private fun findUnreadBoundary(messagesStorage: MessagesStorage, channels: ArrayList<ChannelSnapshot>, minDate: Int): Cursor? {
        val condition = StringBuilder()
        var boundary: Cursor? = null
        var batched = 0
        for (i in channels.indices) {
            val channel = channels[i]
            if (channel.topMessage > channel.readInboxMax || channel.unreadCount > 0) {
                if (condition.isNotEmpty()) {
                    condition.append(" OR ")
                }
                condition.append("uid = ")
                condition.append(channel.dialogId)
                condition.append(" AND mid > ")
                condition.append(channel.readInboxMax)
                batched++
            }
            if (batched > 0 && (batched == DIALOG_BATCH_SIZE || i == channels.size - 1)) {
                val batchBoundary = queryUnreadBoundary(messagesStorage, condition, minDate)
                if (batchBoundary != null && (boundary == null || compareDesc(batchBoundary, boundary) > 0)) {
                    boundary = batchBoundary
                }
                condition.setLength(0)
                batched = 0
            }
        }
        return boundary
    }

    private fun loadChunk(
        messagesStorage: MessagesStorage,
        dialogIds: String,
        minDate: Int,
        page: OlderPage,
        usersToLoad: ArrayList<Long>,
        chatsToLoad: ArrayList<Long>,
    ): Int {
        val sql = StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (")
        sql.append(dialogIds)
        sql.append(") AND mid > 0")
        if (minDate > 0) {
            sql.append(" AND date >= ")
            sql.append(minDate)
        }
        if (!page.last.isEmpty()) {
            appendCursorBound(sql, page.last, Direction.OLDER, false)
        }
        sql.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ")
        sql.append(CHUNK_SIZE)

        var rowCount = 0
        val sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString())
        try {
            while (sqlCursor.next()) {
                rowCount++
                page.last.set(sqlCursor.intValue(2), sqlCursor.longValue(3), sqlCursor.intValue(1))
                if (page.first.isEmpty()) {
                    page.first.set(page.last.date, page.last.uid, page.last.mid)
                }
                val message = readMessage(sqlCursor)
                if (message != null) {
                    page.messages.add(message)
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
                }
            }
        } finally {
            sqlCursor.dispose()
        }
        return rowCount
    }

    private fun queryUnreadBoundary(messagesStorage: MessagesStorage, condition: StringBuilder, minDate: Int): Cursor? {
        val sql = StringBuilder("SELECT date, uid, mid FROM messages_v2 WHERE mid > 0 AND (")
        sql.append(condition)
        sql.append(")")
        if (minDate > 0) {
            sql.append(" AND date >= ")
            sql.append(minDate)
        }
        sql.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT 1")
        try {
            val sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString())
            try {
                if (!sqlCursor.next()) {
                    return null
                }
                val boundary = Cursor()
                boundary.set(sqlCursor.intValue(0), sqlCursor.longValue(1), sqlCursor.intValue(2))
                return boundary
            } finally {
                sqlCursor.dispose()
            }
        } catch (e: Exception) {
            FileLog.e(e)
            return null
        }
    }

    private fun readMessage(sqlCursor: SQLiteCursor): TLRPC.Message? {
        val data = sqlCursor.byteBufferValue(0) ?: return null
        val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
        if (message == null) {
            data.reuse()
            return null
        }
        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId)
        data.reuse()
        if (message is TLRPC.TL_messageEmpty || message.action != null) {
            return null
        }
        message.id = sqlCursor.intValue(1)
        message.date = sqlCursor.intValue(2)
        message.dialog_id = sqlCursor.longValue(3)
        return message
    }

    fun enumerateChannels(feedConfig: FeedConfig, sessionGen: Int, forceRefresh: Boolean): ChannelEnumeration {
        var channelSet = channelSetCache
        val configGen = feedConfig.generation
        if (forceRefresh || channelSet == null || channelSet.sessionGen != sessionGen || channelSet.configGen != configGen) {
            channelSet = buildChannelSet(feedConfig, sessionGen, configGen)
            channelSetCache = channelSet
        }
        val enumeration = ChannelEnumeration()
        enumeration.hasChannels = channelSet.hasChannels
        enumeration.channels.addAll(channelSet.channels)
        for (i in channelSet.includedRows.indices) {
            val row = channelSet.includedRows[i]
            enumeration.included.add(ChannelSnapshot(row[0], row[1].toInt(), row[2].toInt(), row[3].toInt()))
        }
        return enumeration
    }

    fun invalidateChannelCache() {
        channelSetCache = null
    }

    fun loadChannelWindow(dialogIds: ArrayList<Long>, newest: Cursor, oldest: Cursor): WindowPage {
        val page = WindowPage()
        if (dialogIds.isEmpty() || newest.isEmpty() || oldest.isEmpty()) {
            return page
        }
        try {
            val messagesStorage = MessagesStorage.getInstance(currentAccount)
            val usersToLoad = ArrayList<Long>()
            val chatsToLoad = ArrayList<Long>()
            val sql = StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (")
            sql.append(TextUtils.join(",", dialogIds))
            sql.append(") AND mid > 0")
            appendCursorBound(sql, newest, Direction.OLDER, true)
            appendCursorBound(sql, oldest, Direction.NEWER, true)
            sql.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ")
            sql.append(WINDOW_SIZE + 1)

            val sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString())
            try {
                var rowCount = 0
                while (sqlCursor.next()) {
                    rowCount++
                    if (rowCount > WINDOW_SIZE) {
                        page.truncated = true
                        break
                    }
                    val message = readMessage(sqlCursor)
                    if (message != null) {
                        page.messages.add(message)
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
                    }
                }
            } finally {
                sqlCursor.dispose()
            }
            if (usersToLoad.isNotEmpty()) {
                messagesStorage.getUsersInternal(usersToLoad, page.users)
            }
            if (chatsToLoad.isNotEmpty()) {
                messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        clusterGroupedMessages(page.messages)
        return page
    }

    fun loadNewerPage(channels: ArrayList<ChannelSnapshot>, newest: Cursor): NewerPage {
        val page = NewerPage()
        page.first.set(newest.date, newest.uid, newest.mid)
        try {
            val dialogIds = ArrayList<Long>(channels.size)
            for (i in channels.indices) {
                dialogIds.add(channels[i].dialogId)
            }
            val messagesStorage = MessagesStorage.getInstance(currentAccount)
            val usersToLoad = ArrayList<Long>()
            val chatsToLoad = ArrayList<Long>()
            val sql = StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (")
            sql.append(TextUtils.join(",", dialogIds))
            sql.append(") AND mid > 0")
            appendCursorBound(sql, newest, Direction.NEWER, false)
            sql.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT ")
            sql.append(NEWER_PAGE_SIZE)

            var rowCount = 0
            val sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString())
            try {
                while (sqlCursor.next()) {
                    rowCount++
                    page.first.set(sqlCursor.intValue(2), sqlCursor.longValue(3), sqlCursor.intValue(1))
                    val message = readMessage(sqlCursor)
                    if (message != null) {
                        page.messages.add(message)
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
                    }
                }
            } finally {
                sqlCursor.dispose()
            }
            page.hasMore = rowCount == NEWER_PAGE_SIZE
            if (usersToLoad.isNotEmpty()) {
                messagesStorage.getUsersInternal(usersToLoad, page.users)
            }
            if (chatsToLoad.isNotEmpty()) {
                messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        clusterGroupedMessages(page.messages)
        return page
    }

    fun loadOlderPage(channels: ArrayList<ChannelSnapshot>, from: Cursor, completeDialogIds: HashSet<Long>): OlderPage {
        val page = OlderPage()
        val fromTimelineStart = from.isEmpty()
        page.last.set(from.date, from.uid, from.mid)
        try {
            val dialogIds = ArrayList<Long>(channels.size)
            for (i in channels.indices) {
                dialogIds.add(channels[i].dialogId)
            }
            val dialogIdsSql = TextUtils.join(",", dialogIds)
            val messagesStorage = MessagesStorage.getInstance(currentAccount)

            val holeEnds = HashMap<Long, Int>()
            val sqlCursor = messagesStorage.getDatabase().queryFinalized(
                "SELECT uid, max(end) FROM messages_holes WHERE uid IN ($dialogIdsSql) GROUP BY uid"
            )
            try {
                while (sqlCursor.next()) {
                    holeEnds[sqlCursor.longValue(0)] = sqlCursor.intValue(1)
                }
            } finally {
                sqlCursor.dispose()
            }
            for (i in channels.indices) {
                val channel = channels[i]
                val holeEnd = holeEnds[channel.dialogId]
                channel.hasHole = holeEnd != null
                channel.holeEnd = holeEnd ?: 0
            }
            loadChannelDepths(messagesStorage, channels)

            var minDate = 0
            for (i in channels.indices) {
                val channel = channels[i]
                channel.incomplete = !channel.localStartReached && !completeDialogIds.contains(channel.dialogId)
                if (!channel.incomplete) {
                    continue
                }
                page.hasIncomplete = true
                minDate = maxOf(minDate, channel.depthDate)
                val backfillFromMessageId: Long
                if (channel.hasCached) {
                    backfillFromMessageId = channel.depthMid.toLong()
                } else {
                    val knownTop = maxOf(channel.holeEnd, channel.topMessage)
                    backfillFromMessageId = if (knownTop > 0) (knownTop + 1).toLong() else 0L
                }
                page.backfillCandidates.add(longArrayOf(channel.dialogId, backfillFromMessageId, channel.depthDate.toLong()))
            }
            page.backfillCandidates.sortWith { first, second -> second[2].compareTo(first[2]) }
            if (minDate == Int.MAX_VALUE) {
                return page
            }

            val unreadBoundary = if (fromTimelineStart) findUnreadBoundary(messagesStorage, channels, minDate) else null
            val usersToLoad = ArrayList<Long>()
            val chatsToLoad = ArrayList<Long>()
            var loadedRows = 0
            do {
                val chunkRows = loadChunk(messagesStorage, dialogIdsSql, minDate, page, usersToLoad, chatsToLoad)
                page.lastChunkRowCount = chunkRows
                loadedRows += chunkRows
                if (chunkRows < CHUNK_SIZE || unreadBoundary == null || loadedRows >= MAX_ROWS_SCANNED_FOR_UNREAD) {
                    break
                }
            } while (compareDesc(page.last, unreadBoundary) < 0)
            completeTrailingAlbum(messagesStorage, page, usersToLoad, chatsToLoad)

            for (i in dialogIds.indices) {
                val chatId = -dialogIds[i]
                if (!chatsToLoad.contains(chatId)) {
                    chatsToLoad.add(chatId)
                }
            }
            if (usersToLoad.isNotEmpty()) {
                messagesStorage.getUsersInternal(usersToLoad, page.users)
            }
            if (chatsToLoad.isNotEmpty()) {
                messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        clusterGroupedMessages(page.messages)
        return page
    }

    companion object {
        private const val CHUNK_SIZE = 30
        private const val NEWER_PAGE_SIZE = 50
        private const val MAX_ROWS_SCANNED_FOR_UNREAD = 200
        private const val WINDOW_SIZE = 500
        private const val DIALOG_BATCH_SIZE = 64
        private const val ALBUM_TAIL_LOOKUP = 9

        private fun appendCursorBound(sql: StringBuilder, cursor: Cursor, direction: Direction, inclusive: Boolean) {
            val operator = direction.operator
            val midOperator = if (inclusive) "$operator= " else "$operator "
            sql.append(" AND (date ")
            sql.append(operator)
            sql.append(' ')
            sql.append(cursor.date)
            sql.append(" OR date = ")
            sql.append(cursor.date)
            sql.append(" AND (uid ")
            sql.append(operator)
            sql.append(' ')
            sql.append(cursor.uid)
            sql.append(" OR uid = ")
            sql.append(cursor.uid)
            sql.append(" AND mid ")
            sql.append(midOperator)
            sql.append(cursor.mid)
            sql.append("))")
        }

        private fun clusterGroupedMessages(messages: ArrayList<TLRPC.Message>) {
            if (messages.size < 3) {
                return
            }
            val albums = HashMap<Long, ArrayList<TLRPC.Message>>()
            var hasAlbums = false
            for (i in messages.indices) {
                val groupedId = messages[i].grouped_id
                if (groupedId != 0L) {
                    var album = albums[groupedId]
                    if (album == null) {
                        album = ArrayList()
                        albums[groupedId] = album
                    } else {
                        hasAlbums = true
                    }
                    album.add(messages[i])
                }
            }
            if (!hasAlbums) {
                return
            }
            val clustered = ArrayList<TLRPC.Message>(messages.size)
            val appended = HashSet<Long>()
            for (i in messages.indices) {
                val message = messages[i]
                val groupedId = message.grouped_id
                if (groupedId == 0L) {
                    clustered.add(message)
                } else if (appended.add(groupedId)) {
                    clustered.addAll(albums[groupedId]!!)
                }
            }
            messages.clear()
            messages.addAll(clustered)
        }

        private fun compareDesc(first: Cursor, second: Cursor): Int {
            if (first.date != second.date) {
                return if (first.date > second.date) -1 else 1
            }
            if (first.uid != second.uid) {
                return if (first.uid > second.uid) -1 else 1
            }
            return -first.mid.compareTo(second.mid)
        }

        private fun loadChannelDepths(messagesStorage: MessagesStorage, channels: ArrayList<ChannelSnapshot>) {
            val byDialogId = LongSparseArray<ChannelSnapshot>(channels.size)
            for (i in channels.indices) {
                val channel = channels[i]
                channel.depthMid = 0
                channel.depthDate = Int.MAX_VALUE
                channel.hasCached = false
                channel.localStartReached = false
                byDialogId.put(channel.dialogId, channel)
            }
            var from = 0
            while (from < channels.size) {
                val to = minOf(from + DIALOG_BATCH_SIZE, channels.size)
                val sql = StringBuilder()
                for (i in from until to) {
                    if (sql.isNotEmpty()) {
                        sql.append(" UNION ALL ")
                    }
                    val channel = channels[i]
                    sql.append("SELECT uid, mid, date FROM (SELECT uid, mid, date FROM messages_v2 WHERE uid = ")
                    sql.append(channel.dialogId)
                    sql.append(" AND mid >= ")
                    sql.append(maxOf(channel.holeEnd, 1))
                    sql.append(" ORDER BY date ASC, mid ASC LIMIT 1)")
                }
                val sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString())
                try {
                    while (sqlCursor.next()) {
                        val channel = byDialogId.get(sqlCursor.longValue(0))
                        if (channel != null) {
                            channel.depthMid = sqlCursor.intValue(1)
                            channel.depthDate = sqlCursor.intValue(2)
                            channel.hasCached = true
                        }
                    }
                } finally {
                    sqlCursor.dispose()
                }
                from = to
            }
            for (i in channels.indices) {
                val channel = channels[i]
                channel.localStartReached = !channel.hasHole && channel.hasCached
            }
        }
    }
}
