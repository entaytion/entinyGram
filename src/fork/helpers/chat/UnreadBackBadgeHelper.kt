package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.MessagesStorage
import org.telegram.ui.ActionBar.ActionBar

object UnreadBackBadgeHelper {

    @JvmStatic
    fun update(actionBar: ActionBar?, account: Int) {
        if (actionBar == null) return
        val badge = actionBar.backButtonImageView ?: return
        if (!InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.value) {
            actionBar.setIOSUnreadBadgeAvailable(false)
            return
        }
        val count = MessagesStorage.getInstance(account).mainUnreadCount
        actionBar.setIOSUnreadBadgeAvailable(count > 0)
        badge.checkUnreadView(count)
        actionBar.updateBackPillWidth(true)
    }
}
