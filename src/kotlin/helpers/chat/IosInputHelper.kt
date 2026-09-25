package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable

object IosInputHelper {
    const val IOS_LEFT_EDGE_MARGIN_DP = 0
    const val CAPSULE_INSET_DP = 4
    const val NO_ICON_TEXT_INSET_DP = 8
    const val COMPACT_TEXT_INSET_DP = 4
    const val RIGHT_CLUSTER_GAP_DP = 4
    const val IOS_BUBBLE_RADIUS_DP = 22
    const val SENDER_SELECT_WIDTH_DP = 36
    const val BOT_BUTTON_WIDTH_DP = 36
    const val BOT_COMMANDS_MIN_WIDTH_DP = 40

    @JvmStatic
    fun isButtonPlacement(): Boolean = InuConfig.IOS_INPUT_BUTTON_PLACEMENT.value

    @JvmStatic
    fun isAppearance(): Boolean = InuConfig.IOS_INPUT_APPEARANCE.value

    @JvmStatic
    fun isCompact(): Boolean = InuConfig.COMPACT_INPUT_SIZE.value && isAppearance()

    @JvmStatic
    fun getGapDp(): Int = if (isCompact()) 2 else 8

    @JvmStatic
    fun getAttachGravity(): Int = if (isButtonPlacement()) Gravity.BOTTOM or Gravity.LEFT else Gravity.BOTTOM or Gravity.RIGHT

    @JvmStatic
    fun getFieldLeftDp(defaultHeightDp: Int): Int = if (isButtonPlacement()) (IOS_LEFT_EDGE_MARGIN_DP + defaultHeightDp + getGapDp()) else 52

    @JvmStatic
    fun getAttachLayoutRightDp(defaultHeightDp: Int): Int = if (isButtonPlacement()) 0 else defaultHeightDp

    @JvmStatic
    fun getEmojiGravity(): Int = if (isButtonPlacement()) Gravity.BOTTOM or Gravity.RIGHT else Gravity.BOTTOM or Gravity.LEFT

    @JvmStatic
    fun getFieldRightDp(isChat: Boolean, defaultHeightDp: Int): Int = if (isButtonPlacement()) (defaultHeightDp + getGapDp() * 2) else (if (isChat) 50 else 2)

    @JvmStatic
    fun getEmojiLeftDp(): Int = if (isButtonPlacement()) 0 else 2

    @JvmStatic
    fun getEmojiRightDp(): Int = if (isButtonPlacement()) getGapDp() else 0

    @JvmStatic
    fun getAiButtonGravity(): Int = if (isButtonPlacement()) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT

    @JvmStatic
    fun getAiButtonRightMarginDp(defaultHeightDp: Int): Int = if (isButtonPlacement()) (defaultHeightDp + getGapDp()) else 0

    @JvmStatic
    fun drawBubble(canvas: Canvas, bubble: BlurredBackgroundDrawable?, view: View?) {
        if (bubble == null || view == null || view.visibility != View.VISIBLE || view.alpha <= 0) return
        bubble.setBounds(view.left, view.top, view.right, view.bottom)
        bubble.alpha = (255 * view.alpha).toInt()
        bubble.draw(canvas)
    }

    @JvmStatic
    fun drawBubbleSquare(canvas: Canvas, bubble: BlurredBackgroundDrawable?, view: View?, sizePx: Int) {
        if (bubble == null || view == null || view.visibility != View.VISIBLE || view.alpha <= 0) return
        bubble.setBounds(view.right - sizePx, view.bottom - sizePx, view.right, view.bottom)
        bubble.alpha = (255 * view.alpha).toInt()
        bubble.draw(canvas)
    }

    @JvmStatic
    fun resolveVoiceIconColor(isMenuState: Boolean, forbidden: Boolean, resourcesProvider: Theme.ResourcesProvider?): Int {
        return if (!isAppearance() && (forbidden || isMenuState)) {
            Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider)
        } else {
            ActionButtonStyle.resolveIconColor(resourcesProvider)
        }
    }

    @JvmStatic
    fun getTopViewGapDp(): Int = if (!isAppearance()) 0 else (if (isCompact()) 4 else 8)
}
