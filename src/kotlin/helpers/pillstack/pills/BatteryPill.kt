package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

@SuppressLint("ViewConstructor")
class BatteryPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.phosphor_battery_charging) {

    override fun getPillId(): Int = PillType.BATTERY.id

    override fun getRefreshInterval(): Long = 30_000L

    override fun measureText(): String? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return null
        val charging = try {
            manager.isCharging
        } catch (e: Exception) {
            false
        }
        return (if (charging) "⚡" else "") + "$level%"
    }
}
