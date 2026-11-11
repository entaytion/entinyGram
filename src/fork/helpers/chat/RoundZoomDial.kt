package desu.inugram.helpers.chat

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import desu.inugram.helpers.vibration.HapticFeedbackConstants
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class RoundZoomDial(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider?,
) : FrameLayout(context) {

    var sink: ((Float) -> Unit)? = null

    var isAttachedToLens = false
        private set

    private var low = 1f
    private var high = 1f
    private var unit = 1f
    private var goal = 1f
    private var current = 1f
    private var lastSent = 0f
    private var easing = EASE_QUICK
    private var stepping = false

    private var presets = floatArrayOf()
    private var marks = floatArrayOf()
    private var majors = BooleanArray(0)

    private var spread = 0f
    private var spreadAnimator: ValueAnimator? = null
    private var shownFraction = 0f
    private var shownAnimator: ValueAnimator? = null

    private var contentColor = Color.WHITE
    private var glassApplied = false

    private val track = Track(context)
    private val minus = stepButton(R.drawable.zoom_minus, -1)
    private val plus = stepButton(R.drawable.zoom_plus, 1)

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA0000000.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf2(12f)
        textAlign = Paint.Align.CENTER
        typeface = AndroidUtilities.bold()
    }
    private val bubbleRect = RectF()

    private val collapse = Runnable { setSpread(false) }

    private val stepper = object : Runnable {
        override fun run() {
            val from = ln(current)
            val to = ln(goal)
            val next = from + (to - from) * easing
            val settled = abs(to - next) < 0.004f
            current = if (settled) goal else exp(next)
            if (settled || abs(current - lastSent) > lastSent * 0.003f) {
                lastSent = current
                sink?.invoke(current)
            }
            track.invalidate()
            invalidate()
            if (settled) stepping = false else postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(track, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, TRACK_H.toInt(), Gravity.BOTTOM))
        addView(minus, LayoutHelper.createFrame(TRACK_H.toInt(), TRACK_H.toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        addView(plus, LayoutHelper.createFrame(TRACK_H.toInt(), TRACK_H.toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        applyFallbackBackgrounds()
        alpha = 0f
        visibility = GONE
    }

    fun glass(factory: BlurredBackgroundDrawableViewFactory, provider: BlurredBackgroundColorProvider) {
        for (v in arrayOf<View>(track, minus, plus)) {
            val drawable = factory.create(v, provider)
            drawable.setRadius(dpf2(TRACK_H / 2f))
            v.background = drawable
        }
        glassApplied = true
        refreshColors()
    }

    fun attach(minRatio: Float, maxRatio: Float, frontFacing: Boolean, ratio: Float) {
        if (minRatio <= 0f || maxRatio <= minRatio * 1.01f) {
            release()
            return
        }
        low = minRatio
        high = maxRatio
        unit = if (frontFacing) minRatio else 1f
        presets = buildPresets(frontFacing)
        buildMarks()
        goal = (if (ratio.isNaN()) 1f else ratio).coerceIn(low, high)
        current = goal
        lastSent = goal
        isAttachedToLens = true
        layoutTrack()
        track.invalidate()
    }

    fun release() {
        isAttachedToLens = false
        cancelPending()
        spreadAnimator?.cancel()
        spread = 0f
        layoutTrack()
    }

    fun show(visible: Boolean) {
        val target = if (visible && isAttachedToLens) 1f else 0f
        if (target == shownFraction && shownAnimator == null) {
            visibility = if (target > 0f) VISIBLE else GONE
            return
        }
        shownAnimator?.cancel()
        if (target > 0f) visibility = VISIBLE
        shownAnimator = ValueAnimator.ofFloat(shownFraction, target).apply {
            duration = 180
            addUpdateListener {
                shownFraction = it.animatedValue as Float
                alpha = shownFraction
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    shownAnimator = null
                    if (shownFraction <= 0f) {
                        visibility = GONE
                        setSpread(false, animate = false)
                    }
                }
            })
            start()
        }
    }

    fun scaleBy(factor: Float) {
        if (!isAttachedToLens || factor <= 0f || factor.isNaN()) return
        moveTo(goal * factor, EASE_QUICK)
    }

    private fun moveTo(ratio: Float, ease: Float) {
        goal = ratio.coerceIn(low, high)
        easing = ease
        if (!stepping) {
            stepping = true
            postOnAnimation(stepper)
        }
    }

    private fun buildPresets(frontFacing: Boolean): FloatArray {
        val out = ArrayList<Float>(4)
        if (frontFacing) {
            out.add(low)
            if (low * 2f <= high * 1.001f) out.add(low * 2f)
        } else {
            if (low < 0.95f) out.add(low)
            if (1f in low..high) out.add(1f)
            if (2f <= high * 1.001f) out.add(2f)
            val far = if (high >= 5f) 5f else if (high >= 3f) 3f else 0f
            if (far > 0f) out.add(far)
            if (out.isEmpty()) out.add(low)
        }
        return out.toFloatArray()
    }

    private fun buildMarks() {
        val values = ArrayList<Float>()
        var d = kotlin.math.ceil(low / unit * 10f - 0.001f) / 10f
        val top = high / unit
        while (d <= top + 0.0001f && values.size < 160) {
            values.add(d * unit)
            d += when {
                d < 2f -> 0.1f
                d < 5f -> 0.25f
                d < 10f -> 0.5f
                else -> 1f
            }
            d = (d * 100f).roundToInt() / 100f
        }
        for (p in presets) {
            if (values.none { abs(it - p) < p * 0.01f }) values.add(p)
        }
        values.sort()
        marks = values.toFloatArray()
        majors = BooleanArray(marks.size) { i -> presets.any { abs(it - marks[i]) < marks[i] * 0.01f } }
    }

    private fun positionOf(ratio: Float): Float =
        if (high <= low) 0f else (ln(ratio / low) / ln(high / low)).coerceIn(0f, 1f)

    private fun ratioAt(position: Float): Float = low * exp(ln(high / low) * position.coerceIn(0f, 1f))

    private fun label(ratio: Float): String {
        val shown = ratio / unit
        val whole = shown.roundToInt()
        if (abs(shown - whole) < 0.05f && whole >= 1) return "$whole×"
        val text = String.format(Locale.US, "%.1f", shown)
        return (if (shown < 1f) text.removePrefix("0") else text) + "×"
    }

    private fun nearestPreset(ratio: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in presets.indices) {
            val distance = abs(ln(ratio / presets[i]))
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun compactWidth(): Int = presets.size.coerceAtLeast(1) * dp(SLOT) + dp(6f)

    private fun rulerWidth(): Int {
        val room = width - 2 * (dp(TRACK_H) + dp(GAP)) - dp(8f)
        return min(dp(268f), room).coerceAtLeast(compactWidth())
    }

    private fun trackWidth(): Int {
        val a = compactWidth()
        val b = rulerWidth()
        return (a + (b - a) * spread).roundToInt()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutTrack()
    }

    private fun layoutTrack() {
        if (width == 0) return
        val w = trackWidth()
        val cx = width / 2
        val t = height - dp(TRACK_H)
        track.layout(cx - w / 2, t, cx - w / 2 + w, height)
        val offset = w / 2f + dp(GAP) + dp(TRACK_H) / 2f
        minus.translationX = -offset
        plus.translationX = offset
        invalidate()
    }

    private fun setSpread(open: Boolean, animate: Boolean = true) {
        removeCallbacks(collapse)
        val target = if (open) 1f else 0f
        spreadAnimator?.cancel()
        spreadAnimator = null
        if (!animate) {
            spread = target
            layoutTrack()
            track.invalidate()
            return
        }
        if (spread == target) return
        spreadAnimator = ValueAnimator.ofFloat(spread, target).apply {
            duration = 260
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                spread = it.animatedValue as Float
                layoutTrack()
                track.invalidate()
            }
            start()
        }
    }

    private fun scheduleCollapse() {
        removeCallbacks(collapse)
        if (spread > 0f || spreadAnimator != null) postDelayed(collapse, COLLAPSE_DELAY)
    }

    private fun cancelPending() {
        removeCallbacks(collapse)
        removeCallbacks(stepper)
        stepping = false
        minus.stopRepeat()
        plus.stopRepeat()
    }

    private fun refreshColors() {
        contentColor = if (glassApplied) Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider) else Color.WHITE
        val filter = PorterDuffColorFilter(contentColor, PorterDuff.Mode.SRC_IN)
        minus.colorFilter = filter
        plus.colorFilter = filter
        track.invalidate()
    }

    private fun applyFallbackBackgrounds() {
        for (v in arrayOf<View>(track, minus, plus)) {
            v.background = Theme.createRoundRectDrawable(dp(TRACK_H / 2f), 0x66000000)
        }
        refreshColors()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isAttachedToLens || spread <= 0.01f) return
        val x = track.left + track.scaleStart() + positionOf(current) * track.scaleSpan()
        val text = label(current)
        val half = bubbleText.measureText(text) / 2f + dp(6f)
        if (width < half * 2f) return
        val cx = x.coerceIn(half, width - half)
        val bottom = track.top - dpf2(2f)
        bubbleRect.set(cx - half, bottom - dp(15f), cx + half, bottom)
        val a = (255 * spread).toInt()
        bubblePaint.alpha = (0xA0 * spread).toInt()
        bubbleText.alpha = a
        canvas.drawRoundRect(bubbleRect, dpf2(7.5f), dpf2(7.5f), bubblePaint)
        canvas.drawText(text, cx, bubbleRect.centerY() + bubbleText.textSize * 0.36f, bubbleText)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPending()
        spreadAnimator?.cancel()
        shownAnimator?.cancel()
        spreadAnimator = null
        shownAnimator = null
    }

    private fun haptic(strong: Boolean) {
        performHapticFeedback(if (strong) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun stepButton(icon: Int, direction: Int): StepButton = StepButton(context, direction).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(icon)
    }

    private inner class StepButton(context: Context, private val direction: Int) : ImageView(context) {
        private var repeats = 0
        private val repeat = object : Runnable {
            override fun run() {
                repeats++
                if (nudge()) postDelayed(this, REPEAT_EVERY)
            }
        }

        private fun nudge(): Boolean {
            if (!isAttachedToLens) return false
            val growth = min(0.22f, 0.05f + repeats * 0.014f)
            val before = goal
            val factor = if (direction > 0) 1f + growth else 1f / (1f + growth)
            moveTo(goal * factor, EASE_QUICK)
            if (goal == before) return false
            if (goal == low || goal == high) haptic(true)
            return true
        }

        fun stopRepeat() {
            removeCallbacks(repeat)
            isPressed = false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isAttachedToLens) return false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(collapse)
                    isPressed = true
                    repeats = 0
                    if (nudge()) haptic(false)
                    postDelayed(repeat, REPEAT_START)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeat()
                    scheduleCollapse()
                }
            }
            return true
        }
    }

    private inner class Track(context: Context) : View(context) {
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = AndroidUtilities.bold()
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dpf2(2f)
        }

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var velocity: VelocityTracker? = null
        private var downX = 0f
        private var lastX = 0f
        private var scrubbing = false
        private var cursor = 0f
        private var pin = Float.NaN
        private var pinDebt = 0f
        private var lastHaptic = 0L
        private val pressHold = Runnable { beginScrub() }

        fun scaleStart(): Float = dpf2(16f)
        fun scaleSpan(): Float = (width - 2 * scaleStart()).coerceAtLeast(1f)

        override fun onDraw(canvas: Canvas) {
            if (presets.isEmpty()) return
            val compact = 1f - spread
            if (compact > 0.01f) drawPresets(canvas, compact)
            if (spread > 0.01f) drawRuler(canvas, spread)
        }

        private fun drawPresets(canvas: Canvas, fraction: Float) {
            val slot = dp(SLOT).toFloat()
            val startX = (width - slot * presets.size) / 2f
            val cy = height / 2f
            val active = nearestPreset(current)
            for (i in presets.indices) {
                val cx = startX + slot * (i + 0.5f)
                val on = i == active
                val exact = abs(current - presets[i]) < presets[i] * 0.02f
                val text = if (on && !exact) label(current) else label(presets[i])
                if (on) {
                    chipPaint.color = ColorUtils.setAlphaComponent(contentColor, (0x30 * fraction).toInt())
                    canvas.drawCircle(cx, cy, dpf2(14f), chipPaint)
                }
                labelPaint.textSize = dpf2(if (on) 12.5f else 11.5f)
                labelPaint.color = ColorUtils.setAlphaComponent(contentColor, ((if (on) 255 else 190) * fraction).toInt())
                canvas.drawText(text, cx, cy + labelPaint.textSize * 0.36f, labelPaint)
            }
        }

        private fun drawRuler(canvas: Canvas, fraction: Float) {
            val start = scaleStart()
            val span = scaleSpan()
            val base = height - dpf2(8f)
            labelPaint.textSize = dpf2(9.5f)
            labelPaint.color = ColorUtils.setAlphaComponent(contentColor, (200 * fraction).toInt())
            for (i in marks.indices) {
                val x = start + positionOf(marks[i]) * span
                val big = majors[i]
                tickPaint.strokeWidth = dpf2(if (big) 1.6f else 1f)
                tickPaint.color = ColorUtils.setAlphaComponent(contentColor, ((if (big) 230 else 110) * fraction).toInt())
                canvas.drawLine(x, base - dpf2(if (big) 10f else 5f), x, base, tickPaint)
                if (big) canvas.drawText(label(marks[i]), x, dpf2(12f), labelPaint)
            }
            val needleX = start + positionOf(current) * span
            needlePaint.color = ColorUtils.setAlphaComponent(0xFFFFC94A.toInt(), (255 * fraction).toInt())
            canvas.drawLine(needleX, base - dpf2(14f), needleX, base + dpf2(2f), needlePaint)
        }

        private fun beginScrub() {
            if (scrubbing || !isAttachedToLens) return
            scrubbing = true
            removeCallbacks(pressHold)
            cursor = positionOf(goal)
            pin = Float.NaN
            pinDebt = 0f
            setSpread(true)
            haptic(false)
        }

        private fun scrub(dx: Float, speed: Float) {
            val span = scaleSpan()
            val before = cursor
            if (!pin.isNaN()) {
                pinDebt += dx
                if (abs(pinDebt) < dp(PIN_RELEASE)) return
                cursor = pin + pinDebt / span
                pin = Float.NaN
                pinDebt = 0f
            } else {
                val next = (cursor + dx / span).coerceIn(0f, 1f)
                val hit = if (speed < dp(SLOW_SPEED)) presets.firstOrNull {
                    val p = positionOf(it)
                    p != cursor && (cursor - p) * (next - p) <= 0f
                } else null
                if (hit != null) {
                    cursor = positionOf(hit)
                    pin = cursor
                    pinDebt = (next - cursor) * span
                } else {
                    cursor = next
                }
            }
            cursor = cursor.coerceIn(0f, 1f)
            tickFeedback(before, cursor)
            moveTo(ratioAt(cursor), EASE_QUICK)
        }

        private fun tickFeedback(from: Float, to: Float) {
            if (from == to) return
            val lo = min(from, to)
            val hi = kotlin.math.max(from, to)
            var crossed = false
            var strong = false
            for (i in marks.indices) {
                val p = positionOf(marks[i])
                if (p > lo && p <= hi) {
                    crossed = true
                    if (majors[i]) strong = true
                }
            }
            val now = System.currentTimeMillis()
            if (crossed && (strong || now - lastHaptic > 30)) {
                lastHaptic = now
                haptic(strong)
            }
        }

        private fun tapAt(x: Float) {
            if (spread > 0.5f || presets.isEmpty()) return
            val slot = dp(SLOT).toFloat()
            val startX = (width - slot * presets.size) / 2f
            val index = ((x - startX) / slot).toInt()
            if (index !in presets.indices) return
            haptic(false)
            moveTo(presets[index], EASE_GENTLE)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isAttachedToLens) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(collapse)
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    downX = event.x
                    lastX = event.x
                    scrubbing = spread > 0.5f
                    if (scrubbing) {
                        cursor = positionOf(goal)
                        pin = Float.NaN
                    } else {
                        postDelayed(pressHold, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    if (!scrubbing && abs(event.x - downX) > slop) {
                        beginScrub()
                        lastX = event.x
                    }
                    if (scrubbing) {
                        val tracker = velocity
                        tracker?.computeCurrentVelocity(1000)
                        scrub(event.x - lastX, abs(tracker?.xVelocity ?: 0f))
                        lastX = event.x
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(pressHold)
                    if (!scrubbing && event.actionMasked == MotionEvent.ACTION_UP) tapAt(event.x)
                    scrubbing = false
                    velocity?.recycle()
                    velocity = null
                    scheduleCollapse()
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(pressHold)
            velocity?.recycle()
            velocity = null
            scrubbing = false
        }

    }

    private companion object {
        const val TRACK_H = 36f
        const val SLOT = 38f
        const val GAP = 6f
        const val PIN_RELEASE = 18f
        const val SLOW_SPEED = 520f
        const val COLLAPSE_DELAY = 1600L
        const val REPEAT_START = 320L
        const val REPEAT_EVERY = 70L
        const val EASE_QUICK = 0.42f
        const val EASE_GENTLE = 0.17f
    }
}
