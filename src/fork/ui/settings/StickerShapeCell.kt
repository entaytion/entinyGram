package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.StickerShapeHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.Theme

@SuppressLint("ViewConstructor")
class StickerShapeCell(
    context: Context,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val cards = ArrayList<Card>()

    init {
        orientation = HORIZONTAL
        setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f))
        val labels = intArrayOf(R.string.InuStickerShapeDefault, R.string.InuStickerShapeRounded, R.string.InuStickerShapeMessage)
        for (i in labels.indices) {
            val card = Card(context, i, LocaleController.getString(labels[i]))
            card.setOnClickListener {
                if (InuConfig.STICKER_SHAPE.value == i) return@setOnClickListener
                InuConfig.STICKER_SHAPE.value = i
                cards.forEach { it.invalidate() }
                onChanged()
            }
            cards.add(card)
            addView(card, LayoutParams(0, AndroidUtilities.dp(104f), 1f).apply {
                leftMargin = AndroidUtilities.dp(4f)
                rightMargin = AndroidUtilities.dp(4f)
            })
        }
    }

    private class Card(context: Context, private val index: Int, private val label: String) : View(context) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = AndroidUtilities.dp(2f).toFloat()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = AndroidUtilities.dp(13f).toFloat()
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val selected = InuConfig.STICKER_SHAPE.value == index
            val w = width.toFloat()
            val h = AndroidUtilities.dp(80f).toFloat()
            bg.color = Theme.getColor(Theme.key_windowBackgroundWhite)
            rect.set(0f, 0f, w, h)
            val r = AndroidUtilities.dp(10f).toFloat()
            bg.color = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.12f)
            canvas.drawRoundRect(rect, r, r, bg)
            if (selected) {
                stroke.color = Theme.getColor(Theme.key_windowBackgroundWhiteValueText)
                rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
                canvas.drawRoundRect(rect, r, r, stroke)
            }

            val pad = AndroidUtilities.dp(10f).toFloat()
            rect.set(pad, pad, w - pad, h - pad)
            fill.color = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.3f)
            val big = AndroidUtilities.dp(SharedConfig.bubbleRadius.toFloat()).toFloat().coerceAtMost((h - pad * 2) / 2)
            val radii = when (index) {
                StickerShapeHelper.ROUNDED -> FloatArray(8) { AndroidUtilities.dp(6f).toFloat() }
                StickerShapeHelper.MESSAGE -> floatArrayOf(big, big, big, big, big, big, big / 3, big / 3)
                else -> FloatArray(8)
            }
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, fill)

            text.color = Theme.getColor(if (selected) Theme.key_windowBackgroundWhiteValueText else Theme.key_windowBackgroundWhiteBlackText)
            text.typeface = if (selected) AndroidUtilities.bold() else Typeface.DEFAULT
            canvas.drawText(label, w / 2, AndroidUtilities.dp(100f).toFloat(), text)
        }
    }
}
