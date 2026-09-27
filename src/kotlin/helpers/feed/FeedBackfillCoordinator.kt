package desu.inugram.helpers.feed

import android.util.Log
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

class FeedBackfillCoordinator private constructor(private val account: Int) {

    val listeners = LinkedHashSet<(Long, List<TLRPC.Message>) -> Unit>()
    private val inFlight = HashSet<Long>()
    private val pending = LinkedHashMap<Long, Int>()
    private var active = 0

    fun request(candidates: List<Pair<Long, Int>>) {
        for ((dialogId, offsetId) in candidates) {
            if (dialogId !in inFlight) pending[dialogId] = offsetId
        }
        pump()
    }

    private fun pump() {
        while (active < MAX_CONCURRENT && pending.isNotEmpty()) {
            val entry = pending.entries.first()
            val dialogId = entry.key
            val offsetId = entry.value
            pending.remove(dialogId)
            if (!inFlight.add(dialogId)) continue
            active++
            fetch(dialogId, offsetId)
        }
    }

    private fun fetch(dialogId: Long, offsetId: Int) {
        val peer = MessagesController.getInstance(account).getInputPeer(dialogId)
        if (peer == null) {
            finish(dialogId, emptyList())
            return
        }
        val request = TLRPC.TL_messages_getHistory().apply {
            this.peer = peer
            this.offset_id = offsetId
            limit = PAGE_SIZE
        }
        ConnectionsManager.getInstance(account).sendRequest(request) { response, error ->
            val messages = if (error == null && response is TLRPC.messages_Messages) {
                response.messages.filterNot { it is TLRPC.TL_messageEmpty }
            } else {
                if (error != null) Log.d(TAG, "history request failed for $dialogId: ${error.text}")
                emptyList()
            }
            AndroidUtilities.runOnUIThread { finish(dialogId, messages) }
        }
    }

    private fun finish(dialogId: Long, messages: List<TLRPC.Message>) {
        inFlight.remove(dialogId)
        active = (active - 1).coerceAtLeast(0)
        if (messages.isNotEmpty()) listeners.toList().forEach { it(dialogId, messages) }
        pump()
    }

    companion object {
        private const val TAG = "FeedBackfill"
        private const val PAGE_SIZE = 30
        private const val MAX_CONCURRENT = 4
        private val instances = HashMap<Int, FeedBackfillCoordinator>()

        @JvmStatic
        @Synchronized
        fun get(account: Int): FeedBackfillCoordinator = instances.getOrPut(account) { FeedBackfillCoordinator(account) }
    }
}
