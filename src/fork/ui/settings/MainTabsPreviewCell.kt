package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.menu.MainTabsMenuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.glass.GlassTabView
import kotlin.math.abs

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MainTabsPreviewCell(
    context: Context,
    private val onToggle: (MainTabsMenuConfig.Item) -> Unit,
    private val onReorder: (List<MainTabsMenuConfig.Item>) -> Unit,
) : FrameLayout(context) {

    private val group = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var order = emptyList<MainTabsMenuConfig.Item>()
    private var enabledItems = emptySet<MainTabsMenuConfig.Item>()
    private var separateSearch = false
    private var searchChip: SearchBubble? = null
    private var chipWidthDp = MainTabsHelper.tabWidth
    private var draggedItem: MainTabsMenuConfig.Item? = null
    private var dragStartRawX = 0f
    private var dragging = false
    private var dragStartOrder = emptyList<MainTabsMenuConfig.Item>()
    private var dragStartIndex = -1

    init {
        setWillNotDraw(false)
        row.setPadding(dp(ROW_PADDING_DP.toFloat()), dp(ROW_PADDING_DP.toFloat()), dp(ROW_PADDING_DP.toFloat()), dp(ROW_PADDING_DP.toFloat()))
        row.background = Theme.createRoundRectDrawable(dp(MainTabsHelper.mainTabsHeight / 2f), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.14f))
        group.addView(row, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        addView(group, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setState(order: List<MainTabsMenuConfig.Item>, enabledItems: Set<MainTabsMenuConfig.Item>, separateSearch: Boolean) {
        this.order = order.distinct()
        this.enabledItems = enabledItems
        this.separateSearch = separateSearch
        render()
    }

    private fun render() {
        row.removeAllViews()
        searchChip?.let { group.removeView(it) }
        searchChip = null
        visibleOrder().forEach { item -> addTab(item, item in enabledItems) }
        if (separateSearch) {
            val search = SearchBubble(context).apply {
                bind(MainTabsMenuConfig.Item.SEARCH in enabledItems)
                isClickable = true
                setOnClickListener { onToggle(MainTabsMenuConfig.Item.SEARCH) }
            }
            searchChip = search
            group.addView(search, LayoutHelper.createLinear(SEARCH_BUTTON_SIZE_DP, SEARCH_BUTTON_SIZE_DP, 0f, (SEARCH_ZONE_WIDTH_DP - SEARCH_BUTTON_SIZE_DP) / 2, 0, 0, 0, 0))
        }
        requestLayout()
    }

    // entiny: the real bottom bar's own tab view, built the way MainTabsActivity builds it, so the preview cannot drift from it
    private fun createTab(item: MainTabsMenuConfig.Item, enabled: Boolean): GlassTabView {
        val tab = if (item == MainTabsMenuConfig.Item.PROFILE) {
            GlassTabView.createAvatar(context, null, UserConfig.selectedAccount, item.labelRes)
        } else {
            GlassTabView.createMainTab(context, null, animationOf(item), item.labelRes)
        }
        if (MainTabsHelper.isMaterial) tab.inu_setMaterialTabs()
        tab.setMainTabsCompact(!MainTabsHelper.showTitles)
        tab.setSelected(item == MainTabsMenuConfig.Item.CHATS, false)
        tab.alpha = if (enabled) 1f else DISABLED_ALPHA
        return tab
    }

    private fun animationOf(item: MainTabsMenuConfig.Item): GlassTabView.TabAnimation = when (item) {
        MainTabsMenuConfig.Item.CONTACTS -> GlassTabView.TabAnimation.CONTACTS
        MainTabsMenuConfig.Item.SETTINGS -> GlassTabView.TabAnimation.SETTINGS
        MainTabsMenuConfig.Item.CALLS -> GlassTabView.TabAnimation.CALLS
        MainTabsMenuConfig.Item.FEED -> GlassTabView.TabAnimation.INU_FEED
        MainTabsMenuConfig.Item.SEARCH -> GlassTabView.TabAnimation.INU_SEARCH
        else -> GlassTabView.TabAnimation.CHATS
    }

    private fun addTab(item: MainTabsMenuConfig.Item, enabled: Boolean) {
        val tab = createTab(item, enabled).apply {
            tag = item
            isClickable = true
            setOnTouchListener { _, event -> handleTouch(item, event) }
        }
        row.addView(tab, tabLayoutParams())
    }

    private fun handleTouch(item: MainTabsMenuConfig.Item, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggedItem = item
                dragStartRawX = event.rawX
                dragging = false
                dragStartOrder = visibleOrder()
                dragStartIndex = dragStartOrder.indexOf(item)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggedItem == item && dragStartIndex >= 0) {
                    if (!dragging && abs(event.rawX - dragStartRawX) > touchSlop) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) updateDrag(item, event.rawX)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggedItem != item) return true
                if (dragging) {
                    updateDrag(item, event.rawX)
                    val newOrder = orderFromPointer(item, event.rawX)
                    resetDragViews()
                    dragging = false
                    draggedItem = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (newOrder != visibleOrder()) onReorder(newOrder)
                } else {
                    draggedItem = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragStartIndex >= 0 && item != MainTabsMenuConfig.Item.CHATS) onToggle(item)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                resetDragViews()
                dragging = false
                draggedItem = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun orderFromPointer(item: MainTabsMenuConfig.Item, rawX: Float): List<MainTabsMenuConfig.Item> {
        val result = dragStartOrder.toMutableList()
        if (dragStartIndex < 0) return result

        val location = IntArray(2)
        row.getLocationOnScreen(location)
        var target = 0
        for (index in 0 until dragStartOrder.size) {
            val view = row.getChildAt(index) ?: continue
            val center = location[0] + view.left + view.width / 2f
            if (rawX >= center) target = index
        }
        target = target.coerceIn(0, result.lastIndex)
        if (dragStartIndex != target) result.add(target, result.removeAt(dragStartIndex))
        return result
    }

    private fun updateDrag(item: MainTabsMenuConfig.Item, rawX: Float) {
        val chip = row.findViewWithTag<View>(item) ?: return
        chip.translationX = rawX - dragStartRawX
        val targetOrder = orderFromPointer(item, rawX)
        val step = dp((chipWidthDp + CHIP_GAP_DP).toFloat()).toFloat()
        targetOrder.forEachIndexed { index, targetItem ->
            val neighbor = row.findViewWithTag<View>(targetItem) ?: return@forEachIndexed
            if (neighbor !== chip) {
                val originalIndex = dragStartOrder.indexOf(targetItem)
                neighbor.translationX = (index - originalIndex) * step
            }
        }
    }

    private fun resetDragViews() {
        for (index in 0 until row.childCount) {
            row.getChildAt(index).translationX = 0f
        }
    }

    private fun visibleOrder(): List<MainTabsMenuConfig.Item> =
        if (separateSearch) order.filterNot { it == MainTabsMenuConfig.Item.SEARCH } else order

    private fun tabLayoutParams() =
        LayoutHelper.createLinear(chipWidthDp, MainTabsHelper.mainTabsBarHeight - 2 * MainTabsHelper.tabsInnerPaddingVertical, 0f, 2, 0, 2, 0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - dp(SIDE_INSET_DP * 2f)
        val chipCount = row.childCount
        if (chipCount > 0 && availableWidth > 0) {
            val separateWidth = if (separateSearch) dp(SEARCH_ZONE_WIDTH_DP.toFloat()) else 0
            val fitWidthDp = (availableWidth - separateWidth - dp(ROW_PADDING_DP * 2f)) / AndroidUtilities.density / chipCount - CHIP_GAP_DP
            val newChipWidthDp = fitWidthDp.toInt().coerceIn(MIN_CHIP_WIDTH_DP, MainTabsHelper.tabWidth)
            if (newChipWidthDp != chipWidthDp) {
                chipWidthDp = newChipWidthDp
                for (index in 0 until row.childCount) {
                    row.getChildAt(index).layoutParams = tabLayoutParams()
                }
            }
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP.toFloat()), MeasureSpec.EXACTLY),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(0f, (measuredHeight - 1).toFloat(), measuredWidth.toFloat(), (measuredHeight - 1).toFloat(), Theme.dividerPaint)
    }

    private class SearchBubble(context: Context) : FrameLayout(context) {
        init {
            background = Theme.createRoundRectDrawable(dp(SEARCH_BUTTON_SIZE_DP / 2f), Theme.getColor(Theme.key_chats_actionBackground))
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.outline_header_search)
                colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY)
            }
            addView(icon, LayoutHelper.createFrame(28, 28, Gravity.CENTER))
        }

        fun bind(enabled: Boolean) {
            alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    companion object {
        private const val DISABLED_ALPHA = 0.4f
        private const val ROW_PADDING_DP = 4
        private const val SIDE_INSET_DP = 12
        private const val CHIP_GAP_DP = 4
        private const val MIN_CHIP_WIDTH_DP = 40
        private const val SEARCH_BUTTON_SIZE_DP = 52
        private const val SEARCH_ZONE_WIDTH_DP = 64
        private const val HEIGHT_DP = 78
    }
}