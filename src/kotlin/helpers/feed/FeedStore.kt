package desu.inugram.helpers.feed

import android.util.Log
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLRPC
import desu.inugram.helpers.InuUtils
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
    // Keep the newest retained window; older posts remain reachable through normal paging.
    fun loadNewer(onResult: (added: Int) -> Unit) {
        val newest = rows.firstOrNull()
        if (newest == null) { onResult(0); return }
        val after = Cursor(newest.messageOwner?.date ?: 0, newest.getDialogId(), newest.id)
        queryPage(after, CATCHUP_LIMIT, onResult, newer = true)
    }

    // returns true when the timeline changed
    fun mergeLive(messages: List<MessageObject>): Boolean {
        // entiny: live objects are shared with ChatActivity, so the feed keeps its own wide copies
        val eligible = FeedChannelSet.eligibleChannels(account, scope).toHashSet()
        val copies = messages
            .filter { it.messageOwner != null && eligible.contains(it.getDialogId()) }
            .mapNotNull { feedCopy(it.messageOwner) }
        return insert(copies, persist = true) > 0
    }

    fun mergeSynced(dialogId: Long, messages: List<TLRPC.Message>): Int {
        if (!FeedChannelSet.isEligibleChannel(account, dialogId, scope)) return 0
        val copies = messages.asSequence()
            .filter { MessageObject.getDialogId(it) == dialogId }
            .mapNotNull { feedCopy(it) }
            .toList()
        return insert(copies, persist = true)
    }

    fun replace(dialogId: Long, updated: List<MessageObject>): Boolean {
        var changed = false
        val replacements = ArrayList<MessageObject>()
        for (msg in updated) {
            val key = Key(dialogId, msg.id)
            val old = byKey[key] ?: continue
            val copy = feedCopy(msg.messageOwner) ?: continue
            rows[rows.indexOf(old)] = copy
            byKey[key] = copy
            bump(key)
            replacements.add(copy)
            changed = true
        }
        if (changed) persistRows(replacements)
        return changed
    }

    fun updateReactions(dialogId: Long, messageId: Int, reactions: TLRPC.TL_messageReactions): Boolean {
        val key = Key(dialogId, messageId)
        val msg = byKey[key] ?: return false
        MessageObject.updateReactions(msg.messageOwner, reactions)
        bump(key)
        persistRows(listOf(msg))
        return true
    }

    fun updateViews(dialogId: Long, views: Map<Int, Int>, forwards: Map<Int, Int>): Boolean {
        var changed = false
        val messageIds = HashSet<Int>(views.size + forwards.size)
        messageIds.addAll(views.keys)
        messageIds.addAll(forwards.keys)
        for (messageId in messageIds) {
            val key = Key(dialogId, messageId)
            val msg = byKey[key] ?: continue
            val owner = msg.messageOwner ?: continue
            views[msg.id]?.let { if (it > owner.views) { owner.views = it; owner.flags = owner.flags or TLRPC.MESSAGE_FLAG_HAS_VIEWS; changed = true; bump(keyOf(msg)) } }
            forwards[msg.id]?.let { if (it != owner.forwards) { owner.forwards = it; changed = true; bump(keyOf(msg)) } }
        }
        if (changed) persistRows(messageIds.mapNotNull { byKey[Key(dialogId, it)] })
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
        val affectedDialogs = HashSet<Long>()
        val removed = ArrayList<Key>()
        val it = rows.iterator()
        while (it.hasNext()) {
            val msg = it.next()
            if (!predicate(msg)) continue
            val key = keyOf(msg)
            it.remove()
            byKey.remove(key)
            revisions.remove(key)
            removed.add(key)
            affectedDialogs.add(key.dialogId)
            changed = true
        }
        if (changed) {
            recomputeOldest(affectedDialogs)
            deleteRows(removed)
        }
        return changed
    }

    private fun deleteRows(keys: List<Key>) {
        if (keys.isEmpty()) return
        val storage = MessagesStorage.getInstance(account)
        val scopeKey = scopeKey()
        storage.storageQueue.postRunnable {
            var query: org.telegram.SQLite.SQLitePreparedStatement? = null
            try {
                query = storage.database.executeFast("DELETE FROM inu_feed_cache WHERE account_id = ? AND scope_key = ? AND dialog_id = ? AND msg_id = ?")
                for (key in keys) {
                    query.bindInteger(1, account)
                    query.bindString(2, scopeKey)
                    query.bindLong(3, key.dialogId)
                    query.bindInteger(4, key.messageId)
                    query.step()
                    query.requery()
                }
            } catch (e: Exception) {
                Log.d(TAG, "cache delete failed", e)
            } finally {
                query?.dispose()
            }
        }
    }

    private fun recomputeOldest(dialogIds: Set<Long>) {
        for (dialogId in dialogIds) oldestPerChannel.remove(dialogId)
        for (msg in rows) {
            val dialogId = msg.getDialogId()
            if (!dialogIds.contains(dialogId)) continue
            val current = oldestPerChannel[dialogId]
            if (current == null || msg.id < current) oldestPerChannel[dialogId] = msg.id
        }
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
            try {
                val idsCsv = channels.joinToString(",")
                val scopeKey = scopeKey()
                val cmp = if (newer) ">" else "<"
                val order = "DESC"
                val bound = if (cursor == null) "" else String.format(
                    Locale.US,
                    "AND (date %s %d OR (date = %d AND (dialog_id %s %d OR (dialog_id = %d AND msg_id %s %d))))",
                    cmp, cursor.date, cursor.date, cmp, cursor.dialogId, cursor.dialogId, cmp, cursor.messageId,
                )
                readMessages(
                    storage,
                    String.format(
                        Locale.US,
                        "SELECT data FROM inu_feed_cache WHERE account_id = %d AND scope_key = '%s' AND dialog_id IN (%s) %s ORDER BY date %s, dialog_id %s, msg_id %s LIMIT %d",
                        account, scopeKey, idsCsv, bound, order, order, order, limit,
                    ),
                    messages,
                )
                // entiny: album-tail completion only matters for the older/backward page --
                // a newer/catch-up page getting cut mid-album is a rare, cosmetically minor edge case.
                if (!newer) completeTrailingAlbum(storage, messages)
            } catch (e: Exception) {
                Log.d(TAG, "query failed", e)
            }
            val loaded = messages.mapNotNull { feedCopy(it) }
            AndroidUtilities.runOnUIThread {
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
                "SELECT data FROM inu_feed_cache WHERE account_id = %d AND scope_key = '%s' AND dialog_id = %d AND msg_id < %d ORDER BY msg_id DESC LIMIT %d",
                account, scopeKey(), dialogId, tail.id, ALBUM_TAIL_LOOKUP,
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
            while (cursor.next()) {
                val data = cursor.byteBufferValue(0) ?: continue
                val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
                data.reuse()
                if (message != null) out.add(message)
            }
        } finally {
            cursor?.dispose()
        }
    }

    private fun feedCopy(message: TLRPC.Message): MessageObject? {
        val copy = InuUtils.cloneTLObject(message, TLRPC.Message::TLdeserialize) ?: return null
        copy.attachPath = message.attachPath
        copy.send_state = message.send_state
        copy.fwd_msg_id = message.fwd_msg_id
        copy.random_id = message.random_id
        copy.local_id = message.local_id
        copy.dialog_id = message.dialog_id
        copy.ttl = message.ttl
        copy.destroyTime = message.destroyTime
        copy.destroyTimeMillis = message.destroyTimeMillis
        copy.params = message.params?.let { HashMap(it) }
        return MessageObject(account, copy, false, false).apply { forceWideChannelPost = true }
    }

    private fun insert(incoming: List<MessageObject>, persist: Boolean = false): Int {
        var added = 0
        val inserted = ArrayList<MessageObject>()
        for (msg in incoming) {
            val key = keyOf(msg)
            if (byKey.containsKey(key)) continue
            byKey[key] = msg
            rows.add(msg)
            inserted.add(msg)
            added++
            val current = oldestPerChannel[key.dialogId]
            if (current == null || msg.id < current) oldestPerChannel[key.dialogId] = msg.id
        }
        if (added == 0) return 0
        rows.sortWith(TIMELINE_ORDER)
        if (persist) persistRows(inserted)
        return added
    }

    private fun persistRows(messages: List<MessageObject>) {
        if (messages.isEmpty()) return
        val storage = MessagesStorage.getInstance(account)
        val scopeKey = scopeKey()
        storage.storageQueue.postRunnable {
            var query: org.telegram.SQLite.SQLitePreparedStatement? = null
            try {
                query = storage.database.executeFast("INSERT OR REPLACE INTO inu_feed_cache(account_id, scope_key, dialog_id, msg_id, date, data, grouped_id) VALUES(?, ?, ?, ?, ?, ?, ?)")
                for (objectMessage in messages) {
                    val message = objectMessage.messageOwner ?: continue
                    query.bindInteger(1, account)
                    query.bindString(2, scopeKey)
                    query.bindLong(3, objectMessage.getDialogId())
                    query.bindInteger(4, objectMessage.id)
                    query.bindInteger(5, message.date)
                    query.bindTlObject(6, message)
                    query.bindLong(7, message.grouped_id)
                    query.step()
                    query.requery()
                }
            } catch (e: Exception) {
                Log.d(TAG, "cache write failed", e)
            } finally {
                query?.dispose()
            }
        }
    }

    private fun scopeKey(): String = (scope as? FeedScope.Folder)?.let { "f${it.filterId}" } ?: "g"

    companion object {
        private const val TAG = "FeedStore"
        private const val PAGE_SIZE = 30
        private const val ALBUM_TAIL_LOOKUP = 10
        private const val MAX_RETAINED_ROWS = 500
        private const val CATCHUP_LIMIT = MAX_RETAINED_ROWS

        fun keyOf(msg: MessageObject) = Key(msg.getDialogId(), msg.id)

        private val TIMELINE_ORDER = compareByDescending<MessageObject> { it.messageOwner?.date ?: 0 }
            .thenByDescending { it.getDialogId() }
            .thenByDescending { it.id }
    }
}
