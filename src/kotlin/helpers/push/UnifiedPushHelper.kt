package desu.inugram.helpers.push

import android.content.Context
import android.os.SystemClock
import desu.inugram.InuConfig
import desu.inugram.helpers.diag.DiagLog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.PushListenerController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.tl.TL_account
import org.unifiedpush.android.connector.UnifiedPush

// entiny: UnifiedPush as a push provider -- Telegram's "simple push" (token type 4) just wakes the app
// through the user's distributor (ntfy etc.), then updates come over the normal connection.
// Approach ported from Forkgram (https://github.com/forkgram/TelegramAndroid).
object UnifiedPushHelper {

    const val PUSH_TYPE_SIMPLE = 4
    const val DEFAULT_GATEWAY = "https://p2p.belloworld.it/"
    private const val FCM_SEND_PREFIX = "https://fcm.googleapis.com/fcm/send/"
    private const val INSTANCE = "default"
    private const val REGISTRATION_ANSWER_TIMEOUT_MS = 30_000L
    private const val MAX_RETRY_DELAY_MS = 15 * 60 * 1000L

    private var retries = 0
    private var retryRunnable: Runnable? = null
    private var answerTimeout: Runnable? = null

    private val context: Context get() = ApplicationLoader.applicationContext

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.UNIFIED_PUSH.value

    fun distributors(): List<String> = try {
        UnifiedPush.getDistributors(context)
    } catch (e: Throwable) {
        FileLog.e(e)
        emptyList()
    }

    fun currentDistributor(): String? = try {
        UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
    } catch (e: Throwable) {
        null
    }

    @JvmField
    val PROVIDER: PushListenerController.IPushListenerServiceProvider = object : PushListenerController.IPushListenerServiceProvider {
        override fun hasServices(): Boolean = distributors().isNotEmpty()

        override fun getLogTitle(): String = "UnifiedPush"

        override fun getPushType(): Int = PUSH_TYPE_SIMPLE

        override fun onRequestPushToken() {
            Utilities.globalQueue.postRunnable { register(null) }
        }
    }

