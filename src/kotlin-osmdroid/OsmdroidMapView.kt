package desu.inugram.helpers.maps.osm

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.util.Consumer
import org.osmdroid.views.CustomZoomButtonsController
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.IMapsProvider
import org.telegram.ui.ActionBar.Theme
import org.osmdroid.views.MapView as OsmMapView

internal class OsmIMapView(context: Context) : IMapsProvider.IMapView {

    private var dispatchInterceptor: IMapsProvider.ITouchInterceptor? = null
    private var interceptInterceptor: IMapsProvider.ITouchInterceptor? = null
    private var layoutListener: Runnable? = null
    private var firstLayoutDone = false
    private var mapLoadedCallback: Runnable? = null
    var imap: OsmIMap? = null

    init {
        configureOsmdroid(context)
    }

    val mapView = object : OsmMapView(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val di = dispatchInterceptor ?: return super.dispatchTouchEvent(ev)
            return di.onInterceptTouchEvent(ev) { e -> super.dispatchTouchEvent(e) }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val ii = interceptInterceptor ?: return super.onInterceptTouchEvent(ev)
            return ii.onInterceptTouchEvent(ev) { e -> super.onInterceptTouchEvent(e) }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            layoutListener?.run()
            if (!firstLayoutDone && right > left && bottom > top) {
                firstLayoutDone = true
                mapLoadedCallback?.let { post(it) }
            }
        }
    }.apply {
        setTileSource(normalTileSource)
        setMultiTouchControls(true)
        setTilesScaledToDpi(true)
        setMaxZoomLevel(MAX_ZOOM)
        setMinZoomLevel(MIN_ZOOM)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // stock never asks for rotation gestures
        isHorizontalMapRepetitionEnabled = false
        isVerticalMapRepetitionEnabled = false
    }

    val attribution = TextView(context).apply {
        textSize = 10f
        setTextColor(0xFF000000.toInt())
        setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink))
        background = GradientDrawable().apply {
            setColor(0xCCFFFFFF.toInt())
            cornerRadius = AndroidUtilities.dp(100f).toFloat()
        }
        setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(3f), AndroidUtilities.dp(8f), AndroidUtilities.dp(3f))
        linksClickable = true
        movementMethod = LinkMovementMethod.getInstance()
        text = Html.fromHtml(ATTRIBUTION_OSM)
    }

    // map container parallaxes when bottom sheet expands; cancel that translation on attribution so it stays put
    private val container = object : FrameLayout(context) {
        override fun setTranslationY(translationY: Float) {
            super.setTranslationY(translationY)
            attribution.translationY = -translationY
        }
    }.apply {
        addView(mapView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(attribution, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = AndroidUtilities.dp(6f)
            bottomMargin = AndroidUtilities.dp(6f)
        })
    }

    override fun getView(): View = container

    override fun getMapAsync(callback: Consumer<IMapsProvider.IMap>) {
        // osmdroid is synchronous — the tile view is usable the moment it exists. Post so callers
        // relying on the async contract (adding views from the callback) still run after construction.
        mapView.post {
            if (imap == null) imap = OsmIMap(this, mapView)
            imap?.let { callback.accept(it) }
        }
    }

    internal fun setMapLoadedCallback(callback: Runnable?) {
        mapLoadedCallback = callback
        if (firstLayoutDone && callback != null) mapView.post(callback)
    }

    override fun onResume() {
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
    }

    override fun onCreate(savedInstance: Bundle?) {
        // osmdroid has no state restore hook; view construction is enough
    }

    override fun onDestroy() {
        imap?.onDestroy()
        imap = null
        mapView.onDetach()
    }

    override fun onLowMemory() {
        // osmdroid trims its own tile cache via Configuration; nothing to forward
    }

    override fun setOnDispatchTouchEventInterceptor(touchInterceptor: IMapsProvider.ITouchInterceptor?) {
        dispatchInterceptor = touchInterceptor
    }

    override fun setOnInterceptTouchEventInterceptor(touchInterceptor: IMapsProvider.ITouchInterceptor?) {
        interceptInterceptor = touchInterceptor
    }

    override fun setOnLayoutListener(callback: Runnable?) {
        layoutListener = callback
    }
}
