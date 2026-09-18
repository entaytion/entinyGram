package desu.inugram.helpers.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import androidx.annotation.RequiresApi
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object Material3PredictiveBack {

    private const val LAZY_START = 0.015f
    private const val MAX_SCALE = 0.9f
    private const val EDGE_MARGIN_DP = 16f
    private const val CLOSING_ALPHA_FADE = 0.2f
    private const val COMMIT_DURATION = 450L
    private const val CANCEL_DURATION = 200L

    private val GESTURE_INTERP: Interpolator = PathInterpolator(0.1f, 0.1f, 0f, 1f)
    private val VERTICAL_INTERP: Interpolator = DecelerateInterpolator()
    private val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    @JvmStatic
    fun createCallback(
        activity: Activity,
        layout: ActionBarLayout,
        plainBack: Runnable,
    ): OnBackAnimationCallback = Callback(activity, layout, plainBack)

    private class Callback(
        private val activity: Activity,
        private val layout: ActionBarLayout,
        private val plainBack: Runnable,
    ) : OnBackAnimationCallback {

        private var attached = false
        private var invoked = false
        private var finishCancel = false
        private var startTouchY = 0f
        private var swipeEdge = BackEvent.EDGE_LEFT
        private var deviceCornerPx = 0
        private var edgeMarginPx = 0f
        private var enterOffsetPx = 0f
        private var runningAnim: AnimatorSet? = null
        private var savedOutlineProvider: ViewOutlineProvider? = null
        private var savedClipToOutline = false
        private var savedCvbBackground: Drawable? = null
        private var savedCvbForeground: Drawable? = null
        private val scrim = ColorDrawable(Color.BLACK).apply { alpha = Material3BackMotion.SCRIM_ALPHA_BYTE }

        private val outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val sx = view.scaleX.coerceAtLeast(0.01f)
                outline.setRoundRect(0, 0, view.width, view.height, deviceCornerPx / sx)
            }
        }

        override fun onBackStarted(backEvent: BackEvent) {
            // entiny: finalize previous predictive back synchronously to avoid nav lockup if gesture arrives before animator settles
            runningAnim?.let {
                it.removeAllListeners()
                it.cancel()
                runningAnim = null
                finalizeStock(finishCancel)
            }
            if (attached) {
                finalizeStock(cancel = true)
            } else if (layout.predictiveInput) {
                // entiny: roll back eager stock prep if previous gesture was preempted before lazy start
                undoStockPrep()
            }
            invoked = false
            startTouchY = backEvent.touchY
            swipeEdge = backEvent.swipeEdge
            deviceCornerPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.window.decorView.rootWindowInsets
                    ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            } else 0
            edgeMarginPx = dpf2(EDGE_MARGIN_DP)
            enterOffsetPx = dpf2(Material3BackMotion.ENTER_OFFSET_DP)
            layout.onBackStarted(backEvent.touchX, backEvent.touchY)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            if (invoked || !layout.predictiveInput) return
            val rawP = backEvent.progress
            if (!attached) {
                if (rawP <= LAZY_START) return
                attached = true
                layout.inu_m3PredictiveActive = true
                layout.invalidate()
                attachOverlays()
            }
            val p = GESTURE_INTERP.getInterpolation(
                ((rawP - LAZY_START) / (1f - LAZY_START)).coerceIn(0f, 1f)
            )
            applyFrame(p, backEvent.touchY)
        }

        override fun onBackCancelled() {
            invoked = false
            if (!attached) { undoStockPrep(); cleanupViews(); return }
            runFinishAnim(cancel = true)
        }

        override fun onBackInvoked() {
            invoked = true
            if (!attached) {
                undoStockPrep()
                cleanupViews()
                plainBack.run()
                return
            }
            runFinishAnim(cancel = false)
        }

        private fun undoStockPrep() {
            if (!layout.predictiveInput) return
            layout.predictiveInput = false
            layout.predictiveBackInProgress = false
            endStockSlide(true)
        }

        // entiny: block descendant focus during onSlideAnimationEnd to prevent phantom focus on view removal
        private fun endStockSlide(cancel: Boolean) {
            val cv = layout.containerView
            val saved = cv?.descendantFocusability
            cv?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            try {
                layout.onSlideAnimationEnd(cancel)
            } finally {
                if (saved != null) cv.descendantFocusability = saved
            }
        }

        private fun attachOverlays() {
            val cv = layout.containerView ?: return
            savedOutlineProvider = cv.outlineProvider
            savedClipToOutline = cv.clipToOutline
            cv.outlineProvider = outlineProvider
            cv.clipToOutline = true

            val cvb = layout.containerViewBack ?: return
            savedCvbBackground = cvb.background
            savedCvbForeground = cvb.foreground
            val enterBg = Material3BackMotion.getFragmentBackground(layout.backgroundFragment)
            cvb.background = enterBg?.constantState?.newDrawable()
                ?: ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite))
            cvb.foreground = scrim
            cvb.eachChild { it.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        }

        private fun applyFrame(p: Float, touchY: Float) {
            val cv = layout.containerView ?: return
            val cvb = layout.containerViewBack ?: return
            val w = cv.width.toFloat()
            val h = cv.height.toFloat()
            if (w <= 0f || h <= 0f) return

            val scale = 1f - (1f - MAX_SCALE) * p
            val maxDx = ((w - scale * w) / 2f - edgeMarginPx).coerceAtLeast(0f)
            val tx = if (swipeEdge == BackEvent.EDGE_RIGHT) 0f else maxDx * p

            val deltaY = touchY - startTouchY
            val dyCap = ((h - scale * h) / 2f - edgeMarginPx).coerceAtLeast(0f)
            val ySign = if (deltaY >= 0f) 1f else -1f
            val yNorm = (abs(deltaY) / (h / 2f)).coerceIn(0f, 1f)
            val ty = ySign * dyCap * VERTICAL_INTERP.getInterpolation(yNorm)

            cv.pivotX = w / 2f
            cv.pivotY = h / 2f
            cv.scaleX = scale
            cv.scaleY = scale
            cv.translationX = tx
            cv.translationY = ty
            cv.invalidateOutline()

            cvb.eachChild {
                it.translationX = -enterOffsetPx
                it.translationY = ty
                it.scaleX = scale
                it.scaleY = scale
            }
        }

        private fun childFloatAnim(
            cvb: ViewGroup, from: Float, to: Float, apply: (View, Float) -> Unit,
        ): ValueAnimator = ValueAnimator.ofFloat(from, to).apply {
            addUpdateListener {
                val v = it.animatedValue as Float
                cvb.eachChild { c -> apply(c, v) }
            }
        }

        private fun runFinishAnim(cancel: Boolean) {
            finishCancel = cancel
            val cv = layout.containerView ?: run { finalizeStock(cancel); return }
            val cvb = layout.containerViewBack ?: run { finalizeStock(cancel); return }

            val cvTargetTx = if (cancel) 0f else cv.translationX + enterOffsetPx
            val childTargetTx = if (cancel) -enterOffsetPx else 0f

            val first = cvb.getChildAt(0)
            val spatial = mutableListOf<Animator>(
                ObjectAnimator.ofFloat(cv, View.SCALE_X, 1f).apply {
                    addUpdateListener { cv.invalidateOutline() }
                },
                ObjectAnimator.ofFloat(cv, View.SCALE_Y, 1f),
                ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, cvTargetTx),
                ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, 0f),
                childFloatAnim(cvb, first?.translationX ?: 0f, childTargetTx) { c, v -> c.translationX = v },
                childFloatAnim(cvb, first?.translationY ?: 0f, 0f) { c, v -> c.translationY = v },
                childFloatAnim(cvb, first?.scaleX ?: 1f, 1f) { c, v -> c.scaleX = v; c.scaleY = v },
            )
            val animators = spatial.toMutableList()
            if (!cancel) {
                val startScrim = scrim.alpha
                animators += ValueAnimator.ofFloat(0f, 1f).apply {
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        cv.alpha = (1f - f / CLOSING_ALPHA_FADE).coerceIn(0f, 1f)
                        scrim.alpha = (startScrim * (1f - f / Material3BackMotion.SCRIM_FADE).coerceAtLeast(0f)).toInt()
                    }
                }
            }

            runningAnim = AnimatorSet().apply {
                playTogether(animators)
                duration = if (cancel) CANCEL_DURATION else COMMIT_DURATION
                if (cancel) interpolator = EMPHASIZED_DECELERATE else spatial.forEach { it.interpolator = Material3BackMotion.EMPHASIZED }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runningAnim = null
                        finalizeStock(cancel)
                    }
                })
                start()
            }
        }

        private fun finalizeStock(cancel: Boolean) {
            cleanupViews()
            layout.inu_m3PredictiveActive = false
            layout.invalidate()
            if (layout.predictiveInput) {
                layout.predictiveInput = false
                layout.predictiveBackInProgress = false
                endStockSlide(cancel)
            } else if (!cancel) {
                layout.onBackPressed()
            }
            attached = false
        }

        private fun cleanupViews() {
            layout.containerView?.let {
                it.scaleX = 1f
                it.scaleY = 1f
                it.translationX = 0f
                it.translationY = 0f
                it.alpha = 1f
                it.clipToOutline = savedClipToOutline
                it.outlineProvider = savedOutlineProvider ?: ViewOutlineProvider.BACKGROUND
            }
            layout.containerViewBack?.let { cvb ->
                cvb.eachChild {
                    it.translationX = 0f
                    it.translationY = 0f
                    it.scaleX = 1f
                    it.scaleY = 1f
                    it.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                cvb.background = savedCvbBackground
                cvb.foreground = savedCvbForeground
            }
            savedCvbBackground = null
            savedCvbForeground = null
            scrim.alpha = Material3BackMotion.SCRIM_ALPHA_BYTE
        }
    }
}
