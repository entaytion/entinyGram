package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp

/**
 * Width math for "wide" channel posts (InuConfig.WIDE_CHANNEL_POSTS / WIDE_FEED_POSTS) -- ported
 * from exteraless (https://github.com/exteraless/exteraless), app/exteraless/chats/WideChannelPostLayout.java.
 * Pure math, no view/state dependencies, so it ports as-is.
 */
object WideChannelPostLayout {
    private const val OUTER_INSET_DP = 9
    private const val MEDIA_BACKGROUND_CONTENT_INSET_DP = 8
    private const val REGULAR_BACKGROUND_CONTENT_INSET_DP = 17

    @JvmStatic
    fun backgroundWidth(viewportWidth: Int, leadingInset: Int, mediaBackground: Boolean): Int {
        var width = viewportWidth - leadingInset - dp((OUTER_INSET_DP * 2).toFloat())
        if (mediaBackground) width -= dp(OUTER_INSET_DP.toFloat())
        return maxOf(dp(1f), width)
    }

    @JvmStatic
    fun messageTextWidth(viewportWidth: Int, leadingInset: Int): Int {
        return maxOf(dp(1f), backgroundWidth(viewportWidth, leadingInset, false) - dp(31f))
    }

    /** Content width given an already-computed background width. */
    @JvmStatic
    fun mediaContentWidthFromBackground(backgroundWidth: Int, mediaBackground: Boolean): Int {
        val contentInset = if (mediaBackground) MEDIA_BACKGROUND_CONTENT_INSET_DP else REGULAR_BACKGROUND_CONTENT_INSET_DP
        return maxOf(dp(1f), backgroundWidth - dp(contentInset.toFloat()))
    }

    /** Content width computed from the viewport directly (mediaBackground always false here, matching upstream). */
    @JvmStatic
    fun mediaContentWidth(viewportWidth: Int, leadingInset: Int): Int {
        return mediaContentWidthFromBackground(backgroundWidth(viewportWidth, leadingInset, false), false)
    }

    @JvmStatic
    fun groupedMediaViewportWidth(viewportWidth: Int, leadingInset: Int): Int {
        return maxOf(dp(1f), viewportWidth - leadingInset)
    }

    @JvmStatic
    fun groupedMediaContentSpanCount(): Int {
        val viewportWidth = if (AndroidUtilities.isTablet()) AndroidUtilities.getMinTabletSide() else AndroidUtilities.displaySize.x
        return groupedMediaContentSpanCount(viewportWidth)
    }

    @JvmStatic
    fun groupedMediaContentSpanCount(groupedMediaViewportWidth: Int): Int {
        if (groupedMediaViewportWidth <= 0) return 1000
        val contentWidth = maxOf(dp(1f), groupedMediaViewportWidth - dp((OUTER_INSET_DP * 2).toFloat()))
        return (contentWidth * 1000f / groupedMediaViewportWidth).let {
            maxOf(1, minOf(1000, Math.round(it)))
        }
    }
}
