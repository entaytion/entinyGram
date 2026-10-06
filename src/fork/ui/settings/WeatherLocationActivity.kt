package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.pillstack.WeatherLocationHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.WebFile
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ChatAttachAlertLocationLayout
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.LocationActivity

// entiny: where the weather pill reads from -- the device position or a fixed point picked on the map, which needs no location permission (ported from exteraless)
class WeatherLocationActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuWeatherLocation)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val useCurrent = WeatherLocationHelper.useCurrentLocation()
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuWeatherLocation)))
        items.add(
            UItem.asRadio(ROW_USE_CURRENT, LocaleController.getString(R.string.InuWeatherUseCurrentLocation))
                .also { it.checked = useCurrent }
        )
        items.add(
            UItem.asRadio(ROW_SELECT_ON_MAP, LocaleController.getString(R.string.InuWeatherSelectOnMap), WeatherLocationHelper.label())
                .also { it.checked = !useCurrent && WeatherLocationHelper.hasPoint() }
        )
        if (!useCurrent && WeatherLocationHelper.hasPoint()) {
            items.add(UItem.asCustom(mapPreview(context ?: return)))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuWeatherLocationInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            ROW_USE_CURRENT -> {
                InuConfig.WEATHER_USE_CURRENT_LOCATION.value = true
                listView?.adapter?.update(true)
            }

            ROW_SELECT_ON_MAP -> openMapPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    private fun openMapPicker() {
        val fragment = LocationActivity(ChatAttachAlertLocationLayout.LOCATION_TYPE_BIZ)
        if (WeatherLocationHelper.hasPoint()) {
            val initial = TLRPC.TL_channelLocation()
            val point = TLRPC.TL_geoPoint()
            point.lat = WeatherLocationHelper.latitude()
            point._long = WeatherLocationHelper.longitude()
            initial.geo_point = point
            initial.address = WeatherLocationHelper.address()
            fragment.setInitialLocation(initial)
        }
        fragment.setDelegate { location, _, _, _, _ ->
            val geo = location?.geo ?: return@setDelegate
            val address = fragment.addressName?.takeIf { it.isNotEmpty() }
                ?: (location as? TLRPC.TL_messageMediaVenue)?.address
            WeatherLocationHelper.setPoint(geo.lat, geo._long, address)
            InuConfig.WEATHER_USE_CURRENT_LOCATION.value = false
            listView?.adapter?.update(true)
        }
        presentFragment(fragment)
    }

    // entiny: static map preview from the server, the same trick stock uses for story weather stickers
    @SuppressLint("ViewConstructor")
    private fun mapPreview(context: Context): View {
        val container = FrameLayout(context)
        container.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))

        val card = FrameLayout(context)
        card.clipToOutline = true
        card.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider))

        val image = BackupImageView(context)
        card.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val marker = ImageView(context)
        marker.setImageResource(R.drawable.msg_location)
        card.addView(marker, LayoutHelper.createFrame(24f, 24f, Gravity.CENTER))
        container.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PREVIEW_HEIGHT_DP.toFloat()))
        container.setOnClickListener { openMapPicker() }

        val widthDp = (AndroidUtilities.displaySize.x / AndroidUtilities.density).toInt() - 32
        val scale = minOf(2, kotlin.math.ceil(AndroidUtilities.density.toDouble()).toInt())
        runCatching {
            val file = WebFile.createWithGeoPoint(
                WeatherLocationHelper.latitude(), WeatherLocationHelper.longitude(), 0L,
                widthDp * scale, PREVIEW_HEIGHT_DP * scale, 15, scale
            )
            image.setImage(ImageLocation.getForWebFile(file), widthDp.toString() + "_" + PREVIEW_HEIGHT_DP, null, 0, null)
        }
        return container
    }

    companion object {
        private const val PREVIEW_HEIGHT_DP = 160

        private val ROW_USE_CURRENT = InuUtils.generateId()
        private val ROW_SELECT_ON_MAP = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "weather-location",
            titleRes = R.string.InuWeatherLocation,
            iconRes = R.drawable.inu_tabler_cloud,
            factory = ::WeatherLocationActivity,
            entries = listOf(
                SearchRegistry.Entry("weather-use-current-location", R.string.InuWeatherUseCurrentLocation, ROW_USE_CURRENT),
                SearchRegistry.Entry("weather-select-on-map", R.string.InuWeatherSelectOnMap, ROW_SELECT_ON_MAP),
            ),
        )
    }
}
