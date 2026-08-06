package desu.inugram.ui.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Compact brand header: app icon in a Monet-tinted rounded square, app name and a subtitle.
 * Reusable across pages; set [onHeaderClick] to turn it into a tappable row with ripple.
 */
class InuSettingsHeader(context: Context) : LinearLayout(context) {

    private val iconBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = AndroidUtilities.dp(28f).toFloat()
    }

    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.icon_settings_inu)
        background = iconBg
        setPadding(
            AndroidUtilities.dp(13f),
            AndroidUtilities.dp(13f),
            AndroidUtilities.dp(13f),
            AndroidUtilities.dp(13f)
        )
    }

    private val title = TextView(context).apply {
        text = "entinyGram"
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        setTypeface(AndroidUtilities.bold())
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val subtitle = TextView(context).apply {
        text = "${UpdateHelper.stockVersionName} (${BuildConfig.STOCK_VERSION_CODE})"
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** When set, the header becomes clickable (ripple + haptic) and calls this on tap. */
    var onHeaderClick: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    value()
                }
                background = Theme.createRadSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector), 0, 0
                )
            } else {
                isClickable = false
                isFocusable = false
                setOnClickListener(null)
                background = null
            }
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(
            AndroidUtilities.dp(16f),
            AndroidUtilities.dp(4f),
            AndroidUtilities.dp(16f),
            AndroidUtilities.dp(10f)
        )
        addView(icon, LayoutHelper.createLinear(56, 56))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        texts.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = AndroidUtilities.dp(2f)
            }
        )
        addView(
            texts,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = AndroidUtilities.dp(14f)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        updateColors()
    }

    fun setTitleText(text: CharSequence) {
        title.text = text
    }

    fun setSubtitleText(text: CharSequence) {
        subtitle.text = text
    }

    fun updateColors() {
        val dark = Theme.isCurrentThemeDark()
        val (container, onContainer) = iconColors(dark)
        iconBg.setColor(container)
        icon.setColorFilter(onContainer)
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
    }

    private fun iconColors(dark: Boolean): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MonetHelper.getColor(
                if (dark) "monet_primary_container_dark" else "monet_primary_container_light"
            ) to MonetHelper.getColor(
                if (dark) "monet_on_primary_container_dark" else "monet_on_primary_container_light"
            )
        } else {
            val accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon)
            Theme.multAlpha(accent, 0.18f) to accent
        }
}
