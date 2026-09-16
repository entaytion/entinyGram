package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.Theme

// entiny: current MTProto ping for this account, straight off the native connection -- no sockets of our own.
@SuppressLint("ViewConstructor")
class DcPingPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.pillstack_ping) {

    override fun getPillId(): Int = PillType.DC_PING.id

    override fun getRefreshInterval(): Long = 5_000L

    override fun measureText(): String? = try {
        val ping = ConnectionsManager.native_getCurrentPingTime(UserConfig.selectedAccount)
        if (ping <= 0) null else LocaleController.formatString(R.string.InuPillStackDcPingValue, ping)
    } catch (e: Exception) {
        null
    }
}
