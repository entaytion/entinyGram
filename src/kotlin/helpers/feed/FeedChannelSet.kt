package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import android.os.SystemClock
import android.util.Log
import org.telegram.SQLite.SQLiteCursor
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLRPC

sealed class FeedScope {
    object Global : FeedScope()

    data class Folder(val filterId: Int) : FeedScope()
}

object FeedChannelSet {

    // entiny: db snapshot of a dialog the app hasn't paged into dialogs_dict yet
    private class Stored(val folderId: Int, val readMax: Int, val topMessage: Int)

    @Volatile
    var generation: Int = 0
        private set

    @Synchronized
    fun invalidate() {
        generation++
    }

    private data class Cached(val gen: Int, val excluded: Set<String>, val includeArchived: Boolean, val arr: LongArray, val at: Long)
    private val cached = HashMap<Int, Cached>()
    private const val CACHE_TTL_MS = 5_000L

    private val stored = HashMap<Int, Map<Long, Stored>>()
    private val storedLoading = HashSet<Int>()

    // entiny: channels beyond the dialogs pages loaded so far still belong in the feed, so read them from the db once
    private fun ensureStored(account: Int) {
        synchronized(this) {
            if (stored.containsKey(account) || !storedLoading.add(account)) return
        }
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            val rows = HashMap<Long, Stored>()
            var cursor: SQLiteCursor? = null
            try {
                cursor = storage.database.queryFinalized("SELECT did, inbox_max, last_mid, folder_id FROM dialogs WHERE did < 0")
                while (cursor.next()) rows[cursor.longValue(0)] = Stored(cursor.intValue(3), cursor.intValue(1), cursor.intValue(2))
            } catch (e: Exception) {
                Log.d(TAG, "dialogs scan failed", e)
            } finally {
                cursor?.dispose()
            }
            val controller = MessagesController.getInstance(account)
            val chats = ArrayList<TLRPC.Chat>()
            val missing = rows.keys.filter { controller.getChat(-it) == null }
            if (missing.isNotEmpty()) {
                try {
                    storage.getChatsInternal(missing.joinToString(",") { (-it).toString() }, chats)
                } catch (e: Exception) {
                    Log.d(TAG, "chats load failed", e)
                }
            }
            AndroidUtilities.runOnUIThread {
                if (chats.isNotEmpty()) controller.putChats(chats, true)
                val before = eligibleChannels(account).size
                synchronized(this) {
                    stored[account] = rows
                    cached.remove(account)
                }
                if (eligibleChannels(account).size != before) invalidate()
            }
        }
    }

    private inline fun forEachChannelDialog(account: Int, action: (dialogId: Long, folderId: Int) -> Unit) {
        val controller = MessagesController.getInstance(account)
        val seen = HashSet<Long>()
        for (dialog in controller.getAllDialogs()) {
            if (dialog.id >= 0) continue
            seen.add(dialog.id)
            action(dialog.id, dialog.folder_id)
        }
        val extra = synchronized(this) { stored[account] } ?: return
        for ((dialogId, row) in extra) {
            if (dialogId !in seen) action(dialogId, row.folderId)
        }
    }

    @Synchronized
    private fun cachedGlobal(account: Int): Cached {
        val excluded = InuConfig.FEED_EXCLUDED_CHANNELS.value
        val includeArchived = InuConfig.FEED_INCLUDE_ARCHIVED.value
        val now = SystemClock.elapsedRealtime()
        val cur = cached[account]
        if (cur != null && cur.gen == generation && cur.excluded == excluded && cur.includeArchived == includeArchived && now - cur.at < CACHE_TTL_MS) return cur
        ensureStored(account)
        val result = ArrayList<Long>()
        forEachChannelDialog(account) { dialogId, folderId ->
            if ((includeArchived || folderId == 0) && !excluded.contains(dialogId.toString()) && isEligibleChannel(account, dialogId)) {
                result.add(dialogId)
            }
        }
        val next = Cached(generation, excluded, includeArchived, result.toLongArray(), now)
        cached[account] = next
        return next
    }

    fun eligibleChannels(account: Int): LongArray = cachedGlobal(account).arr

    fun eligibleChannels(account: Int, scope: FeedScope): LongArray {
        val global = eligibleChannels(account)
        val folder = scope as? FeedScope.Folder ?: return global
        val dialogFilter = findFilter(account, folder.filterId) ?: return longArrayOf()
        if (dialogFilter.isDefault) return global
        val accountInstance = AccountInstance.getInstance(account)
        return global.filter { dialogFilter.includesDialog(accountInstance, it) }.toLongArray()
    }

    fun isEligibleChannel(account: Int, dialogId: Long): Boolean {
        if (dialogId >= 0) return false
        val chat = MessagesController.getInstance(account).getChat(-dialogId) ?: return false
        return ChatObject.isChannelAndNotMegaGroup(chat) &&
            !ChatObject.isCommunity(chat) &&
            !ChatObject.isNotInChat(chat)
    }

    fun isEligibleChannel(account: Int, dialogId: Long, scope: FeedScope): Boolean {
        if (!isEligibleChannel(account, dialogId)) return false
        if (InuConfig.FEED_EXCLUDED_CHANNELS.value.contains(dialogId.toString())) return false
        val folderId = MessagesController.getInstance(account).dialogs_dict?.get(dialogId)?.folder_id
            ?: storedRow(account, dialogId)?.folderId
            ?: return false
        if (!InuConfig.FEED_INCLUDE_ARCHIVED.value && folderId != 0) return false
        val folder = scope as? FeedScope.Folder ?: return true
        val filter = findFilter(account, folder.filterId) ?: return false
        if (filter.isDefault) return true
        return filter.includesDialog(AccountInstance.getInstance(account), dialogId)
    }

    @Synchronized
    private fun storedRow(account: Int, dialogId: Long): Stored? = stored[account]?.get(dialogId)

    fun topMessage(account: Int, dialogId: Long): Int =
        MessagesController.getInstance(account).dialogs_dict?.get(dialogId)?.top_message ?: storedRow(account, dialogId)?.topMessage ?: 0

    fun readMax(account: Int, dialogId: Long): Int {
        val controller = MessagesController.getInstance(account)
        return maxOf(
            controller.dialogs_dict?.get(dialogId)?.read_inbox_max_id ?: 0,
            controller.dialogs_read_inbox_max[dialogId] ?: 0,
            storedRow(account, dialogId)?.readMax ?: 0,
        )
    }

    private fun findFilter(account: Int, filterId: Int): MessagesController.DialogFilter? =
        MessagesController.getInstance(account).dialogFilters?.firstOrNull { it.id == filterId }

    private fun resolveFilter(account: Int, scope: FeedScope): MessagesController.DialogFilter? {
        val folder = scope as? FeedScope.Folder ?: return null
        val filter = findFilter(account, folder.filterId) ?: return null
        return if (filter.isDefault) null else filter
    }

    fun folderName(account: Int, scope: FeedScope): String? {
        val filter = resolveFilter(account, scope) ?: return null
        return desu.inugram.helpers.dialogs.FolderHelper.getTabInfo(filter).first.takeIf { it.isNotEmpty() }
    }

    fun allChannelsSplit(account: Int): Pair<List<Long>, List<Long>> {
        val excluded = InuConfig.FEED_EXCLUDED_CHANNELS.value
        val includeArchived = InuConfig.FEED_INCLUDE_ARCHIVED.value
        ensureStored(account)
        val shown = ArrayList<Long>()
        val hidden = ArrayList<Long>()
        forEachChannelDialog(account) { dialogId, folderId ->
            if ((includeArchived || folderId == 0) && isEligibleChannel(account, dialogId)) {
                if (excluded.contains(dialogId.toString())) hidden.add(dialogId) else shown.add(dialogId)
            }
        }
        return shown to hidden
    }

    fun pruneStaleExclusions(account: Int) {
        val current = InuConfig.FEED_EXCLUDED_CHANNELS.value
        if (current.isEmpty()) return
        // entiny: until the db snapshot is in, an unpaged channel looks unknown and would lose its exclusion
        val known = synchronized(this) { stored[account] } ?: return
        val controller = MessagesController.getInstance(account)
        val valid = current.filterTo(HashSet()) { key ->
            val id = key.toLongOrNull() ?: return@filterTo false
            controller.dialogs_dict?.get(id) != null || known.containsKey(id)
        }
        if (valid.size != current.size) {
            InuConfig.FEED_EXCLUDED_CHANNELS.value = valid
        }
    }

    private const val TAG = "FeedChannelSet"
}
