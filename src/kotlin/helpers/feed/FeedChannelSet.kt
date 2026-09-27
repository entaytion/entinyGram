package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import android.os.SystemClock
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController

sealed class FeedScope {
    object Global : FeedScope()

    data class Folder(val filterId: Int) : FeedScope()
}

object FeedChannelSet {

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

    @Synchronized
    private fun cachedGlobal(account: Int): Cached {
        val excluded = InuConfig.FEED_EXCLUDED_CHANNELS.value
        val includeArchived = InuConfig.FEED_INCLUDE_ARCHIVED.value
        val now = SystemClock.elapsedRealtime()
        val cur = cached[account]
        if (cur != null && cur.gen == generation && cur.excluded == excluded && cur.includeArchived == includeArchived && now - cur.at < CACHE_TTL_MS) return cur
        val controller = MessagesController.getInstance(account)
        val dialogs = controller.getAllDialogs()
        val result = ArrayList<Long>(dialogs.size)
        for (dialog in dialogs) {
            val dialogId = dialog.id
            if (dialogId >= 0) continue
            if (!includeArchived && dialog.folder_id != 0) continue
            if (excluded.contains(dialogId.toString())) continue
            if (!isEligibleChannel(account, dialogId)) continue
            result.add(dialogId)
        }
        val arr = result.toLongArray()
        val next = Cached(generation, excluded, includeArchived, arr, now)
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
        val controller = MessagesController.getInstance(account)
        val dialog = controller.dialogs_dict?.get(dialogId) ?: return false
        if (!InuConfig.FEED_INCLUDE_ARCHIVED.value && dialog.folder_id != 0) return false
        val folder = scope as? FeedScope.Folder ?: return true
        val filter = findFilter(account, folder.filterId) ?: return false
        if (filter.isDefault) return true
        return filter.includesDialog(AccountInstance.getInstance(account), dialogId)
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
        val controller = MessagesController.getInstance(account)
        val shown = ArrayList<Long>()
        val hidden = ArrayList<Long>()
        for (dialog in controller.getAllDialogs()) {
            val dialogId = dialog.id
            if (dialogId >= 0) continue
            if (!includeArchived && dialog.folder_id != 0) continue
            if (!isEligibleChannel(account, dialogId)) continue
            if (excluded.contains(dialogId.toString())) hidden.add(dialogId) else shown.add(dialogId)
        }
        return shown to hidden
    }

    fun pruneStaleExclusions(account: Int) {
        val current = InuConfig.FEED_EXCLUDED_CHANNELS.value
        if (current.isEmpty()) return
        val controller = MessagesController.getInstance(account)
        val valid = current.filterTo(HashSet()) { key ->
            val id = key.toLongOrNull() ?: return@filterTo false
            controller.dialogs_dict?.get(id) != null
        }
        if (valid.size != current.size) {
            InuConfig.FEED_EXCLUDED_CHANNELS.value = valid
        }
    }
}
