package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.menu.MainTabsMenuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
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
    private var chipWidthDp = CHIP_WIDTH_DP
    private var draggedItem: MainTabsMenuConfig.Item? = null
    private var dragStartRawX = 0f
    private var dragging = false
    private var dragStartOrder = emptyList<MainTabsMenuConfig.Item>()
    private var dragStartIndex = -1

    init {
        setWillNotDraw(false)
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
        addChip(null, true)
        visibleOrder().forEach { item -> addChip(item, item in enabledItems) }
        if (separateSearch) {
            val search = Chip(context).apply {
                bind(R.drawable.outline_header_search, MainTabsMenuConfig.Item.SEARCH.labelRes, MainTabsMenuConfig.Item.SEARCH in enabledItems)
                setStandalone()
                isClickable = true
                setOnClickListener { onToggle(MainTabsMenuConfig.Item.SEARCH) }
            }
            group.addView(search, LayoutHelper.createLinear(SEARCH_BUTTON_SIZE_DP, SEARCH_BUTTON_SIZE_DP, 0f, SEARCH_ZONE_WIDTH_DP, 0, 0, 0, 0))
        }
        requestLayout()
    }

    private fun addChip(item: MainTabsMenuConfig.Item?, enabled: Boolean) {
        val chip = Chip(context).apply {
            bind(item?.iconRes ?: R.drawable.msg_viewchats, item?.labelRes ?: R.string.InuChats, enabled)
            if (item != null) {
                tag = item
                isClickable = true
                background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
                setOnTouchListener { _, event -> handleTouch(item, event) }
            }
        }
        row.addView(chip, chipLayoutParams())
    }

    private fun handleTouch(item: MainTabsMenuConfig.Item, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggedItem = item
                dragStartRawX = event.rawX
                dragging = false
                dragStartOrder = visibleOrder()
                dragStartIndex = dragStartOrder.indexOf(item)
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
                    if (dragStartIndex >= 0) onToggle(item)
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
            val view = row.getChildAt(index + 1) ?: continue
            val center = location[0] + view.left + view.width / 2f
            if (rawX >= center) target = index
        }
        target = target.coerceIn(0, result.lastIndex)
        if (dragStartIndex != target) result.add(target, result.removeAt(dragStartIndex))
        return result
    }

    private fun updateDrag(item: MainTabsMenuConfig.Item, rawX: Float) {
        val chip = row.findViewWithTag<Chip>(item) ?: return
        chip.translationX = rawX - dragStartRawX
        val targetOrder = orderFromPointer(item, rawX)
        val step = dp((chipWidthDp + CHIP_GAP_DP).toFloat()).toFloat()
        targetOrder.forEachIndexed { index, targetItem ->
            val neighbor = row.findViewWithTag<Chip>(targetItem) ?: return@forEachIndexed
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

    private fun chipLayoutParams() =
        LayoutHelper.createLinear(chipWidthDp, LayoutHelper.WRAP_CONTENT, 0f, 2, 0, 2, 0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val chipCount = row.childCount
        if (chipCount > 0 && availableWidth > 0) {
            val separateWidth = if (separateSearch) dp(SEARCH_ZONE_WIDTH_DP.toFloat()) else 0
            val fitWidthDp = (availableWidth - separateWidth) / AndroidUtilities.density / chipCount - CHIP_GAP_DP
            val newChipWidthDp = fitWidthDp.toInt().coerceIn(MIN_CHIP_WIDTH_DP, CHIP_WIDTH_DP)
            if (newChipWidthDp != chipWidthDp) {
                chipWidthDp = newChipWidthDp
                for (index in 0 until row.childCount) {
                    row.getChildAt(index).layoutParams = chipLayoutParams()
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

    private class Chip(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
        private val label = TextView(context).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        private var enabled = true

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon, LayoutHelper.createLinear(28, 28))
            addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 2, 0, 0))
        }

        fun bind(iconRes: Int, labelRes: Int, enabled: Boolean) {
            this.enabled = enabled
            icon.setImageResource(iconRes)
            label.text = LocaleController.getString(labelRes)
            label.visibility = if (desu.inugram.helpers.dialogs.MainTabsHelper.showTitles) VISIBLE else GONE
            val color = Theme.getColor(if (enabled) Theme.key_windowBackgroundWhiteBlackText else Theme.key_windowBackgroundWhiteGrayIcon)
            icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
            icon.alpha = if (enabled) 1f else 0.5f
            label.setTextColor(color)
            label.alpha = if (enabled) 1f else 0.5f
        }

        fun setStandalone() {
            label.visibility = GONE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = Theme.createRoundRectDrawable(dp(SEARCH_BUTTON_SIZE_DP / 2f), Theme.getColor(Theme.key_chats_actionBackground))
            icon.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY)
            icon.alpha = if (enabled) 1f else 0.5f
        }
    }

    companion object {
        private const val CHIP_WIDTH_DP = 76
        private const val CHIP_GAP_DP = 4
        private const val MIN_CHIP_WIDTH_DP = 40
        private const val SEARCH_BUTTON_SIZE_DP = 52
        private const val SEARCH_ZONE_WIDTH_DP = 64
        private const val HEIGHT_DP = 78
    }
}