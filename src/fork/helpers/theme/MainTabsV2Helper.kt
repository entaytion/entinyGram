package desu.inugram.helpers.theme

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import desu.inugram.InuConfig
import me.vkryl.android.AnimatorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.AndroidUtilities.lerp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.glass.GlassTabView
import org.telegram.ui.MainTabsLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object MainTabsV2Helper {
    const val ICON_SIZE = 24f
    const val LABEL_GAP = 6f
    const val LABEL_TEXT_SIZE = 13f
    private const val PILL_SIDE_PADDING = 16f
    private const val INACTIVE_WIDTH = 52f
    private const val MIN_INACTIVE_WIDTH = 36f
    private const val SCREEN_SIDE_RESERVE = 56f
    private const val PILL_ALPHA = 0.16f
    private const val PILL_APPEAR_SCALE_X = 0.9f
    private const val BADGE_X_OFFSET = 11f
    private const val BADGE_Y_OFFSET = 6f

    @JvmStatic
    val isEnabled: Boolean
        get() = InuConfig.BOTTOM_TABS_V2.value

    @JvmStatic
    fun applyTabsLayout(tabsView: MainTabsLayout) {
        if (!isEnabled) return
        tabsView.inu_v2Tabs = true
        tabsView.setMaxWidth(0)
    }

    @JvmStatic
    fun tabsLayoutParams(default: LinearLayout.LayoutParams): LinearLayout.LayoutParams =
        if (isEnabled) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT) else default

    private fun pillWidth(labelWidth: Float): Float =
        dpf2(PILL_SIDE_PADDING * 2 + ICON_SIZE) + if (labelWidth > 0f) dpf2(LABEL_GAP) + labelWidth else 0f

    // entiny: the selected tab gets icon + label in one pill, the rest stay icon-only; widths follow the target selection, the animator only moves the rects
    @JvmStatic
    fun fillWidths(layout: MainTabsLayout, widths: FloatArray, available: Int) {
        val visible = ArrayList<Int>()
        for (a in 0 until layout.childCount) {
            if (layout.isViewVisible(layout.getChildAt(a))) visible.add(a) else widths[a] = 0f
        }
        if (visible.isEmpty()) return
        val customWidth = layout.inu_widthMode != 0
        val limit = (if (customWidth) available else min(available, AndroidUtilities.displaySize.x - dp(SCREEN_SIDE_RESERVE))).coerceAtLeast(0)
        var inactive = dpf2(INACTIVE_WIDTH)
        val others = (visible.size - 1).coerceAtLeast(0)
        val selectedIndex = visible.firstOrNull { (layout.getChildAt(it) as? GlassTabView)?.isTabSelected == true }
        var active = inactive
        if (selectedIndex != null) {
            val label = (layout.getChildAt(selectedIndex) as GlassTabView).measureTextWidth()
            active = max(inactive, pillWidth(label))
        }
        if (customWidth) {
            val equal = limit.toFloat() / visible.size
            if (others > 0 && selectedIndex != null) {
                val minimum = min(dpf2(MIN_INACTIVE_WIDTH), equal)
                active = active.coerceIn(equal, (limit - minimum * others).coerceAtLeast(equal))
                inactive = (limit - active) / others
            } else {
                active = equal
                inactive = equal
            }
        } else if (selectedIndex == null) {
            if (inactive * visible.size > limit) inactive = limit.toFloat() / visible.size
        } else if (active + inactive * others > limit) {
            val minimum = min(dpf2(MIN_INACTIVE_WIDTH), limit.toFloat() / visible.size)
            inactive = max(minimum, (limit - active) / max(1, others))
            active = max(inactive, min(active, limit - inactive * others))
        }
        for (a in visible) widths[a] = if (a == selectedIndex) active else inactive
    }

    // entiny: bar width follows the animated rects, otherwise the glass background snaps while the tabs slide
    @JvmStatic
    fun animatedWidth(animatedTotal: Float, finalTotal: Int): Int =
        if (animatedTotal > 0f) animatedTotal.roundToInt() else finalTotal

    @JvmStatic
    fun needsRelayout(animatedTotal: Float, paddings: Int, measuredWidth: Int): Boolean =
        animatedTotal > 0f && animatedTotal.roundToInt() + paddings != measuredWidth

    @JvmStatic
    fun contentLeft(visualWidth: Float, factor: Float, labelWidth: Float): Float {
        val block = dpf2(ICON_SIZE) + if (labelWidth > 0f) (dpf2(LABEL_GAP) + labelWidth) * factor else 0f
        return (visualWidth - block) / 2f
    }

    @JvmStatic
    fun layoutContent(icon: View?, label: View, visualWidth: Float, factor: Float, labelWidth: Float) {
        val left = contentLeft(visualWidth, factor, labelWidth)
        icon?.translationX = left
        label.translationX = left + dpf2(ICON_SIZE) + dpf2(LABEL_GAP)
        label.alpha = if (labelWidth > 0f) factor else 0f
    }

    @JvmStatic
    fun badgeCenterX(visualWidth: Float, factor: Float, labelWidth: Float): Float =
        contentLeft(visualWidth, factor, labelWidth) + dpf2(ICON_SIZE) / 2f + dpf2(BADGE_X_OFFSET)

    @JvmStatic
    fun badgeCenterY(height: Float): Float = height / 2f - dpf2(BADGE_Y_OFFSET)

    private val pillRect = RectF()

    @JvmStatic
    fun drawPill(canvas: Canvas, paint: Paint, visualWidth: Float, height: Float, color: Int, factor: Float) {
        val alpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(factor)
        pillRect.set(0f, 0f, visualWidth, height)
        paint.color = Theme.multAlpha(color, PILL_ALPHA * alpha)
        canvas.save()
        canvas.scale(lerp(PILL_APPEAR_SCALE_X, 1f, factor), 1f, visualWidth / 2f, height / 2f)
        canvas.drawRoundRect(pillRect, height / 2f, height / 2f, paint)
        canvas.restore()
    }
}
