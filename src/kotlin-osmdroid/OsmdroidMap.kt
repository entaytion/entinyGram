package desu.inugram.helpers.maps.osm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.text.Html
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import desu.inugram.helpers.maps.LocationProvider
import desu.inugram.helpers.maps.createLocationProvider
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.IMapsProvider.LatLng
import org.osmdroid.views.MapView as OsmMapView

private const val IDLE_DELAY_MS = 300L
private const val DEFAULT_ANIMATION_MS = 1000L

internal class OsmIMap(
    private val viewWrapper: OsmIMapView,
    private val mapView: OsmMapView,
) : IMapsProvider.IMap {

    private val ctx: Context get() = mapView.context

    private val markers = ArrayList<OsmIMarker>()
    private val circles = ArrayList<OsmICircle>()
    private var markerClickListener: IMapsProvider.OnMarkerClickListener? = null
    private var destroyed = false

    private var cameraIdleCallback: Runnable? = null
    private var cameraMoveCallback: Runnable? = null
    private var moveStartedListener: IMapsProvider.OnCameraMoveStartedListener? = null
    private var moving = false
    private var programmaticMove = false

    private val locationProvider: LocationProvider by lazy { createLocationProvider(ctx) }
    private var locationStarted = false
    private var locationConsumer: Consumer<Location>? = null
    private var visualLocationEnabled = false
    private var myLocationMarker: OsmIMarker? = null
    private var myHeadingMarker: OsmIMarker? = null
    private var myAccuracyCircle: OsmICircle? = null

    init {
        // osmdroid has no separate "idle" event — DelayedMapListener debounces scroll/zoom for us.
        mapView.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onIdle(); return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onIdle(); return false
            }
        }, IDLE_DELAY_MS))

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onMove(); return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onMove(); return false
            }
        })
    }

    private fun onMove() {
        if (destroyed) return
        if (!moving) {
            moving = true
            val reason = if (programmaticMove)
                IMapsProvider.OnCameraMoveStartedListener.REASON_API_ANIMATION
            else
                IMapsProvider.OnCameraMoveStartedListener.REASON_GESTURE
            moveStartedListener?.onCameraMoveStarted(reason)
        }
        cameraMoveCallback?.run()
    }

    private fun onIdle() {
        if (destroyed) return
        moving = false
        programmaticMove = false
        cameraIdleCallback?.run()
    }

    fun onDestroy() {
        destroyed = true
        if (locationStarted) {
            locationProvider.stop()
            locationStarted = false
        }
        markers.clear()
        circles.clear()
    }

    // region camera

    private fun applyUpdate(update: IMapsProvider.ICameraUpdate, animated: Boolean, durationMs: Long?) {
        if (destroyed) return
        programmaticMove = true
        val controller = mapView.controller
        when (val u = update as OsmCameraUpdate) {
            is OsmCameraUpdate.Target ->
                if (animated) controller.animateTo(u.point, null, durationMs) else controller.setCenter(u.point)

            is OsmCameraUpdate.TargetZoom ->
                if (animated) {
                    controller.animateTo(u.point, u.zoom, durationMs)
                } else {
                    controller.setZoom(u.zoom)
                    controller.setCenter(u.point)
                }

            is OsmCameraUpdate.Bounds -> {
                if (u.box.latitudeSpan <= 0.0 && u.box.longitudeSpanWithDateLine <= 0.0) {
                    // degenerate (single point) box — zoomToBoundingBox would divide by zero
                    val center = GeoPoint(u.box.centerLatitude, u.box.centerLongitude)
                    if (animated) controller.animateTo(center, null, durationMs) else controller.setCenter(center)
                } else {
                    mapView.zoomToBoundingBox(u.box, animated, u.padding)
                }
            }
        }
    }

    override fun animateCamera(update: IMapsProvider.ICameraUpdate) {
        applyUpdate(update, animated = true, durationMs = null)
    }

    override fun animateCamera(update: IMapsProvider.ICameraUpdate, callback: IMapsProvider.ICancelableCallback?) {
        animateCamera(update, DEFAULT_ANIMATION_MS.toInt(), callback)
    }

    override fun animateCamera(update: IMapsProvider.ICameraUpdate, duration: Int, callback: IMapsProvider.ICancelableCallback?) {
        applyUpdate(update, animated = true, durationMs = duration.toLong())
        // osmdroid's animator exposes no completion hook; approximate with a timed post
        if (callback != null) {
            mapView.postDelayed({ if (!destroyed) callback.onFinish() else callback.onCancel() }, duration.toLong())
        }
    }

    override fun moveCamera(update: IMapsProvider.ICameraUpdate) {
        applyUpdate(update, animated = false, durationMs = null)
    }

    override fun getMaxZoomLevel(): Float = mapView.maxZoomLevel.toFloat()
    override fun getMinZoomLevel(): Float = mapView.minZoomLevel.toFloat()

    override fun getCameraPosition(): IMapsProvider.CameraPosition {
        val center = mapView.mapCenter
        return IMapsProvider.CameraPosition(
            LatLng(center.latitude, center.longitude),
            mapView.zoomLevelDouble.toFloat(),
        )
    }

    override fun setOnCameraIdleListener(callback: Runnable?) {
        cameraIdleCallback = callback
    }

    override fun setOnCameraMoveListener(callback: Runnable?) {
        cameraMoveCallback = callback
    }

    override fun setOnCameraMoveStartedListener(listener: IMapsProvider.OnCameraMoveStartedListener) {
        moveStartedListener = listener
    }

    override fun setOnMapLoadedCallback(callback: Runnable?) {
        viewWrapper.setMapLoadedCallback(callback)
    }

    override fun getProjection(): IMapsProvider.IProjection = object : IMapsProvider.IProjection {
        override fun toScreenLocation(latLng: LatLng): Point =
            mapView.projection.toPixels(latLng.toGeo(), null)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // osmdroid has no camera padding; shifting the drawn center is the closest equivalent
        mapView.setMapCenterOffset((left - right) / 2, (top - bottom) / 2)
        val attr = viewWrapper.attribution
        val lp = attr.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = bottom + AndroidUtilities.dp(16f)
        attr.layoutParams = lp
    }

    // endregion

    override fun setMapType(mapType: Int) {
        val (source, attr) = when (mapType) {
            IMapsProvider.MAP_TYPE_SATELLITE, IMapsProvider.MAP_TYPE_HYBRID ->
                EsriSatelliteTileSource to ATTRIBUTION_SATELLITE

            else -> normalTileSource to ATTRIBUTION_OSM
        }
        viewWrapper.attribution.text = Html.fromHtml(attr)
        mapView.setTileSource(source)
        mapView.invalidate()
    }

    override fun setMapStyle(style: IMapsProvider.IMapStyleOptions?) {} // fixed raster styles

    override fun getUiSettings(): IMapsProvider.IUISettings = OsmUISettings(mapView)

    // region markers & circles

    private fun resToDrawable(resId: Int): Drawable? = ContextCompat.getDrawable(ctx, resId)

    private fun bitmapToDrawable(bitmap: Bitmap): Drawable = BitmapDrawable(ctx.resources, bitmap)

    override fun addMarker(markerOptions: IMapsProvider.IMarkerOptions): IMapsProvider.IMarker {
        val o = markerOptions as OsmMarkerOptionsImpl
        val marker = Marker(mapView).apply {
            position = o.position
            setAnchor(o.anchorU, o.anchorV)
            isFlat = o.flat
            title = o.titleText
            snippet = o.snippetText
            // stock draws its own callouts; never show osmdroid's bubble.
            // cast disambiguates Marker.setInfoWindow(MarkerInfoWindow) from OverlayWithIW.setInfoWindow(InfoWindow)
            setInfoWindow(null as MarkerInfoWindow?)
            val drawable = o.bitmap?.let { bitmapToDrawable(it) }
                ?: o.iconResId.takeIf { it != 0 }?.let { resToDrawable(it) }
            if (drawable != null) icon = drawable
        }
        val wrapper = OsmIMarker(marker)
        marker.setOnMarkerClickListener { _, _ ->
            markerClickListener?.onClick(wrapper) ?: false
        }
        markers.add(wrapper)
        mapView.overlays.add(marker)
        mapView.invalidate()
        return wrapper
    }

    override fun addCircle(circleOptions: IMapsProvider.ICircleOptions): IMapsProvider.ICircle {
        val o = circleOptions as OsmCircleOptionsImpl
        // no-arg ctor deliberately: Polygon(MapView) attaches a default BasicInfoWindow we don't want
        val polygon = Polygon().apply {
            fillPaint.color = o.fill
            outlinePaint.color = o.stroke
            outlinePaint.strokeWidth = o.strokeWidth.toFloat()
        }
        val wrapper = OsmICircle(polygon, o.center, o.radiusMeters)
        // circles must render under markers
        mapView.overlays.add(0, polygon)
        circles.add(wrapper)
        mapView.invalidate()
        return wrapper
    }

    override fun setOnMarkerClickListener(listener: IMapsProvider.OnMarkerClickListener) {
        markerClickListener = listener
    }

    // endregion

    // region my location

    override fun setMyLocationEnabled(enabled: Boolean) {
        visualLocationEnabled = enabled
        if (enabled) {
            ensureLocationStarted()
            return
        }
        myLocationMarker?.remove(); myLocationMarker = null
        myHeadingMarker?.remove(); myHeadingMarker = null
        myAccuracyCircle?.remove(); myAccuracyCircle = null
        stopLocationIfIdle()
    }

    override fun setOnMyLocationChangeListener(callback: Consumer<Location>?) {
        locationConsumer = callback
        if (callback != null) ensureLocationStarted() else stopLocationIfIdle()
    }

    private fun ensureLocationStarted() {
        if (locationStarted) return
        locationStarted = true
        locationProvider.start { onLocation(it) }
        locationProvider.requestLastLocation { it?.let(::onLocation) }
    }

    private fun stopLocationIfIdle() {
        if (visualLocationEnabled || locationConsumer != null || !locationStarted) return
        locationProvider.stop()
        locationStarted = false
    }

    private fun onLocation(loc: Location) {
        if (destroyed) return
        locationConsumer?.accept(loc)
        if (visualLocationEnabled) updateMyLocationVisuals(loc)
    }

    private fun updateMyLocationVisuals(loc: Location) {
        val pos = LatLng(loc.latitude, loc.longitude)
        val accuracy = loc.accuracy.toDouble().coerceAtLeast(1.0)

        val dot = myLocationMarker
        if (dot == null) {
            myLocationMarker = addMarker(OsmMarkerOptionsImpl().apply {
                position = pos.toGeo()
                anchorU = 0.5f; anchorV = 0.5f
                bitmap = blueDotBitmap()
            }) as OsmIMarker
        } else dot.setPosition(pos)

        if (loc.hasBearing()) {
            val heading = myHeadingMarker
            if (heading == null) {
                myHeadingMarker = (addMarker(OsmMarkerOptionsImpl().apply {
                    position = pos.toGeo()
                    anchorU = 0.5f; anchorV = 1f
                    flat = true
                    bitmap = headingArrowBitmap(ctx)
                }) as OsmIMarker).also { it.setRotation(loc.bearing.toInt()) }
            } else {
                heading.setPosition(pos)
                heading.setRotation(loc.bearing.toInt())
            }
        } else {
            myHeadingMarker?.remove()
            myHeadingMarker = null
        }

        val circle = myAccuracyCircle
        if (circle == null) {
            myAccuracyCircle = addCircle(OsmCircleOptionsImpl().apply {
                center = pos.toGeo()
                radiusMeters = accuracy
                fill = 0x224285F4
                stroke = 0x554285F4
                strokeWidth = AndroidUtilities.dp(1f)
            }) as OsmICircle
        } else {
            circle.setCenter(pos)
            circle.setRadius(accuracy)
        }
    }

    // endregion

    internal inner class OsmIMarker(val marker: Marker) : IMapsProvider.IMarker {
        private var tagObj: Any? = null

        override fun getTag(): Any? = tagObj
        override fun setTag(tag: Any?) {
            tagObj = tag
        }

        override fun getPosition(): LatLng = marker.position.toApi()

        override fun setPosition(latLng: LatLng) {
            marker.position = latLng.toGeo()
            mapView.invalidate()
        }

        override fun setRotation(rotation: Int) {
            marker.rotation = rotation.toFloat()
            mapView.invalidate()
        }

        override fun setIcon(bitmap: Bitmap) {
            marker.icon = bitmapToDrawable(bitmap)
            mapView.invalidate()
        }

        override fun setIcon(resId: Int) {
            resToDrawable(resId)?.let {
                marker.icon = it
                mapView.invalidate()
            }
        }

        override fun remove() {
            markers.remove(this)
            marker.remove(mapView) // also detaches it from mapView.overlays
            mapView.invalidate()
        }
    }

    internal inner class OsmICircle(
        val polygon: Polygon,
        private var centerPoint: GeoPoint,
        private var radiusM: Double,
    ) : IMapsProvider.ICircle {

        init {
            rebuild()
        }

        private fun rebuild() {
            polygon.points = Polygon.pointsAsCircle(centerPoint, radiusM.coerceAtLeast(0.0))
            mapView.invalidate()
        }

        override fun setStrokeColor(color: Int) {
            polygon.outlinePaint.color = color
            mapView.invalidate()
        }

        override fun setFillColor(color: Int) {
            polygon.fillPaint.color = color
            mapView.invalidate()
        }

        override fun setRadius(radius: Double) {
            radiusM = radius
            rebuild()
        }

        override fun getRadius(): Double = radiusM

        override fun setCenter(latLng: LatLng) {
            centerPoint = latLng.toGeo()
            rebuild()
        }

        override fun remove() {
            circles.remove(this)
            mapView.overlays.remove(polygon)
            mapView.invalidate()
        }
    }
}

internal class OsmUISettings(private val mapView: OsmMapView) : IMapsProvider.IUISettings {
    override fun setZoomControlsEnabled(enabled: Boolean) {
        mapView.zoomController.setVisibility(
            if (enabled) CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
            else CustomZoomButtonsController.Visibility.NEVER
        )
    }

    // stock draws its own "my location" FAB; osmdroid has no built-in one either way
    override fun setMyLocationButtonEnabled(enabled: Boolean) {}

    // osmdroid's MapView is never rotated by gestures here, so a compass would always point north
    override fun setCompassEnabled(enabled: Boolean) {}
}
