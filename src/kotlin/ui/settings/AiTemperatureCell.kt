package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBar
import kotlin.math.roundToInt

/**
 * Temperature slider cell featuring discrete markers 0.0, 1.0, 2.0 and a smooth seekbar.
 * Matches the reference design in AI Chat generation settings.
 */
@SuppressLint("ViewConstructor")
class AiTemperatureCell(
    context: Context,
    initialValue: Float = 1.0f,
    private val onChanged: (Float) -> Unit,
) : LinearLayout(context) {

    private val min = 0.0f
    private val max = 2.0f
    private val step = 0.1f

    var value: Float = snap(initialValue)
        private set

    private val label0 = TextView(context).apply {
        text = "0.0"
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        gravity = Gravity.START
    }

    private val label1 = TextView(context).apply {
        text = "1.0"
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        gravity = Gravity.CENTER
    }

    private val label2 = TextView(context).apply {
        text = "2.0"
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        gravity = Gravity.END
    }

    private val seekBarView = SeekBarWrapper(
        context,
        snapProgress = { p -> snapProgress(p, step) },
    ).apply {
        onProgressChanged = { progress ->
            val newValue = snap(min + progress * (max - min))
            if (newValue != value) {
                value = newValue
                onChanged(newValue)
            }
        }
        setProgress((value - min) / (max - min))
    }

    init {
        orientation = VERTICAL
        setPadding(
            AndroidUtilities.dp(20f),
            AndroidUtilities.dp(12f),
            AndroidUtilities.dp(20f),
            AndroidUtilities.dp(12f)
        )

        val labelsRow = FrameLayout(context).apply {
            addView(label0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(label1, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
            addView(label2, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }

        addView(labelsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6f, 0f, 6f, 4f))
        addView(seekBarView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38))
    }

    private fun snap(v: Float): Float {
        val count = ((v - min) / step).roundToInt()
        return (min + count * step).coerceIn(min, max)
    }

    private fun snapProgress(p: Float, stepVal: Float): Float {
        val range = max - min
        if (range <= 0f) return p
        val snapped = ((p * range / stepVal).roundToInt() * stepVal / range)
        return snapped.coerceIn(0f, 1f)
    }

    fun updateColors() {
        val color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2)
        label0.setTextColor(color)
        label1.setTextColor(color)
        label2.setTextColor(color)
        seekBarView.updateColors()
    }

    private class SeekBarWrapper(
        context: Context,
        private val snapProgress: (Float) -> Float,
    ) : View(context) {
        var onProgressChanged: ((Float) -> Unit)? = null
        private val seekBar = SeekBar(this)

        init {
            updateColors()
            seekBar.setDelegate(object : SeekBar.SeekBarDelegate {
                override fun onSeekBarDrag(progress: Float) = onDrag(progress)
                override fun onSeekBarContinuousDrag(progress: Float) = onDrag(progress)
            })
        }

        fun updateColors() {
            seekBar.setColors(
                Theme.getColor(Theme.key_player_progressBackground),
                Theme.getColor(Theme.key_player_progressCachedBackground),
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progressBackground),
            )
            invalidate()
        }

        private fun onDrag(progress: Float) {
            onProgressChanged?.invoke(progress)
            invalidate()
        }

        fun setProgress(progress: Float) {
            seekBar.setProgress(progress)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            seekBar.setSize(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            seekBar.draw(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    val thumbWidth = AndroidUtilities.dp(24f)
                    val denom = (width - thumbWidth).coerceAtLeast(1).toFloat()
                    val raw = ((event.x - thumbWidth / 2f) / denom).coerceIn(0f, 1f)
                    val snapped = snapProgress(raw)
                    seekBar.setProgress(snapped)
                    onProgressChanged?.invoke(snapped)
                    invalidate()
                    return true
                }
                else -> return false
            }
        }
    }
}
