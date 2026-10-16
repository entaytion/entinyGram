package desu.inugram.helpers.accounts

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig

object BotAccountHelper {
    private const val POLL_MS = 45_000L

    private var polling = false

    private val tick = object : Runnable {
        override fun run() {
            if (!anyBot()) {
                polling = false
                return
            }
            poll()
            AndroidUtilities.runOnUIThread(this, POLL_MS)
        }
    }

    @JvmStatic
    fun isBot(account: Int): Boolean {
        val config = UserConfig.getInstance(account)
        return config.isClientActivated && config.currentUser?.bot == true
    }

    private fun anyBot(): Boolean = (0 until UserConfig.MAX_ACCOUNT_COUNT).any { isBot(it) }

    private fun poll() {
        if (ApplicationLoader.mainInterfacePaused) return
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (isBot(account)) MessagesController.getInstance(account).getDifference()
        }
    }

    @JvmStatic
    fun ensurePolling() {
        if (!anyBot()) return
        poll()
        if (polling) return
        polling = true
        AndroidUtilities.runOnUIThread(tick, POLL_MS)
    }

    @JvmStatic
    fun applyDifferenceTooLong(account: Int, pts: Int, date: Int, qts: Int): Boolean {
        if (!isBot(account)) return false
        val storage = MessagesStorage.getInstance(account)
        storage.lastPtsValue = pts
        storage.lastDateValue = date
        storage.lastQtsValue = qts
        return true
    }
}
