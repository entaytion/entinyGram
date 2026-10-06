package desu.inugram.helpers.pillstack

import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import java.util.Locale

// entiny: weather pill reads either the device position or a fixed point picked on the map (no location permission needed) -- ported from exteraless
object WeatherLocationHelper {

    @JvmStatic
    fun useCurrentLocation(): Boolean = InuConfig.WEATHER_USE_CURRENT_LOCATION.value

    @JvmStatic
    fun hasPoint(): Boolean = point() != null

    @JvmStatic
    fun latitude(): Double = point()?.first ?: 0.0

    @JvmStatic
    fun longitude(): Double = point()?.second ?: 0.0

    @JvmStatic
    fun address(): String = InuConfig.WEATHER_LOCATION_ADDRESS.value

    @JvmStatic
    fun setPoint(latitude: Double, longitude: Double, address: String?) {
        InuConfig.WEATHER_LOCATION.value = "$latitude,$longitude"
        InuConfig.WEATHER_LOCATION_ADDRESS.value = address.orEmpty()
    }

    @JvmStatic
    fun label(): CharSequence {
        val point = point() ?: return LocaleController.getString(R.string.InuWeatherLocationNotSet)
        val address = address()
        return if (address.isNotEmpty()) address else String.format(Locale.US, "%.4f, %.4f", point.first, point.second)
    }

    private fun point(): Pair<Double, Double>? {
        val raw = InuConfig.WEATHER_LOCATION.value
        if (raw.isEmpty()) return null
        val comma = raw.indexOf(',')
        if (comma <= 0) return null
        val latitude = raw.substring(0, comma).toDoubleOrNull() ?: return null
        val longitude = raw.substring(comma + 1).toDoubleOrNull() ?: return null
        return latitude to longitude
    }
}
