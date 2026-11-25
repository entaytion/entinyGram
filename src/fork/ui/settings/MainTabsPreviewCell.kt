package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.feed.FeedHelper
import desu.inugram.helpers.menu.MainTabsMenuConfig.Item
import desu.inugram.helpers.theme.M3MainTabsHelper
import desu.inugram.helpers.theme.MainTabsV2Helper
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.chat.ViewPositionWatcher
import org.telegram.ui.Components.glass.GlassTabView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.MainTabsLayout
import org.telegram.utils.glass.source.GlassSourceColor
import org.telegram.utils.glass.source.GlassSourceRenderNode

internal class MainTabsPreviewCell(
    context: Context,
    private val account: Int,
    private val provider: Theme.ResourcesProvider?,
) : FrameLayout(context), NotificationCenter.NotificationCenterDelegate {
    private val scene = FrameLayout(context)
    private val chats = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val scroll = object : ScrollView(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (canScrollVertically(1) || canScrollVertically(-1)) {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
            super.onScrollChanged(left, top, oldLeft, oldTop)
            if (MainTabsHelper.isHideOnScroll && top != oldTop) {
                setBarHiddenByScroll(top > oldTop)
            }
            invalidateScene()
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(chats, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
    }
    private val empty = TextView(context).apply {
        gravity = Gravity.CENTER
        text = getString(R.string.InuBottomTabsPreviewEmpty)
        setTextSize(14f)
    }
    private val fade = View(context)
    private val bar = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val current = state
            if (current != null && !current.material) {
                val separateWidth = if (current.separateSearch) dp(56f) else 0
                val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - dp(12f) - separateWidth).coerceAtLeast(1)
                val tabCount = current.order.size - if (current.separateSearch) 1 else 0
                val normalWidth = dp((tabCount * MainTabsHelper.tabWidth + 2 * (MainTabsHelper.mainTabsMargin + MainTabsHelper.TAB_PADDING)).toFloat())
                val width = if (MainTabsHelper.hasCustomWidth) availableWidth else minOf(availableWidth, normalWidth)
                layout?.setMaxWidth(width)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val fallback = GlassSourceColor()
    private val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) GlassSourceRenderNode(fallback) else null
    private val glass = BlurredBackgroundDrawableViewFactory(source ?: fallback, source ?: fallback, fallback).apply {
        setSourceRootView(ViewPositionWatcher(scene), scene)
        setLiquidGlassEffectAllowed(true)
    }
    private var sceneDirty = true
    private var state: BarState? = null
    private var selection = Item.CHATS
    private var layout: MainTabsLayout? = null
    private var updateGlassColors: (() -> Unit)? = null
    private val notifications = NotificationCenter.getInstance(account)
    private val events = intArrayOf(NotificationCenter.dialogsNeedReload, NotificationCenter.updateInterfaces)
    private var startTab = InuConfig.START_TAB.value

    private data class BarState(
        val order: List<Item>,
        val material: Boolean,
        val ios: Boolean,
        val floating: Boolean,
        val compact: Boolean,
        val titles: Boolean,
        val avatar: Boolean,
        val separateSearch: Boolean,
        val width: Int,
    )

    init {
        scene.clipToOutline = true
        scene.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        scene.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        scene.addView(fade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 76, Gravity.BOTTOM))
        scene.addView(bar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        addView(scene, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.FILL, 16f, 8f, 16f, 8f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        events.forEach { notifications.addObserver(this, it) }
        refreshChats()
        refresh()
    }

    override fun onDetachedFromWindow() {
        events.forEach { notifications.removeObserver(this, it) }
        super.onDetachedFromWindow()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.dialogsNeedReload) refreshChats()
        if (id == NotificationCenter.updateInterfaces) {
            val mask = args.firstOrNull() as? Int ?: 0
            for (index in 0 until chats.childCount) (chats.getChildAt(index) as DialogCell).update(mask)
            invalidateScene()
        }
        refreshBadges()
    }

    private fun refreshChats() {
        chats.removeAllViews()
        MessagesController.getInstance(account).getDialogs(0).asSequence()
            .filterNot { it is TLRPC.TL_dialogFolder }
            .take(10)
            .forEach { dialog ->
                val cell = DialogCell(null, context, false, false, account, provider)
                cell.setDialog(dialog, DialogsActivity.DIALOGS_TYPE_DEFAULT, 0)
                cell.useSeparator = true
                chats.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
        empty.visibility = if (chats.childCount == 0) VISIBLE else GONE
        invalidateScene()
    }

    fun refresh() {
        val color = color(Theme.key_windowBackgroundWhite)
        scene.background = Theme.createRoundRectDrawable(dp(14f), color)
        fallback.setColor(color)
        empty.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText))
        fade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, color))

        val order = if (MainTabsHelper.isHidden) emptyList() else MainTabsHelper.configuredOrder()
        val next = BarState(
            order, MainTabsHelper.isMaterial, MainTabsHelper.isIos, MainTabsHelper.isV2, MainTabsHelper.isCompact,
            MainTabsHelper.showTitles, !InuConfig.BOTTOM_TABS_HIDE_PROFILE_AVATAR.value,
            Item.SEARCH in order && InuConfig.BOTTOM_TABS_SEARCH_SEPARATE.value && !MainTabsHelper.isMaterial,
            InuConfig.BOTTOM_TABS_WIDTH.value,
        )
        val nextStartTab = InuConfig.START_TAB.value
        if (state != next || startTab != nextStartTab) {
            val previous = state
            state = next
            if (previous == null || startTab != nextStartTab || selection !in order || (next.separateSearch && selection == Item.SEARCH)) {
                selection = order.firstOrNull { !it.isShortcut && it.index == nextStartTab } ?: Item.CHATS
            }
            startTab = nextStartTab
            renderBar(next)
        }
        setBarHiddenByScroll(false)
        val lp = bar.layoutParams as LayoutParams
        lp.width = if (next.material) LayoutHelper.MATCH_PARENT else LayoutHelper.WRAP_CONTENT
        lp.height = dp(MainTabsHelper.mainTabsBarHeight.toFloat())
        lp.bottomMargin = dp(MainTabsHelper.bottomLiftDp.toFloat())
        bar.layoutParams = lp
        updateGlassColors?.invoke()
        refreshBadges()
        invalidateScene()
    }

    private fun renderBar(state: BarState) {
        for (index in 0 until bar.childCount) glass.unsubscribe(bar.getChildAt(index))
        bar.removeAllViews()
        layout = null
        updateGlassColors = null
        if (state.order.isEmpty()) return
        val tabs = MainTabsLayout(context, provider)
        layout = tabs
        val margin = MainTabsHelper.mainTabsMargin
        val vertical = dp(MainTabsHelper.tabsInnerPaddingVertical.toFloat())
        val horizontal = dp((margin + MainTabsHelper.TAB_PADDING).toFloat())
        tabs.setPadding(horizontal, vertical, horizontal, vertical)
        M3MainTabsHelper.applyTabsLayout(tabs)
        MainTabsHelper.applyIosTabsLayout(tabs)
        MainTabsV2Helper.applyTabsLayout(tabs)
        MainTabsHelper.applyWidth(tabs)
        val background = glass.create(tabs, BlurredBackgroundProviderImpl.inu_mainTabs(provider, state.ios)).apply {
            setRadius(dp(MainTabsHelper.mainTabsHeight / 2f).toFloat())
            setPadding(dp(margin - 0.334f))
        }
        M3MainTabsHelper.applyTabsBackground(background)
        tabs.background = background
        state.order.filterNot { state.separateSearch && it == Item.SEARCH }.forEach { item ->
            val tab = MainTabsHelper.createTab(context, provider, account, item)
            tab.tag = item
            tab.contentDescription = getString(item.labelRes)
            tab.setSelected(item == selection, false)
            tab.setOnClickListener {
                selection = item
                tabs.setTabSelected(tab, true)
                tabs.requestLayout()
            }
            tabs.addView(tab)
        }
        M3MainTabsHelper.applyTabsBar(bar)
        bar.addView(tabs, M3MainTabsHelper.tabsLayoutParams(LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT)))
        val searchBackground = if (state.separateSearch) {
            val search = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.outline_header_search)
                setColorFilter(color(Theme.key_glass_tabUnselected))
                contentDescription = getString(R.string.Search)
            }
            val drawable = glass.create(search, BlurredBackgroundProviderImpl.inu_mainTabs(provider, state.ios)).apply {
                setRadius(dp(26f).toFloat())
            }
            search.background = drawable
            bar.addView(search, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { marginStart = dp(4f) })
            drawable
        } else null
        updateGlassColors = {
            background.updateColors()
            searchBackground?.updateColors()
        }
    }

    private fun refreshBadges() {
        layout?.findViewWithTag<GlassTabView>(Item.CHATS)
            ?.setCounter(MainTabsHelper.chatsBadge(account), false, false)
        layout?.findViewWithTag<GlassTabView>(Item.FEED)
            ?.setCounter(FeedHelper.unreadBadge(account), false, false)
    }

    private fun setBarHiddenByScroll(hidden: Boolean) {
        val show = !MainTabsHelper.isHidden && !hidden
        bar.visibility = if (show) VISIBLE else GONE
        fade.visibility = if (show && !MainTabsHelper.hideFade) VISIBLE else GONE
    }

    private fun invalidateScene() {
        sceneDirty = true
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source != null && canvas.isHardwareAccelerated &&
            scene.width > 0 && scene.height > 0 && (sceneDirty || source.needUpdateDisplayList(scene.width, scene.height))
        ) {
            val capture = source.beginRecording(scene.width, scene.height)
            capture.drawColor(color(Theme.key_windowBackgroundWhite))
            scroll.draw(capture)
            source.endRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                source.setBlur(dp(18f).toFloat() * InuConfig.LIQUID_GLASS_BLUR.value.coerceIn(10, 200) / 100f)
            }
            source.invalidateDisplayListForDrawables()
            sceneDirty = false
        }
        super.dispatchDraw(canvas)
    }

    private fun color(key: Int) = Theme.getColor(key, provider)

    companion object {
        const val HEIGHT_DP = 224
    }
}
