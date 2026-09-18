package desu.inugram.helpers.pillstack.pills

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

class ColoredBackground(colorTop: Int = 0xFF1BA4ED.toInt(), colorBottom: Int = 0xFF1488E1.toInt()) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    init {
        paint.shader = LinearGradient(
            0f, 0f, 0f, AndroidUtilities.dp(28f).toFloat(),
            intArrayOf(colorTop, colorBottom), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = AndroidUtilities.dp(1f).toFloat()
        strokePaint.shader = LinearGradient(
            0f, 0f, 0f, AndroidUtilities.dp(28f).toFloat(),
            intArrayOf(0x4DFFFFFF, 0x00FFFFFF, 0x1AFFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val radius = AndroidUtilities.dp(14f).toFloat()
        rectF.set(bounds)
        canvas.drawRoundRect(rectF, radius, radius, paint)
        if (!Theme.isCurrentThemeDark() || isMonetTheme()) return
        val width = AndroidUtilities.dp(1f).toFloat()
        strokePaint.strokeWidth = width
        rectF.inset(width / 2f, width / 2f)
        canvas.drawRoundRect(rectF, radius, radius, strokePaint)
    }

    private fun isMonetTheme(): Boolean = Theme.getActiveTheme()?.inu_isMonet() == true

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
