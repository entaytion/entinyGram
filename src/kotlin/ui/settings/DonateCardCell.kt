package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

@SuppressLint("ViewConstructor")
class DonateCardCell(
    context: Context,
    onSupport: () -> Unit,
    onHide: () -> Unit,
) : FrameLayout(context) {

    init {
        val rtl = LocaleController.isRTL
        val side = if (rtl) Gravity.RIGHT else Gravity.LEFT
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        val textColor = Theme.getColor(Theme.key_featuredStickers_buttonText)
        val accent = Theme.getColor(Theme.key_featuredStickers_addButton)

        val card = FrameLayout(context).apply {
            background = Theme.createRoundRectDrawable(dp(14f), accent)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        }
        content.addView(TextView(context).apply {
            gravity = side
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            setTextColor(textColor)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuDonateTitle)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, if (rtl) 28f else 0f, 0f, if (rtl) 0f else 28f, 0f))
        content.addView(TextView(context).apply {
            gravity = side
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(textColor)
            alpha = 0.9f
            text = LocaleController.getString(R.string.InuDonateText)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))
        content.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(accent)
            typeface = AndroidUtilities.bold()
            background = Theme.createRoundRectDrawable(dp(10f), textColor)
            text = LocaleController.getString(R.string.InuDonateButton)
            setOnClickListener { onSupport() }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0f, 14f, 0f, 0f))
        card.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))

        card.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_close)
            setColorFilter(textColor)
            alpha = 0.7f
            contentDescription = LocaleController.getString(R.string.InuDonateHide)
            setOnClickListener { onHide() }
        }, LayoutHelper.createFrame(32f, 32f, Gravity.TOP or (if (rtl) Gravity.LEFT else Gravity.RIGHT), 6f, 6f, 6f, 0f))

        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.FILL_HORIZONTAL, 12f, 12f, 12f, 4f))
    }
}
