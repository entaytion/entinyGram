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
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live, directly-interactive preview of the bottom tab bar: tap a tab to enable/disable it
 * (dims to 50% like a disabled state, matching what the real bar does once restarted — see
 * `MainTabsHelper.isEnabled`/`visualOrder`), drag it to reorder. Uses touch listeners
 * (not click listeners) on the chips — same mechanism [MenuOrderRow]'s drag handle already uses
 * reliably inside this RecyclerView, unlike a plain click listener which can get shadowed by the
 * list's own item-click dispatch.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MainTabsPreviewCell(
    context: Context,
    private val onToggle: (MainTabsMenuConfig.Item) -> Unit,
    private val onReorder: (List<MainTabsMenuConfig.Item>) -> Unit,
) : FrameLayout(context) {

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val chipViews = LinkedHashMap<MainTabsMenuConfig.Item, Chip>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var order: List<MainTabsMenuConfig.Item> = emptyList()
    private var dragOrder: List<MainTabsMenuConfig.Item> = emptyList()
    private var dragging = false
    private var dragFromIndex = -1
    private var dragStartRawX = 0f

    init {
        setWillNotDraw(false)
        addView(row, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
    }

    /** [order] excludes Chats — it's fixed, always first, non-interactive. */
    fun setState(order: List<MainTabsMenuConfig.Item>, enabledItems: Set<MainTabsMenuConfig.Item>) {
        this.order = order
        this.dragOrder = order
        row.removeAllViews()
        chipViews.clear()
        addChip(null, true)
        for (item in order) addChip(item, item in enabledItems)
        requestLayout()
    }

    private fun addChip(item: MainTabsMenuConfig.Item?, enabled: Boolean) {
        val chip = Chip(context)
        chip.bind(
            iconRes = item?.iconRes ?: R.drawable.msg_viewchats,
            labelRes = item?.labelRes ?: R.string.Chats,
            enabled = enabled,
        )
        if (item != null) {
            chipViews[item] = chip
            chip.isClickable = true
            chip.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            chip.setOnTouchListener { _, ev -> handleTouch(item, chip, ev) }
        }
        row.addView(chip, LayoutHelper.createLinear(CHIP_WIDTH_DP, LayoutHelper.WRAP_CONTENT, 0f, 2, 0, 2, 0))
    }

    private fun handleTouch(item: MainTabsMenuConfig.Item, chip: Chip, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = ev.rawX
                dragFromIndex = order.indexOf(item)
                dragOrder = order
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
                    chip.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(150)
                        .withEndAction { chip.elevation = 0f }.start()
                    if (dragOrder != order) onReorder(dragOrder)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    onToggle(item)
                }
                dragging = false
            }
        }
        return true
    }

    /** shifts [item] to whichever slot the finger has crossed into, sliding the displaced chips out of the way */
    private fun checkSwap(item: MainTabsMenuConfig.Item, dx: Float) {
        val slotPx = dp(SLOT_WIDTH_DP.toFloat())
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
            val originalIdx = order.indexOf(other)
            chip.animate().translationX(((idx - originalIdx) * slotPx).toFloat()).setDuration(120).start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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
        }
        private var enabled = true

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon, LayoutHelper.createLinear(28, 28))
            addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 2, 0, 0))
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
    }

    companion object {
        private const val CHIP_WIDTH_DP = 64
        private const val SLOT_WIDTH_DP = CHIP_WIDTH_DP + 4
        private const val HEIGHT_DP = 78
    }
}
