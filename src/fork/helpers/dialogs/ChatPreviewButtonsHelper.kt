package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.BaseFragment

object ChatPreviewButtonsHelper {
    @JvmStatic
    fun addItems(fragment: BaseFragment, menu: ActionBarPopupWindow.ActionBarPopupWindowLayout) {
        if (!InuConfig.CHAT_PREVIEW_BUTTONS.value) return
        val context = fragment.parentActivity ?: return
        val open = ActionBarMenuSubItem(context, false, false)
        open.setTextAndIcon(LocaleController.getString(R.string.AccDescrOpenChat), R.drawable.msg_openin)
        open.setMinimumWidth(160)
        open.setOnClickListener { fragment.parentLayout?.expandPreviewFragment() }
        menu.addView(open)
        val close = ActionBarMenuSubItem(context, false, true)
        close.setTextAndIcon(LocaleController.getString(R.string.Close), R.drawable.msg_close)
        close.setMinimumWidth(160)
        close.setOnClickListener { fragment.finishPreviewFragment() }
        menu.addView(close)
    }
}
