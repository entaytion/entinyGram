package desu.inugram.helpers.maps

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader

// entiny: a plugin's raster tile source for the osmdroid map; `{n}` stands for a host number 0..4
object EntinyMapTiles {
    class Source(val name: String, val urlTemplate: String, val attribution: String, val minZoom: Int, val maxZoom: Int, val tileSize: Int) {
        fun url(z: Int, x: Int, y: Int): String = urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{n}", Math.floorMod(x + y, 5).toString())
    }

    @Volatile
    @JvmStatic
    var active: Source? = null
        private set

    @JvmStatic
    fun set(name: String, urlTemplate: String, attribution: String, minZoom: Int, maxZoom: Int, tileSize: Int) {
        require(urlTemplate.startsWith("https://") && listOf("{z}", "{x}", "{y}").all(urlTemplate::contains)) { "tile url needs https and {z} {x} {y}" }
        require(minZoom in 0..maxZoom && maxZoom <= 22 && tileSize in 128..512) { "bad zoom or tile size" }
        apply(Source(name, urlTemplate, attribution, minZoom, maxZoom, tileSize))
    }

    @JvmStatic
    fun clear() = apply(null)

    private fun apply(source: Source?) = AndroidUtilities.runOnUIThread {
        active = source
        ApplicationLoader.inu_resetMapsProvider()
    }
}
