package desu.inugram.helpers.pillstack

import desu.inugram.helpers.menu.MenuOrderItem
import org.telegram.messenger.R

// entiny: the fixed pill types (id < RateInstances.FIRST_ID); rate pairs are dynamic, see RateInstances.
enum class PillType(
    val id: Int,
    override val key: String,
    override val labelRes: Int,
    override val iconRes: Int,
) : MenuOrderItem {
    CLOCK(1, "clock", R.string.InuPillStackClock, R.drawable.phosphor_clock),
    WEATHER(2, "weather", R.string.InuPillStackWeather, R.drawable.phosphor_sun),
    CACHE(3, "cache", R.string.StorageUsage, R.drawable.msg_filled_storageusage),
    PROXY(4, "proxy", R.string.InuPillStackProxy, R.drawable.proxy_on_solar),
    GHOST(5, "ghost", R.string.InuGhostMode, R.drawable.inu_ghost),
    RAM(6, "ram", R.string.InuPillStackRam, R.drawable.pillstack_ram),
    NET_SPEED(8, "net_speed", R.string.InuPillStackNetSpeed, R.drawable.pillstack_netspeed),
    DC_PING(9, "dc_ping", R.string.InuPillStackDcPing, R.drawable.pillstack_ping),
    BATTERY(10, "battery", R.string.InuPillStackBattery, R.drawable.phosphor_battery_charging),
    STORAGE(11, "storage", R.string.InuPillStackStorage, R.drawable.phosphor_hard_drive),
}
