package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Stories.recorder.Weather

// entiny: emoji + temperature from stock's own Weather class (used by story stickers) -- no icon mapping, no fixed-location picker, just current location.
@SuppressLint("ViewConstructor")
class WeatherPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) : BasePill(context, resourcesProvider) {

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)
    private var requestInFlight = false

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
        iconView.visibility = GONE
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        NotificationCenter.listenEmojiLoading(textView)
        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setTypeface(AndroidUtilities.bold())
        textView.setIncludeFontPadding(false)
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)

        Weather.getCached()?.let { setData(it, false) }
    }

    override fun getPillId(): Int = PillType.WEATHER.id

    override fun getRefreshInterval(): Long = 15 * 60 * 1000L

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = false

    override fun onUpdateData(force: Boolean) {
        if (requestInFlight) return
        requestInFlight = true
        if (force) animateSizeChange()
        startLoading()
        Weather.fetch(force) { state ->
            requestInFlight = false
            if (state == null) setErrorState() else {
                markDataUpdated()
                setData(state, true)
            }
        }
    }

    private fun setData(state: Weather.State, animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.visibility = GONE
        val emoji = state.emoji
        textView.setText((if (emoji.isNullOrEmpty()) "" else "$emoji ") + state.temperature, animated)
        textView.visibility = VISIBLE
    }

    private fun setErrorState() {
        stopLoading()
        animateSizeChange()
        iconView.setImageResource(R.drawable.msg_retry)
        iconView.visibility = VISIBLE
        textView.setText(LocaleController.getString(R.string.Retry), true)
        textView.visibility = VISIBLE
    }

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
