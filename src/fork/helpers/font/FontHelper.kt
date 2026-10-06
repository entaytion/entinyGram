package desu.inugram.helpers.font

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import android.text.TextPaint
import android.widget.TextView
import androidx.annotation.RequiresApi
import desu.inugram.helpers.font.FontConfig.FontMode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.ui.ActionBar.Theme
import java.lang.reflect.Field
import java.util.Hashtable

object FontHelper {
    private const val TAG = "InuFonts"

    // entiny: default/mono fonts captured before replacing via reflection
    val stockDefault: Typeface = Typeface.DEFAULT
    val stockMonospace: Typeface = Typeface.MONOSPACE
    private var emojiScale = 1f
    private var emojiBottomPaddingRatio = 0f

    private val typefaceDefaultField = Typeface::class.java.getDeclaredField("DEFAULT")
    private val typefaceDefaultBoldField = Typeface::class.java.getDeclaredField("DEFAULT_BOLD")
    private val typefaceMonospaceField = Typeface::class.java.getDeclaredField("MONOSPACE")
    private val typefaceSansSerifField = Typeface::class.java.getDeclaredField("SANS_SERIF")

    fun init(context: Context) {
        FontLibrary.loadStorage(context)
        validateActiveAppFont()
        validateMonoFont()

        try {
            typefaceDefaultField.isAccessible = true
            typefaceDefaultBoldField.isAccessible = true
            typefaceMonospaceField.isAccessible = true
            typefaceSansSerifField.isAccessible = true
        } catch (_: Exception) {
        }
    }

    fun validateActiveAppFont() {
        val m = FontConfig.FONT.value as? FontMode.Custom ?: return
        val id = m.fontId as? FontId.Family ?: run {
            FileLog.d("$TAG: validateActiveAppFont: non-family app font ${m.fontId.token()}, resetting to bundled")
            return resetAppFont()
        }
        if (if (id.id.isEmpty()) !FontLibrary.hasAnyFamily() else !FontLibrary.containsFamily(id.id)) {
            FileLog.d("$TAG: validateActiveAppFont: family ${id.id} not loaded, resetting to bundled")
            resetAppFont()
        }
    }

    fun resetAppFont() {
        FontConfig.FONT.value = FontMode.Bundled
    }

    fun validateMonoFont() {
        when (val id = FontId.parse(FontConfig.MONO_FONT.value.ifEmpty { return })) {
            is FontId.Builtin -> Unit
            is FontId.System -> {
                FileLog.d("$TAG: validateMonoFont: system font ${id.name} can't be the mono font, resetting to stock monospace")
                resetMonoFont()
            }

            is FontId.Family -> if (!FontLibrary.containsFamily(id.id)) {
                FileLog.d("$TAG: validateMonoFont: family ${id.id} not loaded, resetting to stock monospace")
                resetMonoFont()
            }
        }
    }

    fun resetMonoFont() {
        FontConfig.MONO_FONT.value = ""
    }

    fun isActiveCustomFont(id: FontId): Boolean {
        val m = FontConfig.FONT.value as? FontMode.Custom ?: return false
        return maybeResolveLegacyEmpty(m.fontId) == id
    }

    fun isActiveMonoFont(id: FontId): Boolean {
        val token = FontConfig.MONO_FONT.value
        return token.isNotEmpty() && FontId.parse(token) == id
    }

    fun maybeResolveLegacyEmpty(fontId: FontId): FontId? {
        if (fontId is FontId.Family && fontId.id.isEmpty()) {
            return FontLibrary.firstFamilyId()?.let { FontId.Family(it) }
        }
        return fontId
    }

    fun getActiveFallbackIds(): List<FontId> =
        (FontConfig.FONT.value as? FontMode.Custom)?.fallbacks ?: emptyList()

