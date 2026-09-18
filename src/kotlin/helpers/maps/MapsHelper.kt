package desu.inugram.helpers.maps

import desu.inugram.InuConfig
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.MessagesController

object MapsHelper {
    // entiny: probe osmdroid presence at runtime so trimming it degrades to Google Maps without crashing
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

    @JvmStatic
    fun isHybridAvailable(): Boolean = InuConfig.MAP_PROVIDER.value != InuConfig.MapProviderItem.OSM_LITE

    @JvmStatic
    fun overrideMapProvider(stock: Int): Int = when (InuConfig.MAP_PREVIEW_PROVIDER.value) {
        InuConfig.MapPreviewProviderItem.DEFAULT -> stock
        InuConfig.MapPreviewProviderItem.TELEGRAM -> 2
        // entiny: 101 disambiguates manual Google override from server-pushed Google in syncMapProvider
        InuConfig.MapPreviewProviderItem.GOOGLE -> 101
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
