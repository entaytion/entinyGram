package desu.inugram.helpers.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import google_material.Hct
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Md3PlayerColors private constructor(val dark: Boolean, private val c: IntArray) {
    val surface get() = c[0]
    val surfaceLow get() = c[1]
    val surfaceContainer get() = c[2]
    val surfaceHigh get() = c[3]
    val onSurface get() = c[4]
    val onSurfaceVariant get() = c[5]
    val outlineVariant get() = c[6]
    val primary get() = c[7]
    val onPrimary get() = c[8]
    val primaryContainer get() = c[9]
    val onPrimaryContainer get() = c[10]
    val secondaryContainer get() = c[11]
    val onSecondaryContainer get() = c[12]

    fun lightStatusBar(): Boolean = ColorUtils.calculateLuminance(surface) > 0.5

    fun shadow(): Int = if (dark) Color.BLACK else ColorUtils.blendARGB(primary, Color.BLACK, 0.4f)

    fun ripple(): Int = ColorUtils.setAlphaComponent(onSurface, 0x1f)

    fun provider(base: Theme.ResourcesProvider?): Theme.ResourcesProvider = object : Theme.ResourcesProvider {
        override fun getColor(key: Int): Int = when (key) {
            Theme.key_actionBarDefaultSubmenuBackground, Theme.key_dialogBackground -> surfaceContainer
            Theme.key_actionBarDefaultSubmenuItem, Theme.key_dialogTextBlack, Theme.key_windowBackgroundWhiteBlackText -> onSurface
            Theme.key_actionBarDefaultSubmenuItemIcon, Theme.key_dialogTextGray2, Theme.key_windowBackgroundWhiteGrayText -> onSurfaceVariant
            Theme.key_actionBarDefaultSubmenuSeparator, Theme.key_windowBackgroundGray -> surfaceLow
            Theme.key_dialogButtonSelector, Theme.key_listSelector -> ripple()
            Theme.key_windowBackgroundWhite -> surface
            Theme.key_featuredStickers_addButton, Theme.key_windowBackgroundWhiteBlueText -> primary
            else -> Theme.getColor(key, base)
        }

        override fun isDark(): Boolean = dark
    }

    companion object {
        @JvmStatic
        fun fromSeed(seed: Int, dark: Boolean): Md3PlayerColors {
            val hue = Hct.fromInt(seed).hue
            fun tone(chroma: Double, t: Double): Int = Hct.from(hue, chroma, t).toInt()
            val p = 36.0
            val s = 16.0
            val n = 6.0
            val nv = 8.0
            return if (dark) {
                Md3PlayerColors(true, intArrayOf(
                    tone(n, 6.0), tone(n, 10.0), tone(n, 12.0), tone(n, 17.0),
                    tone(n, 90.0), tone(nv, 80.0), tone(nv, 30.0),
                    tone(p, 80.0), tone(p, 20.0), tone(p, 30.0), tone(p, 90.0),
                    tone(s, 30.0), tone(s, 90.0),
                ))
            } else {
                Md3PlayerColors(false, intArrayOf(
                    tone(n, 98.0), tone(n, 96.0), tone(n, 94.0), tone(n, 92.0),
                    tone(n, 10.0), tone(nv, 30.0), tone(nv, 80.0),
                    tone(p, 40.0), tone(p, 100.0), tone(p, 90.0), tone(p, 10.0),
                    tone(s, 90.0), tone(s, 10.0),
                ))
            }
        }

        @JvmStatic
        fun lerp(a: Md3PlayerColors, b: Md3PlayerColors, t: Float): Md3PlayerColors {
            if (t <= 0f) return a
            if (t >= 1f) return b
            return Md3PlayerColors(b.dark, IntArray(a.c.size) { ColorUtils.blendARGB(a.c[it], b.c[it], t) })
        }

        @JvmStatic
        fun isDark(resourcesProvider: Theme.ResourcesProvider?): Boolean =
            ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)) < 0.5

        @JvmStatic
        fun fallbackSeed(resourcesProvider: Theme.ResourcesProvider?): Int {
            val color = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)
            return if (Color.alpha(color) == 0) 0xff4285f4.toInt() else color or 0xff000000.toInt()
        }

        fun sample(bitmap: Bitmap?): Bitmap? {
            if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
            return try {
                var source: Bitmap = bitmap
                if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
                    source = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                }
                val k = min(1f, 112f / max(source.width, source.height))
                val w = max(1, (source.width * k).roundToInt())
                val h = max(1, (source.height * k).roundToInt())
                val small = Bitmap.createScaledBitmap(source, w, h, true)
                if (small === source) source.copy(Bitmap.Config.ARGB_8888, false) else small
            } catch (e: Throwable) {
                null
            }
        }

        fun seedFromSample(small: Bitmap?, fallback: Int): Int {
            if (small == null) return fallback
            return try {
                val swatches = Palette.from(small).maximumColorCount(24).clearFilters().generate().swatches
                val total = swatches.sumOf { it.population }.toFloat()
                if (total <= 0f) return fallback
                var best = fallback
                var bestScore = Double.NEGATIVE_INFINITY
                for (swatch in swatches) {
                    val hct = Hct.fromInt(swatch.rgb)
                    val proportion = swatch.population / total
                    if (hct.chroma < 5.0 || proportion < 0.01f) continue
                    val chromaScore = (hct.chroma - 48.0) * if (hct.chroma < 48.0) 0.1 else 0.3
                    val score = proportion * 100.0 * 0.7 + chromaScore
                    if (score > bestScore) {
                        bestScore = score
                        best = swatch.rgb or 0xff000000.toInt()
                    }
                }
                best
            } catch (e: Throwable) {
                fallback
            }
        }
    }
}

