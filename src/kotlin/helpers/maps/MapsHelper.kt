package desu.inugram.helpers.maps

import desu.inugram.InuConfig
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.MessagesController

object MapsHelper {
    // lite builds (-PNOMAPS) compile without org.maplibre.gl and src/kotlin-maps entirely,
    // so this class is probed by name rather than referenced directly.
    @JvmField
    val hasMapLibre: Boolean = try {
        Class.forName("org.maplibre.android.MapLibre")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    @JvmStatic
    fun newMapLibreProvider(): IMapsProvider =
        Class.forName("desu.inugram.helpers.maps.MapLibreMapsProvider")
            .getDeclaredConstructor()
            .newInstance() as IMapsProvider

    @JvmStatic
    fun isHybridAvailable(): Boolean {
        return InuConfig.MAP_PROVIDER.value != InuConfig.MapProviderItem.OSM
    }

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
