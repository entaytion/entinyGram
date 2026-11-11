package desu.inugram.helpers.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import desu.inugram.helpers.vibration.HapticFeedbackConstants
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class RoundExposureRail(context: Context, private val onRight: Boolean) : View(context) {

    var onLevel: ((Float) -> Unit)? = null

    var level = 0.5f
        private set

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dpf2(1.5f)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpf2(10f)
        textAlign = Paint.Align.CENTER
        typeface = AndroidUtilities.bold()
        setShadowLayer(dpf2(2f), 0f, 0f, 0x66000000)
    }
    private val caption = LocaleController.getString(R.string.InuRoundExposure)
    private val shell = RectF()
    private val filled = RectF()

    private var lastY = 0f
    private var dragging = false
    private val idleHide = Runnable { fade(false) }

    init {
        alpha = 0f
        visibility = GONE
    }

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun reveal() {
        removeCallbacks(idleHide)
        fade(true)
        if (!dragging) postDelayed(idleHide, IDLE_MS)
    }

    fun conceal() {
        removeCallbacks(idleHide)
        if (visibility == GONE) return
        fade(false)
    }

    private fun fade(visible: Boolean) {
        animate().cancel()
        if (visible) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(160).start()
        } else {
            animate().alpha(0f).setDuration(220).withEndAction { visibility = GONE }.start()
        }
    }

    private fun tint(value: Float): Int {
        val t = abs(value - 0.5f) * 2f
        val eased = t * (2f - t)
        val grey = if (value < 0.5f) (MID - (MID - DARK) * eased) else (MID + (255 - MID) * eased)
        val g = grey.roundToInt().coerceIn(0, 255)
        return Color.rgb(g, g, g)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dpf2(3f)
        val tuck = dpf2(TUCK)
        val left = if (onRight) inset else inset - tuck
        val right = if (onRight) width - inset + tuck else width - inset
        val radius = (right - left) / 2f
        shell.set(left, inset, right, height - inset)
        canvas.drawRoundRect(shell, radius, radius, shellPaint)

        val top = shell.top + (1f - level) * shell.height()
        filled.set(shell.left, top, shell.right, shell.bottom)
        fillPaint.color = tint(level)
        fillPaint.alpha = 0xE6
        canvas.save()
        canvas.clipRect(filled)
        canvas.drawRoundRect(shell, radius, radius, fillPaint)
        canvas.restore()

        val mid = shell.centerY()
        val visibleCx = if (onRight) (shell.left + width) / 2f else shell.right / 2f
        canvas.drawLine(visibleCx - dpf2(5f), mid, visibleCx + dpf2(5f), mid, notchPaint)

        val along = shell.height() / 4f
        canvas.save()
        canvas.rotate(if (onRight) 90f else -90f, visibleCx, mid)
        captionPaint.color = if (level > 0.78f) 0xFF202020.toInt() else Color.WHITE
        val textX = if (onRight) visibleCx - along else visibleCx + along
        canvas.drawText(caption, textX, mid + captionPaint.textSize * 0.35f, captionPaint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                lastY = event.y
                removeCallbacks(idleHide)
                fade(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val span = (height - dpf2(6f)).coerceAtLeast(1f)
                val before = level
                val next = (level - (event.y - lastY) / span).coerceIn(0f, 1f)
                lastY = event.y
                if (next != before) {
                    if ((before - 0.5f) * (next - 0.5f) <= 0f && before != 0.5f) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    } else if ((next == 0f || next == 1f)) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    setLevel(next)
                    onLevel?.invoke(next)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                reveal()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(idleHide)
        animate().cancel()
        dragging = false
    }

    private companion object {
        const val IDLE_MS = 3500L
        const val TUCK = 12f
        const val DARK = 58f
        const val MID = 172f
    }
}
