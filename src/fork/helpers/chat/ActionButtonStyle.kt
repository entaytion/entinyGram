package desu.inugram.helpers.chat

import android.graphics.Color
import desu.inugram.InuConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed

object ActionButtonStyle {
    const val ACCENT = 0
    const val NEUTRAL = 1
    const val WHITE = 2

    @JvmStatic
    fun getCurrentStyle(): Int = InuConfig.ACTION_BUTTON_STYLE.value

    @JvmStatic
    fun resolveBackgroundColor(resourcesProvider: Theme.ResourcesProvider?): Int {
        return when (getCurrentStyle()) {
            NEUTRAL -> Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider)
            WHITE -> Color.WHITE
            else -> Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider)
        }
    }

    @JvmStatic
    fun resolveIconColor(resourcesProvider: Theme.ResourcesProvider?): Int {
        return when (getCurrentStyle()) {
            NEUTRAL -> Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider)
            WHITE -> Color.BLACK
            else -> Color.WHITE
        }
    }

    @JvmStatic
    fun resolveBubbleColorProvider(
        whiteColorProvider: BlurredBackgroundColorProviderThemed?,
        neutralColorProvider: BlurredBackgroundColorProviderThemed?,
        accentColorProvider: BlurredBackgroundColorProviderThemed?,
    ): BlurredBackgroundColorProviderThemed? {
        return when (getCurrentStyle()) {
            WHITE -> whiteColorProvider ?: neutralColorProvider
            NEUTRAL -> neutralColorProvider
            else -> accentColorProvider ?: neutralColorProvider
        }
    }
}
