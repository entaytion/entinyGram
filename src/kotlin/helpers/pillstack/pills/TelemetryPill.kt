package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator

// entiny: base for local-telemetry pills (RAM, CPU, net speed, DC ping) -- no network, tap refreshes.
@SuppressLint("ViewConstructor")
abstract class TelemetryPill(context: Context, resourcesProvider: Theme.ResourcesProvider?, iconResId: Int) :
    BasePill(context, resourcesProvider) {

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)
    @Volatile
    private var measureGeneration = 0

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.setImageResource(iconResId)
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setTypeface(AndroidUtilities.bold())
        textView.setIncludeFontPadding(false)
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        textView.visibility = GONE
        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)
        startLoading()
    }

    /** Take a measurement and return the pill's text; null means no data yet (keeps shimmering). */
    protected abstract fun measureText(): String?

    override fun onUpdateData(force: Boolean) {
        val generation = ++measureGeneration
        Utilities.globalQueue.postRunnable {
            val text = runCatching { measureText() }.getOrNull()
            AndroidUtilities.runOnUIThread {
                if (generation != measureGeneration) return@runOnUIThread
                if (text == null) {
                    // entiny: CpuPill's first read only seeds a baseline -- keep the shimmer instead of flashing a placeholder.
                    startLoading()
                    return@runOnUIThread
                }
                stopLoading()
                val old = textView.text
                if (textView.visibility != VISIBLE || old == null || old.toString() != text) {
                    animateSizeChange()
                    textView.setText(text, textView.visibility == VISIBLE)
                    textView.visibility = VISIBLE
                }
                markDataUpdated()
            }
        }
    }

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = false

    override fun drawableHotspotChanged(x: Float, y: Float) {
        if (loading) return
        super.drawableHotspotChanged(x, y)
        layout.drawableHotspotChanged(x - layout.left, y - layout.top)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun updateColors() {
        val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f)
        )
        textView.setTextColor(color)
        iconView.setColorFilter(color)
        updateLoadingColors()
    }
}
