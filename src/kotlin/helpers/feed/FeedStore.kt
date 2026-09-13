package desu.inugram.helpers.feed

import android.util.Log
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import java.util.Locale

/**
 * In-memory merged timeline across every eligible channel for one account, read straight from the
 * LOCAL message cache (`messages_v2`) -- no network here, see [FeedBackfillCoordinator] for that.
 *
 * Ordering key is `(date, dialogId, messageId)`, the only ordering that stays stable when several
 * channels post within the same second (mirrors ExteraGram's own `compareTimeline`). Rows are
 * merged into a single list kept newest-first; [oldestCursor]/[newestCursor] bound what has been
 * loaded so `loadOlder`/`loadNewer` know where to resume.
 *
 * All public methods are safe to call from the UI thread; the actual DB read runs on
 * `MessagesStorage`'s own storage queue and results are delivered back via [onResult].
 */
class FeedStore(private val account: Int) {

    /** `(date, dialogId, messageId)` triple used as the merge/sort/cursor key throughout. */
    data class Key(val date: Int, val dialogId: Long, val messageId: Int)

    private val lock = Object()
    private val rows = ArrayList<MessageObject>() // newest first, deduplicated by (dialogId, id)
    private val seen = HashSet<Pair<Long, Int>>() // (dialogId, messageId) keys already in `rows`
    private val oldestPerChannel = HashMap<Long, Int>() // dialogId -> lowest message id merged in so far
    private var oldestCursor: Key? = null
    private var newestCursor: Key? = null
    private var channelGenerationSeen = -1

    /** Current merged rows, newest first. Safe to call from the UI thread. */
    fun snapshot(): List<MessageObject> = synchronized(lock) { ArrayList(rows) }

    fun oldestLoaded(): Key? = synchronized(lock) { oldestCursor }
    fun newestLoaded(): Key? = synchronized(lock) { newestCursor }

    /**
     * Lowest message id merged in so far for [dialogId] (the boundary a backfill request should
     * page further back from), or null if nothing from that channel has been loaded yet.
     */
    fun oldestForChannel(dialogId: Long): Int? = synchronized(lock) { oldestPerChannel[dialogId] }

    /** Clears everything -- the eligible channel set changed underneath us. */
    fun reset() {
        synchronized(lock) {
            rows.clear()
            seen.clear()
            oldestPerChannel.clear()
            oldestCursor = null
            newestCursor = null
        }
    }

    private fun rebuildIfChannelsChanged() {
        if (channelGenerationSeen != FeedChannelSet.generation) {
            reset()
            channelGenerationSeen = FeedChannelSet.generation
        }
    }

    /** First page: the most recent [PAGE_SIZE] messages across every eligible channel. */
    fun loadInitial(onResult: (added: List<MessageObject>) -> Unit) {
        rebuildIfChannelsChanged()
        queryPage(before = null, limit = PAGE_SIZE, onResult = onResult)
    }

    /** Older page, resuming from [oldestCursor]. No-op (empty result) if nothing is loaded yet. */
    fun loadOlder(onResult: (added: List<MessageObject>) -> Unit) {
        val before = synchronized(lock) { oldestCursor } ?: run { loadInitial(onResult); return }
        queryPage(before = before, limit = PAGE_SIZE, onResult = onResult)
    }

    /**
     * Anything newer than [newestCursor]. Used both on open (catch up since last session) and on
     * `didReceiveNewMessages` for an eligible channel while the store is warm.
     */
    fun loadNewer(onResult: (added: List<MessageObject>) -> Unit) {
        val after = synchronized(lock) { newestCursor } ?: run { loadInitial(onResult); return }
        queryPage(after = after, limit = PAGE_SIZE_NEWER, onResult = onResult)
    }

    /** Drops rows belonging to [dialogId]/[messageIds] (message deletion). */
    fun removeMessages(dialogId: Long, messageIds: Collection<Int>) {
        if (messageIds.isEmpty()) return
        synchronized(lock) {
            val ids = messageIds.toHashSet()
            val it = rows.iterator()
            while (it.hasNext()) {
                val row = it.next()
                if (row.getDialogId() == dialogId && ids.contains(row.id)) {
                    seen.remove(dialogId to row.id)
                    it.remove()
                }
            }
        }
    }

    /** Drops every row belonging to [dialogId] (history cleared / channel left). */
    fun removeDialog(dialogId: Long) {
        synchronized(lock) {
            val it = rows.iterator()
            while (it.hasNext()) {
                val row = it.next()
                if (row.getDialogId() == dialogId) {
                    seen.remove(dialogId to row.id)
                    it.remove()
                }
            }
        }
    }

