package desu.inugram.ui.appicons

// entiny: ported from exteraless (app.exteraless.appicons), GPL-3.0

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CheckBox2
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LauncherIconController.LauncherIcon
import kotlin.math.max
import kotlin.math.min

class AppIconCell(context: Context, private val resourcesProvider: Theme.ResourcesProvider?) : LinearLayout(context) {
    private val previewContainer = FrameLayout(context)
    private val preview = IconPreviewView(context, 5)
    private val checkBox: CheckBox2
    private val title = TextView(context)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selection = 0f
    private var selectionAnimator: ValueAnimator? = null

    val icon: LauncherIcon? get() = preview.icon

    init {
        setWillNotDraw(false)
        clipChildren = false
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(12f))

        previewContainer.clipChildren = false
        previewContainer.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.NO_GRAVITY))
        checkBox = CheckBox2(context, 21, object : Theme.ResourcesProvider {
            override fun getColor(key: Int): Int =
                if (key == Theme.key_windowBackgroundWhite) selectionColor() else Theme.getColor(key, resourcesProvider)
        })
        checkBox.setColor(Theme.key_featuredStickers_addButton, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck)
        checkBox.setDrawUnchecked(false)
        checkBox.setDrawBackgroundAsArc(4)
        checkBox.setProgressDelegate { progress -> preview.setPreviewScale(1f - progress * 0.08f) }
        previewContainer.addView(checkBox, LayoutHelper.createFrame(24f, 24f, Gravity.RIGHT or Gravity.BOTTOM, 0f, 0f, 1f, 1f))
        addView(previewContainer, LayoutHelper.createLinear(68, 68, Gravity.CENTER_HORIZONTAL))

        title.maxLines = 2
        title.gravity = Gravity.CENTER
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        title.setLineSpacing(0f, 0.95f)
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider))
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0))

        ScaleStateListAnimator.apply(this, 0.05f, 1.2f)
    }

    fun set(icon: LauncherIcon, checked: Boolean, animated: Boolean) {
        preview.setIcon(icon, false)
        title.text = AppIcons.title(icon)
        checkBox.setChecked(checked, animated)
        val to = if (checked) 1f else 0f
        selectionAnimator?.cancel()
        selectionAnimator = null
        if (!animated) {
            selection = to
            invalidate()
            return
        }
        if (selection == to) return
        selectionAnimator = ValueAnimator.ofFloat(selection, to).setDuration(220).apply {
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                selection = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = max(
            AndroidUtilities.dp(40f),
            min(AndroidUtilities.dp(68f), MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight),
        )
        val params = previewContainer.layoutParams as LayoutParams
        if (params.width != size) {
            params.width = size
            params.height = size
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun selectionColor(): Int = ColorUtils.blendARGB(
        Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
        Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider),
        selection * 0.07f,
    )

    override fun onDraw(canvas: Canvas) {
        if (selection <= 0f) return
        selectionPaint.color = selectionColor()
        AndroidUtilities.rectTmp.set(
            AndroidUtilities.dp(2f).toFloat(), AndroidUtilities.dp(2f).toFloat(),
            (width - AndroidUtilities.dp(2f)).toFloat(), (height - AndroidUtilities.dp(2f)).toFloat(),
        )
        canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(16f).toFloat(), AndroidUtilities.dp(16f).toFloat(), selectionPaint)
    }
}
