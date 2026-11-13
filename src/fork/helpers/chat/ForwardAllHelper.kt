package desu.inugram.helpers.chat

import android.os.Bundle
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.DialogsActivity
import java.lang.ref.WeakReference

object ForwardAllHelper {

    private const val PAGE_SIZE = 100
    private const val CHUNK_DELAY_MS = 2500L

    @JvmStatic
    fun confirmAndPick(activity: ChatActivity) {
        val context = activity.parentActivity ?: return
        AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuForwardAllTitle))
            .setMessage(LocaleController.getString(R.string.InuForwardAllConfirm))
            .setPositiveButton(LocaleController.getString(R.string.Forward)) { _, _ -> pick(activity) }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun pick(activity: ChatActivity) {
        val args = Bundle().apply {
            putBoolean("onlySelect", true)
            putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
            putBoolean("canSelectTopics", true)
        }
        val fragment = DialogsActivity(args)
        fragment.setDelegate { dlg, dids, _, _, notify, _, _, _ ->
            if (dids.isEmpty()) return@setDelegate false
            val targets = dids.map { it.dialogId }
            dlg.finishFragment()
            start(WeakReference(activity), activity.currentAccount, activity.dialogId, activity.topicId.toInt(), targets, notify)
            true
        }
        activity.presentFragment(fragment)
    }

    private fun start(
        ref: WeakReference<ChatActivity>,
        account: Int,
        dialogId: Long,
        topicId: Int,
        targets: List<Long>,
        notify: Boolean,
    ) {
        ref.get()?.let {
            BulletinFactory.of(it)
                .createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.InuForwardAllStarted))
                .show()
        }
        collect(ref, account, dialogId, topicId, targets, notify, 0, ArrayList())
    }

    private fun collect(
        ref: WeakReference<ChatActivity>,
        account: Int,
        dialogId: Long,
        topicId: Int,
        targets: List<Long>,
        notify: Boolean,
        offsetId: Int,
        acc: ArrayList<MessageObject>,
    ) {
        val controller = MessagesController.getInstance(account)
        val peer = controller.getInputPeer(dialogId)
        if (peer == null) {
            send(ref, account, targets, notify, acc)
            return
        }
        val req = TLRPC.TL_messages_search()
        req.peer = peer
        req.q = ""
        req.filter = TLRPC.TL_inputMessagesFilterEmpty()
        req.offset_id = offsetId
        req.limit = PAGE_SIZE
        if (topicId != 0) {
            req.flags = req.flags or 2
            req.top_msg_id = topicId
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (error != null || response !is TLRPC.messages_Messages) {
                    FileLog.e("ForwardAllHelper: search failed for $dialogId: ${error?.text}")
                    send(ref, account, targets, notify, acc)
                    return@runOnUIThread
                }
                controller.putUsers(response.users, false)
                controller.putChats(response.chats, false)
                var minId = Int.MAX_VALUE
                for (message in response.messages) {
                    if (message.id < minId) minId = message.id
                    if (message is TLRPC.TL_messageEmpty || message is TLRPC.TL_messageService) continue
                    val obj = MessageObject(account, message, false, false)
                    if (obj.canForwardMessage()) acc.add(obj)
                }
                if (response.messages.size < PAGE_SIZE || minId == Int.MAX_VALUE) {
                    send(ref, account, targets, notify, acc)
                } else {
                    collect(ref, account, dialogId, topicId, targets, notify, minId, acc)
                }
            }
        }
    }

    private fun send(
        ref: WeakReference<ChatActivity>,
        account: Int,
        targets: List<Long>,
        notify: Boolean,
        collected: ArrayList<MessageObject>,
    ) {
        collected.reverse()
        val chunks = collected.chunked(PAGE_SIZE)
        val helper = SendMessagesHelper.getInstance(account)
        fun step(index: Int) {
            if (index >= chunks.size) {
                ref.get()?.let {
                    BulletinFactory.of(it)
                        .createSimpleBulletin(
                            R.raw.forward,
                            LocaleController.formatString(R.string.InuForwardAllDone, collected.size),
                        )
                        .show()
                }
                return
            }
            for (target in targets) {
                helper.sendMessage(ArrayList(chunks[index]), target, false, false, notify, 0, 0L)
            }
            AndroidUtilities.runOnUIThread({ step(index + 1) }, CHUNK_DELAY_MS)
        }
        step(0)
    }
}
