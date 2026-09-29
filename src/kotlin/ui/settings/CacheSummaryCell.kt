package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

@SuppressLint("ViewConstructor")
class CacheSummaryCell(
    context: Context,
    private val onClearAll: () -> Unit,
    private val onRowClick: (Int) -> Unit,
) : LinearLayout(context) {

    class Row(val color: Int, val icon: Int, val title: String, val detail: String, val size: Long)

    private class UsageBar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var segments: List<Pair<Int, Long>> = emptyList()

        fun setSegments(value: List<Pair<Int, Long>>) {
            segments = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            val total = segments.sumOf { it.second }
            val gap = AndroidUtilities.dp(2f).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val save = canvas.save()
            val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            paint.color = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 40)
            canvas.drawRect(rect, paint)
            if (total > 0) {
                val used = width - gap * (segments.count { it.second > 0 } - 1).coerceAtLeast(0)
                var x = 0f
                for ((color, size) in segments) {
                    if (size <= 0) continue
                    val w = (used * size / total).toFloat().coerceAtLeast(AndroidUtilities.dp(4f).toFloat())
                    paint.color = color
                    canvas.drawRect(x, 0f, (x + w).coerceAtMost(width.toFloat()), height.toFloat(), paint)
                    x += w + gap
                }
            }
            canvas.restoreToCount(save)
        }
    }

    private val sizeView: TextView
    private val subtitleView: TextView
    private val clearButton: TextView
    private val bar: UsageBar
    private val rowsLayout: LinearLayout

    init {
        orientation = VERTICAL
        val margin = AndroidUtilities.dp(16f)
        setPadding(margin, AndroidUtilities.dp(18f), margin, AndroidUtilities.dp(16f))
        background = Theme.createRoundRectDrawable(AndroidUtilities.dp(16f), Theme.getColor(Theme.key_windowBackgroundWhite))

        sizeView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32f)
            setTypeface(AndroidUtilities.bold())
        }
        subtitleView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        }
        addView(sizeView)
        addView(subtitleView)

        bar = UsageBar(context)
        addView(bar, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(10f)).apply {
            topMargin = AndroidUtilities.dp(14f)
            bottomMargin = AndroidUtilities.dp(6f)
        })

        rowsLayout = LinearLayout(context).apply { orientation = VERTICAL }
        addView(rowsLayout, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        clearButton = TextView(context).apply {
            text = LocaleController.getString(R.string.InuCacheClearAll)
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTypeface(AndroidUtilities.bold())
            setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(12f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed),
            )
            setOnClickListener { if (isEnabled) onClearAll() }
        }
        addView(
            clearButton,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply { topMargin = AndroidUtilities.dp(12f) },
        )
    }

    fun bind(sizeLabel: String, subtitle: String, rows: List<Row>, clearEnabled: Boolean) {
        sizeView.text = sizeLabel
        subtitleView.text = subtitle
        clearButton.isEnabled = clearEnabled
        clearButton.alpha = if (clearEnabled) 1f else 0.5f
        bar.setSegments(rows.map { it.color to it.size })
        rowsLayout.removeAllViews()
        rows.forEachIndexed { index, row -> rowsLayout.addView(buildRow(index, row)) }
    }

    private fun buildRow(index: Int, row: Row): View {
        val ctx = context
        val line = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2)
            setOnClickListener { onRowClick(index) }
        }
        val circle = FrameLayout(ctx).apply {
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(18f), ColorUtils.setAlphaComponent(row.color, 48))
        }
        circle.addView(ImageView(ctx).apply {
            setImageResource(row.icon)
            setColorFilter(row.color, PorterDuff.Mode.SRC_IN)
        }, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        line.addView(circle, LayoutHelper.createLinear(36, 36))

        val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = row.title
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            maxLines = 1
        })
        texts.addView(TextView(ctx).apply {
            text = row.detail
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            maxLines = 1
        })
        line.addView(texts, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply { leftMargin = AndroidUtilities.dp(12f) })

        line.addView(TextView(ctx).apply {
            text = AndroidUtilities.formatFileSize(row.size)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        })
        return line
    }
}
