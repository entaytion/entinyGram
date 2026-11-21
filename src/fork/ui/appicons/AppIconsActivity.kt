package desu.inugram.ui.appicons

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
import org.telegram.ui.LauncherIconController.LauncherIcon
import org.telegram.ui.PremiumPreviewFragment
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import org.telegram.utils.glass.source.GlassSourceColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AppIconsActivity : BaseFragment() {
    private sealed class Entry {
        object Hero : Entry()
        class Header(val title: CharSequence) : Entry()
        class Item(val icon: LauncherIcon) : Entry()
    }

    private val entries = ArrayList<Entry>()
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
    private var glassSource: GlassSourceColor? = null
    private var bottomInset = 0

    override fun isSupportEdgeToEdge(): Boolean = true

    override fun createView(context: Context): View {
        previewIcon = AppIcons.current()
        appliedIcon = previewIcon
        tint = AppIcons.tint(AppIcons.accent(context, previewIcon!!))
        buildEntries()

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

        val source = GlassSourceColor()
        source.setColor(baseColor())
        glassSource = source
        actionBar.setupGlass(
            BlurredBackgroundDrawableViewFactory(source),
            BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()),
        )
        actionBar.setGlassOnlyBack()

        heroView = AppIconHeroView(context, getResourceProvider())
        heroView.set(previewIcon!!)
        heroView.setTint(tint)

        listView = RecyclerListView(context, getResourceProvider())
        layoutManager = GridLayoutManager(context, 4)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (entries[position] is Entry.Item) 1 else layoutManager.spanCount
        }
        listView.layoutManager = layoutManager
        listView.adapter = Adapter()
        listView.clipToPadding = false
        listView.isVerticalScrollBarEnabled = false
        listView.itemAnimator = null
        listView.setSelectorDrawableColor(Color.TRANSPARENT)
        listView.setOnItemClickListener { _, position ->
            (entries.getOrNull(position) as? Entry.Item)?.let { preview(it.icon) }
        }
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                contentView.invalidate()
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
        updateListPadding()
        fragmentView = contentView
        return contentView
    }

    private fun buildEntries() {
        val all = AppIcons.available(currentAccount)
        entries.clear()
        entries.add(Entry.Hero)
        fun section(title: CharSequence, list: List<LauncherIcon>) {
            if (list.isEmpty()) return
            entries.add(Entry.Header(title))
            list.forEach { entries.add(Entry.Item(it)) }
        }
        val own = setOf(LauncherIcon.DEFAULT, LauncherIcon.OLD)
        section("entinyGram", all.filter { it in own })
        section(LocaleController.getString(R.string.InuAppIconsConcepts), all.filter { it.concept && it !in own })
        section("Telegram", all.filter { !it.concept && it !in own })
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

    override fun isLightStatusBar(): Boolean = AndroidUtilities.computePerceivedBrightness(baseColor()) > 0.6f

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
        val gray = getThemedColor(Theme.key_windowBackgroundWhite)
        return if (tint == 0) gray else ColorUtils.blendARGB(gray, tint, 0.18f)
    }

    private fun animateTint(to: Int) {
        cancelTintAnimation()
        if (tint == to) return
        val from = tint
        if (from == 0 || to == 0) {
            tint = to
            onTintChanged()
            return
        }
        tintAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(280).apply {
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                tint = ColorUtils.blendARGB(from, to, it.animatedValue as Float)
                onTintChanged()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tint = to
                    onTintChanged()
                }
            })
            start()
        }
    }

    private fun onTintChanged() {
        heroView.setTint(tint)
        contentView.invalidate()
        glassSource?.let {
            it.setColor(baseColor())
            actionBar.invalidate()
        }
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
        val top = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight()
        val bottom = if (::buttonContainer.isInitialized && buttonContainer.height > 0) buttonContainer.height else bottomInset + AndroidUtilities.dp(72f)
        if (listView.paddingTop != top || listView.paddingBottom != bottom || listView.paddingLeft != side) {
            listView.setPadding(side, top, side, bottom)
        }
    }

    private fun spanCount(width: Int): Int {
        val available = width - AndroidUtilities.dp(16f)
        return max(3, min(6, (available / AndroidUtilities.dp(92f).toFloat()).roundToInt()))
    }

    private inner class ContentView(context: Context) : FrameLayout(context) {
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var glowColor = 0
        private var glowWidth = 0
        private var glowCy = 0f

        init {
            setWillNotDraw(false)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            buttonContainer.setPadding(0, 0, 0, bottomInset)
            val span = spanCount(MeasureSpec.getSize(widthMeasureSpec))
            if (layoutManager.spanCount != span) layoutManager.spanCount = span
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite))
            if (tint == 0) return
            val hero = listView.getChildAt(0)?.takeIf { listView.getChildAdapterPosition(it) == 0 }
            val scrolled = if (hero != null) max(0, listView.paddingTop - hero.top) else Int.MAX_VALUE / 2
            val fade = 1f - min(1f, scrolled / AndroidUtilities.dp(280f).toFloat())
            if (fade <= 0f) return
            val cy = if (hero != null) (hero.top + AndroidUtilities.dp(AppIconHeroView.MOCK_DP / 2f)).toFloat() else 0f
            if (glowColor != tint || glowWidth != width || glowCy != cy) {
                glowColor = tint
                glowWidth = width
                glowCy = cy
                glowPaint.shader = RadialGradient(
                    width / 2f, cy, width * 0.95f,
                    intArrayOf(ColorUtils.setAlphaComponent(tint, 190), ColorUtils.setAlphaComponent(tint, 0)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                )
            }
            glowPaint.alpha = (fade * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemViewType == TYPE_ITEM

        override fun getItemCount(): Int = entries.size

        override fun getItemViewType(position: Int): Int = when (entries[position]) {
            is Entry.Hero -> TYPE_HERO
            is Entry.Header -> TYPE_HEADER
            is Entry.Item -> TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val view: View = when (viewType) {
                TYPE_HERO -> {
                    (heroView.parent as? ViewGroup)?.removeView(heroView)
                    heroView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    heroView.setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(8f))
                    heroView
                }
                TYPE_HEADER -> TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    typeface = AndroidUtilities.bold()
                    setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(18f), AndroidUtilities.dp(14f), AndroidUtilities.dp(4f))
                    setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider()))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                else -> AppIconCell(context, getResourceProvider()).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val entry = entries[position]) {
                is Entry.Hero -> {}
                is Entry.Header -> (holder.itemView as TextView).text = entry.title
                is Entry.Item -> (holder.itemView as AppIconCell).set(entry.icon, entry.icon == previewIcon, false)
            }
        }
    }

    private companion object {
        const val TYPE_HERO = 0
        const val TYPE_HEADER = 1
        const val TYPE_ITEM = 2
    }
}
