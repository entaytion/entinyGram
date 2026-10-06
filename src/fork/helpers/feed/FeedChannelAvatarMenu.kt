package desu.inugram.helpers.feed

import android.view.Gravity
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.ItemOptions
import java.util.function.Consumer

object FeedChannelAvatarMenu {

    private fun deleteFeedRows(chatActivity: ChatActivity, dialogId: Long, onRowsDeleted: Consumer<ArrayList<Int>>?) {
        val deletedIds = FeedController.getInstance(chatActivity.getCurrentAccount())
            .deleteHistory(dialogId, Int.MAX_VALUE)
        onRowsDeleted?.accept(deletedIds)
    }

    private fun leaveChannel(
        chatActivity: ChatActivity,
        chat: TLRPC.Chat,
        onLeft: Runnable?,
        onRowsDeleted: Consumer<ArrayList<Int>>?,
    ) {
        if (chatActivity.getParentActivity() == null) {
            return
        }
        AlertsCreator.createClearOrDeleteDialogAlert(chatActivity, false, chat, null, false, true, false, false, MessagesStorage.BooleanCallback { revoke ->
            val dialogId = -chat.id
            if (ChatObject.isNotInChat(chat)) {
                chatActivity.getMessagesController().deleteDialog(dialogId, 0, revoke)
            } else {
                chatActivity.getMessagesController().deleteParticipantFromChat(
                    chat.id,
                    chatActivity.getMessagesController().getUser(chatActivity.getUserConfig().getClientUserId()),
                    null,
                    revoke,
                    revoke
                )
            }
            deleteFeedRows(chatActivity, dialogId, onRowsDeleted)
            onLeft?.run()
        })
    }

    @JvmStatic
    fun show(
        chatActivity: ChatActivity?,
        cell: ChatMessageCell?,
        chat: TLRPC.Chat?,
        onOpenChat: Runnable?,
        onLeft: Runnable?,
        onRowsDeleted: Consumer<ArrayList<Int>>?,
    ) {
        if (chatActivity == null || cell == null || chat == null) {
            return
        }
        val canLeave = !chat.creator && !ChatObject.isNotInChat(chat)
        val isChannel = chat.broadcast
        ItemOptions.makeOptions(chatActivity, cell)
            .add(
                if (isChannel) R.drawable.msg_channel else R.drawable.msg_discussion,
                LocaleController.getString(if (isChannel) R.string.OpenChannel2 else R.string.OpenGroup2),
                onOpenChat
            )
            .add(
                R.drawable.menu_hide_gift,
                LocaleController.getString(R.string.InuFeedHideChannel),
                Runnable { chatActivity.hideFeedChannelWithUndo(-chat.id, chat.title) }
            )
            .addIf(
                canLeave,
                R.drawable.msg_leave,
                LocaleController.getString(if (isChannel) R.string.LeaveChannelMenu else R.string.LeaveMegaMenu),
                true,
                Runnable { leaveChannel(chatActivity, chat, onLeft, onRowsDeleted) }
            )
            .setDrawScrim(false)
            .setGravity(Gravity.LEFT)
            .forceBottom(true)
            .show()
    }
}
