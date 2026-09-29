package desu.inugram.helpers.feed

import android.util.Log
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
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

    fun contains(key: Key): Boolean = byKey.containsKey(key)

    fun head(): MessageObject? = rows.firstOrNull()

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
    fun loadNewer(onResult: (added: Int) -> Unit) = loadNewer(0, onResult)

    // entiny: pages upward from the head (oldest first) so a long absence leaves no gap
    private fun loadNewer(total: Int, onResult: (added: Int) -> Unit) {
        val newest = rows.firstOrNull()
        if (newest == null) { onResult(total); return }
        val after = Cursor(newest.messageOwner?.date ?: 0, newest.getDialogId(), newest.id)
        queryPage(after, CATCHUP_PAGE, { added ->
            if (added >= CATCHUP_PAGE && total + added < MAX_RETAINED_ROWS) loadNewer(total + added, onResult) else onResult(total + added)
        }, newer = true)
    }

    // returns true when the timeline changed
    fun mergeLive(messages: List<MessageObject>, shouldInsert: () -> Boolean, onResult: () -> Unit) {
        // entiny: live objects are shared with ChatActivity, so clone on the UI thread and build the wide row (text layout) off it
        val verdicts = HashMap<Long, Boolean>()
        val copies = messages
            .filter {
                it.messageOwner != null && it.getDialogId() < 0 &&
                    verdicts.getOrPut(it.getDialogId()) { FeedChannelSet.isEligibleChannel(account, it.getDialogId(), scope) }
            }
            .mapNotNull { cloneForFeed(it.messageOwner) }
        if (copies.isEmpty()) return
        Utilities.globalQueue.postRunnable {
            val built = copies.map { buildRow(it) }
            AndroidUtilities.runOnUIThread {
                if (shouldInsert() && insert(built, persist = true) > 0) onResult()
            }
        }
    }

    // entiny: copies and text layouts are built off the UI thread, only the insert happens on it
    fun mergeSynced(dialogId: Long, messages: List<TLRPC.Message>, onResult: (added: Int) -> Unit) {
        if (!FeedChannelSet.isEligibleChannel(account, dialogId, scope)) {
            onResult(0)
            return
        }
        Utilities.globalQueue.postRunnable {
            val copies = messages.filter { MessageObject.getDialogId(it) == dialogId }.mapNotNull { feedCopy(it) }
            AndroidUtilities.runOnUIThread {
                // entiny: memory must stay a gapless prefix of the cache, so posts older than the tail only go to the cache
                val tail = rows.lastOrNull()
                if (!FeedChannelSet.isEligibleChannel(account, dialogId, scope)) {
                    onResult(0)
                    return@runOnUIThread
                }
                val (inMemory, older) = if (tail == null) copies to emptyList() else copies.partition { TIMELINE_ORDER.compare(it, tail) <= 0 }
                persistRows(older)
                onResult(insert(inMemory, persist = true) + older.size)
            }
        }
    }

    // entiny: only channels whose top post is newer than what the cache holds need a history request
    fun channelsWithNewerPosts(onResult: (List<Pair<Long, Int>>) -> Unit) {
        val channels = FeedChannelSet.eligibleChannels(account, scope)
        if (channels.isEmpty()) {
            onResult(emptyList())
            return
        }
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            val cachedNewest = HashMap<Long, Int>()
            var cursor: SQLiteCursor? = null
            try {
                cursor = storage.database.queryFinalized(
                    String.format(
                        Locale.US,
                        "SELECT dialog_id, MAX(msg_id) FROM inu_feed_cache WHERE account_id = %d AND scope_key = '%s' GROUP BY dialog_id",
                        account, scopeKey(),
                    ),
                )
                while (cursor.next()) cachedNewest[cursor.longValue(0)] = cursor.intValue(1)
            } catch (e: Exception) {
                Log.d(TAG, "cache scan failed", e)
            } finally {
                cursor?.dispose()
            }
            AndroidUtilities.runOnUIThread {
                onResult(channels.filter { FeedChannelSet.topMessage(account, it) > (cachedNewest[it] ?: 0) }.map { it to 0 })
            }
        }
    }

    // entiny: the on-disk cache would otherwise grow with every post ever seen
    fun pruneCache() {
        val storage = MessagesStorage.getInstance(account)
        val scopeKey = scopeKey()
        storage.storageQueue.postRunnable {
            var cursor: SQLiteCursor? = null
            try {
                cursor = storage.database.queryFinalized(
                    "SELECT date FROM inu_feed_cache WHERE account_id = ? AND scope_key = ? ORDER BY date DESC LIMIT 1 OFFSET ?",
                    account, scopeKey, MAX_CACHED_ROWS,
                )
                val cutoff = if (cursor.next()) cursor.intValue(0) else return@postRunnable
                cursor.dispose()
                cursor = null
                storage.database.executeFast("DELETE FROM inu_feed_cache WHERE account_id = $account AND scope_key = '$scopeKey' AND date < $cutoff").stepThis().dispose()
            } catch (e: Exception) {
                Log.d(TAG, "cache prune failed", e)
            } finally {
                cursor?.dispose()
            }
        }
    }

    fun updateReactions(dialogId: Long, messageId: Int, reactions: TLRPC.TL_messageReactions): Boolean {
        val key = Key(dialogId, messageId)
        val msg = byKey[key] ?: return false
        MessageObject.updateReactions(msg.messageOwner, reactions)
        // entiny: the cell only relayouts reactions on these flags; an album's bottom cell draws the primary's reactions
        val affected = if (msg.hasValidGroupId()) rows.filter { it.getDialogId() == dialogId && it.getGroupId() == msg.getGroupId() } else listOf(msg)
        for (member in affected) {
            member.forceUpdate = true
            member.reactionsChanged = true
            bump(keyOf(member))
        }
        persistRows(listOf(msg))
        return true
    }

    fun remove(dialogId: Long, messageIds: Collection<Int>): Boolean {
        if (messageIds.isEmpty()) return false
        val ids = messageIds.toHashSet()
        return removeWhere { it.getDialogId() == dialogId && ids.contains(it.id) }
    }

    fun removeDialog(dialogId: Long): Boolean = removeWhere { it.getDialogId() == dialogId }

    // entiny: the store outlives the screen, so drop the old tail when nobody is looking
    fun trim(keep: Int = MAX_RETAINED_ROWS) {
        if (rows.size <= keep) return
        val cut = ArrayList(rows.subList(keep, rows.size))
        val cutKeys = cut.mapTo(HashSet()) { keyOf(it) }
        removeWhere(persist = false) { cutKeys.contains(keyOf(it)) }
        oldestPerChannel.clear()
        for (msg in rows) {
            val current = oldestPerChannel[msg.getDialogId()]
            if (current == null || msg.id < current) oldestPerChannel[msg.getDialogId()] = msg.id
        }
    }

    private fun removeWhere(persist: Boolean = true, predicate: (MessageObject) -> Boolean): Boolean {
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
            if (persist) deleteRows(removed)
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
                val order = if (newer) "ASC" else "DESC"
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
            val loaded = messages.mapNotNull { feedCopy(it, clone = false) }
            AndroidUtilities.runOnUIThread {
                // entiny: the channel set may have changed while the page was loading
                onResult(insert(loaded.filter { FeedChannelSet.isEligibleChannel(account, it.getDialogId(), scope) }))
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

    // entiny: rows read from the cache are already private, only shared stock messages need cloning
    private fun feedCopy(message: TLRPC.Message, clone: Boolean = true): MessageObject? {
        val copy = if (clone) cloneForFeed(message) ?: return null else message
        return buildRow(copy)
    }

    private fun cloneForFeed(message: TLRPC.Message): TLRPC.Message? {
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
        return copy
    }

    private fun buildRow(copy: TLRPC.Message): MessageObject {
        return MessageObject(account, copy, false, false).apply {
            forceWideChannelPost = true
            if (Theme.chat_msgTextPaint != null) {
                try {
                    checkLayout()
                } catch (e: Exception) {
                    Log.d(TAG, "layout prebuild failed", e)
                }
            }
        }
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
        private const val MAX_CACHED_ROWS = 3000
        private const val CATCHUP_PAGE = 100

        fun keyOf(msg: MessageObject) = Key(msg.getDialogId(), msg.id)

        private val TIMELINE_ORDER = compareByDescending<MessageObject> { it.messageOwner?.date ?: 0 }
            .thenByDescending { it.getDialogId() }
            .thenByDescending { it.id }
    }
}
