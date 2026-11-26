package desu.inugram.helpers.chat

import android.content.Context

class ZoomLevelButtonsView(context: Context) : LevelButtonsView(context, activeLevel = 1f, noLevel = -1f) {

    override fun formatLevel(level: Float): String =
        if (level == level.toInt().toFloat()) "${level.toInt()}×" else "$level×"

    override fun levelFromLabel(label: String): Float =
        label.trimEnd('×').toFloatOrNull() ?: -1f
}
