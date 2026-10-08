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
import kotlin.math.roundToInt

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
    private val chipViews = LinkedHashMap<MainTabsMenuConfig.Item, Chip>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var order: List<MainTabsMenuConfig.Item> = emptyList()
    private var dragOrder: List<MainTabsMenuConfig.Item> = emptyList()
    private var dragging = false
    private var dragFromIndex = -1
    private var dragStartRawX = 0f
    private var separateSearch = false

    private var chipWidthDp = CHIP_WIDTH_DP

    init {
        setWillNotDraw(false)
        group.addView(row, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        addView(group, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setState(order: List<MainTabsMenuConfig.Item>, enabledItems: Set<MainTabsMenuConfig.Item>, separateSearch: Boolean) {
        this.order = order
        this.separateSearch = separateSearch
        this.dragOrder = visibleOrder()
        group.removeAllViews()
        group.addView(row, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        row.removeAllViews()
        chipViews.clear()
        addChip(null, true)
        for (item in visibleOrder()) addChip(item, item in enabledItems)
        if (separateSearch) {
            val searchChip = Chip(context).apply {
                bind(R.drawable.outline_header_search, MainTabsMenuConfig.Item.SEARCH.labelRes, MainTabsMenuConfig.Item.SEARCH in enabledItems)
                setStandalone()
                isClickable = true
                foreground = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
                setOnClickListener { onToggle(MainTabsMenuConfig.Item.SEARCH) }
            }
            group.addView(
                searchChip,
                LayoutHelper.createLinear(
                    SEARCH_BUTTON_SIZE_DP,
                    SEARCH_BUTTON_SIZE_DP,
                    0f, SEARCH_ZONE_WIDTH_DP, 0, 0, 0, 0,
                ),
            )
        }
        requestLayout()
    }

    private fun addChip(item: MainTabsMenuConfig.Item?, enabled: Boolean) {
        val chip = Chip(context)
        chip.bind(
            iconRes = item?.iconRes ?: R.drawable.msg_viewchats,
            labelRes = item?.labelRes ?: R.string.InuChats,
            enabled = enabled,
        )
        if (item != null) {
            chipViews[item] = chip
            chip.isClickable = true
            chip.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            chip.setOnTouchListener { _, ev -> handleTouch(item, chip, ev) }
        }
        row.addView(chip, LayoutHelper.createLinear(chipWidthDp, LayoutHelper.WRAP_CONTENT, 0f, 2, 0, 2, 0))
    }

    private fun handleTouch(item: MainTabsMenuConfig.Item, chip: Chip, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = ev.rawX
                dragFromIndex = visibleOrder().indexOf(item)
                dragOrder = visibleOrder()
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - dragStartRawX
                if (!dragging && abs(dx) > touchSlop) {
                    dragging = true
                    chip.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start()
                    chip.elevation = dp(4f).toFloat()
                    chip.bringToFront()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    chip.translationX = dx
                    checkSwap(item, dx)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    chipViews.values.forEach { if (it !== chip) it.translationX = 0f }
                    chip.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(150)
                        .withEndAction { chip.elevation = 0f }.start()
                    if (dragOrder != visibleOrder()) onReorder(fullOrder(dragOrder))
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    onToggle(item)
                }
                dragging = false
            }
        }
        return true
    }

    private fun checkSwap(item: MainTabsMenuConfig.Item, dx: Float) {
        val slotPx = dp((chipWidthDp + CHIP_GAP_DP).toFloat())
        val curIdx = dragOrder.indexOf(item)
        val targetIdx = (dragFromIndex + (dx / slotPx).roundToInt()).coerceIn(0, dragOrder.size - 1)
        if (targetIdx == curIdx) return
        val mutable = dragOrder.toMutableList()
        mutable.removeAt(curIdx)
        mutable.add(targetIdx, item)
        dragOrder = mutable
        for ((idx, other) in dragOrder.withIndex()) {
            if (other == item) continue
            val chip = chipViews[other] ?: continue
            val originalIdx = visibleOrder().indexOf(other)
            chip.animate().translationX(((idx - originalIdx) * slotPx).toFloat()).setDuration(120).start()
        }
    }

    private fun visibleOrder(): List<MainTabsMenuConfig.Item> =
        if (separateSearch) order.filterNot { it == MainTabsMenuConfig.Item.SEARCH } else order

    private fun fullOrder(visible: List<MainTabsMenuConfig.Item>): List<MainTabsMenuConfig.Item> {
        if (!separateSearch) return visible
        val searchIndex = order.indexOf(MainTabsMenuConfig.Item.SEARCH)
        if (searchIndex < 0) return visible
        return visible.toMutableList().apply {
            add(searchIndex.coerceIn(0, size), MainTabsMenuConfig.Item.SEARCH)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val chipCount = row.childCount
        if (chipCount > 0 && availableWidth > 0) {
            val separateWidth = if (separateSearch) dp(SEARCH_ZONE_WIDTH_DP.toFloat()) else 0
            val fitWidthDp = (availableWidth - separateWidth) / AndroidUtilities.density / chipCount - CHIP_GAP_DP
            val newChipWidthDp = fitWidthDp.toInt().coerceIn(MIN_CHIP_WIDTH_DP, CHIP_WIDTH_DP)
            if (newChipWidthDp != chipWidthDp) {
                chipWidthDp = newChipWidthDp
                for (i in 0 until row.childCount) {
                    (row.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.width = dp(chipWidthDp.toFloat())
                }
            }
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP.toFloat()), MeasureSpec.EXACTLY)
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
            // entiny: accent FAB colors so Monet keeps the circle tinted instead of matching the bar
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
