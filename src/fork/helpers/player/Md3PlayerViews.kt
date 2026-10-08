package desu.inugram.helpers.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LiteMode
import org.telegram.messenger.MessageObject
import org.telegram.ui.Components.BackupImageView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private fun dp(value: Float): Int = AndroidUtilities.dp(value)

private fun dpf(value: Float): Float = AndroidUtilities.dpf2(value)

private fun clamp01(v: Float): Float = max(0f, min(1f, v))

private const val TAU = (PI * 2).toFloat()

class Md3Spring(value: Float, private val stiffness: Float, dampingRatio: Float, private val epsilon: Float) {
    private val damping = 2f * dampingRatio * sqrt(stiffness)
    var value = value
    var target = value
    private var velocity = 0f

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun settled(): Boolean = abs(value - target) < epsilon && abs(velocity) < epsilon * 10f

    fun step(dt: Float): Boolean {
        if (settled()) {
            value = target
            velocity = 0f
            return false
        }
        var remaining = min(dt, 0.064f)
        while (remaining > 0f) {
            val h = min(remaining, 0.008f)
            val a = -stiffness * (value - target) - damping * velocity
            velocity += a * h
            value += velocity * h
            remaining -= h
        }
        if (settled()) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}

class Md3PlayerIcon(stroke: String?, fill: String?, sizeDp: Float) : Drawable() {
    private val strokePath = stroke?.let { path(it) }
    private val fillPath = fill?.let { path(it) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val size = dp(sizeDp)
    private var fillStroke = false

    fun setColor(color: Int) {
        if (strokePaint.color != color || fillPaint.color != color) {
            strokePaint.color = color
            fillPaint.color = color
            invalidateSelf()
        }
    }

    fun setFillStroke(fill: Boolean) {
        if (fillStroke != fill) {
            fillStroke = fill
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.centerX() - size / 2f, b.centerY() - size / 2f)
        val k = size / 24f
        canvas.scale(k, k)
        fillPath?.let { canvas.drawPath(it, fillPaint) }
        strokePath?.let {
            if (fillStroke) canvas.drawPath(it, fillPaint)
            canvas.drawPath(it, strokePaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        val a = max(0, min(255, alpha))
        strokePaint.alpha = a
        fillPaint.alpha = a
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = size

    override fun getIntrinsicHeight(): Int = size

    companion object {
        private fun circle(cx: Float, cy: Float, r: Float): String =
            "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

        private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
            "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${2 * r - w}a$r $r 0 0 1 ${-r} ${-r}v${2 * r - h}a$r $r 0 0 1 $r ${-r}z"

        val CHEVRON_DOWN = "M6 9l6 6 6-6"
        val MORE = circle(12f, 5f, 1.8f) + circle(12f, 12f, 1.8f) + circle(12f, 19f, 1.8f)
        val HEART = "M12 20.5s-7.5-4.6-7.5-10.3A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.6c0 5.7-7.5 10.3-7.5 10.3z"
        val SHUFFLE = "M16 4h4v4M4 20L20 4M20 16v4h-4M14.5 14.5L20 20M4 4l5 5"
        val REPEAT = "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4"
        val PREV = roundRect(5f, 5.5f, 2.6f, 13f, 1.2f) + "M19 6.6v10.8a1 1 0 0 1-1.55.84l-8.1-5.4a1 1 0 0 1 0-1.68l8.1-5.4A1 1 0 0 1 19 6.6z"
        val NEXT = roundRect(16.4f, 5.5f, 2.6f, 13f, 1.2f) + "M5 6.6v10.8a1 1 0 0 0 1.55.84l8.1-5.4a1 1 0 0 0 0-1.68l-8.1-5.4A1 1 0 0 0 5 6.6z"
        val PLAY = "M8 5.8v12.4a1 1 0 0 0 1.54.84l9.6-6.2a1 1 0 0 0 0-1.68l-9.6-6.2A1 1 0 0 0 8 5.8z"
        val PAUSE = roundRect(6f, 5f, 4.6f, 14f, 1.6f) + roundRect(13.4f, 5f, 4.6f, 14f, 1.6f)
        val SPEED = "M12 14l3.5-3.5M4.6 18a8.5 8.5 0 1 1 14.8 0"
        val QUEUE = "M4 6h12M4 11h12M4 16h7M15 14v6l5-3z"
        val NOTE = "M9 17V5l11-2v12" + circle(6.5f, 17f, 2.5f) + circle(17.5f, 15f, 2.5f)
        val CLOSE = "M6 6l12 12M18 6L6 18"

        private val cache = HashMap<String, Path>()

        private fun path(d: String): Path = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }

        fun stroke(d: String, sizeDp: Float) = Md3PlayerIcon(d, null, sizeDp)

        fun fill(d: String, sizeDp: Float) = Md3PlayerIcon(null, d, sizeDp)
    }
}

class Md3MorphButton(context: Context, private var icon: Md3PlayerIcon?) : View(context) {
    fun interface PressListener {
        fun onPressChanged(button: Md3MorphButton, pressed: Boolean)
    }

    val widthSpring = Md3Spring(0f, 700f, 1f, 0.5f)
    private val radiusLeft = Md3Spring(0f, 520f, 0.55f, 0.3f)
    private val radiusRight = Md3Spring(0f, 520f, 0.55f, 0.3f)
    private val alt = Md3Spring(0f, 520f, 0.6f, 0.002f)
    private val active = Md3Spring(0f, 600f, 1f, 0.002f)
    private val badge = Md3Spring(0f, 600f, 1f, 0.002f)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AndroidUtilities.bold()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, context.resources.displayMetrics)
        fontFeatureSettings = "tnum"
    }
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AndroidUtilities.bold()
        textSize = dp(10f).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()
    private val radii = FloatArray(8)
    private var altIcon: Md3PlayerIcon? = null
    private var text: String? = null
    private var badgeText: String? = null
    private var bgColor = 0
    private var fgColor = 0
    private var activeBgColor = 0
    private var activeFgColor = 0
    private var badgeBg = 0
    private var badgeFg = 0
    private var lastFrame = 0L
    var pressListener: PressListener? = null
    var baseHeight = 0f
    var scale = 1f

    init {
        isClickable = true
        isFocusable = true
    }

    fun setIcon(value: Md3PlayerIcon?) {
        icon = value
        invalidate()
    }

    fun setAltIcon(value: Md3PlayerIcon?) {
        altIcon = value
        invalidate()
    }

    fun setText(value: String?) {
        if (!TextUtils.equals(text, value)) {
            text = value
            invalidate()
        }
    }

    fun setColors(bg: Int, fg: Int, activeBg: Int, activeFg: Int) {
        bgColor = bg
        fgColor = fg
        activeBgColor = activeBg
        activeFgColor = activeFg
        invalidate()
    }

    fun setBadgeColors(bg: Int, fg: Int) {
        badgeBg = bg
        badgeFg = fg
        invalidate()
    }

    fun setRadius(left: Float, right: Float, animated: Boolean) {
        if (animated) {
            radiusLeft.target = left
            radiusRight.target = right
        } else {
            radiusLeft.snap(left)
            radiusRight.snap(right)
        }
        invalidate()
    }

    fun setRadius(r: Float, animated: Boolean) = setRadius(r, r, animated)

    fun setAlt(value: Boolean, animated: Boolean) {
        val t = if (value) 1f else 0f
        if (animated) alt.target = t else alt.snap(t)
        invalidate()
    }

    fun setActive(value: Boolean, animated: Boolean) {
        val t = if (value) 1f else 0f
        if (animated) active.target = t else active.snap(t)
        invalidate()
    }

    fun setBadge(value: String?, animated: Boolean) {
        if (value != null) badgeText = value
        val t = if (value != null) 1f else 0f
        if (animated) badge.target = t else badge.snap(t)
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (changed) pressListener?.onPressChanged(this, pressed)
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        playSoundEffect(SoundEffectConstants.CLICK)
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1000f
        lastFrame = now
        var animating = radiusLeft.step(dt)
        animating = radiusRight.step(dt) || animating
        animating = alt.step(dt) || animating
        animating = active.step(dt) || animating
        animating = badge.step(dt) || animating

        val w = width.toFloat()
        val h = height.toFloat()
        val a = clamp01(active.value)
        val bg = ColorUtils.blendARGB(bgColor, activeBgColor, a)
        val fg = ColorUtils.blendARGB(fgColor, activeFgColor, a)
        val half = min(w, h) / 2f
        val rl = max(0f, min(half, radiusLeft.value * scale))
        val rr = max(0f, min(half, radiusRight.value * scale))
        if ((bg ushr 24) != 0) {
            bgPaint.color = bg
            rect.set(0f, 0f, w, h)
            radii[0] = rl; radii[1] = rl; radii[6] = rl; radii[7] = rl
            radii[2] = rr; radii[3] = rr; radii[4] = rr; radii[5] = rr
            path.rewind()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, bgPaint)
        }

        val iconSize = icon?.intrinsicWidth ?: 0
        val label = text
        val contentWidth = if (label != null) iconSize + dp(8f) + textPaint.measureText(label) else 0f
        val iconCx = if (label != null) (w - contentWidth) / 2f + iconSize / 2f else w / 2f
        val cy = h / 2f
        val second = altIcon
        if (second == null) {
            drawIcon(canvas, icon, iconCx, cy, fg, 1f, 1f)
        } else {
            val t = alt.value
            drawIcon(canvas, icon, iconCx, cy, fg, 1f - t, 1f - 0.4f * t)
            drawIcon(canvas, second, iconCx, cy, fg, t, 0.6f + 0.4f * t)
        }
        if (label != null) {
            textPaint.color = fg
            val tx = iconCx + iconSize / 2f + dp(8f)
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, tx, ty, textPaint)
        }
        val b = clamp01(badge.value)
        val bt = badgeText
        if (b > 0f && bt != null) {
            val r = dp(7.5f) * (0.6f + 0.4f * b)
            val bx = w - dp(3f) - dp(7.5f)
            val by = (dp(3f) + dp(7.5f)).toFloat()
            badgePaint.color = ColorUtils.setAlphaComponent(badgeBg, (255 * b).toInt())
            canvas.drawCircle(bx, by, r, badgePaint)
            badgeTextPaint.color = ColorUtils.setAlphaComponent(badgeFg, (255 * b).toInt())
            canvas.drawText(bt, bx, by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint)
        }

        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawIcon(canvas: Canvas, drawable: Md3PlayerIcon?, cx: Float, cy: Float, color: Int, alpha: Float, s: Float) {
        if (drawable == null) return
        val a = clamp01(alpha)
        if (a <= 0f) return
        val size = drawable.intrinsicWidth
        drawable.setColor(color)
        drawable.alpha = (255 * a).toInt()
        drawable.setBounds((cx - size / 2f).toInt(), (cy - size / 2f).toInt(), (cx + size / 2f).toInt(), (cy + size / 2f).toInt())
        canvas.save()
        canvas.scale(s * scale, s * scale, cx, cy)
        drawable.draw(canvas)
        canvas.restore()
    }
}

class Md3RingPlayButton(context: Context, radiusDp: Float, strokeDp: Float, iconDp: Float, amplitudeDp: Float, private val waves: Int) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(strokeDp)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(strokeDp)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val waveFrame = Runnable { invalidate() }
    private val playIcon = Md3PlayerIcon.fill(Md3PlayerIcon.PLAY, iconDp)
    private val pauseIcon = Md3PlayerIcon.fill(Md3PlayerIcon.PAUSE, iconDp)
    private val alt = Md3Spring(0f, 520f, 0.6f, 0.002f)
    private val radius = dp(radiusDp).toFloat()
    private val maxAmplitude = dpf(amplitudeDp)
    private var ringColor = 0
    private var iconColor = 0
    private var msg: MessageObject? = null
    private var playing = false
    private var amplitude = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var drawnProgress = -1f

