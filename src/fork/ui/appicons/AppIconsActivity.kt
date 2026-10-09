package desu.inugram.ui.appicons

// entiny: ported from exteraless (app.exteraless.appicons), GPL-3.0

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AppIconBulletinLayout
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor
import org.telegram.ui.LauncherIconController.LauncherIcon
import org.telegram.ui.PremiumPreviewFragment
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AppIconsActivity : BaseFragment() {
    private val icons = ArrayList<LauncherIcon>()
    private var appliedIcon: LauncherIcon? = null
    private var previewIcon: LauncherIcon? = null

    private lateinit var contentView: ContentView
    private lateinit var heroView: AppIconHeroView
    private lateinit var listView: RecyclerListView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var buttonContainer: FrameLayout
    private lateinit var button: ButtonWithCounterView

    private var tint = 0
    private var tintAnimator: ValueAnimator? = null
    private var glassSource: BlurredBackgroundSourceColor? = null
    private var heroHeight = 0
    private var bottomInset = 0
    private var collapse = 0f
    private var topFade = 0f

    override fun isSupportEdgeToEdge(): Boolean = true

    override fun createView(context: Context): View {
        icons.clear()
        icons.addAll(AppIcons.available(currentAccount))
        previewIcon = AppIcons.current()
        appliedIcon = previewIcon
        tint = AppIcons.tint(AppIcons.accent(context, previewIcon!!))

        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(false)
        actionBar.setCastShadows(false)
        actionBar.setAddToContainer(false)
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false)
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        contentView = ContentView(context)

        val source = BlurredBackgroundSourceColor()
        source.setColor(topColor())
        glassSource = source
        actionBar.setupGlass(
            BlurredBackgroundDrawableViewFactory(source),
            BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()),
        )
        actionBar.setGlassOnlyBack()

        heroView = AppIconHeroView(context, getResourceProvider())
        heroView.set(previewIcon!!)
        contentView.addView(heroView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))

        listView = RecyclerListView(context, getResourceProvider())
        layoutManager = GridLayoutManager(context, 4)
        listView.layoutManager = layoutManager
        listView.adapter = Adapter()
        listView.clipToPadding = false
        listView.isVerticalScrollBarEnabled = false
        listView.setSelectorDrawableColor(Color.TRANSPARENT)
        listView.setOnItemClickListener { _, position ->
            if (position >= 0 && position < icons.size) preview(icons[position])
        }
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateCollapse()
            }
        })
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.NO_GRAVITY))

        button = ButtonWithCounterView(context, true, getResourceProvider())
        button.setRoundRadius(24)
        button.setOnClickListener { apply() }
        updateButton(false)
        buttonContainer = FrameLayout(context)
        buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48f, Gravity.BOTTOM, 16f, 12f, 16f, 12f))
        buttonContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
        contentView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM))

        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))
        updateButtonFade()
        fragmentView = contentView
        return contentView
    }

    override fun onFragmentDestroy() {
        cancelTintAnimation()
        super.onFragmentDestroy()
    }

    private fun cancelTintAnimation() {
        tintAnimator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        tintAnimator = null
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        bottomInset = bottom
        if (::contentView.isInitialized) contentView.requestLayout()
        updateListPadding()
    }

    override fun isLightStatusBar(): Boolean = AndroidUtilities.computePerceivedBrightness(topColor()) > 0.6f

    private fun preview(icon: LauncherIcon) {
        if (icon == previewIcon) return
        previewIcon = icon
        heroView.set(icon)
        animateTint(AppIcons.tint(AppIcons.accent(context, icon)))
        updateButton(true)
        for (i in 0 until listView.childCount) {
            val cell = listView.getChildAt(i) as? AppIconCell ?: continue
            cell.icon?.let { cell.set(it, it == previewIcon, true) }
        }
    }

    private fun apply() {
        val icon = previewIcon ?: return
        if (icon == appliedIcon) {
            finishFragment()
            return
        }
        if (AppIcons.locked(icon)) {
            showDialog(PremiumFeatureBottomSheet(this, PremiumPreviewFragment.PREMIUM_FEATURE_APPLICATION_ICONS, true))
            return
        }
        AppIcons.apply(icon)
        appliedIcon = icon
        updateButton(true)
        Bulletin.make(this, AppIconBulletinLayout(context, icon, getResourceProvider()), Bulletin.DURATION_SHORT).show()
    }

    private fun updateButton(animated: Boolean) {
        if (!::button.isInitialized) return
        val res = if (previewIcon == appliedIcon) R.string.InuAppIconsKeep else R.string.InuAppIconsSelect
        button.setText(LocaleController.getString(res), animated)
    }

    private fun baseColor(): Int {
        val gray = getThemedColor(Theme.key_windowBackgroundGray)
        return if (tint == 0) gray else ColorUtils.blendARGB(gray, tint, 0.25f)
    }

    private fun topColor(): Int = if (tint == 0) baseColor() else ColorUtils.blendARGB(baseColor(), tint, 0.7f)

    private fun animateTint(to: Int) {
        cancelTintAnimation()
        if (tint == to) return
        val from = tint
        if (from == 0 || to == 0) {
            tint = to
            invalidateTop()
            updateStatusBar()
            return
        }
        tintAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(280).apply {
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                tint = ColorUtils.blendARGB(from, to, it.animatedValue as Float)
                invalidateTop()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tint = to
                    updateStatusBar()
                }
            })
            start()
        }
    }

    private fun invalidateTop() {
        contentView.invalidate()
        glassSource?.let {
            it.setColor(topColor())
            actionBar.invalidate()
        }
    }

    private fun updateStatusBar() {
        parentActivity?.let { AndroidUtilities.setLightStatusBar(it.window, isLightStatusBar()) }
    }

    private fun updateButtonFade() {
        val color = getThemedColor(Theme.key_windowBackgroundWhite)
        buttonContainer.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0), color, color),
        )
    }

    private fun updateListPadding() {
        if (!::listView.isInitialized) return
        val side = AndroidUtilities.dp(8f)
        val top = heroHeight + AndroidUtilities.dp(10f)
        val bottom = if (::buttonContainer.isInitialized && buttonContainer.height > 0) buttonContainer.height else bottomInset + AndroidUtilities.dp(72f)
        if (listView.paddingTop != top || listView.paddingBottom != bottom || listView.paddingLeft != side) {
            listView.setPadding(side, top, side, bottom)
        }
        updateCollapse()
    }

    private fun collapseRange(): Int =
        max(0, heroHeight - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.statusBarHeight)

    private fun updateCollapse() {
        if (!::listView.isInitialized || !::heroView.isInitialized) return
        var scrolled = if (listView.childCount > 0) Int.MAX_VALUE / 2 else 0
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            if (listView.getChildAdapterPosition(child) == 0) {
                scrolled = listView.paddingTop - child.top
                break
            }
        }
        scrolled = max(0, scrolled)
        val range = collapseRange()
        val newCollapse = if (range <= 0) 0f else min(1f, scrolled / range.toFloat())
        val newFade = min(1f, scrolled / AndroidUtilities.dp(16f).toFloat())
        if (abs(newCollapse - collapse) > 0.0005f || abs(newFade - topFade) > 0.0005f) {
            collapse = newCollapse
            topFade = newFade
            heroView.setCollapse(collapse)
            contentView.invalidate()
        }
    }

    private fun spanCount(width: Int): Int {
        val available = width - AndroidUtilities.dp(8f) * 2
        val count = max(3, min(8, (available / AndroidUtilities.dp(100f).toFloat()).roundToInt()))
        return if (count < 8 && available / count.toFloat() > AndroidUtilities.dp(112f)) count + 1 else count
    }

    private inner class ContentView(context: Context) : FrameLayout(context) {
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val panelPath = Path()
        private val radii = FloatArray(8)
        private var glowColor = 0
        private var glowCy = 0f
        private var glowWidth = 0
        private var topFadeDrawable: GradientDrawable? = null
        private var topFadeColor = 0

        init {
            setWillNotDraw(false)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            heroView.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(28f), 0, AndroidUtilities.dp(40f))
            val available = (MeasureSpec.getSize(heightMeasureSpec) * 0.45f).toInt() - AndroidUtilities.statusBarHeight -
                AndroidUtilities.dp(68f) - heroView.getTextBlockHeight()
            heroView.setPreviewSizeDp(max(64, min(AppIconHeroView.PREVIEW_DP, (available / AndroidUtilities.density).toInt())))
            buttonContainer.setPadding(0, 0, 0, bottomInset)
            val span = spanCount(MeasureSpec.getSize(widthMeasureSpec))
            if (layoutManager.spanCount != span) layoutManager.spanCount = span
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val height = heroView.measuredHeight
            if (height > 0 && height != heroHeight) {
                heroHeight = height
                AndroidUtilities.runOnUIThread { updateListPadding() }
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            updateCollapse()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(baseColor())
            if (tint == 0 || collapse >= 1f || heroHeight <= 0) return
            val cy = heroView.previewCenterY()
            if (glowColor != tint || glowWidth != width || glowCy != cy) {
                glowColor = tint
                glowWidth = width
                glowCy = cy
                val radius = max(width, heroHeight) * 0.9f
                glowPaint.shader = RadialGradient(
                    width / 2f, cy, radius,
                    intArrayOf(ColorUtils.setAlphaComponent(tint, 200), ColorUtils.setAlphaComponent(tint, 0)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                )
            }
            glowPaint.alpha = ((1f - collapse) * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }

        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
            if (child !== listView) return super.drawChild(canvas, child, drawingTime)
            val radius = AndroidUtilities.dp(20f).toFloat()
            radii[0] = radius
            radii[1] = radius
            radii[2] = radius
            radii[3] = radius
            val top = listView.y + heroHeight - collapseRange() * collapse
            AndroidUtilities.rectTmp.set(0f, top, width.toFloat(), height.toFloat())
            panelPath.rewind()
            panelPath.addRoundRect(AndroidUtilities.rectTmp, radii, Path.Direction.CW)
            val white = getThemedColor(Theme.key_windowBackgroundWhite)
            panelPaint.color = white
            canvas.drawPath(panelPath, panelPaint)
            canvas.save()
            canvas.clipPath(panelPath)
            val result = super.drawChild(canvas, child, drawingTime)
            if (topFade > 0f) {
                var drawable = topFadeDrawable
                if (drawable == null || topFadeColor != white) {
                    topFadeColor = white
                    drawable = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(white, ColorUtils.setAlphaComponent(white, 0)),
                    )
                    topFadeDrawable = drawable
                }
                drawable.setBounds(0, top.toInt(), width, top.toInt() + AndroidUtilities.dp(24f))
                drawable.alpha = (topFade * 255).toInt()
                drawable.draw(canvas)
            }
            canvas.restore()
            return result
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemCount(): Int = icons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = AppIconCell(parent.context, getResourceProvider())
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val icon = icons[position]
            (holder.itemView as AppIconCell).set(icon, icon == previewIcon, false)
        }
    }
}
