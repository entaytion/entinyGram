package desu.inugram.helpers.feed

import android.util.Log
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import java.util.Locale

// entiny: single source of truth for a feed scope; every mutation happens on the UI thread
class FeedStore(private val account: Int, private val scope: FeedScope = FeedScope.Global) {

    data class Key(val dialogId: Long, val messageId: Int)

    private data class Cursor(val date: Int, val dialogId: Long, val messageId: Int)

    private val rows = ArrayList<MessageObject>()
    private val byKey = HashMap<Key, MessageObject>()
    private val revisions = HashMap<Key, Int>()
    private val oldestPerChannel = HashMap<Long, Int>()
    private var channelGenerationSeen = -1

    val size: Int get() = rows.size

    // newest first
    fun snapshot(): List<MessageObject> = ArrayList(rows)

    fun revision(msg: MessageObject): Int = revisions[keyOf(msg)] ?: 0

    fun oldestForChannel(dialogId: Long): Int? = oldestPerChannel[dialogId]

    fun newestIdPerChannel(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (msg in rows) {
            val dialogId = msg.getDialogId()
            if (msg.id > (out[dialogId] ?: 0)) out[dialogId] = msg.id
        }
        return out
    }

    // returns true when the channel set changed and the timeline was reset
    fun ensureChannelGeneration(): Boolean {
        if (channelGenerationSeen == FeedChannelSet.generation) return false
        channelGenerationSeen = FeedChannelSet.generation
        rows.clear()
        byKey.clear()
        revisions.clear()
        oldestPerChannel.clear()
        return true
    }

    fun loadOlder(onResult: (added: Int) -> Unit) {
        val oldest = rows.lastOrNull()
        val before = oldest?.let { Cursor(it.messageOwner?.date ?: 0, it.getDialogId(), it.id) }
        queryPage(before, PAGE_SIZE, onResult, newer = false)
    }

    // entiny: catch up on posts that arrived while this store had no attached (visible) screen --
    // live pushes only reach FeedController while isActive, so a gap while away is otherwise permanent.
    // Bounded rather than unlimited: a bigger gap than CATCHUP_LIMIT still leaves a silent hole, but the
    // regular loadOlder()/backfill path can still reach that history by scrolling down from here.
    fun loadNewer(onResult: (added: Int) -> Unit) {
        val newest = rows.firstOrNull()
        if (newest == null) { onResult(0); return }
        val after = Cursor(newest.messageOwner?.date ?: 0, newest.getDialogId(), newest.id)
        queryPage(after, CATCHUP_LIMIT, onResult, newer = true)
    }

    // returns true when the timeline changed
    fun mergeLive(messages: List<MessageObject>): Boolean {
        // entiny: live objects are shared with ChatActivity, so the feed keeps its own wide copies
        val copies = messages
            .filter { it.messageOwner != null && FeedChannelSet.isEligibleChannel(account, it.getDialogId(), scope) }
            .map { feedCopy(it.messageOwner) }
        return insert(copies) > 0
    }

    fun replace(dialogId: Long, updated: List<MessageObject>): Boolean {
        var changed = false
        for (msg in updated) {
            val key = Key(dialogId, msg.id)
            val old = byKey[key] ?: continue
            val copy = feedCopy(msg.messageOwner)
            rows[rows.indexOf(old)] = copy
            byKey[key] = copy
            bump(key)
            changed = true
        }
        return changed
    }

    fun updateReactions(dialogId: Long, messageId: Int, reactions: TLRPC.TL_messageReactions): Boolean {
        val key = Key(dialogId, messageId)
        val msg = byKey[key] ?: return false
        MessageObject.updateReactions(msg.messageOwner, reactions)
        bump(key)
        return true
    }

    fun updateViews(dialogId: Long, views: Map<Int, Int>, forwards: Map<Int, Int>): Boolean {
        var changed = false
        for (msg in rows) {
            if (msg.getDialogId() != dialogId) continue
            val owner = msg.messageOwner ?: continue
            views[msg.id]?.let { if (it > owner.views) { owner.views = it; owner.flags = owner.flags or TLRPC.MESSAGE_FLAG_HAS_VIEWS; changed = true; bump(keyOf(msg)) } }
            forwards[msg.id]?.let { if (it != owner.forwards) { owner.forwards = it; changed = true; bump(keyOf(msg)) } }
        }
        return changed
    }

    fun remove(dialogId: Long, messageIds: Collection<Int>): Boolean {
        if (messageIds.isEmpty()) return false
        val ids = messageIds.toHashSet()
        return removeWhere { it.getDialogId() == dialogId && ids.contains(it.id) }
    }

    fun removeDialog(dialogId: Long): Boolean = removeWhere { it.getDialogId() == dialogId }

    // entiny: the store outlives the screen, so drop the old tail when nobody is looking
    fun trim() {
        if (rows.size <= MAX_RETAINED_ROWS) return
        val cut = ArrayList(rows.subList(MAX_RETAINED_ROWS, rows.size))
        val cutKeys = cut.mapTo(HashSet()) { keyOf(it) }
        removeWhere { cutKeys.contains(keyOf(it)) }
        oldestPerChannel.clear()
        for (msg in rows) {
            val current = oldestPerChannel[msg.getDialogId()]
            if (current == null || msg.id < current) oldestPerChannel[msg.getDialogId()] = msg.id
        }
    }

