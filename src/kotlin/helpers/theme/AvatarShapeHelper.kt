package desu.inugram.helpers.theme

import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities

object AvatarShapeHelper {

    private const val BASE_AVATAR_SIZE_DP = 56.0f
    private const val STORY_RADIUS_COMPENSATION = 2.5f
    private const val FORUM_RADIUS_MULTIPLIER = 42
    private const val FORUM_RADIUS_SHIFT = 6

    @JvmStatic
    @JvmOverloads
    fun getAvatarRoundRadius(
        sizeDp: Float,
        forum: Boolean = false,
        storyCompensation: Boolean = false
    ): Int {
        val corners = InuConfig.AVATAR_CORNERS.value
        val unified = InuConfig.UNIFIED_AVATAR_RADIUS.value
        return getAvatarRoundRadiusInternal(sizeDp, false, corners, forum, storyCompensation, unified)
    }

    @JvmStatic
    fun getAvatarRoundRadius(
        sizeDp: Float,
        avatarCorners: Float,
        forum: Boolean,
        storyCompensation: Boolean,
        singleCornerRadius: Boolean
    ): Int {
        return getAvatarRoundRadiusInternal(sizeDp, false, avatarCorners, forum, storyCompensation, singleCornerRadius)
    }

    @JvmStatic
    @JvmOverloads
    fun getAvatarRoundRadiusPx(
        sizePx: Float,
        forum: Boolean = false,
        storyCompensation: Boolean = false
    ): Int {
        val corners = InuConfig.AVATAR_CORNERS.value
        val unified = InuConfig.UNIFIED_AVATAR_RADIUS.value
        return getAvatarRoundRadiusInternal(sizePx, true, corners, forum, storyCompensation, unified)
    }

    @JvmStatic
    fun getAvatarRoundRadiusPx(
        sizePx: Float,
        avatarCorners: Float,
        forum: Boolean,
        storyCompensation: Boolean,
        singleCornerRadius: Boolean
    ): Int {
        return getAvatarRoundRadiusInternal(sizePx, true, avatarCorners, forum, storyCompensation, singleCornerRadius)
    }

    @JvmStatic
    fun getAvatarRoundRadiusInternal(
        size: Float,
        sizeIsPx: Boolean,
        avatarCorners: Float,
        forum: Boolean,
        storyCompensation: Boolean,
        singleCornerRadius: Boolean
    ): Int {
        if (avatarCorners == 0.0f) {
            return 1
        }
        var radius = (avatarCorners * size) / BASE_AVATAR_SIZE_DP
        if (storyCompensation) {
            radius -= STORY_RADIUS_COMPENSATION
        }
        if (!sizeIsPx) {
            radius = AndroidUtilities.dp(radius).toFloat()
        }
        if (forum && !singleCornerRadius) {
            radius = ((radius.toInt() * FORUM_RADIUS_MULTIPLIER) shr FORUM_RADIUS_SHIFT).toFloat()
        }
        return Math.ceil(radius.toDouble()).toInt().coerceAtLeast(1)
    }

    @JvmStatic
    fun getAvatarSquareness(): Float {
        return getAvatarSquareness(InuConfig.AVATAR_CORNERS.value)
    }

    @JvmStatic
    fun getAvatarSquareness(avatarCorners: Float): Float {
        val squareness = 1.0f - (avatarCorners / 28.0f)
        return squareness.coerceIn(0.0f, 1.0f)
    }

    @JvmStatic
    fun getOnlineDotOffset(dotOffset: Float, radius: Float): Float {
        return getOnlineDotOffset(dotOffset, radius, getAvatarSquareness())
    }

    @JvmStatic
    fun getOnlineDotOffset(dotOffset: Float, radius: Float, squareness: Float): Float {
        val diag = (radius / Math.sqrt(2.0)).toFloat()
        return dotOffset + (diag - dotOffset) * squareness
    }

    @JvmStatic
    fun resolveRadius(requestedRadius: Int): Int {
        if (requestedRadius <= 0) return requestedRadius
        val corners = InuConfig.AVATAR_CORNERS.value
        if (corners >= 28.0f) return requestedRadius
        if (corners <= 0.0f) return 0
        return (requestedRadius * (corners / 28.0f)).toInt()
    }
}
