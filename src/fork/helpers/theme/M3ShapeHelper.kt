package desu.inugram.helpers.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities

object M3ShapeHelper {
    private const val RADIUS_DP = 28f

    @JvmStatic
    fun sheet(base: Drawable): Drawable =
        if (InuConfig.MATERIAL3_DIALOGS.value) ShapeDrawable(base, true) else base

    @JvmStatic
    fun dialog(base: Drawable): Drawable =
        if (InuConfig.MATERIAL3_DIALOGS.value) ShapeDrawable(base, false) else base

    @JvmStatic
    fun dialogRadius(stock: Int): Int =
        if (InuConfig.MATERIAL3_DIALOGS.value) AndroidUtilities.dp(RADIUS_DP) else stock

    // entiny: same insets as the stock 9-patch so layouts do not shift, only the corner radius changes
    private class ShapeDrawable(base: Drawable, private val topOnly: Boolean) : Drawable() {
        private val pad = Rect().also { base.getPadding(it) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            rect.set(
                (b.left + pad.left).toFloat(), (b.top + pad.top).toFloat(),
                (b.right - pad.right).toFloat(), (b.bottom - pad.bottom).toFloat(),
            )
            val r = AndroidUtilities.dp(RADIUS_DP).toFloat()
            if (topOnly) {
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }

        override fun getPadding(padding: Rect): Boolean {
            padding.set(pad)
            return true
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
