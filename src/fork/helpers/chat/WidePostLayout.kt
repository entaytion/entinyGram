package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.ui.Cells.ChatMessageCell

object WidePostLayout {
    private const val STATE_UNKNOWN: Byte = 0
    private const val STATE_NARROW: Byte = 1
    private const val STATE_WIDE: Byte = 2

    @JvmField
    var generation = 1

    @JvmStatic
    fun invalidate() {
        generation++
    }

    @JvmStatic
    fun isWide(m: MessageObject): Boolean {
        if (m.inu_wideGeneration != generation) {
            m.inu_wideGeneration = generation
            m.inu_wideState = STATE_UNKNOWN
        }
        if (m.inu_wideState == STATE_UNKNOWN) {
            val state = compute(m)
            if (state == STATE_UNKNOWN) return false
            m.inu_wideState = state
        }
        return m.inu_wideState == STATE_WIDE
    }

    private fun compute(m: MessageObject): Byte {
        if (!InuConfig.WIDE_CHANNEL_POSTS.value) return STATE_NARROW
        val owner = m.messageOwner ?: return STATE_NARROW
        val peer = owner.peer_id ?: return STATE_NARROW
        if (peer.channel_id == 0L || owner.action != null || m.eventId != 0L || m.preview || m.sendPreview
            || m.previewForward || m.isRepostPreview || m.isSponsored || m.isCompactMode || m.isOutOwner
        ) return STATE_NARROW
        val chat = MessagesController.getInstance(m.currentAccount).getChat(peer.channel_id) ?: return STATE_UNKNOWN
        return if (ChatObject.isChannelAndNotMegaGroup(chat)) STATE_WIDE else STATE_NARROW
    }

    @JvmStatic
    fun layoutKey(m: MessageObject): Int = if (m.inu_isWide()) dp(inset()) + 1 else 0

    private fun inset(): Float = InuConfig.WIDE_CHANNEL_POSTS_INSET.value

    @JvmStatic
    fun bubbleWidth(m: MessageObject): Int = maxOf(dp(48f), m.getParentWidth() - dp(3f) - dp(inset()))

    @JvmStatic
    fun textWidth(m: MessageObject): Int = bubbleWidth(m) - dp(31f)

    @JvmStatic
    fun photoWidth(m: MessageObject, mediaBackground: Boolean): Int =
        bubbleWidth(m) - dp(if (mediaBackground) 14f else 17f)

    @JvmStatic
    fun isStretchableMedia(m: MessageObject): Boolean = when (m.type) {
        MessageObject.TYPE_PHOTO, MessageObject.TYPE_VIDEO, MessageObject.TYPE_GIF,
        MessageObject.TYPE_EXTENDED_MEDIA_PREVIEW -> !m.needDrawBluredPreview() || m.hasExtendedMediaPreview()
        else -> false
    }

    @JvmStatic
    fun stretchBubble(m: MessageObject, current: Int): Int {
        if (m.isAnyKindOfSticker || m.isAnimatedEmoji || m.isDice || m.isRoundVideo) return current
        when (m.type) {
            MessageObject.TYPE_PHOTO, MessageObject.TYPE_VIDEO, MessageObject.TYPE_GIF,
            MessageObject.TYPE_EXTENDED_MEDIA_PREVIEW, MessageObject.TYPE_STORY, MessageObject.TYPE_STORY_MENTION -> return current
        }
        return maxOf(current, bubbleWidth(m))
    }

    @JvmStatic
    fun groupSpan(): Int {
        val width = if (AndroidUtilities.isTablet()) AndroidUtilities.getMinTabletSide() else AndroidUtilities.displaySize.x
        if (width <= 0) return 800
        return (1000f * (width - dp(inset())) / width).toInt().coerceIn(500, 1000)
    }

    @JvmStatic
    fun groupKey(group: MessageObject.GroupedMessages): Int =
        if (group.messages.isEmpty()) 0 else layoutKey(group.messages[0])

    @JvmStatic
    fun ensureGroup(group: MessageObject.GroupedMessages, cell: ChatMessageCell): Boolean {
        if (group.inu_wideKey != groupKey(group)) group.calculate()
        if (cell.inu_wideGroupKey == group.inu_wideKey) return false
        cell.inu_wideGroupKey = group.inu_wideKey
        return true
    }
}