    fun setColors(ring: Int, track: Int, icon: Int) {
        ringColor = ring
        trackPaint.color = track
        iconColor = icon
        invalidate()
    }

    fun setMessage(messageObject: MessageObject?) {
        msg = messageObject
        invalidate()
    }

    fun setPlaying(value: Boolean, animated: Boolean) {
        val t = if (value) 1f else 0f
        if (playing == value && alt.target == t) return
        playing = value
        if (animated) alt.target = t else alt.snap(t)
        lastFrame = 0L
        invalidate()
    }

    fun progressChanged() {
        if (amplitude > 0f) return
        val progress = clamp01(msg?.audioProgress ?: 0f)
        if (abs(progress - drawnProgress) * TAU * radius >= 1f) invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(waveFrame)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        val animating = alt.step(dt)
        val targetAmp = if (playing && !LiteMode.isPowerSaverApplied()) maxAmplitude else 0f
        amplitude += (targetAmp - amplitude) * (1f - 0.85.pow((dt / 0.04f).toDouble()).toFloat())
        if (abs(amplitude - targetAmp) < dpf(0.03f)) amplitude = targetAmp
        if (amplitude > 0f) phase = (phase + 6f * dt) % TAU
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, trackPaint)
        val progress = clamp01(msg?.audioProgress ?: 0f)
        drawnProgress = progress
        val sweep = 360f * progress
        if (sweep > 0.5f) {
            path.rewind()
            var deg = 0f
            var first = true
            while (true) {
                val last = deg >= sweep
                val d = if (last) sweep else deg
                val th = Math.toRadians((d - 90f).toDouble())
                val r = radius + amplitude * sin(waves * Math.toRadians(d.toDouble()) - phase).toFloat()
                val x = cx + r * cos(th).toFloat()
                val y = cy + r * sin(th).toFloat()
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                if (last) break
                deg += 3f
            }
            ringPaint.color = ringColor
            canvas.drawPath(path, ringPaint)
        }
        val t = clamp01(alt.value)
        drawIcon(canvas, playIcon, 1f - t, 1f - 0.4f * alt.value)
        drawIcon(canvas, pauseIcon, t, 0.6f + 0.4f * alt.value)
        removeCallbacks(waveFrame)
        if (animating || targetAmp != amplitude) {
            postInvalidateOnAnimation()
        } else if (amplitude > 0f) {
            postDelayed(waveFrame, 33)
        } else {
            lastFrame = 0L
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Md3PlayerIcon, alpha: Float, scale: Float) {
        if (alpha <= 0f) return
        val size = icon.intrinsicWidth
        val cx = width / 2
        val cy = height / 2
        icon.setColor(iconColor)
        icon.alpha = (255 * min(1f, alpha)).toInt()
        icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        canvas.save()
        canvas.scale(scale, scale, cx.toFloat(), cy.toFloat())
        icon.draw(canvas)
        canvas.restore()
    }
}

