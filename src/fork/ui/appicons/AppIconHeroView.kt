package desu.inugram.ui.appicons

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LauncherIconController.LauncherIcon

class AppIconHeroView(context: Context, private val resourcesProvider: Theme.ResourcesProvider?) : LinearLayout(context) {
    private val mock = MockView(context)
    private val title: AnimatedTextView
    private val subtitle: AnimatedTextView
    private var icon: LauncherIcon? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(mock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, MOCK_DP, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0))
        title = addText(22, true, 28, 20)
        subtitle = addText(14, false, 20, 6)
        updateColors()
    }

    private fun addText(sizeDp: Int, bold: Boolean, heightDp: Int, topDp: Int): AnimatedTextView {
        val view = AnimatedTextView(context, true, true, false).apply {
            setGravity(Gravity.CENTER)
            setTypeface(if (bold) AndroidUtilities.bold() else null)
            setTextSize(AndroidUtilities.dp(sizeDp.toFloat()).toFloat())
            setIncludeFontPadding(false)
            setAllowCancel(true)
            setAnimationProperties(0.35f, 0L, 260L, CubicBezierInterpolator.EASE_OUT_QUINT)
        }
        addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, heightDp, Gravity.CENTER_HORIZONTAL, 24, topDp, 24, 0))
        return view
    }

    fun updateColors() {
        val color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
        title.setTextColor(color)
        subtitle.setTextColor(ColorUtils.setAlphaComponent(color, 179))
        mock.updateColors()
    }

    fun set(icon: LauncherIcon) {
        if (this.icon == icon) return
        val animated = this.icon != null
        this.icon = icon
        mock.preview.setIcon(icon, animated)
        title.setText(AppIcons.title(icon), animated)
        subtitle.setText(AppIcons.description(icon) ?: "", animated)
    }

    fun setTint(tint: Int) = mock.setTint(tint)

    fun previewCenterY(): Float = top + mock.top + mock.height / 2f

    private inner class MockView(context: Context) : FrameLayout(context) {
        val preview = IconPreviewView(context, 0)
        private val label = TextView(context)
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val card = Path()
        private val ghost = Path()
        private var tint = 0

        init {
            setWillNotDraw(false)
            clipChildren = false
            addView(preview, LayoutHelper.createFrame(ICON_DP, ICON_DP.toFloat(), Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0f, 34f, 0f, 0f))
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            label.text = context.applicationInfo.loadLabel(context.packageManager)
            addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0f, (34 + ICON_DP + 8).toFloat(), 0f, 0f))
            updateColors()
        }

        fun updateColors() {
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
            invalidate()
        }

        fun setTint(color: Int) {
            tint = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val white = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)
            val text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
            val w = width.toFloat()
            val h = height.toFloat()
            card.rewind()
            card.addRoundRect(0f, 0f, w, h, AndroidUtilities.dp(28f).toFloat(), AndroidUtilities.dp(28f).toFloat(), Path.Direction.CW)
            cardPaint.color = if (tint == 0) white else ColorUtils.blendARGB(white, tint, 0.45f)
            canvas.drawPath(card, cardPaint)
            canvas.save()
            canvas.clipPath(card)
            ghostPaint.color = ColorUtils.setAlphaComponent(text, 0x12)
            val cx = w / 2f
            val top = AndroidUtilities.dp(34f).toFloat()
            val size = AndroidUtilities.dp(ICON_DP * 0.62f).toFloat()
            val pad = AndroidUtilities.dp(ICON_DP * 0.5f + 22f)
            val ghostTop = top + (AndroidUtilities.dp(ICON_DP.toFloat()) - size) / 2f
            for (side in intArrayOf(-1, 1)) {
                for (step in 0..1) {
                    val center = cx + side * (pad + step * (size + AndroidUtilities.dp(16f)))
                    ghost.rewind()
                    ghost.addRoundRect(center - size / 2f, ghostTop, center + size / 2f, ghostTop + size, size * 0.28f, size * 0.28f, Path.Direction.CW)
                    canvas.drawPath(ghost, ghostPaint)
                    val bar = AndroidUtilities.dp(26f).toFloat()
                    val barTop = ghostTop + size + AndroidUtilities.dp(10f)
                    canvas.drawRoundRect(center - bar / 2f, barTop, center + bar / 2f, barTop + AndroidUtilities.dp(5f), AndroidUtilities.dp(3f).toFloat(), AndroidUtilities.dp(3f).toFloat(), ghostPaint)
                }
            }
            canvas.restore()
        }
    }

    companion object {
        const val MOCK_DP = 186
        private const val ICON_DP = 88
    }
}
