package desu.inugram.ui.settings

import android.content.Context
import android.view.Gravity
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

/** the small tinted tag in front of a plugin name: its version, or "official" in the accent color */
internal class PluginPill(context: Context, accent: Boolean = false) : TextView(context) {
    init {
        textSize = 11.5f
        typeface = AndroidUtilities.bold()
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(1f), AndroidUtilities.dp(6f), AndroidUtilities.dp(1.5f))
        val color = Theme.getColor(if (accent) Theme.key_windowBackgroundWhiteBlueText2 else Theme.key_windowBackgroundWhiteGrayText)
        setTextColor(color)
        background = Theme.createRoundRectDrawable(AndroidUtilities.dp(6f), Theme.multAlpha(color, 0.16f))
    }
}
