package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CacheControlActivity
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// entiny: Telegram's on-disk cache size + a ring showing device storage fill, reusing stock CacheControlActivity's calculation.
@SuppressLint("ViewConstructor")
class CachePill(context: Context, resourcesProvider: Theme.ResourcesProvider?) : BasePill(context, resourcesProvider) {

    companion object {
        private val lastKnownCacheSize = AtomicLong(-1)
        private var lastKnownProgress = -1f
    }

    private val calculating = AtomicBoolean(false)
    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)
    private val progressDrawable = StorageProgressDrawable()

    private class StorageProgressDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rectF = RectF()
        private var progress = 0f
        private var color = 0

        init {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width()
            val height = bounds.height()
            val size = minOf(width, height) - AndroidUtilities.dp(2f)
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            rectF.set(left, top, left + size, top + size)
            paint.strokeWidth = AndroidUtilities.dp(2f).toFloat()
            paint.color = color
            paint.alpha = 50
            canvas.drawCircle(width / 2f, height / 2f, size / 2f, paint)
            paint.alpha = 255
            canvas.drawArc(rectF, -90f, progress * 360, false, paint)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

        fun setColor(c: Int) {
            color = c
            invalidateSelf()
        }

        fun setProgress(value: Float) {
            progress = value.coerceIn(0.05f, 1f)
            invalidateSelf()
        }
    }

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(8f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f))
        iconView.setImageDrawable(progressDrawable)

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setTypeface(AndroidUtilities.bold())
        textView.setIncludeFontPadding(false)
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)

        if (lastKnownCacheSize.get() != -1L && !isRefreshDue()) {
            setData(lastKnownCacheSize.get(), lastKnownProgress, false)
        } else {
            iconView.visibility = GONE
            textView.visibility = GONE
        }
    }

    override fun getPillId(): Int = PillType.CACHE.id

    override fun getRefreshInterval(): Long = 3 * 60 * 1000L

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = false

    override fun onUpdateData(force: Boolean) {
        val never = lastKnownCacheSize.get() == -1L
        if (!(force || never || isRefreshDue()) || !calculating.compareAndSet(false, true)) return
        if (force || never) CacheControlActivity.resetCalculatedTotalSIze()
        startLoading()
        ImageLoader.getInstance().checkMediaPaths {
            CacheControlActivity.calculateTotalSize { cacheSize ->
                lastKnownCacheSize.set(cacheSize)
                CacheControlActivity.getDeviceTotalSize { totalSize, freeSize ->
                    val progress = if (totalSize > 0) (totalSize - freeSize) / totalSize.toFloat() else 0f
                    lastKnownProgress = progress
                    calculating.set(false)
                    setData(cacheSize, progress, true)
                }
            }
        }
    }

    private fun setData(cacheSize: Long, progress: Float, animated: Boolean) {
        stopLoading()
        val size = AndroidUtilities.formatFileSize(cacheSize)
        if (animated && (textView.text == null || textView.text.toString() != size || visibility == GONE)) {
            animateSizeChange()
        }
        textView.setText(size, animated)
        progressDrawable.setProgress(progress)
        iconView.visibility = VISIBLE
        textView.visibility = VISIBLE
    }

    override fun drawableHotspotChanged(x: Float, y: Float) {
        if (loading) return
        super.drawableHotspotChanged(x, y)
        layout.drawableHotspotChanged(x - layout.left, y - layout.top)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun updateColors() {
        val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f)
        )
        textView.setTextColor(color)
        progressDrawable.setColor(color)
        updateLoadingColors()
    }
}
