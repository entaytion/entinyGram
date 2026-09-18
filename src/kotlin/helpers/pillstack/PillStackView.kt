package desu.inugram.helpers.pillstack

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import desu.inugram.helpers.pillstack.pills.BasePill
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.CubicBezierInterpolator

// entiny: one pill-stack "slot" -- shows one pill, vertical swipe cycles its neighbors. PillStackController runs several side by side for the visible-count feature.
class PillStackView(context: Context) : FrameLayout(context) {

    private val pills = ArrayList<BasePill>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var currentIndex = 0
    private var currentAnimator: ValueAnimator? = null
    private var currentSwipeProgress = 0f
    private var isSwiping = false
    private var isSwipingUp = false
    private var maybeClick = false
    private var longClickPerformed = false
    private var startX = 0f
    private var startY = 0f
    private var stackOnScreen = true

    private val longPressRunnable = Runnable {
        if (!maybeClick || isSwiping || pills.isEmpty()) return@Runnable
        longClickPerformed = pills[currentIndex].onPillLongClicked()
        if (longClickPerformed) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        clipChildren = false
    }

    var onCurrentPillChanged: (() -> Unit)? = null

    fun getPillsCount(): Int = pills.size

    fun getCurrentPillId(): Int? = pills.getOrNull(currentIndex)?.getPillId()

    fun selectPillId(pillId: Int) {
        val index = pills.indexOfFirst { it.getPillId() == pillId }
        if (index >= 0) setCurrentIndex(index)
    }

    fun addPill(pill: BasePill) {
        pills.add(pill)
        addView(pill)
        if (pills.size - 1 != currentIndex) {
            pill.alpha = 0f
            pill.scaleX = 0.8f
            pill.scaleY = 0.8f
            pill.visibility = GONE
        } else {
            pill.visibility = VISIBLE
            pill.onPillSelected()
        }
        pill.onStackVisibilityChanged(stackOnScreen)
    }

    fun clearPills() {
        if (pills.isNotEmpty() && currentIndex < pills.size) {
            pills[currentIndex].onPillUnselected()
        }
        pills.clear()
        removeAllViews()
        currentIndex = 0
    }

    fun setCurrentIndex(index: Int) {
        if (index < 0 || index >= pills.size || index == currentIndex) return
        val previous = pills[currentIndex]
        previous.visibility = GONE
        previous.onPillUnselected()
        currentIndex = index
        val next = pills[index]
        next.visibility = VISIBLE
        next.alpha = 1f
        next.scaleX = 1f
        next.scaleY = 1f
        next.translationY = 0f
        next.onPillSelected()
        requestLayout()
    }

    fun updateColors() {
        for (pill in pills) pill.updateColors()
    }

    private var visibilityFactor = -1f

