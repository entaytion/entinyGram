package desu.inugram.helpers.theme

import desu.inugram.InuConfig

object MonetStyle {
    const val CLASSIC = 0
    const val MATERIAL3 = 1

    // entiny: the Material 3 set lives next to the classic one as monet_<name>_m3.attheme
    @JvmStatic
    fun asset(name: String?): String? {
        if (name == null || InuConfig.MONET_STYLE.value != MATERIAL3) return name
        if (!name.startsWith("monet_") || !name.endsWith(".attheme")) return name
        return name.removeSuffix(".attheme") + "_m3.attheme"
    }
}
