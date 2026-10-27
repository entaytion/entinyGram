package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

// entiny: style and color of the deleted mark picked inline, no dialogs
@SuppressLint("ViewConstructor")
class DeletedMarkCell(
    context: Context,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val styleRow = LinearLayout(context)
    private val colorTitle: TextView
    private val colorRow = LinearLayout(context)
    private val hueBar = HueBar(context)

    init {
        orientation = VERTICAL
        setPadding(AndroidUtilities.dp(21f), AndroidUtilities.dp(12f), AndroidUtilities.dp(21f), AndroidUtilities.dp(16f))

        addView(title(LocaleController.getString(R.string.InuDeletedMark)))
        styleRow.gravity = Gravity.CENTER
        styleRow.setPadding(0, AndroidUtilities.dp(12f), 0, 0)
        addView(styleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        colorTitle = title(LocaleController.getString(R.string.InuDeletedMarkColor))
        colorTitle.setPadding(0, AndroidUtilities.dp(18f), 0, 0)
        addView(colorTitle)
        colorRow.gravity = Gravity.CENTER
        colorRow.setPadding(0, AndroidUtilities.dp(12f), 0, 0)
        addView(colorRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        addView(hueBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0f, 10f, 0f, 0f))
        rebuild()
    }

    private fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
    }

    private fun rebuild() {
        val style = InuConfig.DELETED_MARK_STYLE.value
        val color = InuConfig.DELETED_MARK_COLOR.value
        styleRow.removeAllViews()
        for ((value, icon, size) in STYLES) {
            styleRow.addView(StyleChip(context, icon, size, style == value, if (icon == 0) LocaleController.getString(R.string.InuDeletedMarkText) else null) {
                if (InuConfig.DELETED_MARK_STYLE.value == value) return@StyleChip
                InuConfig.DELETED_MARK_STYLE.value = value
                rebuild()
                onChanged()
            }, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        }
        val hasMark = style != InuConfig.DeletedMarkStyleItem.NOTHING
        colorTitle.visibility = if (hasMark) VISIBLE else GONE
        colorRow.visibility = if (hasMark) VISIBLE else GONE
        val custom = color != 0 && color !in PALETTE
        hueBar.visibility = if (hasMark && (custom || customOpen)) VISIBLE else GONE
        hueBar.setColor(color)

        colorRow.removeAllViews()
        for (c in PALETTE) {
            colorRow.addView(ColorCircle(context, c, color == c, false) {
                customOpen = false
                InuConfig.DELETED_MARK_COLOR.value = c
                rebuild()
                onChanged()
            }, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
        }
        colorRow.addView(ColorCircle(context, if (custom) color else 0, custom || customOpen, true) {
            customOpen = true
            if (InuConfig.DELETED_MARK_COLOR.value in PALETTE) InuConfig.DELETED_MARK_COLOR.value = Color.HSVToColor(floatArrayOf(280f, 0.85f, 0.95f))
            rebuild()
            onChanged()
        }, LinearLayout.LayoutParams(0, AndroidUtilities.dp(42f), 1f))
    }

    private var customOpen = false

    private inner class HueBar(context: Context) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var hue = 280f

        fun setColor(color: Int) {
            if (color == 0) return
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hue = hsv[0]
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
            barPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val barH = AndroidUtilities.dp(10f).toFloat()
            val top = (height - barH) / 2f
            canvas.drawRoundRect(0f, top, width.toFloat(), top + barH, barH / 2f, barH / 2f, barPaint)
            val x = (hue / 360f) * width
            thumbPaint.color = Color.WHITE
            canvas.drawCircle(x, height / 2f, AndroidUtilities.dp(13f).toFloat(), thumbPaint)
            thumbPaint.color = Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f))
            canvas.drawCircle(x, height / 2f, AndroidUtilities.dp(10f).toFloat(), thumbPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            hue = (event.x / width * 360f).coerceIn(0f, 359f)
            InuConfig.DELETED_MARK_COLOR.value = Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f))
            invalidate()
            onChanged()
            if (event.actionMasked == MotionEvent.ACTION_UP) rebuild()
            return true
        }
    }

    private class StyleChip(context: Context, iconRes: Int, sizeDp: Float, selected: Boolean, label: String?, onClick: () -> Unit) : FrameLayout(context) {
        init {
            val tint = Theme.getColor(if (selected) Theme.key_windowBackgroundWhiteBlueIcon else Theme.key_windowBackgroundWhiteGrayIcon)
            if (label != null) {
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    setTextColor(tint)
                }, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
            }
            val icon = ImageView(context).apply {
                if (iconRes != 0) setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(
                    Theme.getColor(if (selected) Theme.key_windowBackgroundWhiteBlueIcon else Theme.key_windowBackgroundWhiteGrayIcon),
                    PorterDuff.Mode.SRC_IN,
                )
            }
            if (label == null) addView(icon, LayoutHelper.createFrame(sizeDp, sizeDp, Gravity.CENTER))
            if (selected) {
                background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_listSelector))
            }
            setOnClickListener { onClick() }
        }
    }

    private class ColorCircle(
        context: Context,
        private val colorValue: Int,
        private val checked: Boolean,
        private val rainbow: Boolean,
        onClick: () -> Unit,
    ) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = AndroidUtilities.dpf2(2f)
        }

        init {
            setOnClickListener { onClick() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (rainbow) {
                val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
                fill.shader = android.graphics.SweepGradient(w / 2f, h / 2f, colors, null)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val outer = minOf(width, height) / 2f - AndroidUtilities.dpf2(1f)
            val base = if (colorValue == 0) 0xFF8E8E93.toInt() else colorValue
            if (!rainbow) fill.color = base
            ring.color = if (rainbow && colorValue == 0) Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon) else base
            if (checked) {
                canvas.drawCircle(cx, cy, outer, ring)
                canvas.drawCircle(cx, cy, outer - AndroidUtilities.dpf2(4f), fill)
            } else {
                canvas.drawCircle(cx, cy, outer - AndroidUtilities.dpf2(2f), fill)
            }
        }
    }

    companion object {
        val PALETTE = intArrayOf(
            0,
            0xFFFF0000.toInt(),
            0xFFDC2626.toInt(),
            0xFFDB2777.toInt(),
            0xFFC026D3.toInt(),
            0xFF9333EA.toInt(),
            0xFF4F46E5.toInt(),
            0xFF2563EB.toInt(),
        )

        // entiny: the 14dp vector marks fill their box, the 24dp tabler outline and stock cancel carry their own padding
        private val STYLES = listOf(
            Triple(InuConfig.DeletedMarkStyleItem.NOTHING, R.drawable.msg_cancel, 22f),
            Triple(InuConfig.DeletedMarkStyleItem.TEXT, 0, 0f),
            Triple(InuConfig.DeletedMarkStyleItem.TRASH_BIN, R.drawable.ic_deleted_mark_trash, 16f),
            Triple(InuConfig.DeletedMarkStyleItem.TRASH_BIN_OUTLINE, R.drawable.inu_tabler_trash, 15f),
            Triple(InuConfig.DeletedMarkStyleItem.CROSS, R.drawable.ic_deleted_mark_cross, 16f),
            Triple(InuConfig.DeletedMarkStyleItem.EYE_CROSSED, R.drawable.ic_deleted_mark_eye_off, 16f),
        )
    }
}