    private fun resolve(targetWeight: Int, targetItalic: Boolean): Typeface? {
        val m = FontConfig.FONT.value as? FontMode.Custom ?: return null
        val primary = maybeResolveLegacyEmpty(m.fontId) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            composite(primary, m.fallbacks, targetWeight, targetItalic)?.let { return it }
        }
        return singleTokenTypeface(primary, targetWeight, targetItalic)
    }

    private fun singleTokenTypeface(token: FontId?, weight: Int, italic: Boolean): Typeface? {
        return FontLibrary.getFamilyTypeface(token ?: return null, weight, italic)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun fontFamilyForToken(token: FontId, weight: Int, italic: Boolean): FontFamily? {
        val font = FontLibrary.getFont(token, weight, italic) ?: run {
            FileLog.d("$TAG: fontFamilyForToken: no font for ${token.token()} w=$weight i=$italic")
            return null
        }
        return try {
            FontFamily.Builder(font).build()
        } catch (e: Throwable) {
            FileLog.e("$TAG: fontFamilyForToken: FontFamily.Builder failed for ${token.token()}", e)
            null
        }
    }

    // entiny: forceSystemFallback retains builder so implicit system fallback renders missing glyphs
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun composite(
        primary: FontId,
        fallbacks: List<FontId>,
        weight: Int,
        italic: Boolean,
        forceSystemFallback: Boolean = false,
    ): Typeface? {
        if (!forceSystemFallback && fallbacks.isEmpty()) return null
        val primaryFamily = fontFamilyForToken(primary, weight, italic) ?: return null
        return try {
            val b = Typeface.CustomFallbackBuilder(primaryFamily)
            for (tok in fallbacks) {
                if (tok == primary) continue
                val fam = fontFamilyForToken(tok, weight, italic) ?: continue
                try {
                    b.addCustomFallback(fam)
                } catch (_: Throwable) {
                    break
                }
            }
            b.setStyle(FontStyle(weight.coerceIn(1, 1000), if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT))
            val tf = b.build()

            // entiny: setStyle won't fake-bold medium weights; force bold when primary lacks heavier face
            val primaryLacksWeight = weight >= 500 &&
                (FontLibrary.getFontFamily(primary)?.lacksWeight(weight, italic) ?: false)
            if (primaryLacksWeight) {
                Typeface.create(tf, if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD)
            } else {
                tf
            }
        } catch (e: Throwable) {
            FileLog.e("$TAG: composite: failed for primary=${primary.token()} w=$weight i=$italic", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun getPreviewTypeface(primary: String?, fallbacks: List<String>, weight: Int, italic: Boolean): Typeface? {
        primary ?: return null
        if (primary == FontConfig.SYSTEM_STACK_ID) return Typeface.create(null as Typeface?, weight, italic)
        val primaryId = FontId.parse(primary)
        val fallbackIds = fallbacks.filter { it != FontConfig.SYSTEM_STACK_ID }.map { FontId.parse(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            composite(primaryId, fallbackIds, weight, italic, forceSystemFallback = true)?.let { return it }
        }
        return singleTokenTypeface(primaryId, weight, italic)
    }

    class PreviewTypefaces(
        val regular: Typeface,
        val bold: Typeface,
        val italic: Typeface,
        val mono: Typeface,
    )

    @RequiresApi(Build.VERSION_CODES.P)
    fun buildPreviewTypefaces(primary: String?, fallbacks: List<String>, monoToken: String): PreviewTypefaces {
        val savedDefault = Typeface.DEFAULT
        trySetStatic(typefaceDefaultField, stockDefault)
        try {
            fun tf(weight: Int, italic: Boolean): Typeface =
                (if (primary != null) getPreviewTypeface(primary, fallbacks, weight, italic) else null)
                    ?: Typeface.create(stockDefault, weight, italic)
            return PreviewTypefaces(
                tf(400, false), tf(700, false), tf(400, true),
                FontLibrary.getTypefaceFor(monoToken) ?: stockMonospace,
            )
        } finally {
            trySetStatic(typefaceDefaultField, savedDefault)
        }
    }

    private fun styleForAsset(assetPath: String): Pair<Int, Boolean>? = when (assetPath) {
        AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM -> 500 to false
        AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD -> 800 to false
        AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC -> 500 to true
        "fonts/ritalic.ttf" -> 400 to true
        "fonts/rcondensedbold.ttf" -> 700 to false
        else -> null
    }

    // entiny: process-wide font install via Typeface statics and sSystemFontMap reflection swaps
    @Suppress("UNCHECKED_CAST")
    @SuppressLint("DiscouragedPrivateApi")
    @RequiresApi(Build.VERSION_CODES.P)
    fun installGlobal() {
        val map = try {
            Typeface::class.java.getDeclaredField("sSystemFontMap").apply { isAccessible = true }
                .get(null) as? MutableMap<String, Typeface>
        } catch (_: Throwable) {
            null
        }

        val mode = FontConfig.FONT.value
        val regular = (mode as? FontMode.Custom)?.let { resolve(400, false) }
        if (mode is FontMode.Custom && regular == null) {
            FileLog.d("$TAG: installGlobal: active custom font ${mode.fontId.token()} did not resolve, app font unchanged")
        }
        if (regular != null) {
            val bold = resolve(700, false) ?: regular
            val stockPaint = TextPaint().apply {
                textSize = 100f
                typeface = stockDefault
            }
            val customPaint = TextPaint().apply {
                textSize = 100f
                typeface = regular
            }
            val stockMetrics = stockPaint.fontMetrics
            val customMetrics = customPaint.fontMetrics
            val stockHeight = stockMetrics.descent - stockMetrics.ascent
            val customHeight = customMetrics.descent - customMetrics.ascent
            if (stockHeight > 0f && customHeight > 0f) {
                emojiScale = (stockHeight / customHeight).coerceIn(0.75f, 1.25f)
                emojiBottomPaddingRatio = (stockMetrics.bottom - stockMetrics.descent) / customHeight
            }
            trySetStatic(typefaceDefaultField, regular)
            trySetStatic(typefaceDefaultBoldField, bold)
            trySetStatic(typefaceSansSerifField, regular)
            for (k in arrayOf(
                "sans-serif", "sans-serif-light", "sans-serif-thin",
                "sans-serif-condensed", "sans-serif-condensed-light"
            )) map?.put(k, regular)
            for (k in arrayOf("sans-serif-medium", "sans-serif-black")) map?.put(k, bold)
        }
        FontLibrary.getTypefaceFor(FontConfig.MONO_FONT.value)?.let { mono ->
            trySetStatic(typefaceMonospaceField, mono)
            map?.put("monospace", mono)
        }
    }

    private fun trySetStatic(field: Field, value: Any) {
        try {
            field.set(null, value)
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun getEmojiScale(): Float = emojiScale

    @JvmStatic
    fun getEmojiBaselineOffset(metrics: Paint.FontMetricsInt?): Float {
        metrics ?: return 0f
        val height = (metrics.descent - metrics.ascent).toFloat()
        if (height <= 0f) return 0f
        return ((metrics.bottom - metrics.descent) - height * emojiBottomPaddingRatio)
            .coerceIn(-height * 0.1f, height * 0.1f)
    }

    @JvmStatic
    fun applyDefaultFont(paint: TextPaint?) {
        if (paint == null || paint.typeface != null) return
        if (FontConfig.FONT.value !is FontMode.Custom) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        resolve(400, false)?.let { paint.typeface = it }
    }

    @JvmStatic
    fun applyDefaultFont(view: TextView?) {
        if (view == null) return
        if (FontConfig.FONT.value !is FontMode.Custom) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        resolve(400, false)?.let { view.typeface = it }
    }

    // entiny: apply custom font to stock Theme TextPaints created without an explicit typeface
    @JvmStatic
    fun onThemePaintsCreated() {
        if (FontConfig.FONT.value !is FontMode.Custom) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val tf = resolve(400, false) ?: return
        try {
            for (field in Theme::class.java.declaredFields) {
                val name = field.name
                if (!name.endsWith("Paint")) continue
                if (!(name.startsWith("chat_") || name.startsWith("dialogs_") || name.startsWith("profile_"))) continue
                val value = field.get(null) ?: continue
                when (value) {
                    is TextPaint -> if (value.typeface == null) value.typeface = tf
                    is Array<*> -> for (item in value) {
                        if (item is TextPaint && item.typeface == null) item.typeface = tf
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun onGetTypeface(cache: Hashtable<String, Typeface>, assetPath: String): Typeface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val (weight, italic) = styleForAsset(assetPath) ?: return null
        val mode = FontConfig.FONT.value
        val keyPart = when (mode) {
            FontMode.Bundled -> return null
            FontMode.System -> "sys"
            is FontMode.Custom -> "c:${mode.fontId.token()}:${mode.fallbacks.joinToString(",") { it.token() }}"
        }
        val key = "inu:$keyPart:$assetPath"
        cache[key]?.let { return it }
        val tf = when (mode) {
            FontMode.System -> Typeface.create(null as Typeface?, weight, italic)
            is FontMode.Custom -> resolve(weight, italic)
            else -> null
        } ?: return null
        cache[key] = tf
        return tf
    }
}