    // picks the saved distributor if still installed, otherwise the given or the first one
    fun register(preferred: String?) {
        try {
            SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime()
            val all = distributors()
            if (all.isEmpty()) return
            val saved = UnifiedPush.getSavedDistributor(context)
            val target = preferred?.takeIf { it in all } ?: saved?.takeIf { it in all } ?: all.first()
            if (target != saved) UnifiedPush.saveDistributor(context, target)
            DiagLog.log("push", "register distributor=$target installed=$all")
            UnifiedPush.register(context, INSTANCE, "entinyGram", null)
            awaitAnswer()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    // entiny: switching providers must drop the old server registration, or it keeps waking the wrong channel
    fun setEnabled(enabled: Boolean, distributor: String? = null) {
        DiagLog.trace("push", "setEnabled $enabled distributor=$distributor previousType=${SharedConfig.pushType} tokenSet=${SharedConfig.pushString.isNotEmpty()}")
        if (InuConfig.UNIFIED_PUSH.value == enabled && distributor == null) return
        unregisterFromTelegram(SharedConfig.pushType, SharedConfig.pushString)
        InuConfig.UNIFIED_PUSH.value = enabled
        SharedConfig.pushString = ""
        SharedConfig.saveConfig()
        cancelRetry()
        if (enabled) {
            Utilities.globalQueue.postRunnable { register(distributor) }
        } else {
            lastEndpoint = null
            try {
                UnifiedPush.unregister(context, INSTANCE)
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            ApplicationLoader.getPushProvider().onRequestPushToken()
        }
    }

    private fun unregisterFromTelegram(type: Int, token: String?) {
        if (token.isNullOrEmpty()) return
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(a).isClientActivated) continue
            val req = TL_account.unregisterDevice()
            req.token_type = type
            req.token = token
            ConnectionsManager.getInstance(a).sendRequest(req, null)
        }
    }

    private var lastEndpoint: String? = null

    fun getGateway(): String = InuConfig.UNIFIED_PUSH_GATEWAY.value.trim()

    fun setGateway(gateway: String) {
        val trimmed = gateway.trim().let { if (it.isNotEmpty() && !it.endsWith("/")) "$it/" else it }
        if (InuConfig.UNIFIED_PUSH_GATEWAY.value == trimmed) return
        InuConfig.UNIFIED_PUSH_GATEWAY.value = trimmed
        if (isEnabled()) {
            val endpoint = lastEndpoint
            if (endpoint != null) {
                applyEndpoint(endpoint)
            } else {
                register(null)
            }
        }
    }

    internal fun onEndpoint(url: String) {
        lastEndpoint = url
        applyEndpoint(url)
    }

    private fun applyEndpoint(url: String) {
        val host = try { android.net.Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null }
        val gateway = getGateway()
        DiagLog.log("push", "endpoint host=$host gateway=${gateway.isNotEmpty()} enabled=${isEnabled()}")
        if (!isEnabled()) return
        cancelRetry()
        SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime()
        val token = if (gateway.isNotEmpty()) {
            val prefix = if (gateway.endsWith("/")) gateway else "$gateway/"
            if (url.startsWith(FCM_SEND_PREFIX)) {
                // FCM distributor (gCompat-UP) -- route through /fcm/<token> for VAPID signing
                val fcmToken = url.substring(FCM_SEND_PREFIX.length)
                "${prefix}fcm/$fcmToken"
            } else if (gateway == DEFAULT_GATEWAY && host != null && (host == "ntfy.sh" || host.endsWith(".ntfy.sh"))) {
                // ntfy.sh accepts Telegram PUT natively; bypass p2p.belloworld.it to avoid OCI IP rate limits
                url
            } else {
                // Other distributors (Sunup, Prism, Mozilla Autopush, NextPush, etc.)
                prefix + java.net.URLEncoder.encode(url, "UTF-8")
            }
        } else {
            url
        }
        PushListenerController.sendRegistrationToServer(PUSH_TYPE_SIMPLE, token)
    }

    internal fun onLost(retry: Boolean) {
        DiagLog.log("push", "registration lost retry=$retry enabled=${isEnabled()}")
        AndroidUtilities.runOnUIThread { cancelAnswerTimeout() }
        // a late callback after switching back to FCM must not wipe the FCM token
        if (!isEnabled()) return
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__"
        SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime()
        PushListenerController.sendRegistrationToServer(PUSH_TYPE_SIMPLE, null)
        if (retry) scheduleRetry()
    }

    // wake every account; the notification itself arrives over the regular MTProto connection
    internal fun onWake() {
        DiagLog.log("push", "wake from distributor")
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated) {
                ConnectionsManager.onInternalPushReceived(a)
                ConnectionsManager.getInstance(a).resumeNetworkMaybe()
            }
        }
    }

    private fun awaitAnswer() {
        val startedAt = SharedConfig.pushStringGetTimeStart
        AndroidUtilities.runOnUIThread {
            cancelAnswerTimeout()
            val timeout = Runnable {
                answerTimeout = null
                if (isEnabled() && SharedConfig.pushStringGetTimeEnd < startedAt) {
                    FileLog.d("UnifiedPush distributor did not answer the registration")
                    scheduleRetry()
                }
            }
            answerTimeout = timeout
            AndroidUtilities.runOnUIThread(timeout, REGISTRATION_ANSWER_TIMEOUT_MS)
        }
    }

    private fun scheduleRetry() {
        AndroidUtilities.runOnUIThread {
            if (retryRunnable != null) return@runOnUIThread
            val delay = minOf(10_000L shl minOf(retries, 6), MAX_RETRY_DELAY_MS)
            retries++
            val runnable = Runnable {
                retryRunnable = null
                if (isEnabled()) Utilities.globalQueue.postRunnable { register(null) }
            }
            retryRunnable = runnable
            AndroidUtilities.runOnUIThread(runnable, delay)
        }
    }

    private fun cancelAnswerTimeout() {
        answerTimeout?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        answerTimeout = null
    }

    private fun cancelRetry() {
        AndroidUtilities.runOnUIThread {
            retries = 0
            cancelAnswerTimeout()
            retryRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
            retryRunnable = null
        }
    }
}
