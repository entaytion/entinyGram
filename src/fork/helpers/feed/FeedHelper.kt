package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import java.lang.ref.WeakReference
import org.telegram.ui.Components.glass.GlassTabView

object FeedHelper {
    private var counterTab: WeakReference<GlassTabView>? = null

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.FEED_ENABLED.value

    @JvmStatic
    fun updateUnreadCounter(account: Int, tab: GlassTabView?, animated: Boolean) {
        if (tab == null) return
        counterTab = WeakReference(tab)
        tab.setCounter(unreadBadge(account), false, animated)
    }

    fun unreadBadge(account: Int): String? {
        if (!isEnabled() || !InuConfig.FEED_UNREAD_COUNTER.value || InuConfig.BOTTOM_TABS_HIDE_BADGES.value) return null
        val count = FeedController.getInstance(account).getUnreadCount()
        return if (count > 0) LocaleController.formatNumber(count.toLong(), ',') else null
    }

    // entiny: called after feed reads are applied, the main tabs only redraw the badge on their own events
    @JvmStatic
    fun refreshUnreadCounter(account: Int) {
        val tab = counterTab?.get() ?: return
        updateUnreadCounter(account, tab, true)
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        InuConfig.FEED_ENABLED.value = enabled
    }
}
