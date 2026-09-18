package desu.inugram.ui.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout
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
    )

    private val backPath = Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val barRect = RectF()
    private val pillRect = RectF()

    private val titleText = LocaleController.getString(R.string.InuChatHeaderPreviewName)
    private val subtitleText = LocaleController.getString(R.string.Online)
    private val avatarLetter = titleText.take(1).uppercase()

    private var current: Snapshot
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        contentDescription = LocaleController.getString(R.string.InuChatHeaderSettings)
        isFocusable = false
        isClickable = false
        applyColors()
        current = Snapshot(0f, 0f, 0f, 0f, 0f, 0f)
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
    )

    private fun computeTarget(width: Int): Snapshot {
        val margin = dp(CARD_MARGIN_DP)
        val sideInset = dp(SIDE_INSET_DP).toFloat()
        val avatarRadius = dp(AVATAR_RADIUS_DP).toFloat()
        val avatarGap = dp(AVATAR_GAP_DP).toFloat()
        val pillPadH = dp(PILL_PAD_H_DP).toFloat()

        val cardLeft = margin.toFloat()
        val cardRight = (width - margin).toFloat()
        val backCx = if (LocaleController.isRTL) cardRight - sideInset else cardLeft + sideInset
        val dotsCxDefault = if (LocaleController.isRTL) cardLeft + sideInset else cardRight - sideInset
        val contentLeft = minOf(backCx, dotsCxDefault) + dp(ICON_SIZE_DP / 2 + AVATAR_GAP_DP)
        val contentRight = maxOf(backCx, dotsCxDefault) - dp(ICON_SIZE_DP / 2 + AVATAR_GAP_DP)
        val barMidX = (cardLeft + cardRight) / 2f

        val isCenter = InuUtils.centerChatTitle()
        val isPill = InuUtils.compactChatPill()
        val avatarSlot = InuUtils.chatAvatarInMenuSlot()
        val avatarStatic = InuUtils.chatAvatarStatic()
        val avatarOnRight = InuUtils.chatAvatarOnRight()

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
                val start = contentLeft + avatarRadius * 2 + avatarGap
                start + textBlockWidth / 2f
            }
            return Snapshot(avatarCx, dotsAlpha, 0f, barMidX, barMidX, textCenterX)
        }

        val avatarInPill = !avatarSlot && !avatarStatic
        val pillContentWidth = if (avatarInPill) {
            textBlockWidth + pillPadH * 2 + avatarRadius * 2 + avatarGap
        } else {
            textBlockWidth + pillPadH * 2
        }
        val pillLeft = barMidX - pillContentWidth / 2f
        val pillRight = barMidX + pillContentWidth / 2f

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

        return Snapshot(avatarCx, dotsAlpha, 1f, pillLeft, pillRight, textCenterX)
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

        drawBackArrow(canvas, backCx, cy)

        if (current.pillAlpha > 0f) {
            val pillHalfHeight = dp(PILL_HEIGHT_DP / 2).toFloat()
            pillPaint.alpha = (PILL_FILL_ALPHA * current.pillAlpha * 255).toInt()
            pillRect.set(current.pillLeft, cy - pillHalfHeight, current.pillRight, cy + pillHalfHeight)
            canvas.drawRoundRect(pillRect, pillHalfHeight, pillHalfHeight, pillPaint)
        }

        if (current.dotsAlpha > 0f) {
            dotsPaint.alpha = (current.dotsAlpha * 255).toInt()
            drawDots(canvas, dotsCxDefault, cy)
        }

        canvas.drawCircle(current.avatarCx, cy, avatarRadius, avatarPaint)
        canvas.drawText(avatarLetter, current.avatarCx, cy - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint)

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
    }
}
