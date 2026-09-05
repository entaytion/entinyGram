package desu.inugram.helpers.chat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.Utilities

/** Row of round fixed-zoom-level buttons (1x/2x/3x/5x/10x) for the round-video recorder. */
class ZoomLevelButtonsView(context: Context) : LinearLayout(context) {

    private var onLevel: Utilities.Callback<Float>? = null
    private var activeLevel: Float = 1f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    fun setDelegate(callback: Utilities.Callback<Float>) {
        onLevel = callback
    }

    fun setLevels(levels: List<Float>) {
        removeAllViews()
        for (level in levels) {
            val button = TextView(context).apply {
                text = formatLevel(level)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                applyStyle(this, level == activeLevel)
                setOnClickListener {
                    activeLevel = level
                    refreshBackgrounds()
                    try {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    } catch (_: Exception) {
                    }
                    onLevel?.run(level)
                }
            }
            addView(button, LayoutParams(dp(30f), dp(30f)).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
            })
        }
    }

    fun setActiveLevel(level: Float?) {
        activeLevel = level ?: -1f
        refreshBackgrounds()
    }

    private fun refreshBackgrounds() {
        for (i in 0 until childCount) {
            val button = getChildAt(i) as? TextView ?: continue
            val level = levelFromLabel(button.text.toString())
            applyStyle(button, level == activeLevel)
        }
    }

    private fun applyStyle(button: TextView, selected: Boolean) {
        // a dark fill would blend into the (usually near-black) camera preview behind it,
        // so unselected pills stay white-tinted too, just fainter than the selected one.
        button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (selected) Color.argb(230, 255, 255, 255) else Color.argb(60, 255, 255, 255))
        }
    }

    private fun formatLevel(level: Float): String =
        if (level == level.toInt().toFloat()) "${level.toInt()}×" else "$level×"

    private fun levelFromLabel(label: String): Float =
        label.trimEnd('×').toFloatOrNull() ?: -1f
}
