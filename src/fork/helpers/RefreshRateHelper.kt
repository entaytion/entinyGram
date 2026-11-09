package desu.inugram.helpers

import android.app.Activity
import android.os.Build
import desu.inugram.InuConfig

object RefreshRateHelper {
    @JvmStatic
    fun sync(activity: Activity?) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val window = activity.window ?: return
        val target = if (InuConfig.LOCK_MAX_REFRESH_RATE.value) bestModeId(activity) else 0
        val params = window.attributes
        if (params.preferredDisplayModeId == target) return
        params.preferredDisplayModeId = target
        window.attributes = params
    }

    @Suppress("DEPRECATION")
    private fun bestModeId(activity: Activity): Int {
        val display = activity.windowManager.defaultDisplay ?: return 0
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return 0
        return if (best.refreshRate > current.refreshRate + 0.5f) best.modeId else current.modeId
    }
}
