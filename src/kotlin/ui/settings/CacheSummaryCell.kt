package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** Hero card at the top of [CacheManagementSettingsActivity] — total reclaimable size + one-tap clear. */
@SuppressLint("ViewConstructor")
class CacheSummaryCell(
    context: Context,
    private val onClearAll: () -> Unit,
) : LinearLayout(context) {

    private val sizeView: TextView
    private val subtitleView: TextView
    private val clearButton: TextView

    init {
        orientation = VERTICAL
        val margin = AndroidUtilities.dp(16f)
        setPadding(margin, AndroidUtilities.dp(16f), margin, AndroidUtilities.dp(16f))
        background = Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), Theme.getColor(Theme.key_windowBackgroundWhite))

        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconCircle = FrameLayout(context).apply {
            background = Theme.createRoundRectDrawable(
                AndroidUtilities.dp(22f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
            )
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.inu_tabler_trash_x)
            setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        }
        iconCircle.addView(icon, LayoutHelper.createFrame(22, 22, Gravity.CENTER))
        topRow.addView(iconCircle, LayoutHelper.createLinear(44, 44))

        val textCol = LinearLayout(context).apply { orientation = VERTICAL }
        sizeView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            setTypeface(AndroidUtilities.bold())
        }
        subtitleView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        }
        textCol.addView(sizeView)
        textCol.addView(subtitleView)
        topRow.addView(textCol, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply { leftMargin = AndroidUtilities.dp(12f) })

        addView(topRow, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        clearButton = TextView(context).apply {
            text = LocaleController.getString(R.string.InuCacheClearAll)
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTypeface(AndroidUtilities.bold())
            setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed),
            )
            setOnClickListener { if (isEnabled) onClearAll() }
        }
        addView(
            clearButton,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply { topMargin = AndroidUtilities.dp(16f) },
        )
    }

    fun bind(sizeLabel: String, subtitle: String, clearEnabled: Boolean) {
        sizeView.text = sizeLabel
        subtitleView.text = subtitle
        clearButton.isEnabled = clearEnabled
        clearButton.alpha = if (clearEnabled) 1f else 0.5f
    }
}
