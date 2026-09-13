package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController

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

    fun isEligibleChannel(account: Int, dialogId: Long): Boolean {
        if (dialogId >= 0) return false
        val chat = MessagesController.getInstance(account).getChat(-dialogId) ?: return false
        return ChatObject.isChannelAndNotMegaGroup(chat) &&
            !ChatObject.isCommunity(chat) &&
            !ChatObject.isNotInChat(chat)
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
