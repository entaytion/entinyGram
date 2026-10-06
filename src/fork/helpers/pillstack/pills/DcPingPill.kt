package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.InuConfig
import desu.inugram.helpers.pillstack.PillType
import desu.inugram.helpers.pillstack.ProxyGeoHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.Theme

// entiny: current MTProto ping for this account, straight off the native connection -- no sockets of our own.
@SuppressLint("ViewConstructor")
class DcPingPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.pillstack_ping) {

    override fun getPillId(): Int = PillType.DC_PING.id

    override fun getRefreshInterval(): Long = 5_000L

    private fun proxyFlag(): String? {
        if (!InuConfig.PILL_STACK_PROXY_COUNTRY.value || !SharedConfig.isProxyEnabled()) return null
        return ProxyGeoHelper.flagFor(SharedConfig.currentProxy?.settings?.address)
    }

    override fun measureText(): String? = try {
        val ping = ConnectionsManager.native_getCurrentPingTime(UserConfig.selectedAccount)
        if (ping <= 0) null else {
            val value = LocaleController.formatString(R.string.InuPillStackDcPingValue, ping)
            val flag = proxyFlag()
            if (flag != null) "$flag $value" else value
        }
    } catch (e: Exception) {
        null
    }
}
