package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import desu.inugram.helpers.archive.DeletedMessagesHelper
import org.telegram.messenger.AndroidUtilities
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
        refresh()
    }

    fun refresh() {
        val msgs = buildMessages()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ChatMessageCell) {
                child.isChat = true
                child.setMessageObject(msgs[i], null, false, false, false)
                if (child.isAvatarVisible) child.setAvatar(msgs[i])
            }
        }
        invalidate()
    }

    // entiny: the avatar of a cell is drawn by its host list, here the preview is the host
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? ChatMessageCell ?: continue
            if (!child.isAvatarVisible) continue
            val receiver = child.avatarImage ?: continue
            val top = child.top + child.paddingTop
            var y = top + child.layoutHeight
            if (y > child.bottom) y = child.bottom
            if (y - AndroidUtilities.dp(48f) < top) y = top + AndroidUtilities.dp(48f)
            receiver.setImageY((y - AndroidUtilities.dp(44f)).toFloat())
            receiver.setAlpha(1f)
            receiver.setVisible(true, false)
            receiver.draw(canvas)
        }
    }

    companion object {
        private const val MOCK_USER_ID = 888888L

        private fun buildMessages(): Array<MessageObject> {
            val account = UserConfig.selectedAccount
            val controller = MessagesController.getInstance(account)
            controller.putUser(TLRPC.TL_user().apply {
                id = MOCK_USER_ID
                first_name = getString(R.string.InuDeletedMarkPreviewSender)
                status = TLRPC.TL_userStatusRecently()
            }, true)

            val now = (System.currentTimeMillis() / 1000).toInt()
            val deletedTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuDeletedMarkPreviewText)
                date = now
                dialog_id = -1001234567890L
                flags = 259
                id = 1
                from_id = TLRPC.TL_peerUser().apply { user_id = MOCK_USER_ID }
                peer_id = TLRPC.TL_peerChannel().apply { channel_id = 1234567890L }
                media = TLRPC.TL_messageMediaEmpty()
                out = false
            }

            val msgObj = MessageObject(account, deletedTlMessage, true, false).apply {
                deleted = true
                forceAvatar = true
            }
            DeletedMessagesHelper.markAsPreview(msgObj)
            return arrayOf(msgObj)
        }
    }
}
