package desu.inugram.helpers.pillstack

import android.content.Context
import desu.inugram.InuConfig
import desu.inugram.helpers.pillstack.pills.BasePill
import desu.inugram.helpers.pillstack.pills.BatteryPill
import desu.inugram.helpers.pillstack.pills.CachePill
import desu.inugram.helpers.pillstack.pills.ClockPill
import desu.inugram.helpers.pillstack.pills.CpuPill
import desu.inugram.helpers.pillstack.pills.DcPingPill
import desu.inugram.helpers.pillstack.pills.GhostPill
import desu.inugram.helpers.pillstack.pills.NetSpeedPill
import desu.inugram.helpers.pillstack.pills.ProxyPill
import desu.inugram.helpers.pillstack.pills.RamPill
import desu.inugram.helpers.pillstack.pills.StoragePill
import desu.inugram.helpers.pillstack.pills.WeatherPill
import org.telegram.messenger.FileLog
import org.telegram.ui.ActionBar.Theme

// entiny: which pills exist (fixed types + dynamic rate instances) and how to build them.
object PillRegistry {

    fun interface PillCreator {
        fun create(context: Context, resourcesProvider: Theme.ResourcesProvider?): BasePill
    }

    class PillInfo(val id: Int, val iconRes: Int, val creator: PillCreator)

    private val registry = LinkedHashMap<Int, PillInfo>()

    init {
        register(PillInfo(PillType.CLOCK.id, PillType.CLOCK.iconRes) { c, r -> ClockPill(c, r) })
        register(PillInfo(PillType.WEATHER.id, PillType.WEATHER.iconRes) { c, r -> WeatherPill(c, r) })
        register(PillInfo(PillType.CACHE.id, PillType.CACHE.iconRes) { c, r -> CachePill(c, r) })
        register(PillInfo(PillType.PROXY.id, PillType.PROXY.iconRes) { c, r -> ProxyPill(c, r) })
        register(PillInfo(PillType.GHOST.id, PillType.GHOST.iconRes) { c, r -> GhostPill(c, r) })
        register(PillInfo(PillType.RAM.id, PillType.RAM.iconRes) { c, r -> RamPill(c, r) })
        register(PillInfo(PillType.CPU.id, PillType.CPU.iconRes) { c, r -> CpuPill(c, r) })
        register(PillInfo(PillType.NET_SPEED.id, PillType.NET_SPEED.iconRes) { c, r -> NetSpeedPill(c, r) })
        register(PillInfo(PillType.DC_PING.id, PillType.DC_PING.iconRes) { c, r -> DcPingPill(c, r) })
        register(PillInfo(PillType.BATTERY.id, PillType.BATTERY.iconRes) { c, r -> BatteryPill(c, r) })
        register(PillInfo(PillType.STORAGE.id, PillType.STORAGE.iconRes) { c, r -> StoragePill(c, r) })
    }

    fun register(info: PillInfo) {
        registry[info.id] = info
    }

    fun unregister(id: Int) {
        registry.remove(id)
    }

    // entiny: a bad pill throwing here must not take the rest of the row down with it.
    fun createPill(id: Int, context: Context, resourcesProvider: Theme.ResourcesProvider?): BasePill? {
        val info = registry[id] ?: return null
        return try {
            info.creator.create(context, resourcesProvider)
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    fun getIconRes(id: Int): Int? = registry[id]?.iconRes

    fun activePillIds(): List<Int> {
        val fixed = InuConfig.PILL_STACK_LAYOUT.value.filter { it.enabled }.map { it.item.id }
        val rates = RateInstances.getAll().map { it.id }
        return fixed + rates
    }
}
