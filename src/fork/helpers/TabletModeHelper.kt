package desu.inugram.helpers

import desu.inugram.InuConfig

object TabletModeHelper {
    @Volatile
    private var forced: Boolean? = null

    @JvmStatic
    fun sync() {
        forced = when (InuConfig.TABLET_MODE.value) {
            InuConfig.TabletModeItem.ON -> true
            InuConfig.TabletModeItem.OFF -> false
            else -> null
        }
    }

    @JvmStatic
    fun apply(real: Boolean): Boolean = forced ?: real
}
