package desu.inugram.helpers.proxy

import android.os.SystemClock
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.ProxyRotationController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager

object ProxyRotationHelper {
    private const val CHECK_COOLDOWN = 2 * 60 * 1000L

    private var checking = false
    private var checkingStartedAt = 0L
    private val tick = Runnable { rotationTick() }

    @JvmStatic
    fun handle(id: Int, account: Int): Boolean {
        if (!InuConfig.PROXY_AGGRESSIVE_ROTATION.value) return false
        when (id) {
            NotificationCenter.proxyCheckDone -> {
                if (!checking || !isActive() || !isConnecting()) return true
                if (switchToAvailable() || SharedConfig.proxyList.none { it.checking }) checking = false
            }
            NotificationCenter.proxySettingsChanged -> scheduleTick()
            NotificationCenter.didUpdateConnectionState -> {
                if (account != UserConfig.selectedAccount) return true
                if (isConnecting()) {
                    scheduleTick()
                } else {
                    checking = false
                    AndroidUtilities.cancelRunOnUIThread(tick)
                }
            }
        }
        return true
    }

    @JvmStatic
    fun onToggle() {
        AndroidUtilities.cancelRunOnUIThread(tick)
        checking = false
        ProxyRotationController.inu_reset()
        if (InuConfig.PROXY_AGGRESSIVE_ROTATION.value && isConnecting()) scheduleTick()
    }

    private fun isActive(): Boolean =
        SharedConfig.proxyRotationEnabled && SharedConfig.proxyList.size > 1 && SharedConfig.isProxyEnabled()

    private fun isConnecting(): Boolean =
        ConnectionsManager.getInstance(UserConfig.selectedAccount).connectionState == ConnectionsManager.ConnectionStateConnectingToProxy

    private fun timeoutMillis(): Long {
        val timeouts = ProxyRotationController.ROTATION_TIMEOUTS
        val index = SharedConfig.proxyRotationTimeout.takeIf { it in timeouts.indices } ?: ProxyRotationController.DEFAULT_TIMEOUT_INDEX
        return timeouts[index] * 1000L
    }

    private fun scheduleTick() {
        AndroidUtilities.cancelRunOnUIThread(tick)
        if (isActive()) AndroidUtilities.runOnUIThread(tick, timeoutMillis())
    }

    private fun rotationTick() {
        if (!InuConfig.PROXY_AGGRESSIVE_ROTATION.value || !isActive() || !isConnecting()) {
            checking = false
            return
        }
        if (checking && SystemClock.elapsedRealtime() - checkingStartedAt > maxOf(timeoutMillis() * 3, 15000L)) {
            checking = false
            for (info in SharedConfig.proxyList) info.checking = false
        }
        val switched = switchToAvailable()
        startChecks()
        if (!switched) switchToNext()
        scheduleTick()
    }

    private fun startChecks() {
        val now = SystemClock.elapsedRealtime()
        val connections = ConnectionsManager.getInstance(UserConfig.selectedAccount)
        for (info in SharedConfig.proxyList) {
            if (info.checking || now - info.availableCheckTime < CHECK_COOLDOWN) continue
            if (!checking) {
                checking = true
                checkingStartedAt = now
            }
            info.checking = true
            connections.checkProxy(info.settings) { time ->
                AndroidUtilities.runOnUIThread {
                    info.availableCheckTime = SystemClock.elapsedRealtime()
                    info.checking = false
                    if (time == -1L) {
                        info.available = false
                        info.ping = 0
                    } else {
                        info.ping = time
                        info.available = true
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info)
                }
            }
        }
    }

    private fun switchToAvailable(): Boolean {
        val best = SharedConfig.proxyList
            .filter { it !== SharedConfig.currentProxy && !it.checking && it.available }
            .minByOrNull { it.ping } ?: return false
        switchTo(best)
        return true
    }

    private fun switchToNext() {
        val list = SharedConfig.proxyList
        val count = list.size
        val start = list.indexOf(SharedConfig.currentProxy)
        var fallback: SharedConfig.ProxyInfo? = null
        for (a in 1..count) {
            val info = list[Math.floorMod(start + a, count)]
            if (info === SharedConfig.currentProxy) continue
            if (fallback == null) fallback = info
            if (ProxyListToolsHelper.isFailed(info)) continue
            switchTo(info)
            return
        }
        fallback?.let(::switchTo)
    }

    private fun switchTo(info: SharedConfig.ProxyInfo) {
        val editor = MessagesController.getGlobalMainSettings().edit()
        editor.putBoolean("proxy_enabled", true)
        info.settings.toSharedPreferences(editor)
        editor.apply()
        SharedConfig.currentProxy = info
        ConnectionsManager.setProxySettings(true, info.settings)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation)
    }
}
