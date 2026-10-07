package desu.inugram.helpers.maps.osm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Html
import androidx.core.graphics.createBitmap
import desu.inugram.helpers.maps.EntinyMapTiles
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.telegram.messenger.AndroidUtilities

/**
 * Esri World Imagery serves `{z}/{y}/{x}`, while [XYTileSource] builds `{z}/{x}/{y}` —
 * hence the override rather than a plain XYTileSource instance.
 */
internal object EsriSatelliteTileSource : XYTileSource(
    "EntinyEsriWorldImagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "© Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

internal class EntinyTileSource(private val source: EntinyMapTiles.Source) : OnlineTileSourceBase(
    "Entiny_" + source.name, source.minZoom, source.maxZoom, source.tileSize, ".png", arrayOf(), "",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        source.url(MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex))
}

internal val normalTileSource: ITileSource
    get() = EntinyMapTiles.active?.let(::EntinyTileSource) ?: TileSourceFactory.MAPNIK

internal fun normalAttribution(): String =
    EntinyMapTiles.active?.let { Html.escapeHtml(it.attribution) } ?: ATTRIBUTION_OSM

private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
private fun strokePaint(color: Int, widthPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = widthPx
}

internal fun blueDotBitmap(): Bitmap {
    val sizePx = AndroidUtilities.dp(18f).coerceAtLeast(8)
    val bmp = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bmp)
    val c = sizePx / 2f
    canvas.drawCircle(c, c, c, fillPaint(Color.WHITE))
    canvas.drawCircle(c, c, c * 0.78f, fillPaint(0xFF4285F4.toInt()))
    return bmp
}

internal fun headingArrowBitmap(ctx: Context): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val triW = AndroidUtilities.dp(10f).toFloat()
    val triH = triW / 2f
    val gap = AndroidUtilities.dp(9f).toFloat()
    val w = triW.toInt().coerceAtLeast(2)
    val h = (triH + gap).toInt().coerceAtLeast(2)
    val bmp = createBitmap(w, h)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(w / 2f, 0f)
        lineTo(w.toFloat(), triH)
        lineTo(0f, triH)
        close()
    }
    canvas.drawPath(path, fillPaint(0xFF4285F4.toInt()))
    canvas.drawPath(path, strokePaint(Color.WHITE, 0.75f * d))
    return bmp
}
