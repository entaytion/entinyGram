package desu.inugram.helpers.pillstack.pills

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LoadingDrawable

/** Shared base for every pill: auto-refresh scheduling, loading shimmer, size-change animation. */
abstract class BasePill(context: Context, val resourcesProvider: Theme.ResourcesProvider?) :
    FrameLayout(context) {

    companion object {
        private val globalLastUpdateTimes = SparseArray<Long>()
    }

    @JvmField
    protected var loading = false
    protected var loadingDrawable: LoadingDrawable? = null
    private var loadingTargetView: View? = null

    private val rectF = RectF()
    private var stackVisible = true

    private val autoRefreshRunnable = Runnable {
        onUpdateData(false)
        scheduleNextUpdate()
    }

    init {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
        )
        clipChildren = false
        clipToPadding = false
    }

    abstract fun getPillId(): Int

    /** 0 = never auto-refresh. */
    abstract fun getRefreshInterval(): Long

    abstract fun onUpdateData(force: Boolean)

    abstract fun onPillClicked()

    abstract fun onPillLongClicked(): Boolean

    abstract fun updateColors()

    open fun onPillSelected() {}

    open fun onPillUnselected() {}

    fun getThemedColor(key: Int): Int = Theme.getColor(key, resourcesProvider)

    fun getThemedColor(key: Int, alpha: Float): Int = Theme.multAlpha(getThemedColor(key), alpha)

    fun isRefreshDue(): Boolean {
        val interval = getRefreshInterval()
        if (interval <= 0) return true
        val last = globalLastUpdateTimes.get(getPillId(), 0L)
        return last == 0L || SystemClock.elapsedRealtime() - last >= interval
    }

    fun markDataUpdated() {
        globalLastUpdateTimes.put(getPillId(), SystemClock.elapsedRealtime())
        scheduleNextUpdate()
    }

    private fun scheduleNextUpdate() {
        removeCallbacks(autoRefreshRunnable)
        if (!stackVisible) return
        val interval = getRefreshInterval()
        if (interval > 0) postDelayed(autoRefreshRunnable, interval)
    }

    fun onStackVisibilityChanged(visible: Boolean) {
        if (stackVisible == visible) return
        stackVisible = visible
        if (!visible) {
            removeCallbacks(autoRefreshRunnable)
        } else if (getRefreshInterval() > 0) {
            if (isRefreshDue()) onUpdateData(false)
            scheduleNextUpdate()
        }
    }

    fun animateSizeChange() {
        val grandparent = parent?.parent
        if (isLaidOut && visibility == VISIBLE && grandparent is ViewGroup) {
            TransitionManager.beginDelayedTransition(
                grandparent,
                TransitionSet()
                    .addTransition(ChangeBounds())
                    .setDuration(300)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT as TimeInterpolator)
            )
        }
    }

    fun setLoadingTargetView(view: View) {
        loadingTargetView = view
    }

    fun startLoading() {
        loading = true
        var drawable = loadingDrawable
        if (drawable == null) {
            drawable = LoadingDrawable(resourcesProvider)
            drawable.callback = this
            drawable.setGradientScale(2f)
            drawable.setRadiiDp(14f)
            loadingDrawable = drawable
            updateLoadingColors()
        }
        drawable.reset()
        drawable.resetDisappear()
        drawable.alpha = 255
        invalidate()
    }

    fun stopLoading() {
        loading = false
        loadingDrawable?.disappear()
    }

    open fun updateLoadingColors() {
        val drawable = loadingDrawable ?: return
        val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText)
        drawable.setColors(Theme.multAlpha(color, 0.05f), Theme.multAlpha(color, 0.15f))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val drawable = loadingDrawable ?: return
        if (drawable.alpha > 0 || !drawable.isDisappearing) {
            val target = loadingTargetView ?: this
            rectF.set(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat())
            drawable.setBounds(rectF)
            drawable.draw(canvas)
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!stackVisible) return
        val interval = getRefreshInterval()
        if (interval <= 0) return
        val last = globalLastUpdateTimes.get(getPillId(), 0L)
        if (last != 0L) {
            val passed = SystemClock.elapsedRealtime() - last
            if (passed < interval) {
                postDelayed(autoRefreshRunnable, interval - passed)
                return
            }
        }
        autoRefreshRunnable.run()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoRefreshRunnable)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === loadingDrawable || super.verifyDrawable(who)
}
