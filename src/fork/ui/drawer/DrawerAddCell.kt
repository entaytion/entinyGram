package desu.inugram.ui.drawer

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.LayoutHelper
import desu.inugram.helpers.dialogs.DrawerM3SectionsHelper

class DrawerAddCell(context: Context) : FrameLayout(context) {

    private val textView: TextView

    init {
        val isRTL = LocaleController.isRTL
        textView = TextView(context)
        DrawerM3SectionsHelper.styleMenuLabel(textView, 15f, AndroidUtilities.bold())
        textView.setLines(1)
        textView.maxLines = 1
        textView.isSingleLine = true
        textView.gravity = (if (isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        textView.compoundDrawablePadding = AndroidUtilities.dp(13f)
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), (if (isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP, if (isRTL) 16f else 19f, 0f, if (isRTL) 19f else 16f, 0f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(DrawerM3SectionsHelper.menuItemHeight(), MeasureSpec.EXACTLY)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind()
    }

    fun bind() {
        DrawerM3SectionsHelper.styleMenuLabel(textView, 15f, AndroidUtilities.bold())
        textView.text = LocaleController.getString(R.string.AddAccount)
        val drawable = context.getDrawable(R.drawable.msg_add)
        drawable?.colorFilter = PorterDuffColorFilter(DrawerM3SectionsHelper.menuIconColor(), PorterDuff.Mode.MULTIPLY)
        textView.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }
}
