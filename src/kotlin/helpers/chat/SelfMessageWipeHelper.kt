package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory

/**
 * Bulk-deletes every message the current account sent in a dialog: pages backwards through
 * messages.search filtered to fromId=self, deleting each page as it comes in. Reuses
 * MessagesController.deleteMessages, which already chunks batches over the server's 100-id limit.
 */
object SelfMessageWipeHelper {

    private const val PAGE_SIZE = 100

    @JvmStatic
    fun confirmAndDelete(fragment: BaseFragment, currentAccount: Int, dialogId: Long) {
        val context = fragment.parentActivity ?: return
        AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuDeleteMyMessagesTitle))
            .setMessage(LocaleController.getString(R.string.InuDeleteMyMessagesConfirm))
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                start(fragment, currentAccount, dialogId)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun start(fragment: BaseFragment, currentAccount: Int, dialogId: Long) {
        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.InuDeleteMyMessagesStarted))
            .show()
        deletedSoFar.remove(dialogId)
        searchNextPage(fragment, currentAccount, dialogId, 0)
    }

    private val deletedSoFar = HashMap<Long, Int>()

    private fun searchNextPage(fragment: BaseFragment, currentAccount: Int, dialogId: Long, offsetId: Int) {
        val controller = MessagesController.getInstance(currentAccount)
        val peer = controller.getInputPeer(dialogId)
        if (peer == null) {
            finish(fragment, dialogId)
            return
        }
        val req = TLRPC.TL_messages_search()
        req.peer = peer
        req.q = ""
        req.from_id = TLRPC.TL_inputPeerSelf()
        req.filter = TLRPC.TL_inputMessagesFilterEmpty()
        req.offset_id = offsetId
        req.limit = PAGE_SIZE

        ConnectionsManager.getInstance(currentAccount).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (error != null || response !is TLRPC.messages_Messages) {
                    FileLog.e("SelfMessageWipeHelper: search failed for $dialogId: ${error?.text}")
                    finish(fragment, dialogId)
                    return@runOnUIThread
                }
                controller.putUsers(response.users, false)
                controller.putChats(response.chats, false)

                val ids = ArrayList<Int>()
                var minId = Int.MAX_VALUE
                for (message in response.messages) {
                    if (message is TLRPC.TL_messageEmpty || message.action is TLRPC.TL_messageActionHistoryClear) continue
                    ids.add(message.id)
                    if (message.id < minId) minId = message.id
                }

                if (ids.isEmpty()) {
                    finish(fragment, dialogId)
                    return@runOnUIThread
                }

                deletedSoFar[dialogId] = (deletedSoFar[dialogId] ?: 0) + ids.size
                controller.deleteMessages(ids, null, null, dialogId, 0, true, ChatActivity.MODE_DEFAULT)

                if (response.messages.size < PAGE_SIZE) {
                    finish(fragment, dialogId)
                } else {
                    searchNextPage(fragment, currentAccount, dialogId, minId)
                }
            }
        }
    }

    private fun finish(fragment: BaseFragment, dialogId: Long) {
        val count = deletedSoFar.remove(dialogId) ?: 0
        if (count > 0) {
            BulletinFactory.of(fragment)
                .createSimpleBulletin(
                    R.raw.ic_delete,
                    LocaleController.formatString("InuDeleteMyMessagesDone", R.string.InuDeleteMyMessagesDone, count),
                )
                .show()
        }
    }
}