class Md3WavySeekBar(context: Context) : View(context) {
    interface Delegate {
        fun onSeekStart()
        fun onSeekMove(progress: Float)
        fun onSeekEnd(progress: Float, commit: Boolean)
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    var delegate: Delegate? = null
    private var progressValue = 0f
    private var dragProgress = 0f
    var isDragging = false
        private set
    private var playing = false
    private var amplitude = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private val maxAmplitude = dp(4f).toFloat()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setColors(active: Int, inactive: Int) {
        wavePaint.color = active
        fillPaint.color = active
        trackPaint.color = inactive
        invalidate()
    }

    fun setProgress(value: Float) {
        if (!isDragging && progressValue != value) {
            progressValue = value
            invalidate()
        }
    }

    fun getProgress(): Float = if (isDragging) dragProgress else progressValue

    fun setPlaying(value: Boolean) {
        if (playing != value) {
            playing = value
            lastFrame = 0L
            invalidate()
        }
    }

    private fun left(): Float = dp(3f).toFloat()

    private fun right(): Float = (width - dp(3f)).toFloat()

    private fun progressAt(x: Float): Float {
        val w = right() - left()
        if (w <= 0f) return 0f
        return clamp01((x - left()) / w)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                dragProgress = progressAt(event.x)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                delegate?.onSeekStart()
                delegate?.onSeekMove(dragProgress)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    dragProgress = progressAt(event.x)
                    delegate?.onSeekMove(dragProgress)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    progressValue = dragProgress
                    delegate?.onSeekEnd(progressValue, true)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    delegate?.onSeekEnd(progressValue, false)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        val targetAmp = if (playing && !LiteMode.isPowerSaverApplied()) maxAmplitude else 0f
        amplitude += (targetAmp - amplitude) * (1f - 0.85.pow((dt / 0.04f).toDouble()).toFloat())
        if (abs(amplitude - targetAmp) < dpf(0.05f)) amplitude = targetAmp
        if (amplitude > 0f) phase = (phase + 6f * dt) % TAU

        val cy = height / 2f
        val left = left()
        val right = right()
        val p = if (isDragging) dragProgress else progressValue
        val thumbW = (if (isDragging) dp(3f) else dp(5f)).toFloat()
        val x = left + p * (right - left)
        val gap = dp(6f).toFloat()
        val waveEnd = x - gap - thumbW / 2f
        val lambda = dp(34f).toFloat()
        val step = dp(2f).toFloat()
        if (waveEnd > left) {
            path.rewind()
            var px = left
            var first = true
            while (true) {
                val last = px >= waveEnd
                val cx = if (last) waveEnd else px
                val y = cy + amplitude * sin(TAU * cx / lambda - phase)
                if (first) {
                    path.moveTo(cx, y)
                    first = false
                } else {
                    path.lineTo(cx, y)
                }
                if (last) break
                px += step
            }
            canvas.drawPath(path, wavePaint)
        }
        val stopX = right - dp(1f)
        val restX = min(stopX, x + thumbW / 2f + gap)
        if (restX < stopX) canvas.drawLine(restX, cy, stopX, cy, trackPaint)
        canvas.drawCircle(stopX, cy, dp(2f).toFloat(), fillPaint)
        rect.set(x - thumbW / 2f, cy - dp(14f), x + thumbW / 2f, cy + dp(14f))
        canvas.drawRoundRect(rect, dpf(1.5f), dpf(1.5f), fillPaint)

        if (amplitude > 0f || targetAmp != amplitude) postInvalidateOnAnimation() else lastFrame = 0L
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, getProgress() * 100f)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            progressValue = clamp01(progressValue + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 0.05f else -0.05f)
            delegate?.onSeekEnd(progressValue, true)
            invalidate()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

class Md3ControlsRow(context: Context) : ViewGroup(context), Md3MorphButton.PressListener {
    val shuffle = Md3MorphButton(context, Md3PlayerIcon.stroke(Md3PlayerIcon.SHUFFLE, 22f))
    val prev = Md3MorphButton(context, Md3PlayerIcon.fill(Md3PlayerIcon.PREV, 28f))
    val play = Md3MorphButton(context, Md3PlayerIcon.fill(Md3PlayerIcon.PLAY, 36f))
    val next = Md3MorphButton(context, Md3PlayerIcon.fill(Md3PlayerIcon.NEXT, 28f))
    val repeat = Md3MorphButton(context, Md3PlayerIcon.stroke(Md3PlayerIcon.REPEAT, 22f))
    private val buttons = arrayOf(shuffle, prev, play, next, repeat)
    private var pressed: Md3MorphButton? = null
    private var playing = false
    private var shuffleOn = false
    private var repeatOn = false
    private var rowScale = 1f
    private var lastFrame = 0L
    private var ticking = false
    private val tick = Runnable { onTick() }

