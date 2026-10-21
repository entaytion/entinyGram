package desu.inugram.helpers.translate

import desu.inugram.InuConfig
import desu.inugram.helpers.translate.engine.EntinyTranslate
import java.util.concurrent.Executor
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LanguageDetector
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.LaunchActivity

object OutgoingTranslateHelper {
    private const val SPINNER_DELAY_MS = 300L

    // entiny: set right before the send is replayed with the translated text, so the replay is not translated again
    private var bypass = false
    private var running = false

    /** true when the send was taken over: the text is translated first and the send is replayed through [resend] */
    @JvmStatic
    fun intercept(enterView: ChatActivityEnterView, message: CharSequence, resend: Runnable): Boolean {
        if (bypass) {
            bypass = false
            return false
        }
        if (!InuConfig.TRANSLATE_ON_SEND.value || enterView.editingMessageObject != null) return false
        val text = message.toString()
        if (text.isBlank() || text.trimStart().startsWith("/")) return false
        val toLang = TranslateHelper.outgoingTargetLanguage()
        if (toLang.isEmpty()) return false
        if (running) return true
        running = true

        val fragment = enterView.parentFragment
        val account = fragment?.currentAccount ?: UserConfig.selectedAccount
        var cancelled = false
        val progress = AlertDialog(enterView.context, AlertDialog.ALERT_TYPE_SPINNER).apply {
            setOnCancelListener {
                cancelled = true
                running = false
            }
            showDelayed(SPINNER_DELAY_MS)
        }

        fun replay() {
            bypass = true
            try {
                resend.run()
            } finally {
                bypass = false
            }
        }

        fun finish(translated: CharSequence?, problem: String?) {
            progress.dismiss()
            if (cancelled) return
            running = false
            if (translated.isNullOrBlank()) {
                val host = fragment ?: LaunchActivity.getSafeLastFragment()
                if (host != null) {
                    BulletinFactory.of(host).createErrorBulletin(LocaleController.formatString(R.string.InuTranslateProviderFailed, problem.orEmpty())).show()
                }
                return
            }
            enterView.setFieldText(translated)
            replay()
        }

        fun requestTelegram() {
            val source = arrayOf<CharSequence>(message)
            val from = TLRPC.TL_textWithEntities()
            from.entities = MediaDataController.getInstance(account).getEntities(source, true)
            from.text = source[0]?.toString().orEmpty()
            val request = TLRPC.TL_messages_translateText()
            request.to_lang = toLang
            request.flags = request.flags or TLObject.FLAG_1
            request.text.add(from)
            ConnectionsManager.getInstance(account).sendRequestTyped(request, Executor { AndroidUtilities.runOnUIThread(it) }, Utilities.Callback2 { response, error ->
                val translated = response?.result?.firstOrNull()?.let { MessageObject.formatTextWithEntities(it) }
                finish(translated, error?.text)
            })
        }

        fun translate() {
            val handled = EntinyTranslate.isActive(account) &&
                EntinyTranslate.handleDraft(text, toLang, account, Utilities.Callback { result -> AndroidUtilities.runOnUIThread { finish(result, null) } })
            if (!handled) requestTelegram()
        }

        if (LanguageDetector.hasSupport()) {
            LanguageDetector.detectLanguage(text, { detected ->
                AndroidUtilities.runOnUIThread {
                    if (sameLanguage(detected, toLang)) {
                        progress.dismiss()
                        if (!cancelled) {
                            running = false
                            replay()
                        }
                    } else {
                        translate()
                    }
                }
            }, { AndroidUtilities.runOnUIThread { translate() } })
        } else {
            translate()
        }
        return true
    }

    private fun sameLanguage(detected: String?, target: String): Boolean =
        !detected.isNullOrEmpty() && detected.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)
}
