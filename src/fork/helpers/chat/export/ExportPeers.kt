package desu.inugram.helpers.chat.export

import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC

internal object ExportPeers {
    internal fun peerId(peer: TLRPC.Peer): Long = when (peer) {
        is TLRPC.TL_peerUser -> peer.user_id
        is TLRPC.TL_peerChat -> peer.chat_id
        is TLRPC.TL_peerChannel -> peer.channel_id
        else -> 0
    }

    internal fun displayName(controller: MessagesController, peer: TLRPC.Peer): String {
        return when (peer) {
            is TLRPC.TL_peerUser -> {
                val user = controller.getUser(peer.user_id)
                if (user != null && !UserObject.isDeleted(user)) UserObject.getUserName(user)
                else LocaleController.getString(R.string.HiddenName)
            }
            is TLRPC.TL_peerChat, is TLRPC.TL_peerChannel -> {
                val id = peerId(peer)
                controller.getChat(id)?.title ?: "ID $id"
            }
            else -> "ID ${peerId(peer)}"
        }
    }

    internal fun dialogTitle(controller: MessagesController, dialogId: Long): String {
        if (dialogId > 0) {
            val user = controller.getUser(dialogId)
            if (user != null) return UserObject.getUserName(user)
        } else {
            controller.getChat(-dialogId)?.title?.let { return it }
        }
        return dialogId.toString()
    }
}
