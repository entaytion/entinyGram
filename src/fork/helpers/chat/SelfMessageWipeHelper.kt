package desu.inugram.helpers.chat

import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import java.lang.ref.WeakReference

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
        searchNextPage(WeakReference(fragment), currentAccount, dialogId, 0)
    }

    private val deletedSoFar = HashMap<Long, Int>()

    private fun searchNextPage(fragmentRef: WeakReference<BaseFragment>, currentAccount: Int, dialogId: Long, offsetId: Int) {
        MessageSearchPager.searchPage(
            currentAccount, dialogId, offsetId, PAGE_SIZE, "SelfMessageWipeHelper",
            configure = { it.from_id = TLRPC.TL_inputPeerSelf() },
            onEnd = { finish(fragmentRef, dialogId) },
        ) { response ->
            val ids = ArrayList<Int>()
            var minId = Int.MAX_VALUE
            for (message in response.messages) {
                if (message is TLRPC.TL_messageEmpty || message.action is TLRPC.TL_messageActionHistoryClear) continue
                ids.add(message.id)
                if (message.id < minId) minId = message.id
            }

            if (ids.isEmpty()) {
                finish(fragmentRef, dialogId)
                return@searchPage
            }

            deletedSoFar[dialogId] = (deletedSoFar[dialogId] ?: 0) + ids.size
            MessagesController.getInstance(currentAccount).deleteMessages(ids, null, null, dialogId, 0, true, ChatActivity.MODE_DEFAULT)

            if (response.messages.size < PAGE_SIZE) {
                finish(fragmentRef, dialogId)
            } else {
                searchNextPage(fragmentRef, currentAccount, dialogId, minId)
            }
        }
    }

    private fun finish(fragmentRef: WeakReference<BaseFragment>, dialogId: Long) {
        val count = deletedSoFar.remove(dialogId) ?: 0
        val fragment = fragmentRef.get() ?: return
        if (count > 0) {
            BulletinFactory.of(fragment)
                .createSimpleBulletin(
                    R.raw.ic_delete,
                    LocaleController.formatString(R.string.InuDeleteMyMessagesDone, count),
                )
                .show()
        }
    }
}
