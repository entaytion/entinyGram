package desu.inugram.helpers.feed

import desu.inugram.InuConfig

object FeedHelper {
    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.FEED_ENABLED.value

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        InuConfig.FEED_ENABLED.value = enabled
        if (!enabled) FeedController.releaseInactive()
    }
}
