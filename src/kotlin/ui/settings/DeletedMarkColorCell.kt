package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.ChatHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

@SuppressLint("ViewConstructor")
class DeletedMarkColorCell(
    context: Context,
    private val onColorChanged: (Int) -> Unit,
) : LinearLayout(context) {

    private val titleView: TextView
    private val iconView: ImageView
    private val colorRow: LinearLayout

    companion object {
        val PALETTE_COLORS = intArrayOf(
            0,                     // 0 = Default (Theme gray / unset)
            0xFFFF0000.toInt(),    // Red
            0xFFDC2626.toInt(),    // Crimson
            0xFFDB2777.toInt(),    // Pink
            0xFFC026D3.toInt(),    // Magenta
            0xFF9333EA.toInt(),    // Purple
            0xFF4F46E5.toInt(),    // Indigo
            0xFF2563EB.toInt(),    // Blue
        )
    }

    init {
        orientation = VERTICAL
        setPadding(AndroidUtilities.dp(21f), AndroidUtilities.dp(12f), AndroidUtilities.dp(21f), AndroidUtilities.dp(16f))

        // Top Row: Title + Deleted Icon
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleView = TextView(context).apply {
            text = LocaleController.getString(R.string.InuDeletedMarkColor)
            textSize = 16f
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        }
        topRow.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        iconView = ImageView(context).apply {
            setImageResource(ChatHelper.deletedMarkIconRes())
        }
        topRow.addView(iconView, LayoutHelper.createLinear(24, 24))
        addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        updateIconTint()

        // Bottom Row: Color Circles
        colorRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(14f), 0, 0)
        }

        buildColorCircles()
        addView(colorRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    fun refreshIcon() {
        iconView.setImageResource(ChatHelper.deletedMarkIconRes())
        updateIconTint()
    }

    private fun updateIconTint() {
        val selected = InuConfig.DELETED_MARK_COLOR.value
        if (selected == 0) {
            iconView.colorFilter = PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
                PorterDuff.Mode.SRC_IN
            )
        } else {
            iconView.colorFilter = PorterDuffColorFilter(selected, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun buildColorCircles() {
        colorRow.removeAllViews()
        val current = InuConfig.DELETED_MARK_COLOR.value

        for (color in PALETTE_COLORS) {
            val circleView = ColorCircleView(context, color, isChecked = (current == color)) {
                InuConfig.DELETED_MARK_COLOR.value = color
                updateIconTint()
                buildColorCircles()
                onColorChanged(color)
            }
            colorRow.addView(
                circleView,
                LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f),
            )
        }
    }

    private class ColorCircleView(
        context: Context,
        val colorValue: Int,
        val isChecked: Boolean,
        val onClickAction: () -> Unit,
    ) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (colorValue == 0) 0xFF8E8E93.toInt() else colorValue
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = AndroidUtilities.dpf2(2f)
            color = if (colorValue == 0) 0xFF8E8E93.toInt() else colorValue
        }

        init {
            setOnClickListener { onClickAction() }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val outerRadius = minOf(width, height) / 2f - AndroidUtilities.dpf2(1f)

            if (isChecked) {
                // Outer ring
                canvas.drawCircle(cx, cy, outerRadius, ringPaint)
                // Inner circle with gap
                val innerRadius = outerRadius - AndroidUtilities.dpf2(4f)
                canvas.drawCircle(cx, cy, innerRadius, fillPaint)
            } else {
                val radius = outerRadius - AndroidUtilities.dpf2(2f)
                canvas.drawCircle(cx, cy, radius, fillPaint)
            }
        }
    }
}
