package desu.inugram.helpers.pillstack.pills

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.ui.settings.PillStackSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LoadingDrawable
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity

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

    protected val layout = LinearLayout(context)
    protected val iconView = ImageView(context)
    protected val textView = AnimatedTextView(context, true, true, true)

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

    abstract fun getRefreshInterval(): Long

    abstract fun onUpdateData(force: Boolean)

    abstract fun onPillClicked()

    abstract fun onPillLongClicked(): Boolean

    abstract fun updateColors()

    open fun onPillSelected() {}

    open fun onPillUnselected() {}

    // entiny: shared long-press menu -- anchored on the pill, without a scrim so the search bar stays visible
    protected fun showPillMenu(configure: ItemOptions.() -> Unit): Boolean {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return false
        val options = ItemOptions.makeOptions(fragment, this)
        options.configure()
        options.setDrawScrim(false).setDimAlpha(0).show()
        return true
    }

    protected fun openPillSettings(): Boolean {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return false
        fragment.presentFragment(PillStackSettingsActivity())
        return true
    }

    protected fun ItemOptions.addSettingsEntry() {
        add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings)) { this@BasePill.openPillSettings() }
    }

    // entiny: shared pill chrome -- pills only pass what actually differs between them
    protected fun setupPill(
        iconResId: Int = 0,
        startPaddingDp: Float = 8f,
        endPaddingDp: Float = 8f,
        iconGapDp: Float = 4f,
    ) {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(startPaddingDp), 0, AndroidUtilities.dp(endPaddingDp), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (iconResId != 0) iconView.setImageResource(iconResId)
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, iconGapDp, 0f))

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setIncludeFontPadding(false)
        textView.setTypeface(AndroidUtilities.bold())
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)
    }

    protected fun defaultAccent(): Int = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)

    protected fun activeAccent(): Int = getThemedColor(Theme.key_windowBackgroundWhiteGreenText)

    protected open fun applyIconColor(color: Int) {
        iconView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
    }

    protected fun applyPillColors(accent: Int = defaultAccent()) {
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(accent, 0.09f),
            Theme.multAlpha(accent, 0.1f)
        )
        textView.setTextColor(accent)
        applyIconColor(accent)
        updateLoadingColors()
    }

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
        // entiny: only the selected pill is visible in the stack, hidden pills must not tick
        if (!stackVisible || !isShown) return
        val interval = getRefreshInterval()
        if (interval > 0) postDelayed(autoRefreshRunnable, interval)
    }

    fun onStackVisibilityChanged(visible: Boolean) {
        if (stackVisible == visible) return
        stackVisible = visible
        if (!visible) {
            removeCallbacks(autoRefreshRunnable)
        } else if (visibility == VISIBLE && getRefreshInterval() > 0) {
            if (isRefreshDue()) onUpdateData(false)
            scheduleNextUpdate()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility == VISIBLE) {
            if (stackVisible && getRefreshInterval() > 0) {
                if (isRefreshDue()) onUpdateData(false)
                scheduleNextUpdate()
            }
        } else {
            removeCallbacks(autoRefreshRunnable)
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
        if (drawable.alpha > 0 || drawable.isDisappearing) {
            val target = loadingTargetView ?: this
            rectF.set(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat())
            drawable.setBounds(rectF)
            drawable.draw(canvas)
            invalidate()
        }
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility != VISIBLE) return
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
