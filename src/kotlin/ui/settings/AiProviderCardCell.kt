package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

class AiProviderCardCell(
    context: Context,
    kind: Int,
    title: CharSequence,
    subtitle: CharSequence,
    tags: List<String>,
    accent: Int = accentFor(kind),
    onClick: () -> Unit,
) : FrameLayout(context) {

    init {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(12f), AndroidUtilities.dp(14f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            }
            foreground = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2, AndroidUtilities.dp(16f))
            isClickable = true
            setOnClickListener { onClick() }
        }

        val monogram = TextView(context).apply {
            text = title.toString().trim().take(1).uppercase()
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        card.addView(monogram, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 14, 0))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        })
        if (subtitle.isNotEmpty()) {
            column.addView(TextView(context).apply {
                text = subtitle
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        }
        if (tags.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val tint = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
            for (tag in tags) {
                row.addView(TextView(context).apply {
                    text = tag
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(tint)
                    setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(2f), AndroidUtilities.dp(8f), AndroidUtilities.dp(2f))
                    background = GradientDrawable().apply {
                        cornerRadius = AndroidUtilities.dp(8f).toFloat()
                        setColor(ColorUtils.setAlphaComponent(tint, 32))
                    }
                }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 6f, 0f))
            }
            column.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))
        }
        card.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.setMargins(AndroidUtilities.dp(12f), AndroidUtilities.dp(5f), AndroidUtilities.dp(12f), AndroidUtilities.dp(5f))
        addView(card, lp)
    }

    companion object {
        fun accentFor(kind: Int): Int = when (kind) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> 0xFF4285F4.toInt()
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> 0xFF10A37F.toInt()
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> 0xFFF55036.toInt()
            InuConfig.AI_PROVIDER_OPENROUTER -> 0xFF6467F2.toInt()
            InuConfig.TRANSCRIBE_PROVIDER_CF -> 0xFFF6821F.toInt()
            else -> 0xFF8E8E93.toInt()
        }
    }
}
