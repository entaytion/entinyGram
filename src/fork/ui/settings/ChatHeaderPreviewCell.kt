package desu.inugram.ui.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator

@SuppressLint("ViewConstructor")
class ChatHeaderPreviewCell(context: Context) : FrameLayout(context) {

    private data class Snapshot(
        val avatarCx: Float,
        val dotsAlpha: Float,
        val pillAlpha: Float,
        val pillLeft: Float,
        val pillRight: Float,
        val textCenterX: Float,
        val badgeEdge: Float,
        val badgeAlpha: Float,
    )

    private val backPath = Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val backPillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val barRect = RectF()
    private val pillRect = RectF()
    private val backRect = RectF()

    // entiny: the cell used to read InuChatHeaderPreviewName, which no longer exists -- your own name previews the real header better anyway.
    private val titleText: String = run {
        val user = org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).currentUser
        if (user != null) org.telegram.messenger.ContactsController.formatName(user.first_name, user.last_name)
        else LocaleController.getString(R.string.AppName)
    }
    private val subtitleText = LocaleController.getString(R.string.Online)
    private val avatarLetter = titleText.take(1).uppercase()
    private val badgeText = BADGE_COUNT.toString()

    private var current: Snapshot
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        contentDescription = LocaleController.getString(R.string.InuChatHeaderSettings)
        isFocusable = false
        isClickable = false
        applyColors()
        current = Snapshot(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    private fun applyColors() {
        barPaint.color = Theme.getColor(Theme.key_actionBarDefault)
        val iconColor = Theme.getColor(Theme.key_actionBarDefaultIcon)
        iconPaint.color = iconColor
        iconPaint.strokeWidth = dp(2f).toFloat()
        dotsPaint.color = iconColor
        avatarPaint.color = Theme.multAlpha(iconColor, 0.3f)
        // entiny: RGB only, alpha is set per-frame below (Paint.alpha replaces, not multiplies)
        pillPaint.color = iconColor or (0xFF shl 24)
        titlePaint.color = Theme.getColor(Theme.key_actionBarDefaultTitle)
        titlePaint.textSize = dp(15f).toFloat()
        subtitlePaint.color = Theme.getColor(Theme.key_actionBarDefaultSubtitle)
        subtitlePaint.textSize = dp(12.33f).toFloat()
        backPillPaint.color = iconColor or (0xFF shl 24)
        bubblePaint.color = Theme.getColor(Theme.key_chat_goDownButtonCounterBackground)
        bubbleTextPaint.color = Theme.getColor(Theme.key_chat_goDownButtonCounter)
        bubbleTextPaint.textSize = dp(10f).toFloat()
        // entiny: same keys the real CounterView gets in ActionBar.createUnreadView for the iOS-styled counter
        chipPaint.color = Theme.getColor(Theme.key_actionBarActionModeDefault)
        chipTextPaint.color = Theme.getColor(Theme.key_actionBarActionModeDefaultIcon)
        chipTextPaint.textSize = dp(12f).toFloat()
    }

    fun refresh(animated: Boolean = true) {
        applyColors()
        if (measuredWidth <= 0) return
        val newTarget = computeTarget(measuredWidth)
        animator?.cancel()
        val canAnimate = animated && isAttachedToWindow && SharedConfig.animationsEnabled()
        if (!canAnimate) {
            current = newTarget
            invalidate()
            return
        }
        val from = current
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                current = lerp(from, newTarget, it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    private fun lerp(a: Snapshot, b: Snapshot, f: Float) = Snapshot(
        avatarCx = a.avatarCx + (b.avatarCx - a.avatarCx) * f,
        dotsAlpha = a.dotsAlpha + (b.dotsAlpha - a.dotsAlpha) * f,
        pillAlpha = a.pillAlpha + (b.pillAlpha - a.pillAlpha) * f,
        pillLeft = a.pillLeft + (b.pillLeft - a.pillLeft) * f,
        pillRight = a.pillRight + (b.pillRight - a.pillRight) * f,
        textCenterX = a.textCenterX + (b.textCenterX - a.textCenterX) * f,
        badgeEdge = a.badgeEdge + (b.badgeEdge - a.badgeEdge) * f,
        badgeAlpha = a.badgeAlpha + (b.badgeAlpha - a.badgeAlpha) * f,
    )

    private fun computeTarget(width: Int): Snapshot {
        val margin = dp(CARD_MARGIN_DP)
        val sideInset = dp(SIDE_INSET_DP).toFloat()
        val avatarRadius = dp(AVATAR_RADIUS_DP).toFloat()
        val avatarGap = dp(AVATAR_GAP_DP).toFloat()
        val pillPadH = dp(PILL_PAD_H_DP).toFloat()
        val rtl = LocaleController.isRTL

        val cardLeft = margin.toFloat()
        val cardRight = (width - margin).toFloat()
        val backCx = if (rtl) cardRight - sideInset else cardLeft + sideInset
        val dotsCxDefault = if (rtl) cardLeft + sideInset else cardRight - sideInset
        val barMidX = (cardLeft + cardRight) / 2f

        val badgeOn = InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.value
        val iosCounter = badgeOn && InuConfig.UNREAD_BADGE_ON_BACK_BUTTON_IOS.value
        val badgeAlpha = if (badgeOn) 1f else 0f
        val badgeEdge = if (iosCounter) counterEdge(backCx, rtl) else if (rtl) cardRight else cardLeft

        var contentLeft = minOf(backCx, dotsCxDefault) + dp(ICON_SIZE_DP / 2 + AVATAR_GAP_DP)
        var contentRight = maxOf(backCx, dotsCxDefault) - dp(ICON_SIZE_DP / 2 + AVATAR_GAP_DP)
        if (iosCounter) {
            // entiny: the widened back pill owns that space, exactly like ActionBar's leftLimit = inu_getBackPillWidth() - dp(6)
            val gap = dp(BADGE_CONTENT_GAP_DP)
            if (rtl) contentRight = minOf(contentRight, badgeEdge + gap)
            else contentLeft = maxOf(contentLeft, badgeEdge - gap)
        }

        val isCenter = InuUtils.centerChatTitle()
        val isPill = InuUtils.compactChatPill()
        val avatarSlot = InuUtils.chatAvatarInMenuSlot()
        val avatarStatic = InuUtils.chatAvatarStatic()
        val avatarOnRight = InuUtils.chatAvatarOnRight()
        val noAvatar = InuUtils.hideChatAvatar()

        val titleWidth = titlePaint.measureText(titleText)
        val subtitleWidth = subtitlePaint.measureText(subtitleText)
        val textBlockWidth = maxOf(titleWidth, subtitleWidth)

        val dotsAlpha = if (avatarSlot) 0f else 1f

        if (!isPill) {
            val avatarCx = when {
                avatarOnRight -> contentRight - avatarRadius
                else -> contentLeft + avatarRadius
            }
            val textCenterX = if (isCenter) {
                (contentLeft + contentRight) / 2f
            } else {
                val start = contentLeft + if (noAvatar) 0f else avatarRadius * 2 + avatarGap
                start + textBlockWidth / 2f
            }
            return Snapshot(avatarCx, dotsAlpha, 0f, barMidX, barMidX, textCenterX, badgeEdge, badgeAlpha)
        }

        val avatarInPill = !avatarSlot && !avatarStatic && !noAvatar
        val pillContentWidth = if (avatarInPill) {
            textBlockWidth + pillPadH * 2 + avatarRadius * 2 + avatarGap
        } else {
            textBlockWidth + pillPadH * 2
        }
        var pillLeft = barMidX - pillContentWidth / 2f
        var pillRight = barMidX + pillContentWidth / 2f
        if (iosCounter) {
            val gap = dp(BADGE_CONTENT_GAP_DP)
            if (rtl) {
                val maxRight = badgeEdge + gap
                if (pillRight > maxRight) {
                    val d = pillRight - maxRight
                    pillRight -= d
                    pillLeft -= d
                }
            } else {
                val minLeft = badgeEdge - gap
                if (pillLeft < minLeft) {
                    val d = minLeft - pillLeft
                    pillLeft += d
                    pillRight += d
                }
            }
        }

        val avatarCx: Float
        val textCenterX: Float
        if (avatarInPill) {
            if (avatarOnRight) {
                val textAreaLeft = pillLeft + pillPadH
                textCenterX = textAreaLeft + textBlockWidth / 2f
                avatarCx = textAreaLeft + textBlockWidth + avatarGap + avatarRadius
            } else {
                avatarCx = pillLeft + pillPadH + avatarRadius
                val textAreaLeft = avatarCx + avatarRadius + avatarGap
                textCenterX = textAreaLeft + textBlockWidth / 2f
            }
        } else {
            textCenterX = barMidX
            avatarCx = if (avatarSlot) dotsCxDefault else contentLeft + avatarRadius
        }

        return Snapshot(avatarCx, dotsAlpha, 1f, pillLeft, pillRight, textCenterX, badgeEdge, badgeAlpha)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) refresh(animated = false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // entiny: incoming height spec can be EXACTLY and clip us, so it's ignored here
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = dp(CARD_MARGIN_DP * 2 + CARD_HEIGHT_DP)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (measuredWidth <= 0 || measuredHeight <= 0) return
        val margin = dp(CARD_MARGIN_DP).toFloat()
        val radius = dp(CARD_RADIUS_DP).toFloat()
        val barTop = (measuredHeight - dp(CARD_HEIGHT_DP)) / 2f
        barRect.set(margin, barTop, measuredWidth - margin, barTop + dp(CARD_HEIGHT_DP))
        canvas.drawRoundRect(barRect, radius, radius, barPaint)

        val cy = barRect.centerY()
        val sideInset = dp(SIDE_INSET_DP).toFloat()
        val backCx = if (LocaleController.isRTL) barRect.right - sideInset else barRect.left + sideInset
        val dotsCxDefault = if (LocaleController.isRTL) barRect.left + sideInset else barRect.right - sideInset
        val avatarRadius = dp(AVATAR_RADIUS_DP).toFloat()

        val rtl = LocaleController.isRTL
        val iosCounter = current.badgeAlpha > 0f && InuConfig.UNREAD_BADGE_ON_BACK_BUTTON_IOS.value
        if (iosCounter) drawBackPill(canvas, cy, rtl)
        drawBackArrow(canvas, backCx, cy)
        if (iosCounter) {
            drawCounterChip(canvas, backCx, cy, rtl)
        } else if (current.badgeAlpha > 0f) {
            drawUnreadBubble(canvas, backCx, cy, rtl)
        }

        if (current.pillAlpha > 0f && !InuConfig.CHAT_HEADER_NO_PILL.value) {
            val pillHalfHeight = dp(PILL_HEIGHT_DP / 2).toFloat()
            pillPaint.alpha = (PILL_FILL_ALPHA * current.pillAlpha * 255).toInt()
            pillRect.set(current.pillLeft, cy - pillHalfHeight, current.pillRight, cy + pillHalfHeight)
            canvas.drawRoundRect(pillRect, pillHalfHeight, pillHalfHeight, pillPaint)
        }

        if (current.dotsAlpha > 0f) {
            dotsPaint.alpha = (current.dotsAlpha * 255).toInt()
            drawDots(canvas, dotsCxDefault, cy)
        }

        if (!InuUtils.hideChatAvatar()) {
            canvas.drawCircle(current.avatarCx, cy, avatarRadius, avatarPaint)
            canvas.drawText(avatarLetter, current.avatarCx, cy - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint)
        }

        drawTextBlock(canvas, current.textCenterX, cy)
    }

    private fun drawTextBlock(canvas: Canvas, cx: Float, cy: Float) {
        val titleHeight = titlePaint.descent() - titlePaint.ascent()
        val subtitleHeight = subtitlePaint.descent() - subtitlePaint.ascent()
        val gap = dp(1f)
        val blockTop = cy - (titleHeight + gap + subtitleHeight) / 2f
        val titleBaseline = blockTop - titlePaint.ascent()
        val subtitleBaseline = blockTop + titleHeight + gap - subtitlePaint.ascent()
        canvas.drawText(titleText, cx, titleBaseline, titlePaint)
        canvas.drawText(subtitleText, cx, subtitleBaseline, subtitlePaint)
    }

    private fun drawBackArrow(canvas: Canvas, cx: Float, cy: Float) {
        val half = dp(5f).toFloat()
        backPath.reset()
        if (LocaleController.isRTL) {
            backPath.moveTo(cx - half, cy - half)
            backPath.lineTo(cx + half, cy)
            backPath.lineTo(cx - half, cy + half)
        } else {
            backPath.moveTo(cx + half, cy - half)
            backPath.lineTo(cx - half, cy)
            backPath.lineTo(cx + half, cy + half)
        }
        canvas.drawPath(backPath, iconPaint)
    }

    // entiny: chip is 18/24/30dp for 1/2/3 digits, exactly what ActionBar.setUnread hands to CounterView
    private fun counterChipWidth(): Float = dp(
        when (badgeText.length) {
            1 -> CHIP_WIDTH_ONE_DP
            2 -> CHIP_WIDTH_TWO_DP
            else -> CHIP_WIDTH_THREE_DP
        }
    ).toFloat()

    private fun counterRightPad(): Float = when (badgeText.length) {
        1 -> CHIP_PAD_ONE_DP
        2 -> CHIP_PAD_TWO_DP
        else -> CHIP_PAD_THREE_DP
    }

    // entiny: where the widened back pill ends, mirroring ActionBar.inu_getBackPillWidth
    private fun counterEdge(backCx: Float, rtl: Boolean): Float {
        val sign = if (rtl) -1f else 1f
        val chipOuter = backCx + sign * (dp(CHIP_GAP_DP) + counterChipWidth())
        return chipOuter + sign * dp(counterRightPad())
    }

    private fun drawBackPill(canvas: Canvas, cy: Float, rtl: Boolean) {
        val halfHeight = dp(BACK_PILL_HEIGHT_DP / 2).toFloat()
        backPillPaint.alpha = (BACK_PILL_FILL_ALPHA * current.badgeAlpha * 255).toInt()
        val near = if (rtl) barRect.right else barRect.left
        backRect.set(minOf(near, current.badgeEdge), cy - halfHeight, maxOf(near, current.badgeEdge), cy + halfHeight)
        canvas.drawRoundRect(backRect, halfHeight, halfHeight, backPillPaint)
    }

    private fun drawCounterChip(canvas: Canvas, backCx: Float, cy: Float, rtl: Boolean) {
        val sign = if (rtl) -1f else 1f
        val chipWidth = counterChipWidth()
        val halfHeight = dp(CHIP_HEIGHT_DP / 2).toFloat()
        val anchor = backCx + sign * dp(CHIP_GAP_DP)
        val left = if (rtl) anchor - chipWidth else anchor
        backRect.set(left, cy - halfHeight, left + chipWidth, cy + halfHeight)
        canvas.drawRoundRect(backRect, halfHeight, halfHeight, chipPaint)
        chipTextPaint.alpha = (current.badgeAlpha * 255).toInt()
        canvas.drawText(badgeText, left + chipWidth / 2f, cy - (chipTextPaint.descent() + chipTextPaint.ascent()) / 2f, chipTextPaint)
    }

    private fun drawUnreadBubble(canvas: Canvas, backCx: Float, cy: Float, rtl: Boolean) {
        val sign = if (rtl) -1f else 1f
        val offset = dp(BUBBLE_OFFSET_DP).toFloat()
        val radius = dp(BUBBLE_RADIUS_DP).toFloat()
        bubbleTextPaint.alpha = (current.badgeAlpha * 255).toInt()
        canvas.drawCircle(backCx + sign * offset, cy - offset, radius, bubblePaint)
        canvas.drawText(badgeText, backCx + sign * offset, cy - offset - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f, bubbleTextPaint)
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float) {
        val r = dp(1.5f).toFloat()
        val gap = dp(4f).toFloat()
        canvas.drawCircle(cx, cy - gap, r, dotsPaint)
        canvas.drawCircle(cx, cy, r, dotsPaint)
        canvas.drawCircle(cx, cy + gap, r, dotsPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val CARD_MARGIN_DP = 16f
        private const val CARD_HEIGHT_DP = 56f
        private const val CARD_RADIUS_DP = 18f
        private const val SIDE_INSET_DP = 18f
        private const val ICON_SIZE_DP = 22f
        private const val AVATAR_RADIUS_DP = 16f
        private const val AVATAR_GAP_DP = 8f
        private const val PILL_PAD_H_DP = 14f
        private const val PILL_HEIGHT_DP = 36f
        private const val PILL_FILL_ALPHA = 0.16f
        private const val BACK_PILL_HEIGHT_DP = 40f
        private const val BACK_PILL_FILL_ALPHA = 0.22f
        private const val BADGE_CONTENT_GAP_DP = 6f
        private const val CHIP_GAP_DP = 14f
        private const val CHIP_HEIGHT_DP = 16f
        private const val CHIP_WIDTH_ONE_DP = 18f
        private const val CHIP_WIDTH_TWO_DP = 24f
        private const val CHIP_WIDTH_THREE_DP = 30f
        private const val CHIP_PAD_ONE_DP = 3.5f
        private const val CHIP_PAD_TWO_DP = 7.5f
        private const val CHIP_PAD_THREE_DP = 13.5f
        private const val BUBBLE_OFFSET_DP = 11f
        private const val BUBBLE_RADIUS_DP = 9f
        private const val BADGE_COUNT = 12
    }
}
