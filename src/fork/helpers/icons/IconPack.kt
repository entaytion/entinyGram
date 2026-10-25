package desu.inugram.helpers.icons

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.util.SparseIntArray
import com.caverock.androidsvg.SVG
import org.json.JSONObject
import org.telegram.messenger.FileLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class IconPack private constructor(
    val id: String,
    val name: String,
    val author: String,
    private val zip: ZipFile,
    private val icons: Map<String, String>,
) : Closeable {
    val iconCount get() = icons.size
    val iconNames: Set<String> get() = icons.keys

    private val entries = SparseArray<String?>()
    private val originalSizes = SparseIntArray()
    // ponytail: decoded icons are never evicted, fine for vector packs; add an LruCache if huge raster packs show up
    private val sources = ConcurrentHashMap<String, Any>()

    private class SvgSource(val content: BitmapIconDrawable.Content.Svg, val widthDp: Float, val heightDp: Float, val aspect: Float)

    fun loadLottieJson(resources: Resources, id: Int): String? {
        val path = getEntry(resources, id) ?: return null
        return loadSource(path) as? String
    }

    fun hasLottie(resources: Resources, id: Int) = getEntry(resources, id)?.endsWith(".json", ignoreCase = true) == true

    fun loadDrawable(resources: Resources, id: Int, density: Int, theme: Resources.Theme?): Drawable? {
        val path = getEntry(resources, id) ?: return null
        return when (val source = loadSource(path)) {
            is VectorIcon -> {
                val (width, height) = getSvgSize(resources, id, density, theme, source.widthDp, source.heightDp, source.aspect)
                VectorIcon.Drawable(source, width, height)
            }

            is SvgSource -> {
                val (width, height) = getSvgSize(resources, id, density, theme, source.widthDp, source.heightDp, source.aspect)
                BitmapIconDrawable(source.content, width, height)
            }

            is Bitmap -> {
                val (width, height) = fitOriginal(resources, id, density, theme, source.width.toFloat() / source.height)
                BitmapIconDrawable(BitmapIconDrawable.Content.Raster(source), width, height)
            }

            else -> null
        }
    }

    private fun getSvgSize(
        resources: Resources,
        id: Int,
        density: Int,
        theme: Resources.Theme?,
        widthDp: Float,
        heightDp: Float,
        aspect: Float,
    ): Pair<Int, Int> {
        if (widthDp <= 0 || heightDp <= 0) return fitOriginal(resources, id, density, theme, aspect)
        val displayDpi = resources.displayMetrics.densityDpi
        return convertDpToPx(widthDp, displayDpi, density) to convertDpToPx(heightDp, displayDpi, density)
    }

    fun prewarm() {
        for (path in icons.values) {
            if (path.endsWith(".svg", ignoreCase = true)) loadSource(path)
        }
    }

    @Synchronized
    private fun getEntry(resources: Resources, id: Int): String? {
        val index = entries.indexOfKey(id)
        if (index >= 0) return entries.valueAt(index)
        val entry = try {
            when (resources.getResourceTypeName(id)) {
                "drawable" -> icons[resources.getResourceEntryName(id)]
                "raw" -> icons[LOTTIE_PREFIX + resources.getResourceEntryName(id)]
                else -> null
            }
        } catch (_: Resources.NotFoundException) {
            null
        }
        entries.put(id, entry)
        return entry
    }

    private fun loadSource(path: String): Any? {
        sources[path]?.let { return it.takeIf { it !== FAILED } }
        val source = try {
            decode(path)
        } catch (e: Exception) {
            FileLog.e("InuIconPacks: failed to decode $path in $id", e)
            null
        } ?: FAILED
        sources[path] = source
        return source.takeIf { it !== FAILED }
    }

    private fun decode(path: String): Any? {
        if (path.endsWith(".json", ignoreCase = true)) return String(readEntry(zip, path, MAX_LOTTIE_BYTES))
        val bytes = readEntry(zip, path, MAX_ICON_BYTES)
        if (!path.endsWith(".svg", ignoreCase = true)) return decodeBitmap(bytes)
        VectorIcon.parse(bytes)?.let { return it }

        val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
        val widthDp = svg.documentWidth
        val heightDp = svg.documentHeight
        if (svg.documentViewBox == null && widthDp > 0 && heightDp > 0) svg.setDocumentViewBox(0f, 0f, widthDp, heightDp)
        val viewBox = svg.documentViewBox
        val aspect = if (viewBox != null && viewBox.width() > 0 && viewBox.height() > 0) viewBox.width() / viewBox.height() else 1f
        // androidsvg renders at the declared size inside the viewport; make it fill whatever we draw into
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        return SvgSource(BitmapIconDrawable.Content.Svg(svg), widthDp, heightDp, aspect)
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_BITMAP_SIDE) sampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }

    private fun fitOriginal(resources: Resources, id: Int, density: Int, theme: Resources.Theme?, aspect: Float): Pair<Int, Int> {
        val size = getOriginalSize(resources, id, density, theme)
        var boxWidth = size shr SIZE_SHIFT
        var boxHeight = size and SIZE_MASK
        if (boxWidth <= 0 || boxHeight <= 0) {
            boxWidth = convertDpToPx(FALLBACK_SIZE_DP, resources.displayMetrics.densityDpi, density)
            boxHeight = boxWidth
        }
        val width = min(boxWidth.toFloat(), boxHeight * aspect)
        return width.roundToInt().coerceAtLeast(1) to (width / aspect).roundToInt().coerceAtLeast(1)
    }

    private fun getOriginalSize(resources: Resources, id: Int, density: Int, theme: Resources.Theme?): Int {
        if (density == 0) synchronized(originalSizes) {
            val cached = originalSizes.get(id, -1)
            if (cached != -1) return cached
        }
        val original = try {
            resources.getDrawableForDensity(id, density, theme)
        } catch (_: Resources.NotFoundException) {
            null
        }
        val width = original?.intrinsicWidth?.coerceIn(0, SIZE_MASK) ?: 0
        val height = original?.intrinsicHeight?.coerceIn(0, SIZE_MASK) ?: 0
        val size = (width shl SIZE_SHIFT) or height
        if (density == 0) synchronized(originalSizes) { originalSizes.put(id, size) }
        return size
    }

    // mirrors VectorDrawable: TypedArray.getDimensionPixelSize, then Drawable.scaleFromDensity for explicit densities
    private fun convertDpToPx(dp: Float, displayDpi: Int, density: Int): Int {
        val base = (dp * displayDpi / 160f + 0.5f).toInt().coerceAtLeast(1)
        if (density == 0 || density == displayDpi) return base
        return (base * density / displayDpi.toFloat()).roundToInt().coerceAtLeast(1)
    }

    override fun close() = zip.close()

    companion object {
        private const val METADATA = "metadata.json"
        private const val MAX_ENTRIES = 8192
        private const val MAX_METADATA_BYTES = 1 shl 20
        private const val MAX_ICON_BYTES = 1 shl 20
        private const val MAX_LOTTIE_BYTES = 4 shl 20
        private const val LOTTIE_PREFIX = "lottie:"
        private const val MAX_BITMAP_SIDE = 512
        private const val FALLBACK_SIZE_DP = 24f
        private const val SIZE_SHIFT = 16
        private const val SIZE_MASK = 0xFFFF
        private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        private val FAILED = Any()

        @Throws(IOException::class)
        fun open(file: File): IconPack {
            val zip = ZipFile(file)
            try {
                if (zip.size() > MAX_ENTRIES) throw IOException("too many entries")
                val json = JSONObject(String(readEntry(zip, METADATA, MAX_METADATA_BYTES)))
                val id = json.getString("packId")
                if (!ID_PATTERN.matches(id)) throw IOException("invalid pack id")
                val name = json.getString("packName").trim()
                if (name.isEmpty()) throw IOException("empty pack name")

                val iconsJson = json.getJSONObject("icons")
                val icons = HashMap<String, String>(iconsJson.length())
                for (key in iconsJson.keys()) {
                    val path = iconsJson.getString(key)
                    if (zip.getEntry(path)?.isDirectory != false) throw IOException("missing $path")
                    icons[key] = path
                }
                if (icons.isEmpty()) throw IOException("no icons")
                return IconPack(id, name, json.optString("author").trim(), zip, icons)
            } catch (e: Exception) {
                zip.close()
                throw e as? IOException ?: IOException(e)
            }
        }

        private fun readEntry(zip: ZipFile, path: String, limit: Int): ByteArray {
            val entry = zip.getEntry(path) ?: throw IOException("missing $path")
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > limit) throw IOException("$path is too large")
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        }
    }
}
