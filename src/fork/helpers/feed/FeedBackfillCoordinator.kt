package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

internal class FeedBackfillCoordinator(
    private val currentAccount: Int,
    private val onRoundFinished: (Boolean) -> Unit,
) {
    private var guid = 0
    private val pending = HashSet<Long>()
    private val exhausted = HashSet<Long>()
    private var loadIndex = 0
    private var failed = false
    private var timeout: Runnable? = null

    fun cancel() {
        timeout?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        timeout = null
        val cancelledGuid = guid
        guid = 0
        pending.clear()
        if (cancelledGuid != 0) ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(cancelledGuid)
    }

    private fun finishRound(success: Boolean) {
        cancel()
        onRoundFinished(success)
    }

    private fun onResult(dialogId: Long) {
        pending.remove(dialogId)
        if (pending.isEmpty()) finishRound(!failed)
    }

    fun clearExhausted() {
        exhausted.clear()
    }

    fun getExhaustedSnapshot(): HashSet<Long> = HashSet(exhausted)

    fun onLoadingMessagesFailed(args: Array<out Any?>) {
        if (guid == 0 || args.getOrNull(0) != guid) return
        val request = args.getOrNull(1) as? TLRPC.TL_messages_getHistory ?: return
        val peer = request.peer ?: return
        val dialogId = -(if (peer.channel_id != 0L) peer.channel_id else peer.chat_id)
        if (dialogId !in pending) return
        failed = true
        onResult(dialogId)
    }

    fun onMessagesDidLoad(args: Array<out Any?>) {
        if (guid == 0 || args.getOrNull(10) != guid) return
        val dialogId = args.getOrNull(0) as? Long ?: return
        if (dialogId !in pending) return
        val messages = args.getOrNull(2) as? ArrayList<*> ?: return
        if (messages.size < MESSAGES_PER_CHANNEL) exhausted.add(dialogId)
        onResult(dialogId)
    }

    fun startRound(candidates: ArrayList<LongArray>) {
        cancel()
        failed = false
        guid = ConnectionsManager.generateClassGuid()
        val startedGuid = guid
        val batch = candidates.distinctBy { it[0] }.take(MAX_CHANNELS_PER_ROUND)
        pending.addAll(batch.map { it[0] })
        if (pending.isEmpty()) {
            finishRound(true)
            return
        }
        timeout = Runnable {
            if (guid == startedGuid) finishRound(false)
        }.also { AndroidUtilities.runOnUIThread(it, ROUND_TIMEOUT_MS) }
        val messagesController = MessagesController.getInstance(currentAccount)
        for (candidate in batch) {
            messagesController.loadMessages(
                candidate[0], 0L, false, MESSAGES_PER_CHANNEL, candidate[1].toInt(), 0, false, 0,
                startedGuid, 0, 0, 0, 0L, 0, loadIndex++, false
            )
        }
    }

    companion object {
        private const val MAX_CHANNELS_PER_ROUND = 4
        private const val MESSAGES_PER_CHANNEL = 20
        private const val ROUND_TIMEOUT_MS = 10000L
    }
}
