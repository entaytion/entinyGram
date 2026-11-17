package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

internal object MessageSearchPager {

    fun searchPage(
        account: Int,
        dialogId: Long,
        offsetId: Int,
        limit: Int,
        logTag: String,
        configure: (TLRPC.TL_messages_search) -> Unit,
        onEnd: () -> Unit,
        onPage: (TLRPC.messages_Messages) -> Unit,
    ) {
        val controller = MessagesController.getInstance(account)
        val peer = controller.getInputPeer(dialogId)
        if (peer == null) {
            onEnd()
            return
        }
        val req = TLRPC.TL_messages_search()
        req.peer = peer
        req.q = ""
        req.filter = TLRPC.TL_inputMessagesFilterEmpty()
        req.offset_id = offsetId
        req.limit = limit
        configure(req)
        ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (error != null || response !is TLRPC.messages_Messages) {
                    FileLog.e("$logTag: search failed for $dialogId: ${error?.text}")
                    onEnd()
                    return@runOnUIThread
                }
                controller.putUsers(response.users, false)
                controller.putChats(response.chats, false)
                onPage(response)
            }
        }
    }
}
