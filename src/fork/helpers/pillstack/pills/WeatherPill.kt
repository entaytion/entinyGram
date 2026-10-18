package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.InuConfig
import desu.inugram.helpers.pillstack.PillType
import desu.inugram.helpers.pillstack.WeatherLocationHelper
import desu.inugram.ui.settings.WeatherLocationActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Stories.recorder.Weather

// entiny: emoji + temperature from stock's own Weather class (used by story stickers) -- no icon mapping; the point comes from the device or from WeatherLocationActivity.
@SuppressLint("ViewConstructor")
class WeatherPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) : BasePill(context, resourcesProvider) {

    private var requestInFlight = false

    init {
        NotificationCenter.listenEmojiLoading(textView)
        setupPill()
        iconView.visibility = GONE
        Weather.getCached()?.let { setData(it, false) }
    }

    override fun getPillId(): Int = PillType.WEATHER.id

    override fun getRefreshInterval(): Long = 15 * 60 * 1000L

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = showPillMenu {
        add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh)) { onUpdateData(true) }
        add(R.drawable.msg_language, if (InuConfig.WEATHER_FAHRENHEIT.value) "°C" else "°F") {
            InuConfig.WEATHER_FAHRENHEIT.value = !InuConfig.WEATHER_FAHRENHEIT.value
            Weather.getCached()?.let { setData(it, true) }
        }
        add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings)) {
            LaunchActivity.getSafeLastFragment()?.presentFragment(WeatherLocationActivity())
        }
    }

    override fun onUpdateData(force: Boolean) {
        if (requestInFlight) return
        requestInFlight = true
        if (force) animateSizeChange()
        startLoading()
        if (!WeatherLocationHelper.useCurrentLocation() && WeatherLocationHelper.hasPoint()) {
            Weather.fetch(WeatherLocationHelper.latitude(), WeatherLocationHelper.longitude()) { onWeatherFetched(it) }
        } else {
            Weather.fetch(force) { onWeatherFetched(it) }
        }
    }

    private fun onWeatherFetched(state: Weather.State?) {
        requestInFlight = false
        if (state != null) {
            markDataUpdated()
            setData(state, true)
            return
        }
        // entiny: keep the last known reading instead of wiping it with "Retry" -- the stock cache is keyed by point and hour
        val cached = Weather.getCached()
        if (cached == null) {
            setErrorState()
            return
        }
        markDataUpdated()
        setData(cached, false)
    }

    private fun setData(state: Weather.State, animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.visibility = GONE
        val emoji = state.emoji
        textView.setText((if (emoji.isNullOrEmpty()) "" else "$emoji ") + state.getTemperature(!InuConfig.WEATHER_FAHRENHEIT.value), animated)
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

    override fun applyIconColor(color: Int) {
        iconView.setColorFilter(color)
    }

    override fun updateColors() = applyPillColors()
}
