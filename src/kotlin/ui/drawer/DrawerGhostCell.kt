package desu.inugram.ui.drawer

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Switch
import kotlin.math.abs

class DrawerGhostCell(context: Context) : FrameLayout(context) {

    private val imageView: BackupImageView
    private val textView: TextView
    private val checkBox: Switch

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val isRTL get() = LocaleController.isRTL

    var onSwitchToggled: ((Boolean) -> Unit)? = null

    init {
        imageView = BackupImageView(context)
        imageView.setImageResource(R.drawable.inu_ghost)

        textView = TextView(context)
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        textView.typeface = AndroidUtilities.bold()
        textView.gravity = Gravity.CENTER_VERTICAL or (if (isRTL) Gravity.RIGHT else Gravity.LEFT)

        checkBox = Switch(context)
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_chats_menuBackground, Theme.key_chats_menuBackground)
        checkBox.isClickable = false
        checkBox.isFocusable = false

        val startGravity = if (isRTL) Gravity.RIGHT else Gravity.LEFT
        val endGravity = if (isRTL) Gravity.LEFT else Gravity.RIGHT
        addView(imageView, LayoutHelper.createFrame(24, 24f, startGravity or Gravity.TOP, if (isRTL) 0f else 19f, 12f, if (isRTL) 19f else 0f, 0f))
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), startGravity or Gravity.TOP, if (isRTL) 70f else 72f, 0f, if (isRTL) 72f else 70f, 0f))
        addView(checkBox, LayoutHelper.createFrame(37, 24f, endGravity or Gravity.CENTER_VERTICAL, if (isRTL) 22f else 0f, 0f, if (isRTL) 0f else 22f, 0f))

        setClipChildren(false)
    }

    fun bind(text: CharSequence, iconRes: Int) {
        textView.text = text
        if (iconRes != 0) imageView.setImageResource(iconRes)
        updateColors()
    }

    fun setChecked(checked: Boolean) {
        checkBox.setChecked(checked, false)
        updateIconColor(checked)
    }

    private fun updateIconColor(checked: Boolean) {
        if (checked) {
            imageView.setColorFilter(PorterDuffColorFilter(0xFFF20C3C.toInt(), PorterDuff.Mode.SRC_IN))
        } else {
            imageView.setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.SRC_IN))
        }
    }

    private fun updateColors() {
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
        updateIconColor(checkBox.isChecked)
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_chats_menuBackground, Theme.key_chats_menuBackground)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateColors()
    }

    private var switchDownX = -1f
    private var inSwitchZone = false

    private fun isInSwitchZone(x: Float): Boolean {
        if (checkBox.visibility != VISIBLE) return false
        return if (isRTL) x <= checkBox.right + AndroidUtilities.dp(12f)
        else x >= checkBox.left - AndroidUtilities.dp(12f)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            inSwitchZone = isInSwitchZone(ev.x)
            if (inSwitchZone) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return inSwitchZone
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inSwitchZone) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                switchDownX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - switchDownX) < touchSlop) {
                    val newState = !checkBox.isChecked
                    checkBox.setChecked(newState, true)
                    updateIconColor(newState)
                    onSwitchToggled?.invoke(newState)
                }
                inSwitchZone = false
            }
            MotionEvent.ACTION_CANCEL -> {
                inSwitchZone = false
            }
        }
        return true
    }
}
