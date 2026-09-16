package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

// entiny: current network speed from the TrafficStats delta between reads; shows whichever direction dominates.
@SuppressLint("ViewConstructor")
class NetSpeedPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.pillstack_netspeed) {

    companion object {
        private var previousRx = -1L
        private var previousTx = 0L
        private var previousTime = 0L
    }

    override fun getPillId(): Int = PillType.NET_SPEED.id

    override fun getRefreshInterval(): Long = 2_000L

    override fun measureText(): String? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        val now = SystemClock.elapsedRealtime()
        if (previousRx < 0) {
            previousRx = rx
            previousTx = tx
            previousTime = now
            return null
        }
        val elapsed = now - previousTime
        if (elapsed <= 0) return null
        val rxSpeed = (rx - previousRx).coerceAtLeast(0) * 1000 / elapsed
        val txSpeed = (tx - previousTx).coerceAtLeast(0) * 1000 / elapsed
        previousRx = rx
        previousTx = tx
        previousTime = now
        return if (rxSpeed >= txSpeed) "↓${AndroidUtilities.formatFileSize(rxSpeed)}/s" else "↑${AndroidUtilities.formatFileSize(txSpeed)}/s"
    }
}
