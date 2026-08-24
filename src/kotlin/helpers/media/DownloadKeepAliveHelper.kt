package desu.inugram.helpers.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig
import java.util.ArrayList

/**
 * Keeps in-progress chat downloads alive after the message cell that started them
 * is scrolled off screen or the chat is closed, instead of Telegram auto-cancelling
 * them (see ChatMessageCell.fileDetach). Optionally also holds a partial wake lock
 * while a download is running so Doze/App Standby can't pause it mid-transfer.
 *
 * Ported from the "Don't kill the download!" plugin (materialgram/exteraGram
 * plugin ecosystem, originally @shareui, fixed by @itNotMax/@MaxExteraPlugins).
 */
object DownloadKeepAliveHelper {

    private const val POLL_INTERVAL_MS = 7_000L

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollingStarted = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            syncWakeLock()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @JvmStatic
    fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        handler.post(pollRunnable)
    }

    private fun syncWakeLock() {
        if (!InuConfig.BLOCK_SLEEP_WHILE_DOWNLOADING.value || !hasActiveDownloads()) {
            releaseWakeLock()
            return
        }
        acquireWakeLock()
    }

    private fun hasActiveDownloads(): Boolean {
        try {
            for (account in 0 until UserConfig.getActivatedAccountsCount()) {
                val files = ArrayList<MessageObject>()
                FileLoader.getInstance(account).getCurrentLoadingFiles(files)
                if (files.isNotEmpty()) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "entinyGram:keepDownloadsAwake")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }
}
