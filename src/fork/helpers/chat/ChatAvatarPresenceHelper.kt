package desu.inugram.helpers.chat

import android.graphics.Canvas
import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.PresenceColorHelper
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.IMessageCell

object ChatAvatarPresenceHelper {
    @JvmStatic
    fun draw(canvas: Canvas, avatar: ImageReceiver, cell: IMessageCell) {
        if (!InuConfig.CHAT_PRESENCE_DOTS.value || !avatar.visible) return
        val message = cell.messageObject ?: return
        val userId = message.fromChatId.takeIf { it > 0 } ?: return
        val account = message.currentAccount
        val user = MessagesController.getInstance(account).getUser(userId) ?: return
        if (user.self || user.bot || user.deleted) return
        val online = user.status != null && user.status.expires > ConnectionsManager.getInstance(account).currentTime
        val color = if (InuConfig.PRESENCE_COLOR_DOTS.value) {
            PresenceColorHelper.colorFor(user.status, online)
        } else if (online) {
            Theme.getColor(Theme.key_chats_onlineCircle)
        } else {
            0
        }
        if (color == 0) return
        val cx = avatar.imageX2 - dp(3f)
        val cy = avatar.imageY2 - dp(3f)
        val paint = Theme.dialogs_onlineCirclePaint
        val alpha = (avatar.alpha * 255).toInt().coerceIn(0, 255)
        paint.color = Theme.getColor(if (cell is ChatMessageCell) Theme.key_chat_inBubble else Theme.key_windowBackgroundWhite)
        paint.alpha = alpha
        canvas.drawCircle(cx, cy, dp(6f).toFloat(), paint)
        paint.color = color
        paint.alpha = alpha
        canvas.drawCircle(cx, cy, dp(4.5f).toFloat(), paint)
    }
}
