package desu.inugram.helpers.chat

import desu.inugram.helpers.DebugLogUtils
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.BaseCell
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.ChatMessageCell

object ReactionDebugHelper {
    private var staleUnreadLoggedId = 0

    @JvmStatic
    fun isEnabled(): Boolean = DebugLogUtils.isEnabled()

    @JvmStatic
    fun caller(): String = DebugLogUtils.getCaller()

    @JvmStatic
    fun describe(messageObject: MessageObject?): String {
        val message = messageObject?.messageOwner ?: return "null"
        return "did=${messageObject.dialogId} mid=${message.id} group=${message.grouped_id} ${describe(message.reactions)}"
    }

    @JvmStatic
    fun describe(reactions: TLRPC.MessageReactions?): String {
        if (reactions == null) return "reactions=null"
        val results = reactions.results.joinToString(",") { "${describe(it.reaction)}x${it.count}${if (it.chosen) "*" else ""}" }
        val recent = reactions.recent_reactions.joinToString(",") {
            "${describe(it.reaction)}@${MessageObject.getPeerId(it.peer_id)}/${it.date}${if (it.unread) "!" else ""}"
        }
        return "min=${reactions.min} results=[$results] recent=[$recent]"
    }

    private fun describe(reaction: TLRPC.Reaction?): String = when (reaction) {
        is TLRPC.TL_reactionEmoji -> reaction.emoticon
        is TLRPC.TL_reactionCustomEmoji -> "custom${reaction.document_id}"
        null -> "null"
        else -> reaction.javaClass.simpleName
    }

    @JvmStatic
    fun onMessageObjectReplaced(old: MessageObject?, new: MessageObject) {
        if (!isEnabled() || old == null) return
        val oldDesc = describe(old)
        val newDesc = describe(new)
        if (oldDesc == newDesc) return
        FileLog.d("InuRx replaced object reactionsChanged=${new.reactionsChanged} old=$oldDesc new=$newDesc")
    }

    @JvmStatic
    fun describeCell(cell: BaseCell?): String {
        val (layout, messageObject) = when (cell) {
            is ChatMessageCell -> cell.reactionsLayoutInBubble to cell.messageObject
            is ChatActionCell -> cell.reactionsLayoutInBubble to cell.messageObject
            else -> return "cell=${cell?.javaClass?.simpleName}"
        }
        val primary = (cell as? ChatMessageCell)?.currentMessagesGroup?.findPrimaryMessageObject()
        return "cell=${cell.javaClass.simpleName} cellMid=${messageObject?.id} primaryMid=${primary?.id} layoutMid=${layout.messageObject?.id} layoutUnread=${layout.hasUnreadReactions} data=${describe(messageObject)}"
    }

    @JvmStatic
    fun onVisibleRead(classGuid: Int, cell: BaseCell, count: Int) {
        if (!isEnabled()) return
        FileLog.d("InuRx[$classGuid] visible read count=$count ${describeCell(cell)}")
    }

    @JvmStatic
    fun onReactionsLayoutKept(cell: ChatMessageCell, messageObject: MessageObject) {
        if (!isEnabled()) return
        val shown = cell.reactionsLayoutInBubble.messageObject
        val position = cell.currentPosition
        val expected = when {
            !messageObject.shouldDrawReactions() || messageObject.isExpiredStory -> null
            position == null -> messageObject
            position.flags and MessageObject.POSITION_FLAG_BOTTOM != 0 -> cell.currentMessagesGroup?.findPrimaryMessageObject()
            else -> null
        }
        if (shown === expected) return
        if (shown != null && expected != null && describe(shown.messageOwner?.reactions) == describe(expected.messageOwner?.reactions)) return
        FileLog.d("InuRx cell kept stale reactions cellMid=${messageObject.id} shown=${describe(shown)} expected=${describe(expected)}")
    }

    @JvmStatic
    fun checkStaleUnread(cell: ChatMessageCell, unreadReactionsCount: Int) {
        if (!isEnabled()) return
        val messageObject = cell.messageObject ?: return
        val unreadInData = unreadReactionsCount > 0 && MessageObject.hasUnreadReactions(messageObject.messageOwner)
        if (!unreadInData || cell.reactionsLayoutInBubble.hasUnreadReactions) {
            if (staleUnreadLoggedId == messageObject.id) staleUnreadLoggedId = 0
            return
        }
        if (staleUnreadLoggedId == messageObject.id) return
        staleUnreadLoggedId = messageObject.id
        FileLog.d("InuRx stale unread badge count=$unreadReactionsCount actual=${describe(messageObject)} shown=${describe(cell.reactionsLayoutInBubble.messageObject)}")
    }
}
