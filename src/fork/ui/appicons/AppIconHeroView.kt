package desu.inugram.ui.appicons

// entiny: ported from exteraless (app.exteraless.appicons), GPL-3.0

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LauncherIconController.LauncherIcon
import kotlin.math.min

class AppIconHeroView(context: Context, private val resourcesProvider: Theme.ResourcesProvider?) : LinearLayout(context) {
    private val preview = IconPreviewView(context, 0)
    private val title: AnimatedTextView
    private val subtitle: AnimatedTextView
    private var icon: LauncherIcon? = null
    private var collapse = 0f

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false

        addView(preview, LayoutHelper.createLinear(PREVIEW_DP, PREVIEW_DP, Gravity.CENTER_HORIZONTAL))
        title = addText(22, true, 28, 18)
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
    }

    fun set(icon: LauncherIcon) {
        if (this.icon == icon) return
        val animated = this.icon != null
        this.icon = icon
        preview.setIcon(icon, animated)
        title.setText(AppIcons.title(icon), animated)
        subtitle.setText(AppIcons.description(icon) ?: "", animated)
    }

    fun setPreviewSizeDp(dp: Int) {
        val params = preview.layoutParams as LayoutParams
        val size = AndroidUtilities.dp(dp.toFloat())
        if (params.width != size) {
            params.width = size
            params.height = size
            preview.requestLayout()
        }
    }

    fun getTextBlockHeight(): Int = AndroidUtilities.dp(46f + 26f)

    fun previewCenterY(): Float = top + preview.top + preview.height / 2f

    fun setCollapse(collapse: Float) {
        if (this.collapse != collapse) {
            this.collapse = collapse
            applyCollapse()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyCollapse()
    }

    private fun applyCollapse() {
        val text = min(1f, collapse / 0.35f)
        title.alpha = 1f - text
        title.translationY = -AndroidUtilities.dp(12f) * text
        subtitle.alpha = 1f - text
        subtitle.translationY = -AndroidUtilities.dp(12f) * text
        val height = preview.measuredHeight
        if (height <= 0) return
        val scale = AndroidUtilities.lerp(1f, AndroidUtilities.dp(COLLAPSED_DP.toFloat()) / height.toFloat(), collapse)
        preview.pivotX = preview.measuredWidth / 2f
        preview.pivotY = height / 2f
        preview.scaleX = scale
        preview.scaleY = scale
        val target = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() / 2f - top
        preview.translationY = (target - (preview.top + height / 2f)) * collapse
    }

    companion object {
        const val PREVIEW_DP = 128
        private const val COLLAPSED_DP = 40
    }
}
