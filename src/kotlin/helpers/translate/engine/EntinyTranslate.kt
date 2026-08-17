package desu.inugram.helpers.translate.engine

import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.Bulletin

/**
 * Facade for the stock Java code (`TranslateController`). Keeps the patch surface to a handful
 * of one-line hooks:
 *
 * - [handle] routes `pushToTranslate` into the engine when a third-party provider is selected;
 * - [isInFlight] feeds the translating-spinner state;
 * - [cancelDialog] / [resetDialog] / [unfailMessage] keep engine state in sync with dialog
 *   toggling, language changes and manual "show original".
 */
object EntinyTranslate {

    @JvmStatic
    fun isActive(): Boolean = InuConfig.TRANSLATE_PROVIDER.value != TranslationProviders.PROVIDER_TELEGRAM

    @JvmStatic
    fun currentProviderName(): String {
        val provider = TranslationProviders.current() ?: return LocaleController.getString(R.string.InuTranslateProviderTelegram)
        return LocaleController.getString(provider.nameRes)
    }

    /**
     * Routes one `pushToTranslate` request. Returns true when the engine took it over (the
     * caller must not continue to the stock Telegram path).
     *
     * When the selected provider is not configured yet, shows a one-time bulletin per dialog and
     * returns false so stock (server-side) translation keeps working as a graceful fallback.
     */
    @JvmStatic
    fun handle(
        dialogId: Long,
        msgId: Int,
        isTranscription: Boolean,
        text: String,
        entities: List<TLRPC.MessageEntity>?,
        toLang: String,
        callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_textWithEntities, String>,
    ): Boolean {
        Log.d(TAG, "handle dialog=$dialogId msg=$msgId to=$toLang active=${isActive()}")
        if (!isActive()) return false
        val provider = TranslationProviders.current() ?: return false
        if (!provider.isConfigured()) {
            Log.d(TAG, "provider ${provider.nameRes} not configured; falling back to Telegram API")
            if (configBulletins.add(dialogId)) {
                NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.showBulletin,
                    Bulletin.TYPE_ERROR,
                    LocaleController.getString(R.string.InuTranslateProviderNotConfigured),
                )
            }
            return false
        }
        // If the chosen provider cannot translate into the target language (e.g. Lingo and
        // TranSmart lack Ukrainian), silently use a broader provider instead of failing.
        val effective = TranslationProviders.effectiveProvider(provider, toLang)
        if (effective !== provider) {
            Log.d(TAG, "provider ${provider.nameRes} lacks target $toLang; using ${effective.nameRes}")
        }
        return TranslateEngine.enqueueText(
            dialogId = dialogId,
            msgId = msgId,
            transcription = isTranscription,
            text = text,
            entities = entities,
            toLang = toLang,
            provider = effective,
            callback = callback,
        )
    }

    /** Routes one poll translation request; returns true when the engine took it over. */
    @JvmStatic
    fun handlePoll(
        dialogId: Long,
        msgId: Int,
        poll: TranslateController.PollText,
        toLang: String,
        callback: Utilities.Callback3<Int, TranslateController.PollText, String>,
    ): Boolean {
        Log.d(TAG, "handlePoll dialog=$dialogId msg=$msgId to=$toLang active=${isActive()}")
        if (!isActive()) return false
        val provider = TranslationProviders.current() ?: return false
        if (!provider.isConfigured()) {
            if (configBulletins.add(dialogId)) {
                NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.showBulletin,
                    Bulletin.TYPE_ERROR,
                    LocaleController.getString(R.string.InuTranslateProviderNotConfigured),
                )
            }
            return false
        }
        val effective = TranslationProviders.effectiveProvider(provider, toLang)
        if (effective !== provider) {
            Log.d(TAG, "provider ${provider.nameRes} lacks target $toLang; using ${effective.nameRes}")
        }
        return TranslateEngine.enqueuePoll(
            dialogId = dialogId,
            msgId = msgId,
            poll = poll,
            toLang = toLang,
            provider = effective,
            callback = callback,
        )
    }

    /**
     * Routes one web-preview translation request through the engine when a third-party provider
     * is selected. Returns true when taken over; false lets the caller keep the stock Telegram
     * API path (used when no provider is selected or it is not configured).
     */
    @JvmStatic
    fun handleWebPage(
        dialogId: Long,
        msgId: Int,
        original: TLRPC.TL_webPage,
        parts: List<Pair<Char, String>>,
        toLang: String,
        callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_webPage, String>,
    ): Boolean {
        Log.d(TAG, "handleWebPage dialog=$dialogId msg=$msgId to=$toLang active=${isActive()}")
        if (!isActive()) return false
        val provider = TranslationProviders.current() ?: return false
        if (!provider.isConfigured()) {
            if (configBulletins.add(dialogId)) {
                NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.showBulletin,
                    Bulletin.TYPE_ERROR,
                    LocaleController.getString(R.string.InuTranslateProviderNotConfigured),
                )
            }
            return false
        }
        val effective = TranslationProviders.effectiveProvider(provider, toLang)
        if (effective !== provider) {
            Log.d(TAG, "provider ${provider.nameRes} lacks target $toLang; using ${effective.nameRes}")
        }
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

    /**
     * Called when the user switches translation provider. Clears per-message failure marks so
     * messages that failed under the previous provider are retried with the new one.
     */
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
    fun cancelAll() = TranslateEngine.cancelAll()

    @JvmStatic
    fun unfailMessage(dialogId: Long, msgId: Int, isTranscription: Boolean) =
        TranslateEngine.unfailMessage(dialogId, msgId, isTranscription)

    private val configBulletins = HashSet<Long>()

    private const val TAG = "InuTranslate"
}
