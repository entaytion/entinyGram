package desu.inugram.helpers.security

import android.os.SystemClock
import desu.inugram.InuConfig
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.util.concurrent.ConcurrentHashMap

object GhostScheduleHelper {
    private const val AHEAD = 12

    // entiny: the server sends nothing scheduled closer than this
    private const val MIN = 10
    private const val QUIET_MS = 5000L

    private val scheduledAt = ConcurrentHashMap<Long, Long>()

    private fun suits(account: Int, peer: Long): Boolean {
        if (!InuConfig.GHOST_SCHEDULE_SEND.value || peer == 0L || DialogObject.isEncryptedDialog(peer)) return false
        if (!GhostHelper.isGhostActiveForDialog(peer)) return false
        // entiny: the server refuses scheduled messages to bots
        if (peer > 0 && MessagesController.getInstance(account).getUser(peer)?.bot == true) return false
        return true
    }

    private fun date(account: Int, peer: Long): Int {
        scheduledAt[peer] = SystemClock.elapsedRealtime()
        return ConnectionsManager.getInstance(account).currentTime + AHEAD
    }

    @JvmStatic
    fun paramsDate(account: Int, p: SendMessagesHelper.SendMessageParams): Int {
        if (p.scheduleDate != 0 || p.retryMessageObject != null || p.scheduleRepeatPeriod != 0) return p.scheduleDate
        if (p.quick_reply_shortcut != null || p.quick_reply_shortcut_id != 0 || p.replyToStoryItem != null || p.sendingStory != null) return 0
        if (p.payStars != 0L || p.stars != 0L || p.monoForumPeer != 0L || p.ephemeralReceiverBotId != 0L) return 0
        if (p.invoice != null || p.game != null || p.dice_stake != 0L) return 0
        return if (suits(account, p.peer)) date(account, p.peer) else 0
    }

    @JvmStatic
    fun forwardDate(
        account: Int,
        peer: Long,
        scheduleDate: Int,
        repeat: Int,
        payStars: Long,
        monoForumPeer: Long,
        suggestion: MessageSuggestionParams?,
    ): Int {
        if (scheduleDate != 0 || repeat != 0 || payStars != 0L || monoForumPeer != 0L || suggestion != null) return scheduleDate
        return if (suits(account, peer)) date(account, peer) else 0
    }

    @JvmStatic
    fun justScheduled(dialogId: Long): Boolean {
        val at = scheduledAt[dialogId] ?: return false
        return SystemClock.elapsedRealtime() - at < QUIET_MS
    }

    // entiny: a message waits for its media upload or a retry, so its time can pass; move it ahead again
    @JvmStatic
    fun keepAhead(account: Int, request: TLObject?, scheduled: Boolean) {
        if (!scheduled || !InuConfig.GHOST_SCHEDULE_SEND.value || request == null) return
        val now = ConnectionsManager.getInstance(account).currentTime
        val min = now + MIN
        val ahead = now + AHEAD
        when (request) {
            is TLRPC.TL_messages_sendMessage -> if (request.schedule_date != 0 && request.schedule_date < min) request.schedule_date = ahead
            is TLRPC.TL_messages_sendMedia -> if (request.schedule_date != 0 && request.schedule_date < min) request.schedule_date = ahead
            is TLRPC.TL_messages_sendMultiMedia -> if (request.schedule_date != 0 && request.schedule_date < min) request.schedule_date = ahead
            is TLRPC.TL_messages_forwardMessages -> if (request.schedule_date != 0 && request.schedule_date < min) request.schedule_date = ahead
            is TLRPC.TL_messages_sendInlineBotResult -> if (request.schedule_date != 0 && request.schedule_date < min) request.schedule_date = ahead
        }
    }
}
