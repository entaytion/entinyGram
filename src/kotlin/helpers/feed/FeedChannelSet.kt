package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController

/**
 * Which slice of the eligible channel set a Feed screen (and its [FeedStore]) is looking at.
 *
 * [Folder] is deliberately an INTERSECTION with the global set, not a parallel configuration:
 * folder scope = global-eligible ∩ stock folder membership, so the one exclude list and the one
 * include-archived toggle keep applying uniformly and there is no second per-folder exclude list
 * to keep in sync.
 */
sealed class FeedScope {
    object Global : FeedScope()

    /** [filterId] is a stock `MessagesController.DialogFilter.id`. */
    data class Folder(val filterId: Int) : FeedScope()
}

/**
 * Which dialogs the Feed screen aggregates: broadcast channels the account is still a member of,
 * minus the user's exclude list. Communities are skipped -- they already have their own dedicated
 * post UI, aggregating them into Feed too would just duplicate it.
 *
 * Deliberately does NOT require [ChatObject.isPublic] the way
 * `DialogsChannelsAdapter.updateMyChannels()` does for its own unrelated purpose -- that would
 * wrongly drop private channels the user is actually a member of.
 *
 * [generation] bumps whenever the eligible set could have changed (dialog list reload, exclude
 * list edited, include-archived toggled), so [FeedStore] can tell a cached merge is stale without
 * re-deriving the channel set on every single read.
 */
object FeedChannelSet {

    @Volatile
    var generation: Int = 0
        private set

    fun invalidate() {
        generation++
    }

    /** Eligible channel dialog ids for [account]. Order is not meaningful. */
    fun eligibleChannels(account: Int): LongArray {
        val excluded = InuConfig.FEED_EXCLUDED_CHANNELS.value
        val includeArchived = InuConfig.FEED_INCLUDE_ARCHIVED.value
        val controller = MessagesController.getInstance(account)
        val dialogs = controller.getAllDialogs()
        val result = ArrayList<Long>(dialogs.size)
        for (dialog in dialogs) {
            val dialogId = dialog.id
            if (dialogId >= 0) continue // channels are always negative chat ids
            if (!includeArchived && dialog.folder_id != 0) continue
            if (excluded.contains(dialogId.toString())) continue
            if (!isEligibleChannel(account, dialogId)) continue
            result.add(dialogId)
        }
        return result.toLongArray()
    }

    /**
     * [eligibleChannels] narrowed to [scope]. For [FeedScope.Folder] this is the global set
     * intersected with stock's OWN folder-membership predicate
     * (`MessagesController.DialogFilter.includesDialog`) -- deliberately not reimplemented here,
     * because auto-membership by flags (contacts/groups/channels/bots, exclude-read/muted/archived)
     * plus `alwaysShow`/`neverShow` overrides is non-trivial and stock already owns it.
     *
     * Falls back to the global set when the filter id no longer resolves (folder deleted while a
     * scoped screen was open) or names the default "All chats" filter, whose flags describe the
     * whole chat list rather than a real subset.
     */
    fun eligibleChannels(account: Int, scope: FeedScope): LongArray {
        val global = eligibleChannels(account)
        val dialogFilter = resolveFilter(account, scope) ?: return global
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

    /** [isEligibleChannel] narrowed to [scope]; same intersection semantics as [eligibleChannels]. */
    fun isEligibleChannel(account: Int, dialogId: Long, scope: FeedScope): Boolean {
        if (!isEligibleChannel(account, dialogId)) return false
        val filter = resolveFilter(account, scope) ?: return true
        return filter.includesDialog(AccountInstance.getInstance(account), dialogId)
    }

    /**
     * The stock filter [scope] points at, or null when the scope imposes no folder narrowing at
     * all -- global scope, an unknown filter id, or the default "All chats" filter.
     */
    private fun resolveFilter(account: Int, scope: FeedScope): MessagesController.DialogFilter? {
        val folder = scope as? FeedScope.Folder ?: return null
        val filter = MessagesController.getInstance(account).dialogFilters
            ?.firstOrNull { it.id == folder.filterId } ?: return null
        return if (filter.isDefault) null else filter
    }

    /** Display name for [scope]'s folder, or null for global / an unresolvable folder. */
    fun folderName(account: Int, scope: FeedScope): String? {
        val filter = resolveFilter(account, scope) ?: return null
        return desu.inugram.helpers.dialogs.FolderHelper.getTabInfo(filter).first.takeIf { it.isNotEmpty() }
    }

    /**
     * All broadcast channels the account is a member of (ignoring the exclude list itself, since
     * this is what the "manage excluded channels" screen lists to choose from), split into shown
     * vs hidden by the current exclude set.
     */
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

    /** Drops exclude-list entries for dialogs the account no longer knows about. */
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
