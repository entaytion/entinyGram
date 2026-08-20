package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

/**
 * Loops messages.setTyping so the other side perpetually sees "typing…", "recording…" or
 * "uploading…" — purely a prank/mindgame tool, session-scoped by design (no persistence).
 */
object TypingSpoofHelper {
    const val ACTION_TYPING = 0
    const val ACTION_RECORD_AUDIO = 1
    const val ACTION_RECORD_ROUND = 7
    const val ACTION_UPLOAD_DOCUMENT = 3

    private const val RESEND_DELAY_MS = 4000L

    // safety cap: if nothing ever stops the loop (crash, process kept alive in the background,
    // a missed ChatHelper.onFragmentDestroy call), it auto-stops instead of spoofing forever.
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

    @JvmStatic
    fun start(account: Int, dialogId: Long, threadId: Long, action: Int) {
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

    /** Shared action picker (Off/Typing/Recording voice/Recording round/Uploading file), used
     * both from the chat header menu ([desu.inugram.helpers.chat.ChatActionsHelper]) and from
     * the Stalker Pack settings quick-launch list — same UI, same behavior either way. */
    @JvmStatic
    fun showPicker(fragment: BaseFragment, account: Int, dialogId: Long, threadId: Long) {
        val parent = fragment.parentActivity ?: return
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
