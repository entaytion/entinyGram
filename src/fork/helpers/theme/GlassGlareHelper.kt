package desu.inugram.helpers.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import java.util.WeakHashMap

object GlassGlareHelper {
    private val drawables = WeakHashMap<Drawable, Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val bounds = RectF()
    private val outerRadii = FloatArray(8)
    private val innerRadii = FloatArray(8)
    private val uniformRadii = FloatArray(8)

    val mode: Int
        get() = when {
            InuConfig.DISABLE_GLASS_GLARE.value -> 0
            InuConfig.GLASS_GLARE_SOLID.value -> 2
            else -> 1
        }

    fun setMode(mode: Int) {
        if (mode !in 0..2 || mode == this.mode) return
        InuConfig.DISABLE_GLASS_GLARE.value = mode == 0
        InuConfig.GLASS_GLARE_SOLID.value = mode == 2
        for (drawable in drawables.keys.toList()) {
            if (drawable is BlurredBackgroundDrawable) drawable.updateColors()
            drawables[drawable] = mode
            drawable.invalidateSelf()
        }
    }

    @JvmStatic
    fun isSolid(): Boolean = InuConfig.GLASS_GLARE_SOLID.value

    @JvmStatic
    fun invalidateLiquidGlass() {
        for (drawable in drawables.keys.toList()) {
            if (drawable is BlurredBackgroundDrawable) drawable.updateColors()
            drawable.invalidateSelf()
        }
    }

    @JvmStatic
    fun register(drawable: Drawable) {
        drawables[drawable] = mode
    }

    @JvmStatic
    fun syncMode(drawable: BlurredBackgroundDrawable) {
        val currentMode = mode
        if (drawables[drawable] == currentMode) return
        drawables[drawable] = currentMode
        drawable.updateColors()
    }

    @JvmStatic
    fun drawStroke(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        radii: FloatArray, isTop: Boolean, color: Int,
    ): Boolean {
        if (!InuConfig.GLASS_GLARE_SOLID.value) return false
        if (!isTop) drawOutline(canvas, left, top, right, bottom, radii, color)
        return true
    }

    @JvmStatic
    fun drawStroke(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        radius: Float, isTop: Boolean, color: Int,
    ): Boolean {
        if (!InuConfig.GLASS_GLARE_SOLID.value) return false
        if (!isTop) {
            uniformRadii.fill(radius)
            drawOutline(canvas, left, top, right, bottom, uniformRadii, color)
        }
        return true
    }

    @JvmStatic
    fun drawOutline(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        radii: FloatArray, color: Int,
    ): Boolean {
        if (!InuConfig.GLASS_GLARE_SOLID.value) return false
        if (InuConfig.DISABLE_GLASS_GLARE.value || Color.alpha(color) == 0 || right <= left || bottom <= top) return true
        bounds.set(left, top, right, bottom)
        for (i in outerRadii.indices) outerRadii[i] = radii[i].coerceAtLeast(0f)
        val scale = minOf(
            1f,
            bounds.width() / maxOf(outerRadii[0] + outerRadii[2], outerRadii[6] + outerRadii[4]),
            bounds.height() / maxOf(outerRadii[1] + outerRadii[7], outerRadii[3] + outerRadii[5]),
        )
        for (i in outerRadii.indices) outerRadii[i] *= scale
        outline.rewind()
        outline.fillType = Path.FillType.EVEN_ODD
        outline.addRoundRect(bounds, outerRadii, Path.Direction.CW)
        val width = AndroidUtilities.dpf2(1f)
        bounds.inset(width, width)
        if (!bounds.isEmpty) {
            for (i in innerRadii.indices) innerRadii[i] = (outerRadii[i] - width).coerceAtLeast(0f)
            outline.addRoundRect(bounds, innerRadii, Path.Direction.CW)
        }
        paint.color = color
        canvas.drawPath(outline, paint)
        return true
    }
}
