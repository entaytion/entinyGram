package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import desu.inugram.InuConfig
import me.vkryl.android.animator.FactorAnimator
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.MessagesStorage
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BackDrawable
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator

object UnreadBackBadgeHelper {

    @JvmStatic
    fun refresh(actionBar: ActionBar?, account: Int) {
        if (actionBar == null) return
        if (!InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.value) {
            actionBar.inu_backBadge = null
            return
        }
        badgeOf(actionBar).setCount(MessagesStorage.getInstance(account).mainUnreadCount, true)
    }

    @JvmStatic
    fun preview(actionBar: ActionBar, sample: Int) {
        if (!InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.value) {
            actionBar.inu_backBadge = null
            return
        }
        badgeOf(actionBar).setCount(sample, false)
    }

    private fun badgeOf(actionBar: ActionBar): BackButtonBadge =
        actionBar.inu_backBadge ?: BackButtonBadge(actionBar).also { actionBar.inu_backBadge = it }
}

class BackButtonBadge(private val bar: ActionBar) : FactorAnimator.Target {

    private var label = ""
    private var digits = 1
    private var count = 0
    private var wanted = false

    private val presence = FactorAnimator(PRESENCE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320)
    private val chipWidth = FactorAnimator(WIDTH, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = AndroidUtilities.bold() }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = RectF()

    fun setCount(value: Int, animated: Boolean) {
        count = value.coerceIn(0, MAX_SHOWN)
        if (count > 0) {
            val text = count.toString()
            if (text != label) {
                label = text
                digits = text.length
            }
        }
        val targetWidth = chipWidthFor(digits)
        if (!animated || chipWidth.getFactor() == 0f) chipWidth.forceFactor(targetWidth) else chipWidth.animateTo(targetWidth)
        syncWanted(!animated)
        bar.invalidate()
    }

    fun isMoving(): Boolean = presence.isAnimating() || chipWidth.isAnimating()

    fun pillExtra(): Int {
        if (!iosShape()) return 0
        syncWanted(false)
        return ((chipWidth.getFactor() - dpf2(CHIP_TRIM)) * presence.getFactor()).toInt().coerceAtLeast(0)
    }

    fun beforeBack(canvas: Canvas) {
        syncWanted(false)
        applyTail()
        canvas.save()
        if (iosShape()) canvas.translate(-dpf2(ARROW_SLIDE) * reveal(), 0f)
    }

    fun afterBack(canvas: Canvas, back: View) {
        canvas.restore()
        val fade = reveal()
        if (fade <= 0.01f || label.isEmpty()) return
        val alpha = back.alpha * fade
        val scale = 0.7f + 0.3f * fade
        val cy = back.y + back.height / 2f
        val cx = back.x + back.width / 2f
        if (iosShape()) {
            textPaint.textSize = dpf2(12f)
            val width = (chipWidth.getFactor() * presence.getFactor() + dpf2(CHIP_TRIM) * (1f - presence.getFactor()))
                .coerceIn(dpf2(CHIP_H), chipWidth.getFactor())
            val left = cx - dpf2(ARROW_SLIDE) * fade + dpf2(CHIP_GAP)
            chip.set(left, cy - dpf2(CHIP_H) / 2f, left + width, cy + dpf2(CHIP_H) / 2f)
            drawChip(canvas, alpha, scale, Theme.key_actionBarActionModeDefault, Theme.key_actionBarActionModeDefaultIcon)
        } else {
            textPaint.textSize = dpf2(10.5f)
            val height = dpf2(16f)
            val width = maxOf(height, measuredLabel() + dpf2(9f))
            val left = cx + dpf2(3f)
            val top = cy - dpf2(15f)
            chip.set(left, top, left + width, top + height)
            drawChip(canvas, alpha, scale, Theme.key_chats_unreadCounter, Theme.key_chats_unreadCounterText)
        }
    }

    override fun onFactorChanged(id: Int, factor: Float, fraction: Float, callee: FactorAnimator?) {
        bar.invalidate()
    }

    private fun drawChip(canvas: Canvas, alpha: Float, scale: Float, fillKey: Int, textKey: Int) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        chipPaint.color = Theme.getColor(fillKey, bar.getMenuResourcesProvider())
        chipPaint.alpha = chipPaint.alpha * a / 255
        textPaint.color = Theme.getColor(textKey, bar.getMenuResourcesProvider())
        textPaint.alpha = textPaint.alpha * a / 255
        canvas.save()
        canvas.scale(scale, scale, chip.centerX(), chip.centerY())
        val r = chip.height() / 2f
        canvas.drawRoundRect(chip, r, r, chipPaint)
        val baseline = chip.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, chip.centerX() - measuredLabel() / 2f, baseline, textPaint)
        canvas.restore()
    }

    private fun measuredLabel(): Float = textPaint.measureText(label)

    private fun reveal(): Float {
        if (!iosShape()) return presence.getFactor()
        val smallest = chipWidthFor(1) - dpf2(CHIP_TRIM)
        return if (smallest <= 0f) presence.getFactor() else (pillExtraRaw() / smallest).coerceIn(0f, 1f)
    }

    private fun pillExtraRaw(): Float = ((chipWidth.getFactor() - dpf2(CHIP_TRIM)) * presence.getFactor()).coerceAtLeast(0f)

    private fun iosShape(): Boolean = InuConfig.UNREAD_BADGE_ON_BACK_BUTTON_IOS.value && bar.inu_backPillMode()

    private fun syncWanted(force: Boolean) {
        val want = count > 0 && !bar.isActionModeShowed() && !bar.isSearchFieldVisible()
        if (want == wanted && !force) return
        wanted = want
        if (force) presence.forceFactor(if (want) 1f else 0f) else presence.animateTo(if (want) 1f else 0f)
    }

    private fun applyTail() {
        val arrow = bar.getBackButtonDrawable() as? BackDrawable ?: return
        if (arrow.inu_tail != 0f) {
            arrow.inu_tail = 0f
            arrow.invalidateSelf()
        }
    }

    private fun chipWidthFor(length: Int): Float = when {
        length <= 1 -> dpf2(CHIP_H)
        length == 2 -> dpf2(24f)
        else -> dpf2(31f)
    }

    private companion object {
        const val PRESENCE = 0
        const val WIDTH = 1
        const val MAX_SHOWN = 999
        const val CHIP_H = 18f
        const val CHIP_TRIM = 9f
        const val CHIP_GAP = 10f
        const val ARROW_SLIDE = 5f
    }
}