    fun setVisibilityFactor(factor: Float) {
        if (visibilityFactor == factor) return
        visibilityFactor = factor
        if (factor <= 0.01f) {
            visibility = GONE
            return
        }
        if (visibility != VISIBLE) visibility = VISIBLE
        alpha = factor
        scaleX = AndroidUtilities.lerp(0.6f, 1f, factor)
        scaleY = AndroidUtilities.lerp(0.6f, 1f, factor)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (stackOnScreen == isVisible) return
        stackOnScreen = isVisible
        for (pill in pills) pill.onStackVisibilityChanged(isVisible)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (pills.isEmpty()) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isSwiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) && pills.size > 1) {
                    isSwiping = true
                    currentAnimator?.cancel()
                    startY = event.rawY - (if (isSwipingUp) -currentSwipeProgress * height else currentSwipeProgress * height)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pills.isEmpty()) return super.onTouchEvent(event)
        val current = pills[currentIndex]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                isSwiping = false
                maybeClick = true
                longClickPerformed = false
                current.isPressed = true
                current.drawableHotspotChanged(event.x, event.y)
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = event.rawX - startX
                var dy = event.rawY - startY
                if (!isSwiping && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    maybeClick = false
                    removeCallbacks(longPressRunnable)
                    current.isPressed = false
                    if (Math.abs(dy) > Math.abs(dx) && pills.size > 1) {
                        isSwiping = true
                        currentAnimator?.cancel()
                        startY = event.rawY
                        dy = 0f
                    }
                }
                if (isSwiping) handleSwipeProgress(dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                current.isPressed = false
                if (isSwiping) {
                    finishSwipe(event.rawY - startY)
                } else if (maybeClick && !longClickPerformed) {
                    current.onPillClicked()
                }
                maybeClick = false
                isSwiping = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                current.isPressed = false
                if (isSwiping) cancelSwipe(isSwipingUp)
                maybeClick = false
                isSwiping = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleSwipeProgress(dy: Float) {
        if (pills.size <= 1) return
        val h = height
        if (h <= 0) return
        isSwipingUp = dy < 0
        val progress = Math.abs(dy) / h
        val next = if (isSwipingUp) currentIndex + 1 else currentIndex - 1
        currentSwipeProgress = if (next >= 0 && next < pills.size) Math.min(progress, 1f) else progress
        applyProgress(currentSwipeProgress, isSwipingUp)
    }

    private fun finishSwipe(dy: Float) {
        val h = height
        if (h <= 0) {
            cancelSwipe(isSwipingUp)
            return
        }
        val next = if (isSwipingUp) currentIndex + 1 else currentIndex - 1
        val canSwitch = next >= 0 && next < pills.size
        if (Math.abs(dy) > h * 0.25f && canSwitch) {
            animateToNextPill(isSwipingUp)
        } else {
            cancelSwipe(isSwipingUp)
        }
    }

    private fun applyProgress(progress: Float, up: Boolean) {
        val current = pills[currentIndex]
        val next = if (up) currentIndex + 1 else currentIndex - 1
        for (i in pills.indices) {
            if (i != currentIndex && i != next && pills[i].visibility != GONE) {
                pills[i].visibility = GONE
            }
        }
        if (next >= pills.size || next < 0) {
            val overscroll = height * (1.0 - 1.0 / (progress * 0.18 + 1.0)).toFloat()
            current.translationY = if (up) -overscroll else overscroll
            current.alpha = 1f
            return
        }
        val value = Math.min(progress, 1f)
        val nextPill = pills[next]
        if (nextPill.visibility != VISIBLE) nextPill.visibility = VISIBLE
        val offset = height * value
        current.translationY = if (up) -offset else offset
        current.alpha = 1f - value
        val scaleDelta = 0.2f * value
        current.scaleX = 1f - scaleDelta
        current.scaleY = 1f - scaleDelta
        nextPill.scaleX = 0.8f + scaleDelta
        nextPill.scaleY = 0.8f + scaleDelta
        nextPill.alpha = value
        val from = if (up) height.toFloat() else -height.toFloat()
        nextPill.translationY = from - value * from
    }

    private fun animateToNextPill(up: Boolean) {
        currentAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(currentSwipeProgress, 1f)
        animator.duration = 250
        animator.interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
        animator.addUpdateListener { applyProgress(it.animatedValue as Float, up) }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                val previous = pills[currentIndex]
                previous.visibility = GONE
                previous.isPressed = false
                previous.scaleX = 1f
                previous.scaleY = 1f
                previous.onPillUnselected()

                currentIndex = if (up) currentIndex + 1 else currentIndex - 1
                currentIndex = currentIndex.coerceIn(0, pills.size - 1)

                for (i in pills.indices) {
                    if (i != currentIndex) pills[i].visibility = GONE
                }
                val selected = pills[currentIndex]
                selected.visibility = VISIBLE
                selected.scaleX = 1f
                selected.scaleY = 1f
                selected.translationY = 0f
                selected.alpha = 1f
                selected.onPillSelected()
                currentSwipeProgress = 0f
                onCurrentPillChanged?.invoke()
            }
        })
        currentAnimator = animator
        animator.start()
    }

    private fun cancelSwipe(up: Boolean) {
        currentAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(currentSwipeProgress, 0f)
        animator.duration = 200
        animator.addUpdateListener { applyProgress(it.animatedValue as Float, up) }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                for (i in pills.indices) {
                    if (i != currentIndex) {
                        val pill = pills[i]
                        pill.visibility = GONE
                        pill.isPressed = false
                        pill.scaleX = 1f
                        pill.scaleY = 1f
                    }
                }
                val current = pills[currentIndex]
                current.translationY = 0f
                current.alpha = 1f
                current.scaleX = 1f
                current.scaleY = 1f
                currentSwipeProgress = 0f
            }
        })
        currentAnimator = animator
        animator.start()
    }
}
