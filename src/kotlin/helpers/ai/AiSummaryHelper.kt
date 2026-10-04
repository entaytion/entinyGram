package desu.inugram.helpers.ai

import android.content.Context
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory

object AiSummaryHelper {
    private val loading = HashSet<Int>()

    private const val PROMPT =
        "Summarize the following Telegram channel post clearly and concisely. Preserve important names, dates, numbers and links. " +
            "Use plain text without markdown. Return only the summary."

    // entiny: mirrors TranslateController.isSummarizable minus the server-side Premium flag
    @JvmStatic
    fun canSummarize(m: MessageObject?): Boolean {
        val owner = m?.messageOwner ?: return false
        return !m.isOutOwner && !m.isRestrictedMessage && !m.isSponsored &&
            (m.type == MessageObject.TYPE_TEXT || m.type == MessageObject.TYPE_VIDEO || m.type == MessageObject.TYPE_PHOTO ||
                m.type == MessageObject.TYPE_FILE || m.type == MessageObject.TYPE_MUSIC || m.type == MessageObject.TYPE_POLL) &&
            !owner.message.isNullOrEmpty() && owner.message.length > 100
    }

    @JvmStatic
    fun summarize(context: Context, message: MessageObject) {
        val endpoint = AiComposeHelper.endpointFor(AiComposeHelper.Feature.SUMMARY)
        if (endpoint == null || endpoint.url.isBlank()) {
            AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuAiSummary))
                .setMessage(LocaleController.getString(R.string.InuAiOpenSettings))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show()
            return
        }
        val owner = message.messageOwner ?: return
        if (owner.summaryText != null) {
            owner.summarizedOpen = !owner.summarizedOpen
            commit(message)
            return
        }
        val text = owner.message?.trim().orEmpty()
        if (text.isBlank()) return
        val key = message.dialogId.hashCode() * 31 + message.id
        if (!loading.add(key)) return
        BulletinFactory.global().createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuAiSummarizing)).show()
        AiComposeHelper.request(endpoint, PROMPT, text, answerOnly = true, temperature = AiComposeHelper.temperatureFor(AiComposeHelper.Feature.SUMMARY)) { result, error ->
            loading.remove(key)
            if (result.isNullOrBlank()) {
                BulletinFactory.global()
                    .createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.InuAiSummaryFailed, error.orEmpty()))
                    .show()
                return@request
            }
            owner.summaryText = TLRPC.TL_textWithEntities().apply {
                this.text = clean(result)
                entities = ArrayList()
            }
            owner.summarizedOpen = true
            commit(message)
        }
    }

    private fun commit(message: MessageObject) {
        message.updateTranslation(true)
        MessagesStorage.getInstance(message.currentAccount).updateMessageCustomParams(message.dialogId, message.messageOwner)
        NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.messageTranslated, message, true)
    }

    private fun clean(raw: String): String = raw.trim()
        .replace(Regex("\\*\\*|__|`"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
}
