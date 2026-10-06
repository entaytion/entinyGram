package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp

object WideChannelPostLayout {
    private const val MEDIA_BACKGROUND_CONTENT_INSET_DP = 8
    private const val REGULAR_BACKGROUND_CONTENT_INSET_DP = 17

    // entiny: user-adjustable via a slider in settings; how much side padding the wide-post bubble keeps
    @JvmStatic
    fun outerInsetDp(): Float = InuConfig.WIDE_CHANNEL_POSTS_INSET.value

    @JvmStatic
    fun backgroundWidth(viewportWidth: Int, leadingInset: Int, mediaBackground: Boolean): Int {
        val outerInset = outerInsetDp()
        var width = viewportWidth - leadingInset - dp(outerInset * 2)
        if (mediaBackground) width -= dp(outerInset)
        return maxOf(dp(1f), width)
    }

    @JvmStatic
    fun messageTextWidth(viewportWidth: Int, leadingInset: Int): Int {
        return maxOf(dp(1f), backgroundWidth(viewportWidth, leadingInset, false) - dp(31f))
    }

    @JvmStatic
    fun mediaContentWidthFromBackground(backgroundWidth: Int, mediaBackground: Boolean): Int {
        val contentInset = if (mediaBackground) MEDIA_BACKGROUND_CONTENT_INSET_DP else REGULAR_BACKGROUND_CONTENT_INSET_DP
        return maxOf(dp(1f), backgroundWidth - dp(contentInset.toFloat()))
    }

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
        val contentWidth = maxOf(dp(1f), groupedMediaViewportWidth - dp(outerInsetDp() * 2))
        return (contentWidth * 1000f / groupedMediaViewportWidth).let {
            maxOf(1, minOf(1000, Math.round(it)))
        }
    }
}