    init {
        play.setAltIcon(Md3PlayerIcon.fill(Md3PlayerIcon.PAUSE, 36f))
        val sizes = floatArrayOf(48f, 64f, 88f, 64f, 48f)
        for (i in buttons.indices) {
            val b = buttons[i]
            b.widthSpring.snap(dp(sizes[i]).toFloat())
            b.baseHeight = dp(sizes[i]).toFloat()
            b.pressListener = this
            addView(b)
        }
        shuffle.setRadius(dp(24f).toFloat(), false)
        repeat.setRadius(dp(24f).toFloat(), false)
        prev.setRadius(dp(32f).toFloat(), false)
        next.setRadius(dp(32f).toFloat(), false)
        play.setRadius(dp(44f).toFloat(), false)
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    fun setPlaying(value: Boolean, animated: Boolean) {
        playing = value
        play.setAlt(value, animated)
        updateTargets(animated)
    }

    fun setModes(shuffleActive: Boolean, repeatActive: Boolean, repeatOne: Boolean, animated: Boolean) {
        shuffleOn = shuffleActive
        repeatOn = repeatActive
        shuffle.setActive(shuffleActive, animated)
        repeat.setActive(repeatActive, animated)
        repeat.setBadge(if (repeatOne) "1" else null, animated)
        updateTargets(animated)
    }

    override fun onPressChanged(button: Md3MorphButton, pressed: Boolean) {
        if (pressed) {
            this.pressed = button
        } else if (this.pressed === button) {
            this.pressed = null
        }
        updateTargets(true)
    }

    private fun updateTargets(animated: Boolean) {
        val p = pressed
        val prevW = if (p === prev) 76f else if (p === play) 58f else 64f
        val nextW = if (p === next) 76f else if (p === play) 58f else 64f
        val playW = if (p === play) 100f else if (p === prev || p === next) 80f else 88f
        setButtonWidth(prev, dp(prevW).toFloat(), animated)
        setButtonWidth(next, dp(nextW).toFloat(), animated)
        setButtonWidth(play, dp(playW).toFloat(), animated)
        prev.setRadius(dp(if (p === prev) 18f else 32f).toFloat(), animated)
        next.setRadius(dp(if (p === next) 18f else 32f).toFloat(), animated)
        play.setRadius(dp(if (p === play) 20f else if (playing) 28f else 44f).toFloat(), animated)
        shuffle.setRadius(dp(if (shuffleOn) 14f else 24f).toFloat(), animated)
        repeat.setRadius(dp(if (repeatOn) 14f else 24f).toFloat(), animated)
        if (animated) {
            startTick()
        } else if (width > 0) {
            measureButtons()
            layoutButtons(width, height)
        }
    }

    private fun setButtonWidth(b: Md3MorphButton, w: Float, animated: Boolean) {
        if (animated) b.widthSpring.target = w else b.widthSpring.snap(w)
    }

    private fun startTick() {
        if (!ticking) {
            ticking = true
            lastFrame = 0L
            postOnAnimation(tick)
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1000f
        lastFrame = now
        var animating = false
        for (b in buttons) animating = b.widthSpring.step(dt) || animating
        if (width > 0) {
            measureButtons()
            layoutButtons(width, height)
        } else {
            requestLayout()
        }
        if (animating) postOnAnimation(tick) else ticking = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        ticking = false
        for (b in buttons) b.widthSpring.snap(b.widthSpring.target)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        rowScale = min(1f, (w - dp(16f)) / dp(312f).toFloat())
        measureButtons()
        setMeasuredDimension(w, (dp(88f) * rowScale).roundToInt())
    }

    private fun measureButtons() {
        for (b in buttons) {
            b.scale = rowScale
            val w = max(1, (b.widthSpring.value * rowScale).roundToInt())
            val h = max(1, (b.baseHeight * rowScale).roundToInt())
            b.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutButtons(r - l, b - t)
    }

    private fun layoutButtons(w: Int, h: Int) {
        var total = 0f
        for (b in buttons) total += b.measuredWidth
        val gap = (w - total) / (buttons.size - 1f)
        var x = 0f
        for (b in buttons) {
            val bw = b.measuredWidth
            val bh = b.measuredHeight
            val left = x.roundToInt()
            val top = (h - bh) / 2
            b.layout(left, top, left + bw, top + bh)
            x += bw + gap
        }
    }
}

class Md3CoverImage(context: Context, noteSizeDp: Float) : BackupImageView(context) {
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val note = Md3PlayerIcon.stroke(Md3PlayerIcon.NOTE, noteSizeDp)
    private var radius = 0
    private var currentKey: String? = null
    private var message: MessageObject? = null
    private var fallbackSeed = 0
    private var listener: ((MessageObject, Int) -> Unit)? = null

    init {
        imageReceiver.setDelegate { receiver, set, _, _ ->
            if (set) onBitmap(receiver.bitmap)
        }
    }

    fun setListener(fallbackSeed: Int, listener: (MessageObject, Int) -> Unit) {
        this.fallbackSeed = fallbackSeed
        this.listener = listener
    }

    fun setRadius(r: Int) {
        radius = r
        setRoundRadius(r)
        invalidate()
    }

    fun setColors(background: Int, foreground: Int) {
        placeholderPaint.color = background
        note.setColor(foreground)
        invalidate()
    }

    fun bind(messageObject: MessageObject?) {
        if (messageObject == null) {
            currentKey = null
            message = null
            setImageDrawable(null)
            return
        }
        val file = Md3PlayerArt.fileCover(messageObject)
        val key = Md3PlayerArt.key(messageObject) + if (file != null) ":f" else ""
        if (key == currentKey) return
        currentKey = key
        message = messageObject
        if (file != null) {
            setImageBitmap(file)
            onBitmap(file)
            return
        }
        val full = Md3PlayerArt.fullLocation(messageObject)
        val thumb = Md3PlayerArt.thumbLocation(messageObject)
        if (full != null) {
            setImage(full, null, thumb, null, null as String?, 0L, 1, messageObject)
        } else if (thumb != null) {
            setImage(null, null, thumb, null, null as String?, 0L, 1, messageObject)
        } else {
            setImageDrawable(null)
        }
    }

    private fun onBitmap(bitmap: Bitmap?) {
        val target = message
        val l = listener
        if (bitmap == null || target == null || l == null) return
        Md3PlayerArt.requestSeed(target, bitmap, fallbackSeed) { seed ->
            if (target === message) listener?.invoke(target, seed)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!imageReceiver.hasBitmapImage()) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), placeholderPaint)
            note.setBounds(0, 0, width, height)
            note.draw(canvas)
        }
        super.onDraw(canvas)
    }
}