    /**
     * Merges freshly-arrived [messages] (from a push) straight in, no DB round trip needed.
     * Returns the genuinely-new rows (post-dedup), newest-first -- same convention as every other
     * FeedStore result -- so a live-open [FeedActivity] can append them without a second query.
     */
    fun mergeLive(messages: List<MessageObject>): List<MessageObject> {
        val eligible = messages.filter { FeedChannelSet.isEligibleChannel(account, it.getDialogId()) }
        if (eligible.isEmpty()) return emptyList()
        // Push order is whatever the server batched together, not necessarily newest-first; sort
        // before merging so the returned delta honours the same convention queryPage's results do.
        val sorted = eligible.sortedWith(
            compareByDescending<MessageObject> { it.messageOwner?.date ?: 0 }
                .thenByDescending { it.getDialogId() }
                .thenByDescending { it.id },
        )
        return insertSorted(sorted)
    }

    private fun queryPage(
        before: Key? = null,
        after: Key? = null,
        limit: Int,
        onResult: (List<MessageObject>) -> Unit,
    ) {
        val channels = FeedChannelSet.eligibleChannels(account)
        if (channels.isEmpty()) {
            AndroidUtilities.runOnUIThread { onResult(emptyList()) }
            return
        }
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            val loaded = ArrayList<MessageObject>()
            var cursor: SQLiteCursor? = null
            try {
                val idsCsv = channels.joinToString(",")
                val bound = when {
                    before != null -> String.format(
                        Locale.US,
                        "AND (date < %d OR (date = %d AND (uid < %d OR (uid = %d AND mid < %d))))",
                        before.date, before.date, before.dialogId, before.dialogId, before.messageId,
                    )
                    after != null -> String.format(
                        Locale.US,
                        "AND (date > %d OR (date = %d AND (uid > %d OR (uid = %d AND mid > %d))))",
                        after.date, after.date, after.dialogId, after.dialogId, after.messageId,
                    )
                    else -> ""
                }
                val orderDir = if (after != null) "ASC" else "DESC"
                cursor = storage.database.queryFinalized(
                    String.format(
                        Locale.US,
                        "SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (%s) %s ORDER BY date %s, uid %s, mid %s LIMIT %d",
                        idsCsv, bound, orderDir, orderDir, orderDir, limit,
                    ),
                )
                val clientUserId = UserConfig.getInstance(account).clientUserId
                while (cursor.next()) {
                    val data = cursor.byteBufferValue(0) ?: continue
                    val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
                    message?.readAttachPath(data, clientUserId)
                    data.reuse()
                    if (message == null) continue
                    // messages_v2's own `uid`/`date` columns are authoritative for cursoring even
                    // though the deserialized TL object usually carries the same values itself.
                    loaded.add(MessageObject(account, message, false, false))
                }
            } catch (e: Exception) {
                Log.d(TAG, "query failed", e)
            } finally {
                cursor?.dispose()
            }
            if (after != null) loaded.reverse() // keep the merge step newest-first regardless of scan direction
            AndroidUtilities.runOnUIThread {
                val added = insertSorted(loaded)
                onResult(added)
            }
        }
    }

    /** Inserts [incoming] into [rows] (newest-first), deduplicating and updating both cursors. */
    private fun insertSorted(incoming: List<MessageObject>): List<MessageObject> {
        val added = ArrayList<MessageObject>(incoming.size)
        synchronized(lock) {
            for (msg in incoming) {
                val key = msg.getDialogId() to msg.id
                if (!seen.add(key)) continue
                added.add(msg)
            }
            if (added.isEmpty()) return@synchronized
            for (msg in added) {
                val dialogId = msg.getDialogId()
                val current = oldestPerChannel[dialogId]
                if (current == null || msg.id < current) oldestPerChannel[dialogId] = msg.id
            }
            rows.addAll(added)
            rows.sortWith(
                compareByDescending<MessageObject> { it.messageOwner?.date ?: 0 }
                    .thenByDescending { it.getDialogId() }
                    .thenByDescending { it.id },
            )
            if (rows.size > MAX_RETAINED_ROWS) {
                for (i in MAX_RETAINED_ROWS until rows.size) {
                    seen.remove(rows[i].getDialogId() to rows[i].id)
                }
                while (rows.size > MAX_RETAINED_ROWS) rows.removeAt(rows.size - 1)
            }
            if (rows.isNotEmpty()) {
                val newest = rows.first()
                val oldest = rows.last()
                newestCursor = Key(newest.messageOwner?.date ?: 0, newest.getDialogId(), newest.id)
                oldestCursor = Key(oldest.messageOwner?.date ?: 0, oldest.getDialogId(), oldest.id)
            }
        }
        return added
    }

    companion object {
        private const val TAG = "FeedStore"
        private const val PAGE_SIZE = 30
        private const val PAGE_SIZE_NEWER = 50
        private const val MAX_RETAINED_ROWS = 500
    }
}
