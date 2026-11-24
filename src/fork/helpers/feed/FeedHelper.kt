package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.ui.Components.glass.GlassTabView

object FeedHelper {
    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.FEED_ENABLED.value

    @JvmStatic
    fun updateUnreadCounter(account: Int, tab: GlassTabView?, animated: Boolean) {
        if (tab == null) return
        val count = if (isEnabled() && InuConfig.FEED_UNREAD_COUNTER.value) FeedController.getInstance(account).getUnreadCount() else 0
        tab.setCounter(if (count > 0) LocaleController.formatNumber(count, ',') else null, false, animated)
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        InuConfig.FEED_ENABLED.value = enabled
    }
}
