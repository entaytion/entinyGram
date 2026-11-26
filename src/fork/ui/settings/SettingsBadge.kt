package desu.inugram.ui.settings

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

object SettingsBadge {
    fun title(
        text: CharSequence,
        slug: String = "",
        experimental: Boolean = false,
        isNew: Boolean = NewFeatureBadges.isNew(slug),
        detail: CharSequence? = null,
        enabledCount: Int? = null,
    ): CharSequence {
        if (!experimental && !isNew && detail.isNullOrEmpty() && enabledCount == null) return text

        return SpannableStringBuilder().apply {
            if (experimental) {
                append(pill(LocaleController.getString(R.string.InuBetaPill), Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader)))
                append("  ")
            }
            append(text)
            if (!detail.isNullOrEmpty()) append(" ").append(pill(detail))
            if (isNew) append(" ").append(pill(LocaleController.getString(R.string.InuNewBadge)))
            if (enabledCount != null) append("  ").append(state(enabledCount))
        }
    }

    private fun pill(label: CharSequence, tint: Int = Theme.getColor(Theme.key_featuredStickers_addButton)): CharSequence {
        val pill = SpannableString(" ")
        pill.setSpan(PillSpan(label.toString(), tint), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return pill
    }

    /** "Off" in gray when nothing is enabled, otherwise the count in the accent color */
    private fun state(enabledCount: Int): CharSequence =
        if (enabledCount == 0) {
            pill(LocaleController.getString(R.string.InuSectionOff), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        } else {
            pill(enabledCount.toString())
        }

    /** small tinted pill drawn inline with a settings title */
    private class PillSpan(private val label: String, tint: Int) : ReplacementSpan() {
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = AndroidUtilities.bold()
            textSize = AndroidUtilities.dp(11f).toFloat()
            color = tint
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            alpha = 40
        }
        private val padX = AndroidUtilities.dp(6f)
        private val padY = AndroidUtilities.dp(2.5f)

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: FontMetricsInt?): Int =
            (textPaint.measureText(label) + padX * 2).toInt() + AndroidUtilities.dp(2f)

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val w = textPaint.measureText(label)
            val fm = textPaint.fontMetrics
            val cy = y + (paint.ascent() + paint.descent()) / 2f
            val half = (fm.descent - fm.ascent) / 2f + padY
            val left = x + AndroidUtilities.dp(1f)
            val r = AndroidUtilities.dp(5f).toFloat()
            AndroidUtilities.rectTmp.set(left, cy - half, left + w + padX * 2, cy + half)
            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, bgPaint)
            canvas.drawText(label, left + padX, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }
}
