package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.lang.ref.WeakReference

object SelfReactionWipeHelper {

    private const val PAGE_SIZE = 100
    private const val REMOVE_DELAY_MS = 400L

    private val running = HashSet<Long>()

    @JvmStatic
    fun confirmAndDelete(fragment: BaseFragment, account: Int, dialogId: Long) {
        val context = fragment.parentActivity ?: return
        AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuDeleteMyReactionsTitle))
            .setMessage(LocaleController.getString(R.string.InuDeleteMyReactionsConfirm))
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ -> start(fragment, account, dialogId) }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun start(fragment: BaseFragment, account: Int, dialogId: Long) {
        if (!running.add(dialogId)) return
        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.InuDeleteMyReactionsStarted))
            .show()
        val controller = MessagesController.getInstance(account)
        val targets = arrayListOf(dialogId)
        val chat = if (dialogId < 0) controller.getChat(-dialogId) else null
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            val linked = controller.getChatFull(-dialogId)?.linked_chat_id ?: 0L
            if (linked != 0L) targets.add(-linked)
        }
        Job(WeakReference(fragment), account, dialogId, targets).scan(0)
    }

    private class Job(
        val fragmentRef: WeakReference<BaseFragment>,
        val account: Int,
        val dialogId: Long,
        val targets: List<Long>,
    ) {
        private val controller = MessagesController.getInstance(account)
        private val found = ArrayList<Pair<Long, Int>>()
        private var target = 0
        private var removed = 0

        fun scan(offsetId: Int) {
            if (target >= targets.size) {
                remove(0)
                return
            }
            val did = targets[target]
            val peer = controller.getInputPeer(did)
            if (peer == null) {
                nextTarget()
                return
            }
            val req = TLRPC.TL_messages_getHistory().apply {
                this.peer = peer
                offset_id = offsetId
                limit = PAGE_SIZE
            }
            ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
                AndroidUtilities.runOnUIThread {
                    val wait = floodWait(error)
                    if (wait > 0) {
                        AndroidUtilities.runOnUIThread({ scan(offsetId) }, wait * 1000L)
                        return@runOnUIThread
                    }
                    if (response !is TLRPC.messages_Messages) {
                        FileLog.e("SelfReactionWipeHelper: history failed for $did: ${error?.text}")
                        nextTarget()
                        return@runOnUIThread
                    }
                    var minId = Int.MAX_VALUE
                    for (message in response.messages) {
                        if (message.id < minId) minId = message.id
                        if (hasOwnReaction(message)) found.add(did to message.id)
                    }
                    if (response.messages.size < PAGE_SIZE || minId == Int.MAX_VALUE) nextTarget() else scan(minId)
                }
            }
        }

        private fun nextTarget() {
            target++
            scan(0)
        }

        private fun remove(index: Int) {
            if (index >= found.size) {
                finish()
                return
            }
            val (did, msgId) = found[index]
            val peer = controller.getInputPeer(did)
            if (peer == null) {
                remove(index + 1)
                return
            }
            val req = TLRPC.TL_messages_sendReaction().apply {
                this.peer = peer
                msg_id = msgId
            }
            ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
                if (response is TLRPC.Updates) controller.processUpdates(response, false)
                AndroidUtilities.runOnUIThread {
                    val wait = floodWait(error)
                    if (wait > 0) {
                        AndroidUtilities.runOnUIThread({ remove(index) }, wait * 1000L)
                        return@runOnUIThread
                    }
                    if (error == null) removed++
                    AndroidUtilities.runOnUIThread({ remove(index + 1) }, REMOVE_DELAY_MS)
                }
            }
        }

        private fun finish() {
            running.remove(dialogId)
            val fragment = fragmentRef.get() ?: return
            val text = if (removed > 0) {
                LocaleController.formatString(R.string.InuDeleteMyReactionsDone, removed)
            } else {
                LocaleController.getString(R.string.InuDeleteMyReactionsNone)
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete, text).show()
        }
    }

    private fun hasOwnReaction(message: TLRPC.Message): Boolean =
        message.reactions?.results?.any { (it.flags and 1) != 0 && it.reaction !is TLRPC.TL_reactionPaid } == true

    private fun floodWait(error: TLRPC.TL_error?): Int {
        val text = error?.text ?: return 0
        if (!text.startsWith("FLOOD_WAIT_")) return 0
        return (text.removePrefix("FLOOD_WAIT_").toIntOrNull() ?: 5).coerceIn(1, 300)
    }
}
