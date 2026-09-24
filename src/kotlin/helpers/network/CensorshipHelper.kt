package desu.inugram.helpers.network

import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

object CensorshipHelper {
    private const val TAG = "InuCensorship"

    @Volatile private var tunnel: WsTunnel? = null

    fun init() {
        installLeakGuard()
        if (InuConfig.ANTICENSOR_WS_TUNNEL.value) startTunnel()
    }

    @JvmStatic
    fun isTunnelActive(): Boolean = InuConfig.ANTICENSOR_WS_TUNNEL.value && (tunnel?.port ?: 0) != 0

    @JvmStatic
    fun tunnelHost(): String = WsTunnel.HOST

    @JvmStatic
    fun tunnelPort(): Int = tunnel?.port ?: 0

    @JvmStatic
    fun tunnelSecret(): String = "dd" + secretHex()

    fun setTunnelEnabled(enabled: Boolean) {
        InuConfig.ANTICENSOR_WS_TUNNEL.value = enabled
        if (enabled) startTunnel() else stopTunnel()
        Utilities.globalQueue.postRunnable { reapplyProxy() }
    }

    private fun startTunnel() {
        if (tunnel != null) return
        val t = WsTunnel(hexToBytes(secretHex()))
        try {
            InuConfig.ANTICENSOR_WS_PORT.value = t.start(InuConfig.ANTICENSOR_WS_PORT.value)
            tunnel = t
            Log.d(TAG, "ws tunnel on ${WsTunnel.HOST}:${t.port}")
        } catch (e: IOException) {
            Log.d(TAG, "ws tunnel failed to bind: ${e.message}")
        }
    }

    private fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
    }

    private fun reapplyProxy() {
        if (isTunnelActive()) {
            ConnectionsManager.setProxySettings(true, tunnelHost(), tunnelPort(), "", "", tunnelSecret())
            return
        }
        val prefs = MessagesController.getGlobalMainSettings()
        val proxy = SharedConfig.currentProxy
        if (prefs.getBoolean("proxy_enabled", false) && proxy != null) {
            ConnectionsManager.setProxySettings(true, proxy.settings.address, proxy.settings.port, proxy.settings.user, proxy.settings.password, proxy.settings.secret)
        } else {
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "")
        }
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
        return proxy.takeIf { it.settings.secret.isNullOrEmpty() && !it.settings.address.isNullOrEmpty() }
    }

    private fun installLeakGuard() {
        val original = ProxySelector.getDefault()
        if (original is GuardSelector) return
        ProxySelector.setDefault(GuardSelector(original))
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (!InuConfig.LEAK_GUARD.value || requestorType != RequestorType.SERVER) return null
                val socks = userSocks() ?: return null
                if (requestingHost != socks.settings.address || socks.settings.user.isNullOrEmpty()) return null
                return PasswordAuthentication(socks.settings.user, (socks.settings.password ?: "").toCharArray())
            }
        })
    }

    private class GuardSelector(private val fallback: ProxySelector?) : ProxySelector() {
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
