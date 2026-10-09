package desu.inugram.ui.appicons

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LauncherIconController.LauncherIcon

class AppIconRowCell(context: Context, private val fragment: BaseFragment) : FrameLayout(context) {
    private val preview = IconPreviewView(context, 0)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val arrow = ImageView(context)
    private var shown: LauncherIcon? = null

    init {
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        foreground = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)

        addView(preview, LayoutHelper.createFrame(44f, 44f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 16f, 0f, 0f, 0f))

        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 2, 0, 0))
        addView(
            column,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.CENTER_VERTICAL, 74f, 0f, 48f, 0f),
        )

        arrow.setImageResource(R.drawable.msg_arrowright)
        arrow.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN)
        addView(arrow, LayoutHelper.createFrame(24f, 24f, Gravity.RIGHT or Gravity.CENTER_VERTICAL, 0f, 0f, 14f, 0f))

        setOnClickListener { fragment.presentFragment(AppIconsActivity()) }
        refresh()
    }

    private fun refresh() {
        val icon = AppIcons.current()
        if (icon == shown) return
        shown = icon
        preview.setIcon(icon, false)
        title.text = AppIcons.title(icon)
        val description = AppIcons.description(icon)
        subtitle.text = description ?: ""
        subtitle.visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72f), MeasureSpec.EXACTLY))
    }

    override fun dispatchDraw(canvas: Canvas) {
        refresh()
        super.dispatchDraw(canvas)
    }
}
