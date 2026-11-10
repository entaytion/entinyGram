package desu.inugram.helpers.theme

import android.graphics.drawable.Drawable
import desu.inugram.InuConfig
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import java.util.WeakHashMap

object GlassGlareHelper {
    private val drawables = WeakHashMap<Drawable, Int>()

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
}
