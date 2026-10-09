package desu.inugram.ui.appicons

// entiny: ported from exteraless (app.exteraless.appicons), GPL-3.0

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.LauncherIconController.LauncherIcon
import kotlin.math.min

class IconPreviewView(context: Context, insetDp: Int) : View(context) {
    private val inset = AndroidUtilities.dp(insetDp.toFloat())
    private val shape = Path()
    private var shapeSize = -1

    var icon: LauncherIcon? = null
        private set
    private var bgLayer: Drawable? = null
    private var fgLayer: Drawable? = null
    private var oldBg: Drawable? = null
    private var oldFg: Drawable? = null
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private var scale = 1f

    fun setIcon(icon: LauncherIcon?, animated: Boolean) {
        if (this.icon == icon) return
        val crossfade = animated && this.icon != null
        this.icon = icon
        animator?.cancel()
        animator = null
        if (crossfade) {
            oldBg = bgLayer
            oldFg = fgLayer
        }
        bgLayer = icon?.let { ContextCompat.getDrawable(context, it.background) }
        fgLayer = icon?.let { ContextCompat.getDrawable(context, it.foreground) }
        if (crossfade) {
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).setDuration(260).apply {
                interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
                addUpdateListener {
                    progress = it.animatedValue as Float
                    if (progress >= 1f) {
                        oldBg = null
                        oldFg = null
                    }
                    invalidate()
                }
                start()
            }
        } else {
            progress = 1f
            oldBg = null
            oldFg = null
        }
        invalidate()
    }

    fun setPreviewScale(scale: Float) {
        if (this.scale != scale) {
            this.scale = scale
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height) - inset * 2
        if (size <= 0 || (bgLayer == null && fgLayer == null)) return
        if (shapeSize != size) {
            shapeSize = size
            shape.reset()
            shape.addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.28f, size * 0.28f, Path.Direction.CW)
        }
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(scale, scale, size / 2f, size / 2f)
        canvas.clipPath(shape)
        if (progress < 1f && (oldBg != null || oldFg != null)) {
            AppIcons.draw(canvas, oldBg, oldFg, size)
            canvas.saveLayerAlpha(0f, 0f, size.toFloat(), size.toFloat(), (255 * progress).toInt())
            AppIcons.draw(canvas, bgLayer, fgLayer, size)
            canvas.restore()
        } else {
            AppIcons.draw(canvas, bgLayer, fgLayer, size)
        }
        canvas.restore()
    }
}
