package desu.inugram.helpers.chat

import androidx.core.content.edit
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
            )
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

    @JvmStatic
    fun refreshVisible(adapter: ChatActivity.ChatActivityAdapter): ArrayList<MessageObject> {
        val source = adapter.inu_getSourceMessages()
        if (!hasHideFilter()) return source
        val buffer = adapter.inu_visibleMessages
        buffer.clear()
        val activeDays = HashSet<Int>()
        for (i in 0 until source.size) {
            val msg = source[i]
            if (msg != null && !shouldHide(msg)) {
                buffer.add(msg)
                if (!msg.isDateObject && msg.contentType != 2) activeDays.add(msg.dateKeyInt)
            }
        }
        // single reverse pass: drop date headers with no message left in their day, and drop the
        // unread divider if nothing real follows it — replaces the previous O(n^2) nested scans.
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
