package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import desu.inugram.helpers.security.GhostHelper
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object UnreadFirstHelper {
    @JvmStatic
    fun compare(controller: MessagesController, a: TLRPC.Dialog, b: TLRPC.Dialog): Int {
        if (!InuConfig.UNREAD_CHATS_FIRST.value) return 0
        val unreadA = isUnread(controller, a)
        val unreadB = isUnread(controller, b)
        return if (unreadA == unreadB) 0 else if (unreadA) -1 else 1
    }

    // entiny: muted chats keep their place, only their mentions count
    private fun isUnread(controller: MessagesController, d: TLRPC.Dialog): Boolean {
        if (d.unread_mark || GhostHelper.effectiveMentions(d) > 0) return true
        return controller.getDialogUnreadCount(d) > 0 && !controller.isDialogMuted(d.id, 0)
    }

    @JvmStatic
    fun refresh() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(account).isClientActivated) continue
            MessagesController.getInstance(account).sortDialogs(null)
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        }
    }
}