    private fun removeWhere(predicate: (MessageObject) -> Boolean): Boolean {
        var changed = false
        val it = rows.iterator()
        while (it.hasNext()) {
            val msg = it.next()
            if (!predicate(msg)) continue
            it.remove()
            byKey.remove(keyOf(msg))
            revisions.remove(keyOf(msg))
            changed = true
        }
        return changed
    }

    private fun bump(key: Key) {
        revisions[key] = (revisions[key] ?: 0) + 1
    }

    private fun queryPage(cursor: Cursor?, limit: Int, onResult: (Int) -> Unit, newer: Boolean) {
        val channels = FeedChannelSet.eligibleChannels(account, scope)
        if (channels.isEmpty()) {
            onResult(0)
            return
        }
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            val messages = ArrayList<TLRPC.Message>()
            val users = ArrayList<TLRPC.User>()
            val chats = ArrayList<TLRPC.Chat>()
            try {
                val idsCsv = channels.joinToString(",")
                val cmp = if (newer) ">" else "<"
                val order = if (newer) "ASC" else "DESC"
                val bound = if (cursor == null) "" else String.format(
                    Locale.US,
                    "AND (date %s %d OR (date = %d AND (uid %s %d OR (uid = %d AND mid %s %d))))",
                    cmp, cursor.date, cursor.date, cmp, cursor.dialogId, cursor.dialogId, cmp, cursor.messageId,
                )
                readMessages(
                    storage,
                    String.format(
                        Locale.US,
                        "SELECT data FROM messages_v2 WHERE uid IN (%s) AND mid > 0 %s ORDER BY date %s, uid %s, mid %s LIMIT %d",
                        idsCsv, bound, order, order, order, limit,
                    ),
                    messages,
                )
                // entiny: album-tail completion only matters for the older/backward page --
                // a newer/catch-up page getting cut mid-album is a rare, cosmetically minor edge case.
                if (!newer) completeTrailingAlbum(storage, messages)
                val usersToLoad = ArrayList<Long>()
                val chatsToLoad = ArrayList<Long>()
                for (message in messages) MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
                // entiny: forward sources aren't in memory yet, so load them or the "Forwarded from" line stays empty
                if (usersToLoad.isNotEmpty()) storage.getUsersInternal(usersToLoad, users)
                if (chatsToLoad.isNotEmpty()) storage.getChatsInternal(chatsToLoad.joinToString(","), chats)
            } catch (e: Exception) {
                Log.d(TAG, "query failed", e)
            }
            val loaded = messages.map { feedCopy(it) }
            AndroidUtilities.runOnUIThread {
                val controller = MessagesController.getInstance(account)
                controller.putUsers(users, true)
                controller.putChats(chats, true)
                onResult(insert(loaded))
            }
        }
    }

    // entiny: a page limit can cut an album in half, so pull the rest of the oldest album along with it
    private fun completeTrailingAlbum(storage: MessagesStorage, messages: ArrayList<TLRPC.Message>) {
        val tail = messages.lastOrNull() ?: return
        if (tail.grouped_id == 0L) return
        val dialogId = MessageObject.getDialogId(tail)
        val extra = ArrayList<TLRPC.Message>()
        readMessages(
            storage,
            String.format(
                Locale.US,
                "SELECT data FROM messages_v2 WHERE uid = %d AND mid > 0 AND mid < %d ORDER BY mid DESC LIMIT %d",
                dialogId, tail.id, ALBUM_TAIL_LOOKUP,
            ),
            extra,
        )
        for (message in extra) {
            if (message.grouped_id != tail.grouped_id) break
            messages.add(message)
        }
    }

    private fun readMessages(storage: MessagesStorage, sql: String, out: ArrayList<TLRPC.Message>) {
        var cursor: SQLiteCursor? = null
        try {
            cursor = storage.database.queryFinalized(sql)
            val clientUserId = UserConfig.getInstance(account).clientUserId
            while (cursor.next()) {
                val data = cursor.byteBufferValue(0) ?: continue
                val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
                message?.readAttachPath(data, clientUserId)
                data.reuse()
                if (message != null) out.add(message)
            }
        } finally {
            cursor?.dispose()
        }
    }

    private fun feedCopy(message: TLRPC.Message): MessageObject =
        MessageObject(account, message, false, false).apply { forceWideChannelPost = true }

    private fun insert(incoming: List<MessageObject>): Int {
        var added = 0
        for (msg in incoming) {
            val key = keyOf(msg)
            if (byKey.containsKey(key)) continue
            byKey[key] = msg
            rows.add(msg)
            added++
            val current = oldestPerChannel[key.dialogId]
            if (current == null || msg.id < current) oldestPerChannel[key.dialogId] = msg.id
        }
        if (added == 0) return 0
        rows.sortWith(TIMELINE_ORDER)
        return added
    }

    companion object {
        private const val TAG = "FeedStore"
        private const val PAGE_SIZE = 30
        private const val CATCHUP_LIMIT = 200
        private const val ALBUM_TAIL_LOOKUP = 10
        private const val MAX_RETAINED_ROWS = 500

        fun keyOf(msg: MessageObject) = Key(msg.getDialogId(), msg.id)

        private val TIMELINE_ORDER = compareByDescending<MessageObject> { it.messageOwner?.date ?: 0 }
            .thenByDescending { it.getDialogId() }
            .thenByDescending { it.id }
    }
}
