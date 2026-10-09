package desu.inugram.ui.appicons

// entiny: ported from exteraless (app.exteraless.appicons), GPL-3.0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import desu.inugram.helpers.security.ParanoiaHelper
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.LauncherIconController
import org.telegram.ui.LauncherIconController.LauncherIcon
import kotlin.math.abs
import kotlin.math.min

object AppIcons {
    private val accents = HashMap<LauncherIcon, Int>()
    private val descriptions = mapOf(
        LauncherIcon.DEFAULT to R.string.InuAppIconDescDefault,
        LauncherIcon.OLD to R.string.InuAppIconDescOld,
        LauncherIcon.INU_TRIBUTE to R.string.InuAppIconDescInuTribute,
        LauncherIcon.RADAR to R.string.InuAppIconDescRadar,
        LauncherIcon.CONSTELLATION to R.string.InuAppIconDescConstellation,
        LauncherIcon.ORBIT to R.string.InuAppIconDescOrbit,
        LauncherIcon.AVATAR to R.string.InuAppIconDescAvatar,
        LauncherIcon.NOTHING to R.string.InuAppIconDescNothing,
        LauncherIcon.MONO to R.string.InuAppIconDescMono,
        LauncherIcon.SUNSET to R.string.InuAppIconDescSunset,
        LauncherIcon.AMETHYST to R.string.InuAppIconDescAmethyst,
        LauncherIcon.AURORA to R.string.InuAppIconDescAurora,
        LauncherIcon.GOOGLE26 to R.string.InuAppIconDescGoogle26,
        LauncherIcon.LUNAR to R.string.InuAppIconDescLunar,
        LauncherIcon.MATHBOOK to R.string.InuAppIconDescMathBook,
        LauncherIcon.JUNGLE to R.string.InuAppIconDescJungle,
        LauncherIcon.GOLD to R.string.InuAppIconDescGold,
        LauncherIcon.DISH to R.string.InuAppIconDescDish,
        LauncherIcon.BLUSH to R.string.InuAppIconDescBlush,
        LauncherIcon.NOX_SKY to R.string.InuAppIconDescNoxSky,
        LauncherIcon.INVERSE to R.string.InuAppIconDescInverse,
        LauncherIcon.OSU to R.string.InuAppIconDescOsu,
        LauncherIcon.AMONG_US to R.string.InuAppIconDescAmongUs,
        LauncherIcon.CRIMSON to R.string.InuAppIconDescCrimson,
    )

    private val authors = mapOf(
        LauncherIcon.ORBIT to "@xiurann",
        LauncherIcon.AVATAR to "@xiurann",
        LauncherIcon.NOTHING to "@xiurann",
        LauncherIcon.MONO to "@xiurann",
        LauncherIcon.SUNSET to "@xiurann",
        LauncherIcon.AMETHYST to "@xiurann",
        LauncherIcon.AURORA to "@xiurann",
        LauncherIcon.GOOGLE26 to "@xiurann",
        LauncherIcon.LUNAR to "@xiurann",
        LauncherIcon.MATHBOOK to "@xiurann",
        LauncherIcon.JUNGLE to "@xiurann",
        LauncherIcon.BLUSH to "@xiurann",
        LauncherIcon.NOX_SKY to "@xiurann",
        LauncherIcon.INVERSE to "@xiurann",
        LauncherIcon.OSU to "@xiurann",
        LauncherIcon.AMONG_US to "@xiurann",
        LauncherIcon.CRIMSON to "@mkr_infinity",
    )


    @Volatile
    private var pending: LauncherIcon? = null

    fun title(icon: LauncherIcon): CharSequence = LocaleController.getString(icon.title)

    fun description(icon: LauncherIcon): CharSequence? {
        val text = LocaleController.getString(descriptions[icon] ?: return null)
        val author = authors[icon] ?: return text
        return text + " · " + LocaleController.formatString(R.string.InuAppIconBy, author)
    }

    fun available(account: Int): List<LauncherIcon> {
        val icons = ArrayList(LauncherIcon.values().asList())
        ParanoiaHelper.filterLauncherIcons(icons)
        if (MessagesController.getInstance(account).premiumFeaturesBlocked()) {
            icons.removeAll { it.premium }
        }
        return icons
    }

    fun locked(icon: LauncherIcon): Boolean = icon.premium && !UserConfig.hasPremiumOnAccounts()

    fun current(): LauncherIcon {
        pending?.let { return it }
        return LauncherIcon.values().firstOrNull { LauncherIconController.isEnabled(it) } ?: LauncherIcon.DEFAULT
    }

    fun apply(icon: LauncherIcon) {
        pending = icon
        Utilities.globalQueue.postRunnable {
            LauncherIconController.setIcon(icon)
            if (pending == icon) pending = null
        }
    }

    // adaptive layers are 108dp while only the central 72dp is visible
    fun draw(canvas: Canvas, background: Drawable?, foreground: Drawable?, size: Int) {
        val bleed = size / 4
        background?.apply {
            setBounds(-bleed, -bleed, size + bleed, size + bleed)
            draw(canvas)
        }
        foreground?.apply {
            setBounds(-bleed, -bleed, size + bleed, size + bleed)
            draw(canvas)
        }
    }

    fun accent(context: Context, icon: LauncherIcon): Int {
        accents[icon]?.let { return it }
        var color = 0
        try {
            val size = 24
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap), ContextCompat.getDrawable(context, icon.background), ContextCompat.getDrawable(context, icon.foreground), size)
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            bitmap.recycle()
            color = dominant(pixels)
        } catch (e: Exception) {
            FileLog.e(e)
        }
        accents[icon] = color
        return color
    }

    private fun dominant(pixels: IntArray): Int {
        val bins = 12
        val weight = FloatArray(bins)
        val hue = FloatArray(bins)
        val saturation = FloatArray(bins)
        val lightness = FloatArray(bins)
        val hsl = FloatArray(3)
        var colored = 0
        var extreme = 0
        var extremeLightness = 0f
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 200) continue
            ColorUtils.colorToHSL(pixel, hsl)
            if (hsl[1] < 0.15f) continue
            if (hsl[2] < 0.06f || hsl[2] > 0.94f) {
                extreme++
                extremeLightness += hsl[2]
                continue
            }
            val bin = min(bins - 1, (hsl[0] / 360f * bins).toInt())
            val w = hsl[1] * (1f - abs(hsl[2] * 2f - 1f))
            weight[bin] += w
            hue[bin] += hsl[0] * w
            saturation[bin] += hsl[1] * w
            lightness[bin] += hsl[2] * w
            colored++
        }
        var best = 0
        for (i in 1 until bins) {
            if (weight[i] > weight[best]) best = i
        }
        if (weight[best] > 0f && colored >= extreme * 0.15f) {
            hsl[0] = hue[best] / weight[best]
            hsl[1] = saturation[best] / weight[best]
            hsl[2] = lightness[best] / weight[best]
            return ColorUtils.HSLToColor(hsl)
        }
        if (extreme == 0) return 0
        hsl[0] = 0f
        hsl[1] = 0f
        hsl[2] = extremeLightness / extreme
        return ColorUtils.HSLToColor(hsl)
    }

    fun tint(accent: Int): Int {
        if (accent == 0) return 0
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, hsl)
        hsl[1] = min(hsl[1], 0.6f)
        hsl[2] = if (Theme.isCurrentThemeDark()) 0.22f else 0.86f
        return ColorUtils.HSLToColor(hsl)
    }
}
