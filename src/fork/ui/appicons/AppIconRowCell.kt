package desu.inugram.ui.appicons

import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LauncherIconController.LauncherIcon

class AppIconRowCell(context: Context, private val fragment: BaseFragment) : FrameLayout(context) {
    private val titleView = TextView(context)
    private val badgeLayout = LinearLayout(context)
    private val preview = IconPreviewView(context, 0)
    private val badgeText = TextView(context)
    private var shown: LauncherIcon? = null

    init {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50f))
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        foreground = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)

        val isRtl = LocaleController.isRTL
        val titleGravity = (if (isRtl) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        val badgeGravity = (if (isRtl) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL

        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        titleView.text = LocaleController.getString(R.string.AppIcon)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        addView(
            titleView,
            LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT.toFloat(),
                LayoutHelper.WRAP_CONTENT.toFloat(),
                titleGravity,
                if (isRtl) 0f else 21f,
                0f,
                if (isRtl) 21f else 0f,
                0f
            )
        )

        badgeLayout.orientation = LinearLayout.HORIZONTAL
        badgeLayout.gravity = Gravity.CENTER_VERTICAL
        badgeLayout.setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f))

        badgeLayout.addView(preview, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL))

        badgeText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        badgeText.typeface = AndroidUtilities.bold()
        badgeText.maxLines = 1
        badgeText.ellipsize = TextUtils.TruncateAt.END
        badgeLayout.addView(
            badgeText,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0)
        )

        addView(
            badgeLayout,
            LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT.toFloat(),
                LayoutHelper.WRAP_CONTENT.toFloat(),
                badgeGravity,
                if (isRtl) 16f else 0f,
                0f,
                if (isRtl) 0f else 16f,
                0f
            )
        )

        setOnClickListener { fragment.presentFragment(AppIconsActivity()) }
        refresh()
    }

    private fun refresh() {
        val icon = AppIcons.current()
        if (icon != shown) {
            shown = icon
            preview.setIcon(icon, false)
            badgeText.text = AppIcons.title(icon)
        }
        val accentColor = Theme.getColor(Theme.key_featuredStickers_addButton)
        badgeLayout.background = Theme.createRoundRectDrawable(
            AndroidUtilities.dp(8f),
            Theme.multAlpha(accentColor, 0.12f)
        )
        badgeText.setTextColor(accentColor)
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = AndroidUtilities.dp(50f)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        refresh()
        super.dispatchDraw(canvas)
    }
}
