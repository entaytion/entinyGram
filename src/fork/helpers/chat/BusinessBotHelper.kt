package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.tl.TL_account
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory

object BusinessBotHelper {
    private const val PAUSED_FLAG = 1

    @JvmStatic
    fun menuAvailable(activity: ChatActivity): Boolean {
        if (!InuConfig.HIDE_BUSINESS_BOT_BANNER.value || activity.currentEncryptedChat != null) return false
        return botId(activity) != 0L
    }

    private fun botId(activity: ChatActivity): Long =
        MessagesController.getNotificationsSettings(activity.currentAccount).getLong("dialog_botid" + activity.dialogId, 0)

    fun toggle(activity: ChatActivity) {
        val account = activity.currentAccount
        val dialogId = activity.dialogId
        val prefs = MessagesController.getNotificationsSettings(account)
        val flags = prefs.getInt("dialog_botflags$dialogId", 0)
        val paused = flags and PAUSED_FLAG == 0
        prefs.edit().putInt("dialog_botflags$dialogId", if (paused) flags or PAUSED_FLAG else flags and PAUSED_FLAG.inv()).apply()

        val req = TL_account.toggleConnectedBotPaused()
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId)
        req.paused = paused
        ConnectionsManager.getInstance(account).sendRequest(req, null)

        BulletinFactory.of(activity).createSimpleBulletin(
            R.raw.info,
            LocaleController.getString(if (paused) R.string.InuBusinessBotStopped else R.string.InuBusinessBotStarted),
        ).show()
    }
}