object Md3PlayerArt {
    private val seeds = LruCache<String, Int>(64)
    private val pending = ArrayList<Pair<String, (Int) -> Unit>>()

    @JvmStatic
    fun key(messageObject: MessageObject?): String? {
        if (messageObject == null) return null
        val document = messageObject.document
        if (document != null && document.id != 0L) return "d" + document.id
        return "m" + messageObject.dialogId + "_" + messageObject.id
    }

    fun cachedSeed(messageObject: MessageObject?): Int? {
        val key = key(messageObject) ?: return null
        return seeds.get(key)
    }

    fun isPlaying(messageObject: MessageObject?): Boolean {
        val playing = MediaController.getInstance().playingMessageObject
        return playing != null && messageObject != null && TextUtils.equals(key(playing), key(messageObject))
    }

    fun fileCover(messageObject: MessageObject?): Bitmap? {
        if (!isPlaying(messageObject)) return null
        return MediaController.getInstance().audioInfo?.cover
    }

    fun thumbLocation(messageObject: MessageObject): ImageLocation? {
        val document = messageObject.document
        val thumb = if (document != null) FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) else null
        if (thumb is TLRPC.TL_photoSize || thumb is TLRPC.TL_photoSizeProgressive) {
            return ImageLocation.getForDocument(thumb, document)
        }
        val small = messageObject.getArtworkUrl(true)
        return if (small != null) ImageLocation.getForPath(small) else null
    }

    fun fullLocation(messageObject: MessageObject): ImageLocation? {
        val url = messageObject.getArtworkUrl(false)
        return if (TextUtils.isEmpty(url)) null else ImageLocation.getForPath(url)
    }

    fun requestSeed(messageObject: MessageObject, bitmap: Bitmap, fallback: Int, callback: (Int) -> Unit) {
        val key = key(messageObject) ?: return
        seeds.get(key)?.let {
            callback(it)
            return
        }
        if (pending.any { it.first == key }) {
            pending.add(key to callback)
            return
        }
        val sample = Md3PlayerColors.sample(bitmap) ?: return
        pending.add(key to callback)
        Utilities.globalQueue.postRunnable {
            val seed = Md3PlayerColors.seedFromSample(sample, fallback)
            AndroidUtilities.runOnUIThread {
                seeds.put(key, seed)
                for (i in pending.indices.reversed()) {
                    val p = pending[i]
                    if (p.first == key) {
                        pending.removeAt(i)
                        p.second(seed)
                    }
                }
            }
        }
    }
}
