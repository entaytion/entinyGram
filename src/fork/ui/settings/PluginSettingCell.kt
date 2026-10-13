package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import kotlin.math.max
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCell

class PluginSettingCell(
    context: Context,
    needsCheckBox: Boolean,
    resourcesProvider: Theme.ResourcesProvider?,
) : TextCell(context, 23, false, needsCheckBox, resourcesProvider) {
    private var multiline = false

    fun setMultiline(value: Boolean) {
        if (multiline == value) return
        multiline = value
        textView.setMaxLines(if (value) Int.MAX_VALUE else 1)
        subtitleView.setMaxLines(if (value) Int.MAX_VALUE else 1)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val valueWidth = max(valueTextView.width(), valueSpoilersTextView.textWidth)
        val startPadding = if (imageView.visibility == View.VISIBLE) offsetFromImage else leftPadding
        val endPadding = when {
            checkBox?.visibility == View.VISIBLE -> 71
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
}
