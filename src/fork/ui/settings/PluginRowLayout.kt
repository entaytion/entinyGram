package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.children
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import kotlin.math.ceil
import kotlin.math.max

/** a list row's name, with an optional warning sign right after it; lay it out wrap_content */
internal class RowTitle(context: Context) : TextView(context) {
    private val warningDrawable = ResourcesCompat.getDrawable(resources, R.drawable.inu_tabler_alert_triangle_filled, null)
        ?.mutate()
        ?.apply {
            // bounds keep their height, so the offset only nudges the glyph off the text's optical center
            val size = AndroidUtilities.dp(WARNING_SIZE_DP)
            val offset = AndroidUtilities.dp(WARNING_OFFSET_DP)
            setBounds(0, offset, size, size + offset)
        }

    init {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        textSize = 16f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        compoundDrawablePadding = AndroidUtilities.dp(5f)
        // keeps the warning off the trailing controls once the name is long enough to ellipsize
        setPaddingRelative(0, 0, AndroidUtilities.dp(8f), 0)
    }

    fun setWarning(color: Int?) {
        if (color != null) warningDrawable?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        setCompoundDrawablesRelative(null, null, warningDrawable?.takeIf { color != null }, null)
    }

    // an ellipsized line stops short of the width wrap_content took, which would leave the
    // warning drawable parked at the row's edge instead of right after the ellipsis
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return
        val line = layout?.takeIf { it.lineCount == 1 } ?: return
        val fitted = ceil(line.getLineWidth(0)).toInt() + compoundPaddingLeft + compoundPaddingRight
        if (fitted < measuredWidth) setMeasuredDimension(fitted, measuredHeight)
    }

    companion object {
        private const val WARNING_SIZE_DP = 12.5f
        private const val WARNING_OFFSET_DP = 1.5f
    }
}

/** pill buttons of a normal-view list row; they stack once any of them would wrap */
internal class RowActions(context: Context) : LinearLayout(context) {
    init {
        gravity = Gravity.END
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        orientation = HORIZONTAL
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (children.any { it.visibility != GONE && (it as TextView).lineCount > 1 }) {
            orientation = VERTICAL
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun addAction(textRes: Int, red: Boolean): TextView = TextView(context).apply {
        val color = Theme.getColor(if (red) Theme.key_text_RedBold else Theme.key_windowBackgroundWhiteBlueText2)
        text = LocaleController.getString(textRes)
        textSize = 14f
        typeface = AndroidUtilities.bold()
        setTextColor(color)
        setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(7f), AndroidUtilities.dp(14f), AndroidUtilities.dp(7f))
        background = Theme.createRadSelectorDrawable(
            if (red) Theme.multAlpha(color, 0.12f) else Theme.getColor(Theme.key_listSelector),
            ACTION_RADIUS_DP,
            ACTION_RADIUS_DP,
        )
        this@RowActions.addView(this, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))
    }

    companion object {
        private const val ACTION_RADIUS_DP = 18
    }
}

/** [actions] sit beside the [gutter] while the content is short, and take its width below it otherwise */
@SuppressLint("ViewConstructor")
internal class RowBody(
    context: Context,
    private val gutter: View,
    private val content: View,
    private val actions: RowActions,
) : ViewGroup(context) {
    private val rtl = LocaleController.isRTL
    private val gutterStart = AndroidUtilities.dp(8f)
    private val contentStart = AndroidUtilities.dp(8f + 40f + 12f)
    private val actionsEnd = AndroidUtilities.dp(8f)
    private var actionsY = 0

    init {
        addView(gutter)
        addView(content)
        addView(actions)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        gutter.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(40f), MeasureSpec.EXACTLY), unspecified)
        content.measure(MeasureSpec.makeMeasureSpec(width - contentStart, MeasureSpec.EXACTLY), unspecified)
        actionsY = content.measuredHeight + AndroidUtilities.dp(6f)
        if (actionsY < gutter.measuredHeight) {
            actions.measure(MeasureSpec.makeMeasureSpec(width - contentStart - actionsEnd, MeasureSpec.AT_MOST), unspecified)
            if (actions.orientation == LinearLayout.VERTICAL) actionsY = gutter.measuredHeight
        }
        if (actionsY >= gutter.measuredHeight) {
            actions.measure(MeasureSpec.makeMeasureSpec(width - gutterStart - actionsEnd, MeasureSpec.AT_MOST), unspecified)
        }
        setMeasuredDimension(width, max(gutter.measuredHeight, actionsY + actions.measuredHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(gutter, gutterStart, 0)
        place(content, contentStart, 0)
        place(actions, width - actionsEnd - actions.measuredWidth, actionsY)
    }

    private fun place(child: View, x: Int, y: Int) {
        val left = if (rtl) width - x - child.measuredWidth else x
        child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
    }
}
