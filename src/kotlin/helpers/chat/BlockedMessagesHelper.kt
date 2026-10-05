package desu.inugram.helpers.chat

import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity

object BlockedMessagesHelper {
    private val extraHiddenCache = HashMap<Int, Set<Long>>()

    private fun getExtraHiddenKey(account: Int) = "blocked_messages_extra:$account"

    fun getExtraHidden(account: Int): Set<Long> {
        synchronized(extraHiddenCache) {
            extraHiddenCache[account]?.let { return it }
            val stored = InuConfig.prefs.getStringSet(getExtraHiddenKey(account), null)
            val set = stored?.mapNotNullTo(HashSet(), String::toLongOrNull) ?: emptySet()
            extraHiddenCache[account] = set
            return set
        }
    }

    fun setExtraHidden(account: Int, ids: Collection<Long>) {
        synchronized(extraHiddenCache) {
            extraHiddenCache[account] = HashSet(ids)
        }
        InuConfig.prefs.edit { putStringSet(getExtraHiddenKey(account), ids.map(Long::toString).toHashSet()) }
        invalidateVisible()
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.blockedUsersDidLoad)
    }

    fun isExtraHidden(account: Int, dialogId: Long): Boolean = getExtraHidden(account).contains(dialogId)

    fun toggleExtraHidden(account: Int, dialogId: Long): Boolean {
        val set = HashSet(getExtraHidden(account))
        val added = set.add(dialogId)
        if (!added) set.remove(dialogId)
        setExtraHidden(account, set)
        return added
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        return InuConfig.BLOCKED_MESSAGES_MODE.value != InuConfig.BlockedMessagesModeItem.OFF
    }

    @JvmStatic
    fun isHideMode(): Boolean {
        return InuConfig.BLOCKED_MESSAGES_MODE.value == InuConfig.BlockedMessagesModeItem.HIDE
    }

    private fun hasHideFilter(): Boolean {
        return isHideMode() || (
            RegexFilterHelper.isEnabled() &&
                RegexFilterHelper.getMode() == InuConfig.RegexFilterModeItem.HIDE
            ) ||
            InuConfig.HIDE_GIFT_CARDS_IN_CHAT.value ||
            InuConfig.HIDE_GIVEAWAYS.value ||
            InuConfig.HIDE_CHANNEL_RECOMMENDATIONS.value
    }

    // entiny: similar channels card is synthetic TYPE_JOINED_CHANNEL; drop list-side instead of patching cell
    private fun isChannelRecommendations(messageObject: MessageObject?): Boolean =
        messageObject?.type == MessageObject.TYPE_JOINED_CHANNEL

    // entiny: gift messages render as ChatActionCell; hide at list level like blocked/regex messages
    private fun isGiftMessage(messageObject: MessageObject?): Boolean {
        val action = messageObject?.messageOwner?.action ?: return false
        return action is TLRPC.TL_messageActionStarGift ||
            action is TLRPC.TL_messageActionStarGiftUnique ||
            action is TLRPC.TL_messageActionStarGiftPurchaseOffer ||
            action is TLRPC.TL_messageActionGiftStars ||
            action is TLRPC.TL_messageActionGiftTon ||
            action is TLRPC.TL_messageActionGiftPremium ||
            action is TLRPC.TL_messageActionGiftCode
    }

    @JvmStatic
    fun ensureBlockedPeersLoaded(currentAccount: Int) {
        if (!isEnabled()) return
        val controller = MessagesController.getInstance(currentAccount)
        if (controller.totalBlockedCount == -1 && !controller.loadingBlockedPeers) {
            controller.getBlockedPeers(true)
        }
    }

    @JvmStatic
    fun shouldHide(messageObject: MessageObject?): Boolean {
        if (isHideMode() && isBlockedMessage(messageObject)) return true
        if (RegexFilterHelper.isEnabled() && RegexFilterHelper.getMode() == InuConfig.RegexFilterModeItem.HIDE) {
            if (isRegexFilteredMessage(messageObject)) return true
        }
        if (InuConfig.HIDE_GIFT_CARDS_IN_CHAT.value && isGiftMessage(messageObject)) return true
        if (InuConfig.HIDE_GIVEAWAYS.value && messageObject?.isGiveawayOrGiveawayResults() == true) return true
        if (InuConfig.HIDE_CHANNEL_RECOMMENDATIONS.value && isChannelRecommendations(messageObject)) return true
        return false
    }

    @JvmStatic
    fun shouldSpoil(messageObject: MessageObject?): Boolean {
        if (InuConfig.BLOCKED_MESSAGES_MODE.value == InuConfig.BlockedMessagesModeItem.SPOILER && isBlockedMessage(messageObject)) return true
        if (RegexFilterHelper.isEnabled() && RegexFilterHelper.getMode() == InuConfig.RegexFilterModeItem.SPOILER) {
            if (isRegexFilteredMessage(messageObject)) return true
        }
        return false
    }

    @JvmStatic
    fun checkBlockedEntities(
        messageObject: MessageObject?,
        original: ArrayList<TLRPC.MessageEntity>?,
    ): ArrayList<TLRPC.MessageEntity>? {
        val text = messageObject?.messageOwner?.message
        if (!shouldSpoil(messageObject) || text.isNullOrEmpty()) return original

        val entities = if (original != null) ArrayList(original) else ArrayList()
        val spoiler = TLRPC.TL_messageEntitySpoiler()
        spoiler.offset = 0
        spoiler.length = text.length
        entities.add(spoiler)

        return entities
    }

    @JvmStatic
    fun checkBlockedEntities(messageObject: MessageObject?): ArrayList<TLRPC.MessageEntity>? {
        return checkBlockedEntities(messageObject, messageObject?.messageOwner?.entities)
    }

    private class VisibleState {
        var stale = true
        var source: List<MessageObject>? = null
        var size = -1
        var epoch = -1
        var config = -1
    }

    private val visibleStates = java.util.WeakHashMap<ChatActivity.ChatActivityAdapter, VisibleState>()

    @Volatile
    private var epoch = 0

    @JvmStatic
    fun invalidateVisible() {
        epoch++
    }

    // entiny: filters changed while a chat is open underneath, so force the adapters to relayout
    fun refreshOpenChats() {
        epoch++
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            for (adapter in ArrayList(visibleStates.keys)) adapter.notifyDataSetChanged()
        }
    }

    private fun configStamp(): Int =
        (if (isHideMode()) 1 else 0) or
            (if (RegexFilterHelper.isEnabled() && RegexFilterHelper.getMode() == InuConfig.RegexFilterModeItem.HIDE) 2 else 0) or
            (if (InuConfig.HIDE_GIFT_CARDS_IN_CHAT.value) 4 else 0) or
            (if (InuConfig.HIDE_GIVEAWAYS.value) 8 else 0) or
            (if (InuConfig.HIDE_CHANNEL_RECOMMENDATIONS.value) 16 else 0) or
            (if (InuConfig.REGEX_FILTER_HIDE_REPLIES.value) 32 else 0)

    private fun stateFor(adapter: ChatActivity.ChatActivityAdapter): VisibleState =
        visibleStates.getOrPut(adapter) {
            val state = VisibleState()
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { state.stale = true }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { state.stale = true }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { state.stale = true }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { state.stale = true }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { state.stale = true }
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { state.stale = true }
            })
            state
        }

    private val manualHidden = HashMap<String, HashSet<Int>>()

    private fun manualKey(msg: MessageObject) = "${msg.currentAccount}:${msg.dialogId}"

    private fun isManuallyHidden(msg: MessageObject): Boolean =
        !msg.isDateObject && manualHidden[manualKey(msg)]?.contains(msg.id) == true

    fun hideManually(adapter: ChatActivity.ChatActivityAdapter, messages: Collection<MessageObject>) {
        for (msg in messages) manualHidden.getOrPut(manualKey(msg)) { HashSet() }.add(msg.id)
        epoch++
        adapter.notifyDataSetChanged()
    }

    @JvmStatic
    fun refreshVisible(adapter: ChatActivity.ChatActivityAdapter): ArrayList<MessageObject> {
        val source = adapter.inu_getSourceMessages()
        val state = stateFor(adapter)
        if (!hasHideFilter() && manualHidden.isEmpty()) return source
        val buffer = adapter.inu_visibleMessages
        // entiny: getMessages() runs per adapter query, so rebuild only after the data actually changed
        val config = configStamp()
        if (!state.stale && state.source === source && state.size == source.size && state.epoch == epoch && state.config == config) return buffer
        buffer.clear()
        val activeDays = HashSet<Int>()
        val hidden = BooleanArray(source.size)
        var hiddenGroups: HashSet<Long>? = null
        val hideReplies = InuConfig.REGEX_FILTER_HIDE_REPLIES.value && RegexFilterHelper.isEnabled() &&
            RegexFilterHelper.getMode() == InuConfig.RegexFilterModeItem.HIDE
        val hiddenIds = if (hideReplies) HashSet<Int>() else null
        // entiny: oldest first so a reply to a hidden reply is hidden too
        for (i in source.size - 1 downTo 0) {
            val msg = source[i] ?: continue
            var hide = shouldHide(msg) || isManuallyHidden(msg)
            if (hiddenIds != null) {
                if (hide) {
                    if (RegexFilterHelper.isMessageFiltered(msg)) hiddenIds.add(msg.id)
                } else {
                    val replyId = msg.messageOwner?.reply_to?.reply_to_msg_id ?: 0
                    if (replyId != 0 && hiddenIds.contains(replyId)) {
                        hide = true
                        hiddenIds.add(msg.id)
                    }
                }
            }
            hidden[i] = hide
            // entiny: one hidden album member hides the whole album, otherwise the rest render as loose photos
            if (hide && msg.groupId != 0L) (hiddenGroups ?: HashSet<Long>().also { hiddenGroups = it }).add(msg.groupId)
        }
        for (i in 0 until source.size) {
            val msg = source[i]
            if (msg != null && !hidden[i] && (hiddenGroups == null || msg.groupId == 0L || !hiddenGroups!!.contains(msg.groupId))) {
                buffer.add(msg)
                if (!msg.isDateObject && msg.contentType != 2) activeDays.add(msg.dateKeyInt)
            }
        }
        // entiny: single reverse pass replaces O(n^2) nested scans for dropping empty date headers
        var hasMessageAfter = false
        for (i in buffer.indices.reversed()) {
            val msg = buffer[i]
            if (msg.isDateObject) {
                if (!activeDays.contains(msg.dateKeyInt)) buffer.removeAt(i)
            } else if (msg.contentType == 2) {
                if (!hasMessageAfter) buffer.removeAt(i)
            } else {
                hasMessageAfter = true
            }
        }
        state.source = source
        state.size = source.size
        state.epoch = epoch
        state.config = config
        state.stale = false
        return buffer
    }

    @JvmStatic
    fun filterGroup(group: MessageObject.GroupedMessages?): MessageObject.GroupedMessages? {
        if (group == null || !hasHideFilter()) return group
        for (i in 0 until group.messages.size) {
            if (shouldHide(group.messages[i])) return null
        }
        return group
    }

    private fun isBlockedMessage(messageObject: MessageObject?): Boolean {
        if (messageObject?.messageOwner == null || messageObject.storyItem != null) return false
        // entiny: forwarded channel post carries peer id as both fromChatId and fwd_from.from_id; don't blackout real announcements
        if (messageObject.isForwardedChannelPost) return false
        if (isBlockedPeer(messageObject.currentAccount, messageObject.fromChatId)) return true
        val forwardedFrom = messageObject.messageOwner.fwd_from?.from_id ?: return false
        return isBlockedPeer(messageObject.currentAccount, MessageObject.getPeerId(forwardedFrom))
    }

    private fun isRegexFilteredMessage(messageObject: MessageObject?): Boolean {
        return RegexFilterHelper.isMessageFiltered(messageObject)
    }

    private fun isBlockedPeer(currentAccount: Int, peerId: Long): Boolean {
        if (isExtraHidden(currentAccount, peerId)) return true
        val controller = MessagesController.getInstance(currentAccount)
        if (peerId > 0 && controller.getUser(peerId)?.bot == true) return false
        val userFull = if (peerId > 0) controller.getUserFull(peerId) else null
        return userFull?.blocked == true || controller.blockePeers.indexOfKey(peerId) >= 0
    }
}
