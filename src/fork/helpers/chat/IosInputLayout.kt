package desu.inugram.helpers.chat

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.Components.ChatActivityEnterView
import kotlin.math.max
import kotlin.math.roundToInt

object IosInputLayout {
    const val CAPSULE_INSET_DP = 4
    const val NO_ICON_TEXT_INSET_DP = 8
    const val COMPACT_TEXT_INSET_DP = 4
    const val SENDER_SELECT_WIDTH_DP = 36
    const val BOT_BUTTON_WIDTH_DP = 36
    const val AI_BUTTON_SIZE_REDUCTION_DP = 10
    private const val BOT_COMMANDS_MIN_WIDTH_DP = 40

    @JvmStatic
    fun gapDp(): Int = if (IosInputHelper.isCompact()) 2 else 8

    @JvmStatic
    fun measureFieldLeft(v: ChatActivityEnterView, gapDp: Int, deleteRichDraftButton: View?): Int {
        if (IosInputHelper.isButtonPlacement()) return measurePlacementLeft(v, gapDp)
        if (!IosInputHelper.isAppearance()) return 0
        val height = ChatActivityEnterView.DEFAULT_HEIGHT
        var cursorDp = height + gapDp + CAPSULE_INSET_DP
        var richDp = cursorDp
        val bot = v.botCommandsMenuButton
        val sender = v.senderSelectView
        if (bot != null && bot.tag != null) {
            bot.leftMarginDp(cursorDp)
            cursorDp += pxToDp(bot.measuredWidth) + textInsetDp()
            richDp = cursorDp - 8
        } else if (sender != null && sender.visibility == View.VISIBLE) {
            sender.leftMarginDp(cursorDp)
            cursorDp += SENDER_SELECT_WIDTH_DP + gapDp
            richDp = cursorDp
        } else {
            cursorDp += NO_ICON_TEXT_INSET_DP
        }
        v.emojiButton?.leftMarginDp(0)
        deleteRichDraftButton?.leftMarginDp(0)
        v.messageEditText?.leftMarginDp(cursorDp)
        v.richDraftPreview?.leftMarginDp(richDp)
        return 0
    }

