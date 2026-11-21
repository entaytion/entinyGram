package desu.inugram.helpers.proxy

import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.utils.proxy.ProxySettings
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

object CensorshipHelper {
    @Volatile private var tunnel: WsTunnel? = null
    private var guard: GuardSelector? = null

    @JvmStatic
    fun init() {
        if (InuConfig.LEAK_GUARD.value) installLeakGuard()
        if (InuConfig.ANTICENSOR_WS_TUNNEL.value) startTunnel()
    }

    @JvmStatic
    fun isTunnelActive(): Boolean = InuConfig.ANTICENSOR_WS_TUNNEL.value && tunnel?.isRunning == true

    @JvmStatic
    fun tunnelHost(): String = WsTunnel.HOST

    @JvmStatic
    fun tunnelPort(): Int = tunnel?.port ?: 0

    @JvmStatic
    fun tunnelSecret(): String = "dd" + secretHex()

    @JvmStatic
    fun tunnelSettings(): ProxySettings = ProxySettings.builder()
        .setType(ProxySettings.Type.MTPROTO)
        .setAddress(tunnelHost())
        .setPort(tunnelPort())
        .setSecret(tunnelSecret())
        .build()

    @JvmStatic
    fun onSendRequest(request: TLObject?) {
        if (request is TLRPC.TL_upload_getFile && request.cdn_supported && isTunnelActive()) {
            request.cdn_supported = false
        }
    }

    fun setTunnelEnabled(enabled: Boolean) {
        Utilities.globalQueue.postRunnable {
            if (enabled) startTunnel() else stopTunnel()
            AndroidUtilities.runOnUIThread { reapplyProxy() }
        }
    }

    @Synchronized
    fun setLeakGuardEnabled(enabled: Boolean) {
        if (enabled) installLeakGuard() else uninstallLeakGuard()
    }

    @Synchronized
    private fun startTunnel() {
        if (tunnel?.isRunning == true) return
        tunnel?.stop()
        tunnel = null
        val t = WsTunnel(hexToBytes(secretHex())) { onTunnelDied(it) }
        try {
            InuConfig.ANTICENSOR_WS_PORT.value = t.start(InuConfig.ANTICENSOR_WS_PORT.value)
            tunnel = t
        } catch (e: Throwable) {
            FileLog.e(e)
            t.stop()
        }
    }

    @Synchronized
    private fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
    }

    private fun onTunnelDied(dead: WsTunnel) {
        Utilities.globalQueue.postRunnable {
            if (restartDead(dead)) AndroidUtilities.runOnUIThread { reapplyProxy() }
        }
    }

    @Synchronized
    private fun restartDead(dead: WsTunnel): Boolean {
        if (tunnel !== dead) return false
        dead.stop()
        tunnel = null
        if (InuConfig.ANTICENSOR_WS_TUNNEL.value) startTunnel()
        return true
    }

    private fun reapplyProxy() {
        val proxy = SharedConfig.currentProxy
        val enabled = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false) && proxy != null
        ConnectionsManager.setProxySettings(enabled, proxy?.settings)
    }

    private fun secretHex(): String {
        val stored = InuConfig.ANTICENSOR_WS_SECRET.value
        if (stored.length == 32) return stored
        val fresh = Obfuscated2.randomSecret().joinToString("") { "%02x".format(it) }
        InuConfig.ANTICENSOR_WS_SECRET.value = fresh
        return fresh
    }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun userSocks(): SharedConfig.ProxyInfo? {
        if (!MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)) return null
        val proxy = SharedConfig.currentProxy ?: return null
        return proxy.takeIf { it.settings.type == ProxySettings.Type.SOCKS5 && !it.settings.address.isNullOrEmpty() }
    }

    @Synchronized
    private fun installLeakGuard() {
        if (guard != null) return
        val g = GuardSelector(ProxySelector.getDefault())
        ProxySelector.setDefault(g)
        guard = g
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (!InuConfig.LEAK_GUARD.value || requestorType != RequestorType.SERVER) return null
                val socks = userSocks() ?: return null
                if (requestingHost != socks.settings.address || socks.settings.user.isNullOrEmpty()) return null
                return PasswordAuthentication(socks.settings.user, (socks.settings.password ?: "").toCharArray())
            }
        })
    }

    @Synchronized
    private fun uninstallLeakGuard() {
        val g = guard ?: return
        if (ProxySelector.getDefault() === g) ProxySelector.setDefault(g.fallback)
        Authenticator.setDefault(null)
        guard = null
    }

    private class GuardSelector(val fallback: ProxySelector?) : ProxySelector() {
        private val blackhole = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9))

        override fun select(uri: URI): List<Proxy> {
            if (!InuConfig.LEAK_GUARD.value) return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
            val host = uri.host ?: return listOf(blackhole)
            if (host == "127.0.0.1" || host == "localhost") return listOf(Proxy.NO_PROXY)
            val socks = userSocks() ?: return listOf(blackhole)
            return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(socks.settings.address, socks.settings.port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            if (!InuConfig.LEAK_GUARD.value) fallback?.connectFailed(uri, sa, ioe)
        }
    }
}
