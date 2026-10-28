package desu.inugram.helpers.chat

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.ReactionsContainerLayout

class MessageMenuOverlay(
    context: Context,
    private val menu: View,
    private val reactions: ReactionsContainerLayout?,
    private val listener: Listener,
) : FrameLayout(context) {
    interface Listener {
        fun contains(screenX: Float, screenY: Float): Boolean
        fun canScroll(): Boolean
        fun scroll(delta: Float)
        fun dismiss()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val position = IntArray(2)
    private var menuX = 0
    private var menuY = 0
    private var menuWidth = 1
    private var menuHeight = 1
    private var reactionsX = 0
    private var reactionsY = 0
    var reactionsWidth = 0
        private set
    private var reactionsHeight = 0
    private var pointerId = -1
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var capturing = false

    init {
        addView(menu)
        reactions?.let {
            it.setPadding(dp(4f), dp(4f), dp(4f), dp(22f))
            addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, dp(74f)))
        }
        isFocusableInTouchMode = true
        clipChildren = false
    }

    fun setMenuPosition(x: Int, y: Int, width: Int, height: Int) {
        menuX = x
        menuY = y
        menuWidth = width.coerceAtLeast(1)
        menuHeight = height.coerceAtLeast(1)
        requestLayout()
    }

    fun setReactionsPosition(x: Int, y: Int) {
        reactionsX = x
        reactionsY = y
        requestLayout()
    }

    fun measureReactions(availableWidth: Int): Int {
        val r = reactions ?: return 0
        reactionsWidth = availableWidth.coerceAtMost(r.totalWidth.coerceAtLeast(dp(48f))).coerceAtLeast(1)
        r.layoutParams.width = reactionsWidth
        r.layoutParams.height = dp(74f) + Math.round(r.topOffset)
        r.measure(
            MeasureSpec.makeMeasureSpec(reactionsWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(r.layoutParams.height, MeasureSpec.EXACTLY),
        )
        r.measureHint()
        reactionsWidth = availableWidth.coerceAtMost(reactionsWidth.coerceAtLeast(r.hintTextWidth + dp(24f))).coerceAtLeast(1)
        r.layoutParams.width = reactionsWidth
        reactionsHeight = r.layoutParams.height
        r.measure(
            MeasureSpec.makeMeasureSpec(reactionsWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(reactionsHeight, MeasureSpec.EXACTLY),
        )
        return reactionsHeight
    }

    fun setMenuTranslation(value: Float) {
        menu.translationY = value
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        menu.measure(
            MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(menuHeight, MeasureSpec.EXACTLY),
        )
        reactions?.measure(
            MeasureSpec.makeMeasureSpec(reactionsWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(reactionsHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getLocationOnScreen(position)
        var x = menuX - position[0]
        var y = menuY - position[1]
        menu.layout(x, y, x + menu.measuredWidth, y + menu.measuredHeight)
        reactions?.let {
            x = reactionsX - position[0]
            y = reactionsY - position[1]
            it.layout(x, y, x + reactionsWidth, y + reactionsHeight)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = -1
                dragging = false
                capturing = false
                downY = event.y
                lastY = event.y
                if (listener.contains(event.rawX, event.rawY)) {
                    pointerId = event.getPointerId(0)
                    capturing = true
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!capturing && listener.canScroll() && Math.abs(event.y - downY) > touchSlop) {
                    pointerId = event.getPointerId(0)
                    capturing = true
                    dragging = true
                    lastY = event.y
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (pointerId < 0) {
            if (action == MotionEvent.ACTION_DOWN) listener.dismiss()
            return true
        }
        val index = event.findPointerIndex(pointerId)
        if (index < 0) {
            pointerId = -1
            return true
        }
        val y = event.getY(index)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(y - downY) > touchSlop) dragging = true
                if (dragging) listener.scroll(y - lastY)
                lastY = y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerId = -1
                dragging = false
                capturing = false
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            listener.dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
