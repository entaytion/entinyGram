package desu.inugram.helpers.theme

import android.os.Build
import androidx.core.graphics.ColorUtils
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme

object ChatHeaderThemeHelper {

    @JvmStatic
    fun shouldUseLightHeaderColors(
        shouldHaveLightStatusBarIcons: Boolean,
        actionBar: ActionBar?
    ): Boolean {
        return shouldHaveLightStatusBarIcons
                && actionBar != null
                && actionBar.inu_hideChatPill()
                && !actionBar.inu_nonIsland
    }

    @JvmStatic
    fun getLightHeaderColor(key: Int, defaultColor: Int): Int {
        if (ColorUtils.calculateLuminance(defaultColor) >= 0.5f) {
            return defaultColor
        }
        val isMonet = Theme.getActiveTheme()?.inu_isMonet() == true
        return when (key) {
            Theme.key_actionBarDefaultTitle -> {
                val c = if (isMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MonetHelper.getColor("a2_0")
                } else 0
                if (c != 0) c else -0x1
            }
            Theme.key_actionBarDefaultSubtitle -> {
                val c = if (isMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MonetHelper.getColor("n1_50")
                } else 0
                if (c != 0) c else 0xB3FFFFFF.toInt()
            }
            Theme.key_chat_status -> {
                val c = if (isMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MonetHelper.getColor("a1_200")
                } else 0
                if (c != 0) c else 0xFF64B5F6.toInt()
            }
            Theme.key_chat_muteIcon, Theme.key_chat_lockIcon -> {
                val c = if (isMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MonetHelper.getColor("a1_100")
                } else 0
                if (c != 0) c else 0xB3FFFFFF.toInt()
            }
            else -> defaultColor
        }
    }
}
