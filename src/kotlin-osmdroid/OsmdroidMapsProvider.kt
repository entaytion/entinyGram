package desu.inugram.helpers.maps.osm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.IMapsProvider.LatLng
import org.telegram.messenger.R

internal const val MAX_ZOOM = 19.0
internal const val MIN_ZOOM = 3.0

internal const val ATTRIBUTION_OSM =
    """Data from <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap</a> contributors"""
internal const val ATTRIBUTION_SATELLITE = """Powered by <a href="https://www.esri.com/">Esri</a>"""

/**
 * Pure-Java OSM renderer (osmdroid) — the default map provider.
 * that carries no native `.so` payload. Selected via `InuConfig.MAP_PROVIDER == OSM_LITE` (the default).
 */
class OsmdroidMapsProvider : IMapsProvider {

    override fun initializeMaps(context: Context) {
        configureOsmdroid(context)
    }

    override fun onCreateMapView(context: Context): IMapsProvider.IMapView = OsmIMapView(context)

    override fun onCreateMarkerOptions(): IMapsProvider.IMarkerOptions = OsmMarkerOptionsImpl()
    override fun onCreateCircleOptions(): IMapsProvider.ICircleOptions = OsmCircleOptionsImpl()
    override fun onCreateLatLngBoundsBuilder(): IMapsProvider.ILatLngBoundsBuilder = OsmBoundsBuilderImpl()

    override fun newCameraUpdateLatLng(latLng: LatLng): IMapsProvider.ICameraUpdate =
        OsmCameraUpdate.Target(latLng.toGeo())

    override fun newCameraUpdateLatLngZoom(latLng: LatLng, zoom: Float): IMapsProvider.ICameraUpdate =
        OsmCameraUpdate.TargetZoom(latLng.toGeo(), zoom.toDouble().coerceIn(MIN_ZOOM, MAX_ZOOM))

    override fun newCameraUpdateLatLngBounds(bounds: IMapsProvider.ILatLngBounds, padding: Int): IMapsProvider.ICameraUpdate =
        OsmCameraUpdate.Bounds((bounds as OsmBoundsImpl).box, padding)

    override fun loadRawResourceStyle(context: Context, resId: Int): IMapsProvider.IMapStyleOptions = OsmStyleOptions

    // self-contained renderer; pretend "maps app" is us so isMapsInstalled() never prompts to install Google Maps
    override fun getMapsAppPackageName(): String = ApplicationLoader.applicationContext.packageName
    override fun getInstallMapsString(): Int = R.string.InstallGoogleMaps
}

internal fun configureOsmdroid(context: Context) {
    val app = context.applicationContext
    val config = Configuration.getInstance()
    config.load(app, app.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    // osm tile servers hard-reject the library's default user agent
    config.userAgentValue = app.packageName
    config.osmdroidBasePath = java.io.File(app.cacheDir, "osmdroid").apply { mkdirs() }
    config.osmdroidTileCache = java.io.File(app.cacheDir, "osmdroid/tiles").apply { mkdirs() }
}

internal fun LatLng.toGeo() = GeoPoint(latitude, longitude)
internal fun GeoPoint.toApi() = LatLng(latitude, longitude)

internal object OsmStyleOptions : IMapsProvider.IMapStyleOptions

internal sealed class OsmCameraUpdate : IMapsProvider.ICameraUpdate {
    class Target(val point: GeoPoint) : OsmCameraUpdate()
    class TargetZoom(val point: GeoPoint, val zoom: Double) : OsmCameraUpdate()
    class Bounds(val box: BoundingBox, val padding: Int) : OsmCameraUpdate()
}

internal class OsmBoundsImpl(val box: BoundingBox) : IMapsProvider.ILatLngBounds {
    override fun getCenter(): LatLng = LatLng(box.centerLatitude, box.centerLongitude)
}

internal class OsmBoundsBuilderImpl : IMapsProvider.ILatLngBoundsBuilder {
    private val points = ArrayList<GeoPoint>()
    override fun include(latLng: LatLng) = apply { points.add(latLng.toGeo()) }
    override fun build(): IMapsProvider.ILatLngBounds {
        if (points.isEmpty()) return OsmBoundsImpl(BoundingBox(0.0, 0.0, 0.0, 0.0))
        return OsmBoundsImpl(BoundingBox.fromGeoPoints(points))
    }
}

internal class OsmMarkerOptionsImpl : IMapsProvider.IMarkerOptions {
    var position: GeoPoint = GeoPoint(0.0, 0.0)
    var bitmap: Bitmap? = null
    var iconResId: Int = 0
    var anchorU: Float = 0.5f
    var anchorV: Float = 1f
    var flat: Boolean = false
    var titleText: String? = null
    var snippetText: String? = null

    override fun position(latLng: LatLng) = apply { position = latLng.toGeo() }
    override fun icon(bitmap: Bitmap) = apply { this.bitmap = bitmap; iconResId = 0 }
    override fun icon(resId: Int) = apply { iconResId = resId; bitmap = null }
    override fun anchor(lat: Float, lng: Float) = apply { anchorU = lat; anchorV = lng }
    override fun title(title: String?) = apply { titleText = title }
    override fun snippet(snippet: String?) = apply { snippetText = snippet }
    override fun flat(flat: Boolean) = apply { this.flat = flat }
}

internal class OsmCircleOptionsImpl : IMapsProvider.ICircleOptions {
    var center: GeoPoint = GeoPoint(0.0, 0.0)
    var radiusMeters: Double = 0.0
    var stroke: Int = Color.BLACK
    var fill: Int = Color.TRANSPARENT
    var strokeWidth: Int = 0

    override fun center(latLng: LatLng) = apply { center = latLng.toGeo() }
    override fun radius(radius: Double) = apply { radiusMeters = radius }
    override fun strokeColor(color: Int) = apply { stroke = color }
    override fun fillColor(color: Int) = apply { fill = color }
    override fun strokePattern(items: List<IMapsProvider.PatternItem>) = this
    override fun strokeWidth(width: Int) = apply { strokeWidth = width }
}
