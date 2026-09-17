package desu.inugram.helpers.pillstack

import desu.inugram.helpers.menu.MenuOrderConfig

// entiny: enable/reorder config for the fixed pill types; rate pairs are managed separately by RateInstances.
class PillStackMenuConfig(key: String) : MenuOrderConfig<PillType>(key, PillType.entries, OFF_BY_DEFAULT) {

    override fun itemByKey(key: String): PillType? = PillType.entries.find { it.key == key }

    companion object {
        private val OFF_BY_DEFAULT = setOf(
            PillType.WEATHER, PillType.RAM, PillType.NET_SPEED, PillType.DC_PING,
            PillType.BATTERY, PillType.STORAGE,
        )
    }
}
