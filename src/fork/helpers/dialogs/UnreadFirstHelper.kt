package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import desu.inugram.helpers.security.GhostHelper
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import java.text.Collator

object UnreadFirstHelper {
    @JvmStatic
    fun compare(controller: MessagesController, a: TLRPC.Dialog, b: TLRPC.Dialog): Int {
        if (!InuConfig.UNREAD_CHATS_FIRST.value) return 0
        val unreadA = isUnread(controller, a)
        val unreadB = isUnread(controller, b)
        if (unreadA != unreadB) return if (unreadA) -1 else 1
        if (!unreadA || !InuConfig.UNREAD_CHATS_ALPHABETICAL.value) return 0
        return collator.compare(title(controller, a.id), title(controller, b.id))
    }

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    private fun title(controller: MessagesController, dialogId: Long): String =
        if (dialogId > 0) controller.getUser(dialogId)?.let { UserObject.getUserName(it) } ?: ""
        else controller.getChat(-dialogId)?.title ?: ""

    @JvmStatic
    fun resortOnRead(): Boolean = InuConfig.UNREAD_CHATS_FIRST.value

    @JvmStatic
    fun isUnread(controller: MessagesController, d: TLRPC.Dialog): Boolean =
        d.unread_mark || GhostHelper.effectiveMentions(d) > 0 || controller.getDialogUnreadCount(d) > 0

    @JvmStatic
    fun refresh() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(account).isClientActivated) continue
            MessagesController.getInstance(account).sortDialogs(null)
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        }
    }
}
