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

object DownloadKeepAliveHelper {

    private const val POLL_INTERVAL_MS = 15_000L
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollingStarted = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            syncWakeLock()
            if (!InuConfig.BLOCK_SLEEP_WHILE_DOWNLOADING.value) {
                pollingStarted = false
                return
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @JvmStatic
    fun startPolling() {
        if (pollingStarted) return
        if (!InuConfig.BLOCK_SLEEP_WHILE_DOWNLOADING.value) return
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

    private val loadingFiles = ArrayList<MessageObject>()

    private fun hasActiveDownloads(): Boolean {
        try {
            for (account in 0 until UserConfig.getActivatedAccountsCount()) {
                loadingFiles.clear()
                FileLoader.getInstance(account).getCurrentLoadingFiles(loadingFiles)
                if (loadingFiles.isNotEmpty()) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            // re-acquire after the safety timeout expires: the poll loop renews the lock while downloads are active
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "entinyGram:keepDownloadsAwake")
            wl.setReferenceCounted(false)
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
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
