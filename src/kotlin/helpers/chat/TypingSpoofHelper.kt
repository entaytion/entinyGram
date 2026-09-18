package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

object TypingSpoofHelper {
    const val ACTION_TYPING = 0
    const val ACTION_RECORD_AUDIO = 1
    const val ACTION_RECORD_ROUND = 7
    const val ACTION_UPLOAD_DOCUMENT = 3

    private const val RESEND_DELAY_MS = 4000L

    // entiny: safety cap auto-stops loop if cleanup is missed
    private const val MAX_DURATION_MS = 10 * 60 * 1000L

    private class Loop(val account: Int, val dialogId: Long, val threadId: Long, val action: Int) {
        private var elapsedMs = 0L
        val runnable: Runnable = Runnable {
            MessagesController.getInstance(account).sendTyping(dialogId, threadId, action, 0)
            elapsedMs += RESEND_DELAY_MS
            if (elapsedMs < MAX_DURATION_MS) {
                AndroidUtilities.runOnUIThread(this@Loop.runnable, RESEND_DELAY_MS)
            } else {
                stop(dialogId)
            }
        }
    }

    private val active = HashMap<Long, Loop>()

    // entiny: stock silently no-ops sendTyping for broadcast channels
    @JvmStatic
    fun canSpoofTyping(account: Int, dialogId: Long): Boolean {
        if (dialogId >= 0) return true
        val chat = MessagesController.getInstance(account).getChat(-dialogId) ?: return true
        return !ChatObject.isChannel(chat) || chat.megagroup
    }

    @JvmStatic
    fun start(account: Int, dialogId: Long, threadId: Long, action: Int) {
        if (!canSpoofTyping(account, dialogId)) return
        stop(dialogId)
        val loop = Loop(account, dialogId, threadId, action)
        active[dialogId] = loop
        AndroidUtilities.runOnUIThread(loop.runnable)
    }

    @JvmStatic
    fun stop(dialogId: Long) {
        val loop = active.remove(dialogId) ?: return
        AndroidUtilities.cancelRunOnUIThread(loop.runnable)
        MessagesController.getInstance(loop.account).sendTyping(dialogId, loop.threadId, 2, 0)
    }

    @JvmStatic
    fun isActive(dialogId: Long): Boolean = active.containsKey(dialogId)

    @JvmStatic
    fun activeAction(dialogId: Long): Int = active[dialogId]?.action ?: -1

    @JvmStatic
    fun getActiveDialogIds(): List<Long> = active.keys.toList()

    @JvmStatic
    fun showPicker(fragment: BaseFragment, account: Int, dialogId: Long, threadId: Long) {
        val parent = fragment.parentActivity ?: return
        if (!canSpoofTyping(account, dialogId)) {
            org.telegram.ui.Components.BulletinFactory.of(fragment)
                .createErrorBulletin(LocaleController.getString(R.string.InuTypingSpoofUnsupportedChannel))
                .show()
            return
        }
        val values = intArrayOf(-1, ACTION_TYPING, ACTION_RECORD_AUDIO, ACTION_RECORD_ROUND, ACTION_UPLOAD_DOCUMENT)
        val items = listOf(
            desu.inugram.ui.settings.RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTypingSpoofOff)),
            desu.inugram.ui.settings.RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTypingSpoofTyping)),
            desu.inugram.ui.settings.RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTypingSpoofRecordVoice)),
            desu.inugram.ui.settings.RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTypingSpoofRecordRound)),
            desu.inugram.ui.settings.RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTypingSpoofUploadDocument)),
        )
        val current = if (isActive(dialogId)) activeAction(dialogId) else -1
        fragment.showDialog(
            desu.inugram.ui.settings.RadioDialogBuilder(parent, fragment.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuTypingSpoof))
                .setItems(items, values.indexOf(current).coerceAtLeast(0)) { _, which ->
                    val action = values[which]
                    if (action == -1) stop(dialogId) else start(account, dialogId, threadId, action)
                }.create()
        )
    }
}