    private fun measurePlacementLeft(v: ChatActivityEnterView, gapDp: Int): Int {
        val field = v.messageEditText ?: return 0
        val appearance = IosInputHelper.isAppearance()
        val height = ChatActivityEnterView.DEFAULT_HEIGHT
        val attach = v.attachButton
        var cursorDp = 0
        var hasLeftAttach = false
        if (attach != null && attach.parent === v.messageEditTextContainer && attach.visibility == View.VISIBLE && attach.alpha > 0) {
            attach.leftMarginDp(0)
            cursorDp += height + gapDp
            hasLeftAttach = true
        } else if (v.editingMessageObject != null) {
            cursorDp += gapDp
        } else if (!appearance) {
            cursorDp += NO_ICON_TEXT_INSET_DP
        }
        if (appearance && !(v.isStories && hasLeftAttach)) {
            cursorDp += CAPSULE_INSET_DP
        }
        val sender = v.senderSelectView
        val hasSender = sender != null && sender.visibility == View.VISIBLE
        if (hasSender) {
            sender!!.leftMarginDp(cursorDp)
            cursorDp += SENDER_SELECT_WIDTH_DP + gapDp
        }
        var groupWidthDp = 0
        var nearWidthDp = 0
        val group = v.attachLayout
        if (group != null && group.visibility == View.VISIBLE) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility == View.VISIBLE && child.alpha > 0) {
                    groupWidthDp += if (child === v.botButton) BOT_BUTTON_WIDTH_DP else height
                }
            }
            if (groupWidthDp > 0 && IosInputHelper.isCommandsNearStickers()) {
                nearWidthDp = groupWidthDp
                groupWidthDp = 0
            }
            placeAttachLayout(v, group, nearWidthDp > 0, gapDp)
            if (groupWidthDp > 0) {
                group.translationX = 0f
                group.leftMarginDp(cursorDp)
                cursorDp += groupWidthDp + gapDp
            }
        }
        val bot = v.botCommandsMenuButton
        val hasBot = bot != null && bot.visibility == View.VISIBLE
        if (hasBot) {
            val botWidthDp = if (bot!!.measuredWidth > 0) pxToDp(bot.measuredWidth) else BOT_COMMANDS_MIN_WIDTH_DP
            bot.leftMarginDp(cursorDp)
            cursorDp += botWidthDp + textInsetDp()
        }
        if (appearance && groupWidthDp == 0 && !hasSender && !hasBot) {
            cursorDp += NO_ICON_TEXT_INSET_DP
        }
        field.leftMarginDp(cursorDp)
        v.richDraftPreview?.leftMarginDp(cursorDp - 8)
        return nearWidthDp
    }

    private fun placeAttachLayout(v: ChatActivityEnterView, group: View, near: Boolean, gapDp: Int) {
        val lp = group.layoutParams as FrameLayout.LayoutParams
        val height = ChatActivityEnterView.DEFAULT_HEIGHT
        val gravity = Gravity.BOTTOM or if (near) Gravity.RIGHT else Gravity.LEFT
        val right = if (near) dp((gapDp + height + if (isScheduledReserved(v)) height else 0).toFloat()) else 0
        val left = if (near) 0 else lp.leftMargin
        if (lp.gravity != gravity || lp.rightMargin != right || lp.leftMargin != left) {
            lp.gravity = gravity
            lp.rightMargin = right
            lp.leftMargin = left
            group.layoutParams = lp
        }
    }

    @JvmStatic
    fun updateFieldRight(v: ChatActivityEnterView, gapDp: Int, nearWidthDp: Int, sendButtonWidthPx: Int) {
        val field = v.messageEditText ?: return
        val height = ChatActivityEnterView.DEFAULT_HEIGHT
        val reserve = if (isScheduledReserved(v)) height else 0
        val emoji = v.emojiButton
        val hasInsideRightIcon = emoji != null && emoji.visibility == View.VISIBLE && emoji.alpha > 0
        var rightDp = height + gapDp * 2
        if (IosInputHelper.isAppearance() && !hasInsideRightIcon) {
            rightDp = height + gapDp + NO_ICON_TEXT_INSET_DP
        } else if (ChatInputMenuHelper.isMenuButtonVisible(v)) {
            rightDp += height + gapDp
        }
        field.setRightMarginPx(dp((rightDp + reserve + nearWidthDp).toFloat()))
        emoji?.setRightMarginPx(dp((gapDp + reserve).toFloat()))
        v.recordedAudioPanel?.setRightMarginPx(if (v.editingMessageObject == null) max(0, sendButtonWidthPx - dp(height.toFloat())) else 0)
        ChatInputMenuHelper.getMenuButton(v)?.let {
            IosInputHelper.setInputMenuButtonIconInset(it, hasInsideRightIcon)
            it.translationX = dp(IosInputHelper.getInputMenuButtonOffsetDp(hasInsideRightIcon, height, gapDp).toFloat()).toFloat()
        }
    }

    @JvmStatic
    fun updateScheduledButton(v: ChatActivityEnterView, gapDp: Int) {
        val scheduled = v.scheduledButton ?: return
        if (!IosInputHelper.isButtonPlacement() && !IosInputHelper.isAppearance()) return
        val attach = v.attachButton
        val container = v.messageEditTextContainer
        val rightPx = when {
            IosInputHelper.isButtonPlacement() -> dp(gapDp.toFloat())
            attach != null && attach.visibility == View.VISIBLE && attach.parent === container && attach.width > 0 ->
                container.width - attach.left - attach.width
            else -> {
                val height = ChatActivityEnterView.DEFAULT_HEIGHT
                dp((height * (attach.visibleCount() + v.botButton.visibleCount() + v.giftButton.visibleCount())).toFloat())
            }
        }
        scheduled.setRightMarginPx(rightPx)
    }

    private fun isScheduledReserved(v: ChatActivityEnterView): Boolean {
        val scheduled = v.scheduledButton
        return v.editingMessageObject == null && scheduled != null && scheduled.visibility == View.VISIBLE
    }

    private fun textInsetDp(): Int = if (IosInputHelper.isCompact()) COMPACT_TEXT_INSET_DP else NO_ICON_TEXT_INSET_DP

    private fun pxToDp(px: Int): Int = (px / AndroidUtilities.density).roundToInt()

    private fun View?.visibleCount(): Int = if (this != null && visibility == View.VISIBLE) 1 else 0

    private fun View.leftMarginDp(marginDp: Int) {
        val lp = layoutParams as MarginLayoutParams
        val marginPx = dp(marginDp.toFloat())
        if (lp.leftMargin != marginPx) {
            lp.leftMargin = marginPx
            layoutParams = lp
        }
    }

    private fun View.setRightMarginPx(marginPx: Int) {
        val lp = layoutParams as? MarginLayoutParams ?: return
        if (lp.rightMargin != marginPx) {
            lp.rightMargin = marginPx
            layoutParams = lp
        }
    }
}
