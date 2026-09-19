package desu.inugram.helpers.translate.engine

import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.LaunchActivity

object EntinyTranslate {

    @JvmStatic
    @JvmOverloads
    fun isActive(account: Int = UserConfig.selectedAccount): Boolean {
        if (InuConfig.TRANSLATE_PROVIDER.value != TranslationProviders.PROVIDER_TELEGRAM) return true
        // entiny: MTProto rejects chat translation for non-premium accounts; route through local engine
        return !UserConfig.getInstance(account).isPremium
    }

    @JvmStatic
    fun currentProviderName(): String {
        if (!isActive()) return LocaleController.getString(R.string.InuTranslateProviderTelegram)
        // entiny: isActive()==true means the engine does the translating even when
        // TRANSLATE_PROVIDER is still PROVIDER_TELEGRAM (that value doubles as "no third-party
        // provider chosen", which for a non-premium account still routes through the engine as a
        // premium bypass) - report whatever provider will actually receive the message text, never
        // the raw preference, or the UI claims "Telegram API" while text goes to Google
        val provider = TranslationProviders.current() ?: GoogleWebProvider
        return LocaleController.getString(provider.nameRes)
    }

    // entiny: single provider-resolution path shared by handle()/handlePoll()/handleWebPage() -
    // previously each reimplemented this with a subtly different null-fallback, and the webpage
    // path had none at all, so link previews silently used a different service than the message
    // body for the same account/message
    private fun resolveProvider(dialogId: Long, account: Int): TranslationProvider? {
        if (!isActive(account)) return null
        val provider = TranslationProviders.current() ?: GoogleWebProvider
        if (!provider.isConfigured()) {
            Log.d(TAG, "provider ${provider.nameRes} not configured; falling back to Telegram API")
            if (configBulletins.add(dialogId)) {
                NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.showBulletin,
                    Bulletin.TYPE_ERROR,
                    LocaleController.getString(R.string.InuTranslateProviderNotConfigured),
                )
            }
            return null
        }
        return provider
    }

    @JvmStatic
    @JvmOverloads
    fun handle(
        dialogId: Long,
        msgId: Int,
        isTranscription: Boolean,
        text: String,
        entities: List<TLRPC.MessageEntity>?,
        toLang: String,
        callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_textWithEntities, String>,
        account: Int = UserConfig.selectedAccount,
    ): Boolean {
        val effective = resolveProvider(dialogId, account) ?: return false
        Log.d(TAG, "handle dialog=$dialogId msg=$msgId to=$toLang provider=${effective.id}")
        return TranslateEngine.enqueueText(
            dialogId = dialogId,
            msgId = msgId,
            transcription = isTranscription,
            text = text,
            entities = entities,
            toLang = toLang,
            provider = effective,
            context = conversationContext(dialogId, msgId, effective),
            callback = callback,
        )
    }

    private fun conversationContext(dialogId: Long, msgId: Int, provider: TranslationProvider): List<String> {
        if (provider !== LlmProvider) return emptyList()
        val limit = InuConfig.TRANSLATE_LLM_CONTEXT.value
        if (limit <= 0) return emptyList()
        return runCatching {
            val chat = LaunchActivity.getLastFragment() as? ChatActivity ?: return emptyList()
            if (chat.dialogId != dialogId) return emptyList()
            val messages = chat.messages
            val index = messages.indexOfFirst { it != null && it.id == msgId }
            if (index < 0) return emptyList()
            messages.asSequence()
                .drop(index + 1)
                .mapNotNull { it?.messageOwner?.message?.trim()?.takeIf(String::isNotEmpty) }
                .take(limit)
                .toList()
                .asReversed()
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    @JvmOverloads
    fun handlePoll(
        dialogId: Long,
        msgId: Int,
        poll: TranslateController.PollText,
        toLang: String,
        callback: Utilities.Callback3<Int, TranslateController.PollText, String>,
        account: Int = UserConfig.selectedAccount,
    ): Boolean {
        val effective = resolveProvider(dialogId, account) ?: return false
        Log.d(TAG, "handlePoll dialog=$dialogId msg=$msgId to=$toLang provider=${effective.id}")
        return TranslateEngine.enqueuePoll(
            dialogId = dialogId,
            msgId = msgId,
            poll = poll,
            toLang = toLang,
            provider = effective,
            callback = callback,
        )
    }

    @JvmStatic
    fun handleWebPage(
        dialogId: Long,
        msgId: Int,
        original: TLRPC.TL_webPage,
        parts: List<Pair<Char, String>>,
        toLang: String,
        callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_webPage, String>,
    ): Boolean {
        // entiny: was `TranslationProviders.current() ?: return false` - no Google fallback, so a
        // non-premium account on PROVIDER_TELEGRAM got the message body via Google but the link
        // preview not translated at all. resolveProvider() gives both the same fallback.
        val effective = resolveProvider(dialogId, UserConfig.selectedAccount) ?: return false
        Log.d(TAG, "handleWebPage dialog=$dialogId msg=$msgId to=$toLang provider=${effective.id}")
        return TranslateEngine.enqueueWebPage(
            dialogId = dialogId,
            msgId = msgId,
            original = original,
            parts = parts,
            toLang = toLang,
            provider = effective,
            callback = callback,
        )
    }

    @JvmStatic
    fun onProviderChanged() {
        Log.d(TAG, "provider changed to ${currentProviderName()}; resetting engine failures")
        TranslateEngine.resetAll()
        configBulletins.clear()
    }

    @JvmStatic
    fun isInFlight(dialogId: Long, msgId: Int, isTranscription: Boolean): Boolean =
        TranslateEngine.isInFlight(dialogId, msgId, isTranscription)

    @JvmStatic
    fun cancelDialog(dialogId: Long) = TranslateEngine.cancelDialog(dialogId)

    @JvmStatic
    fun resetDialog(dialogId: Long) {
        TranslateEngine.resetDialog(dialogId)
        configBulletins.remove(dialogId)
    }

    @JvmStatic
    fun unfailMessage(dialogId: Long, msgId: Int, isTranscription: Boolean) =
        TranslateEngine.unfailMessage(dialogId, msgId, isTranscription)

    private val configBulletins = HashSet<Long>()

    private const val TAG = "EntinyTranslate"
}
