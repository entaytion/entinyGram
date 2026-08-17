package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Cells.ChatMessageCell

@SuppressLint("ViewConstructor")
class DeletedMessagePreviewCell(context: Context?, fragment: BaseFragment) :
    MessagesPreviewCell(context, fragment, buildMessages()) {

    init {
        // Ensure child ChatMessageCell is configured as chat message with avatar & name
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ChatMessageCell) {
                child.isChat = true
                if (InuConfig.DELETED_MESSAGES_TRANSPARENT.value) {
                    child.alpha = 0.65f
                }
            }
        }
    }

    override fun invalidate() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ChatMessageCell) {
                child.alpha = if (InuConfig.DELETED_MESSAGES_TRANSPARENT.value) 0.65f else 1.0f
            }
        }
        super.invalidate()
    }

    companion object {
        private const val MOCK_USER_ID = 888888L

        private fun buildMessages(): Array<MessageObject> {
            val account = UserConfig.selectedAccount
            // Preview with the current account's real avatar and name instead of a placeholder.
            val sender = UserConfig.getInstance(account).currentUser ?: TLRPC.TL_user().apply {
                id = MOCK_USER_ID
                first_name = getString(R.string.InuDeletedMarkPreviewSender)
            }
            MessagesController.getInstance(account).putUser(sender, true)

            val now = (System.currentTimeMillis() / 1000).toInt()
            val deletedTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuDeletedMarkPreviewText)
                date = now
                dialog_id = -1001234567890L
                flags = 259
                id = 1
                from_id = TLRPC.TL_peerUser().apply { user_id = sender.id }
                peer_id = TLRPC.TL_peerChannel().apply { channel_id = 1234567890L }
                media = TLRPC.TL_messageMediaEmpty()
                out = false
            }

            val msgObj = MessageObject(account, deletedTlMessage, true, false).apply {
                deleted = true
            }

            return arrayOf(msgObj)
        }
    }
}
