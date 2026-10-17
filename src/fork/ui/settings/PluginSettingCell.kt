package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.view.View
import desu.inugram.helpers.theme.M3SectionsHelper
import kotlin.math.max
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell

class PluginSettingCell(
    context: Context,
    needsCheckBox: Boolean,
    private val resourcesProvider: Theme.ResourcesProvider?,
) : TextCell(context, 23, false, needsCheckBox, resourcesProvider) {
    private var multiline = false
    private var split = false

    fun setMultiline(value: Boolean) {
        if (multiline == value) return
        multiline = value
        textView.setMaxLines(if (value) Int.MAX_VALUE else 1)
        subtitleView.setMaxLines(if (value) Int.MAX_VALUE else 1)
        requestLayout()
    }

    fun setSplit(value: Boolean) {
        android.util.Log.d("InuSplit", "setSplit $value was=$split ${textView.text}")
        if (split == value) return
        split = value
        requestLayout()
        invalidate()
    }

    fun isOnSwitch(x: Float): Boolean =
        if (LocaleController.isRTL) x < AndroidUtilities.dp(SPLIT_LINE_OFFSET) else x > measuredWidth - AndroidUtilities.dp(SPLIT_LINE_OFFSET)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        android.util.Log.d("InuSplit", "dispatchDraw split=$split w=$measuredWidth h=$measuredHeight color=${Integer.toHexString(Theme.dividerPaint.color)} key=${Integer.toHexString(Theme.getColor(Theme.key_divider))} ${textView.text}")
        if (!split) return
        val x = if (LocaleController.isRTL) AndroidUtilities.dp(SPLIT_LINE_OFFSET) else measuredWidth - AndroidUtilities.dp(SPLIT_LINE_OFFSET) - 1
        val height = AndroidUtilities.dp(22f)
        val y = (measuredHeight - height) / 2
        canvas.drawRect(x.toFloat(), y.toFloat(), x + 2f, (y + height).toFloat(), M3SectionsHelper.getSplitDividerPaint(resourcesProvider))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val valueWidth = max(valueTextView.width(), valueSpoilersTextView.textWidth)
        val startPadding = if (imageView.visibility == View.VISIBLE) offsetFromImage else leftPadding
        val endPadding = when {
            checkBox?.visibility == View.VISIBLE -> if (split) SPLIT_LINE_OFFSET.toInt() + 12 else 71
            valueWidth > 0 -> leftPadding + 6
            else -> leftPadding
        }
        val availableWidth = max(0, width - AndroidUtilities.dp((startPadding + endPadding).toFloat()) - valueWidth)
        val textWidthSpec = View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST)
        val textHeightSpec = if (multiline) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(20f), View.MeasureSpec.EXACTLY)
        }
        textView.measure(textWidthSpec, textHeightSpec)
        subtitleView.measure(textWidthSpec, textHeightSpec)
        if (!multiline) return

        var contentHeight = textView.measuredHeight
        if (subtitleView.visibility == View.VISIBLE) {
            contentHeight += subtitleView.measuredHeight + AndroidUtilities.dp(if (heightDp > 50) 4f else 2f)
        }
        val dividerHeight = measuredHeight - AndroidUtilities.dp(heightDp.toFloat())
        setMeasuredDimension(width, max(measuredHeight, contentHeight + AndroidUtilities.dp(16f) + dividerHeight))
    }

    private companion object {
        const val SPLIT_LINE_OFFSET = 74f
    }
}
