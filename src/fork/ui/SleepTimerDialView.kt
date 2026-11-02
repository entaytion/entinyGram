package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import desu.inugram.helpers.media.SleepTimerHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class SleepTimerDialView(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider?,
) : View(context) {
    var onMinutesChanged: ((Int) -> Unit)? = null

    private var minutes = 0
    private var totalDeg = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var lastTouchAngle = 0f
    private var tracking = false

    init {
        isClickable = true
        setMinutes(minutes)
    }

    fun getMinutes(): Int = minutes

    fun setMinutes(value: Int) {
        val next = (value.coerceIn(0, SleepTimerHelper.MAX_MINUTES) / SleepTimerHelper.STEP_MINUTES) * SleepTimerHelper.STEP_MINUTES
        totalDeg = next * DEG_PER_MIN
        if (next != minutes) {
            minutes = next
            contentDescription = describe(next)
            onMinutesChanged?.invoke(next)
        }
        invalidate()
    }

    private fun describe(value: Int): String =
        if (value == 0) LocaleController.getString(R.string.InuSleepTimerOff)
        else SleepTimerHelper.formatMinutes(value)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val dx = e.x - cx
        val dy = e.y - cy
        val dist = kotlin.math.hypot(dx, dy)
        val deadZone = AndroidUtilities.dp(28f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (dist < deadZone) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchAngle = angleOf(dx, dy)
                tracking = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (dist < deadZone) {
                    lastTouchAngle = angleOf(dx, dy)
                    return true
                }
                val a = angleOf(dx, dy)
                var d = a - lastTouchAngle
                while (d > 180) d -= 360
                while (d < -180) d += 360
                lastTouchAngle = a
                totalDeg = (totalDeg + d).coerceIn(
                    0f,
                    SleepTimerHelper.MAX_MINUTES * DEG_PER_MIN,
                )
                val next = (totalDeg / DEG_PER_MIN / SleepTimerHelper.STEP_MINUTES).roundToInt() * SleepTimerHelper.STEP_MINUTES
                val clamped = next.coerceIn(0, SleepTimerHelper.MAX_MINUTES)
                if (clamped != minutes) {
                    minutes = clamped
                    contentDescription = describe(clamped)
                    onMinutesChanged?.invoke(clamped)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun angleOf(dx: Float, dy: Float): Float =
        Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()

    override fun onDraw(canvas: Canvas) {
        val active = Theme.getColor(Theme.key_dialogTextBlue, resourcesProvider)
        val stroke = AndroidUtilities.dp(10f).toFloat()
        val gap = AndroidUtilities.dp(26f).toFloat()
        val outer = min(width, height) / 2f - AndroidUtilities.dp(18f)
        if (outer <= 0) return
        val cx = width / 2f
        val cy = height / 2f

        trackPaint.color = active
        trackPaint.alpha = 38
        trackPaint.strokeWidth = stroke
        activePaint.color = active
        activePaint.strokeWidth = stroke
        knobPaint.color = active
        tickPaint.color = active
        tickPaint.alpha = 90
        tickPaint.strokeWidth = AndroidUtilities.dp(2f).toFloat()

        for (t in 0 until 12) {
            val a = Math.toRadians((t * 30).toDouble())
            val r1 = outer - stroke
            val r0 = r1 - AndroidUtilities.dp(if (t % 3 == 0) 9f else 5f)
            canvas.drawLine(
                (cx + sin(a) * r0).toFloat(), (cy - cos(a) * r0).toFloat(),
                (cx + sin(a) * r1).toFloat(), (cy - cos(a) * r1).toFloat(),
                tickPaint,
            )
        }
        val visibleRings = if (minutes == 0) 1 else (minutes - 1) / 60 + 1
        for (i in 0 until visibleRings) {
            val r = outer - i * gap
            if (r <= 0) continue
            canvas.drawCircle(cx, cy, r, trackPaint)
            val inRing = (minutes - i * 60).coerceIn(0, 60)
            if (inRing > 0) {
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, inRing / 60f * 360f, false, activePaint)
            }
        }
        if (minutes > 0) {
            val headRing = ((minutes - 1) / 60).coerceIn(0, 2)
            val headAngle = Math.toRadians((minutes % 60) / 60.0 * 360.0)
            val hr = outer - headRing * gap
            canvas.drawCircle(
                (cx + sin(headAngle) * hr).toFloat(),
                (cy - cos(headAngle) * hr).toFloat(),
                AndroidUtilities.dp(8f).toFloat(),
                knobPaint,
            )
        }
    }

    companion object {
        private const val DEG_PER_MIN = 6f
    }
}
