package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SlideChooseView

@SuppressLint("ViewConstructor")
class RestrictedContentCell(
    context: Context,
    private val onChanged: (Int) -> Unit,
) : LinearLayout(context) {

    private val chooser = SlideChooseView(context)
    private val info = TextView(context)

    init {
        orientation = VERTICAL
        setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(14f))

        val title = TextView(context).apply {
            text = LocaleController.getString(R.string.InuRestrictedContentMode)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        }
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21f, 0f, 21f, 0f))

        chooser.setOptions(
            InuConfig.RESTRICTED_CONTENT_MODE.value,
            LocaleController.getString(R.string.InuRestrictedContentOff),
            LocaleController.getString(R.string.InuRestrictedContentCopy),
            LocaleController.getString(R.string.InuRestrictedContentFull),
        )
        chooser.setCallback(object : SlideChooseView.Callback {
            override fun onOptionSelected(index: Int) {
                if (InuConfig.RESTRICTED_CONTENT_MODE.value == index) return
                InuConfig.RESTRICTED_CONTENT_MODE.value = index
                refresh()
                onChanged(index)
            }
        })
        addView(chooser, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))

        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21f, 4f, 21f, 0f))
        refresh()
    }

    private fun refresh() {
        info.text = LocaleController.getString(
            when (InuConfig.RESTRICTED_CONTENT_MODE.value) {
                InuConfig.RestrictedContentModeItem.COPY -> R.string.InuAllowCopyRestrictedInfo
                InuConfig.RestrictedContentModeItem.FULL -> R.string.InuAllowForwardRestrictedInfo
                else -> R.string.InuRestrictedContentOffInfo
            }
        )
    }
}
