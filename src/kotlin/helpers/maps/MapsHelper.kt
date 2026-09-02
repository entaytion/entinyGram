package desu.inugram.helpers.maps

import desu.inugram.InuConfig
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.MessagesController

object MapsHelper {
    // osmdroid is pure java and always shipped, but probe it anyway so a build that
    // ever trims it degrades to Google Maps instead of crashing.
    @JvmField
    val hasOsmdroid: Boolean = try {
        Class.forName("org.osmdroid.views.MapView")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    @JvmStatic
    fun newOsmdroidProvider(): IMapsProvider =
        Class.forName("desu.inugram.helpers.maps.osm.OsmdroidMapsProvider")
            .getDeclaredConstructor()
            .newInstance() as IMapsProvider

    /** true when the currently selected renderer can show a real hybrid (satellite + labels) layer. */
    @JvmStatic
    fun isHybridAvailable(): Boolean = InuConfig.MAP_PROVIDER.value != InuConfig.MapProviderItem.OSM_LITE

    @JvmStatic
    // MessagesController.mapProvider values:
    // -1 = disabled
    // 1 = yandex, direct
    // 2 = telegram, via inputWebFileGeoPointLocation
    // 3 = yandex, via webFile proxy
    // 4 = google, via webFile proxy
    // (any other) = google, direct
    fun overrideMapProvider(stock: Int): Int = when (InuConfig.MAP_PREVIEW_PROVIDER.value) {
        InuConfig.MapPreviewProviderItem.DEFAULT -> stock
        InuConfig.MapPreviewProviderItem.TELEGRAM -> 2
        InuConfig.MapPreviewProviderItem.GOOGLE -> 101 // override to 101 to disambiguate with server-pushed google in syncMapProvider
        InuConfig.MapPreviewProviderItem.YANDEX -> 1
        InuConfig.MapPreviewProviderItem.DISABLED -> -1
        else -> stock
    }

    fun syncMapProvider(messagesController: MessagesController) {
        messagesController.mapProvider = overrideMapProvider(messagesController.mainSettings.getInt("mapProvider", 0));
        if (messagesController.mapProvider == 101) {
            messagesController.mapKey = "AIzaSyA81BteNJiB2NZoAJzDV4A-dR4tAqWsYuU" // entinygram google maps api key
        }
    }
}
