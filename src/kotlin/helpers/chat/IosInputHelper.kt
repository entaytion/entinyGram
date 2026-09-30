package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import java.util.WeakHashMap

object IosInputHelper {
    private const val RIGHT_CLUSTER_GAP_DP = 4

    private val slowModePill = WeakHashMap<ChatActivityEnterView, Int>()

    @JvmStatic
    fun isButtonPlacement(): Boolean = InuConfig.IOS_INPUT_BUTTON_PLACEMENT.value

    @JvmStatic
    fun isAppearance(): Boolean = InuConfig.IOS_INPUT_APPEARANCE.value

    @JvmStatic
    fun isCompact(): Boolean = InuConfig.COMPACT_INPUT_SIZE.value && isAppearance()

    @JvmStatic
    fun setInputMenuButtonIconInset(view: View, hasRightIcon: Boolean) {
        val rightInset = if (hasRightIcon) AndroidUtilities.dp(RIGHT_CLUSTER_GAP_DP.toFloat()) else 0
        if (view.paddingRight != rightInset) {
            view.setPadding(view.paddingLeft, view.paddingTop, rightInset, view.paddingBottom)
        }
    }

    @JvmStatic
    fun getInputMenuButtonOffsetDp(hasRightIcon: Boolean, defaultHeightDp: Int, gapDp: Int): Int =
        if (hasRightIcon) -(defaultHeightDp + gapDp) else 0

    @JvmStatic
    fun drawBubble(canvas: Canvas, bubble: BlurredBackgroundDrawable?, view: View?) {
        if (bubble == null || view == null || view.visibility != View.VISIBLE || view.alpha <= 0) return
        drawFollowing(canvas, bubble, view, view.left, view.top, view.right, view.bottom)
    }

    @JvmStatic
    fun drawBubbleSquare(canvas: Canvas, bubble: BlurredBackgroundDrawable?, view: View?, sizePx: Int) {
        if (bubble == null || view == null || view.visibility != View.VISIBLE || view.alpha <= 0) return
        drawFollowing(canvas, bubble, view, view.right - sizePx, view.bottom - sizePx, view.right, view.bottom)
    }

    private fun drawFollowing(canvas: Canvas, bubble: BlurredBackgroundDrawable, view: View, l: Int, t: Int, r: Int, b: Int) {
        val moved = view.translationX != 0f || view.translationY != 0f || view.scaleX != 1f || view.scaleY != 1f
        if (moved) {
            canvas.save()
            canvas.translate(view.translationX, view.translationY)
            if (view.scaleX != 1f || view.scaleY != 1f) {
                canvas.scale(view.scaleX, view.scaleY, (l + r) / 2f, (t + b) / 2f)
            }
        }
        bubble.setBounds(l, t, r, b)
        bubble.alpha = (255 * view.alpha).toInt()
        bubble.draw(canvas)
        if (moved) canvas.restore()
    }

    @JvmStatic
    fun getTopViewGapDp(): Int = if (!isAppearance()) 0 else (if (isCompact()) 4 else 8)

    @JvmStatic
    fun updateSlowModeContainer(enterView: ChatActivityEnterView, visible: Boolean, isPremiumMode: Boolean) {
        val container = enterView.messageEditTextContainer ?: return
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetMarginPx = if (visible && (isAppearance() || isButtonPlacement())) {
            val wanted = AndroidUtilities.dp(14f) + enterView.slowModePillWidth
            // entiny: the countdown text width changes every second; only ever grow so the field does not twitch
            val stable = maxOf(wanted, slowModePill[enterView] ?: 0)
            slowModePill[enterView] = stable
            stable
        } else {
            slowModePill.remove(enterView)
            AndroidUtilities.dp(ChatActivityEnterView.DEFAULT_HEIGHT.toFloat())
        }
        if (lp.rightMargin != targetMarginPx) {
            lp.rightMargin = targetMarginPx
            container.layoutParams = lp
            enterView.textFieldContainer?.invalidate()
        }
    }
}
