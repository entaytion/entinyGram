package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

internal class FeedBackfillCoordinator(
    private val currentAccount: Int,
    private val onRoundFinished: Runnable,
) {
    private val guid = ConnectionsManager.generateClassGuid()
    private val pending = HashSet<Long>()
    private val exhausted = HashSet<Long>()

    private var loadIndex = 0
    private var roundId = 0
    private var running = false

    private fun finishRound() {
        running = false
        roundId++
        pending.clear()
        onRoundFinished.run()
    }

    private fun onRoundTimeout(startedRoundId: Int) {
        if (startedRoundId == roundId && running) {
            exhausted.addAll(pending)
            finishRound()
        }
    }

    private fun onResult(dialogId: Long) {
        if (running && pending.remove(dialogId) && pending.isEmpty()) {
            finishRound()
        }
    }

    fun cancel() {
        running = false
        roundId++
        pending.clear()
        ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(guid)
    }

    fun clearExhausted() {
        exhausted.clear()
    }

    fun getExhaustedSnapshot(): HashSet<Long> = HashSet(exhausted)

    fun onLoadingMessagesFailed(args: Array<out Any?>) {
        if (args[0] as Int != guid) {
            return
        }
        var dialogId = 0L
        val request = args[1]
        if (request is TLRPC.TL_messages_getHistory) {
            val peer = request.peer
            if (peer != null) {
                val peerId = if (peer.channel_id != 0L) peer.channel_id else peer.chat_id
                dialogId = -peerId
            }
        }
        if (dialogId != 0L) {
            exhausted.add(dialogId)
        }
        onResult(dialogId)
    }

    fun onMessagesDidLoad(args: Array<out Any?>) {
        if (args[10] as Int != guid) {
            return
        }
        val dialogId = args[0] as Long
        if ((args[2] as ArrayList<*>).size < MESSAGES_PER_CHANNEL) {
            exhausted.add(dialogId)
        }
        onResult(dialogId)
    }

    fun startRound(candidates: ArrayList<LongArray>) {
        running = true
        val startedRoundId = ++roundId
        pending.clear()

        val channelCount = minOf(MAX_CHANNELS_PER_ROUND, candidates.size)
        for (i in 0 until channelCount) {
            pending.add(candidates[i][0])
        }

        val messagesController = MessagesController.getInstance(currentAccount)
        for (i in 0 until channelCount) {
            val dialogId = candidates[i][0]
            val maxId = candidates[i][1].toInt()
            messagesController.loadMessages(
                dialogId, 0L, false, MESSAGES_PER_CHANNEL, maxId, 0, false, 0,
                guid, 0, 0, 0, 0L, 0, loadIndex++, false
            )
        }

        AndroidUtilities.runOnUIThread({ onRoundTimeout(startedRoundId) }, ROUND_TIMEOUT_MS)
    }

    companion object {
        private const val MAX_CHANNELS_PER_ROUND = 4
        private const val MESSAGES_PER_CHANNEL = 20
        private const val ROUND_TIMEOUT_MS = 10000L
    }
}
