package desu.inugram.ui.appicons

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LauncherIconController.LauncherIcon
import kotlin.math.max
import kotlin.math.min

class AppIconCell(context: Context, private val resourcesProvider: Theme.ResourcesProvider?) : LinearLayout(context) {
    private val previewContainer = object : FrameLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            drawRing(canvas, this)
            super.dispatchDraw(canvas)
        }
    }
    private val preview = IconPreviewView(context, 4)
    private val lock = ImageView(context)
    private val title = TextView(context)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPath = Path()
    private var selection = 0f
    private var selectionAnimator: ValueAnimator? = null

    val icon: LauncherIcon? get() = preview.icon

    init {
        clipChildren = false
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f))

        previewContainer.setWillNotDraw(false)
        previewContainer.clipChildren = false
        previewContainer.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.NO_GRAVITY))
        lock.setImageResource(R.drawable.entiny_app_passcode)
        lock.setColorFilter(0xFFFFFFFF.toInt())
        lock.setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f))
        lock.background = Theme.createCircleDrawable(AndroidUtilities.dp(22f), 0xCC000000.toInt())
        previewContainer.addView(lock, LayoutHelper.createFrame(22f, 22f, Gravity.RIGHT or Gravity.TOP, 0f, 2f, 2f, 0f))
        addView(previewContainer, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL))

        title.maxLines = 2
        title.gravity = Gravity.CENTER
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
        title.setLineSpacing(0f, 0.95f)
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0))
        updateTitleColor()

        ScaleStateListAnimator.apply(this, 0.05f, 1.2f)
    }

    fun set(icon: LauncherIcon, checked: Boolean, animated: Boolean) {
        preview.setIcon(icon, false)
        title.text = AppIcons.title(icon)
        lock.visibility = if (AppIcons.locked(icon)) VISIBLE else GONE
        val to = if (checked) 1f else 0f
        selectionAnimator?.cancel()
        selectionAnimator = null
        if (!animated) {
            applySelection(to)
            return
        }
        if (selection == to) return
        selectionAnimator = ValueAnimator.ofFloat(selection, to).setDuration(240).apply {
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener { applySelection(it.animatedValue as Float) }
            start()
        }
    }

    private fun applySelection(value: Float) {
        selection = value
        preview.setPreviewScale(1f - value * 0.1f)
        updateTitleColor()
        previewContainer.invalidate()
    }

    private fun updateTitleColor() {
        val base = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
        val accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)
        title.setTextColor(ColorUtils.blendARGB(ColorUtils.setAlphaComponent(base, 190), accent, selection))
        title.typeface = if (selection > 0.5f) AndroidUtilities.bold() else null
    }

    private fun drawRing(canvas: Canvas, container: FrameLayout) {
        if (selection <= 0f) return
        val stroke = AndroidUtilities.dp(2.5f).toFloat()
        val half = stroke / 2f
        ringPaint.strokeWidth = stroke
        ringPaint.color = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), (selection * 255).toInt())
        val radius = (container.width - AndroidUtilities.dp(8f)) * 0.28f + AndroidUtilities.dp(3f)
        AndroidUtilities.rectTmp.set(half, half, container.width - half, container.height - half)
        ringPath.rewind()
        ringPath.addRoundRect(AndroidUtilities.rectTmp, radius, radius, Path.Direction.CW)
        canvas.drawPath(ringPath, ringPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = max(
            AndroidUtilities.dp(44f),
            min(AndroidUtilities.dp(72f), MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight),
        )
        val params = previewContainer.layoutParams as LayoutParams
        if (params.width != size) {
            params.width = size
            params.height = size
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
