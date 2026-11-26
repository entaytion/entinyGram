package desu.inugram.helpers.chat

import android.content.Context

class ExposureLevelButtonsView(context: Context) : LevelButtonsView(context, activeLevel = 0f, noLevel = 0f) {

    override fun formatLevel(level: Float): String {
        val rounded = Math.round(level * 10f) / 10f
        val sign = if (rounded > 0f) "+" else ""
        return if (rounded == rounded.toInt().toFloat()) "$sign${rounded.toInt()}" else "$sign$rounded"
    }

    override fun levelFromLabel(label: String): Float =
        label.toFloatOrNull() ?: 0f
}
