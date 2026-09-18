package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.View
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.spoilers.SpoilerEffect
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SpoilerHelper {
    private class State {
        var baseColor: Int = 0
        var drawAlphaMultiplier: Float = 1f
        var prevLeft: Float = Float.NaN
        var prevRight: Float = Float.NaN
        var nextLeft: Float = Float.NaN
        var nextRight: Float = Float.NaN
    }

    private val states = WeakHashMap<SpoilerEffect, State>()
    private fun stateOf(e: SpoilerEffect) = states.getOrPut(e) { State() }

    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempPath = Path()
    private val tempRect = RectF()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }

    @JvmStatic
    fun drawSolidIfOverridden(canvas: Canvas, effect: SpoilerEffect, parent: View?, lastColor: Int, mAlpha: Int): Boolean {
        val mode = InuConfig.TEXT_SPOILER_MODE.value
        if (mode == InuConfig.TextSpoilerModeItem.DEFAULT) return false
        val bounds = effect.bounds
        if (bounds.isEmpty) return true

        if (mode == InuConfig.TextSpoilerModeItem.EPSTEIN) {
            solidPaint.color = Color.BLACK
            solidPaint.alpha = (mAlpha * stateOf(effect).drawAlphaMultiplier).toInt().coerceIn(0, 0xFF)
            canvas.drawRect(bounds, solidPaint)
            return true
        }

        // entiny: pin pre-reveal baseColor and alpha so ripple PorterDuff.CLEAR is the only visible change
        val state = stateOf(effect)
        if (effect.rippleProgress < 0) state.baseColor = lastColor
        val alphaScale = if (isOutgoingBubble(parent)) 0.45f else 0.25f
        solidPaint.color = state.baseColor
        solidPaint.alpha = (0xFF * alphaScale * state.drawAlphaMultiplier).toInt().coerceIn(0, 0xFF)

        val r = dp(4f).toFloat()
        val tlR = if (state.prevLeft <= bounds.left) 0f else r
        val trR = if (state.prevRight >= bounds.right) 0f else r
        val blR = if (state.nextLeft <= bounds.left) 0f else r
        val brR = if (state.nextRight >= bounds.right) 0f else r

        tempPath.rewind()
        tempRect.set(bounds)
        tempPath.addRoundRect(tempRect, floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR), Path.Direction.CW)
        canvas.drawPath(tempPath, solidPaint)

        if (state.prevLeft < bounds.left)
            drawFillet(canvas, bounds.left.toFloat(), bounds.top.toFloat(), dx = -1, dy = +1, r = r)
        if (state.prevRight > bounds.right)
            drawFillet(canvas, bounds.right.toFloat(), bounds.top.toFloat(), dx = +1, dy = +1, r = r)
        if (state.nextLeft < bounds.left)
            drawFillet(canvas, bounds.left.toFloat(), bounds.bottom.toFloat(), dx = -1, dy = -1, r = r)
        if (state.nextRight > bounds.right)
            drawFillet(canvas, bounds.right.toFloat(), bounds.bottom.toFloat(), dx = +1, dy = -1, r = r)

        return true
    }

    @JvmStatic
    fun setDrawAlphaMultiplier(effect: SpoilerEffect, alpha: Float) {
        stateOf(effect).drawAlphaMultiplier = alpha.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun drawMediaSpoilerIfOverridden(canvas: Canvas, cell: ChatMessageCell): Boolean {
        val mode = InuConfig.MEDIA_SPOILER_MODE.value
        if (mode == InuConfig.MediaSpoilerModeItem.TELEGRAM) return false
        val photoImage = cell.photoImage
        // entiny: skip custom indicator when stock draws self-destruct indicator or download button
        val msg = cell.messageObject
        val isSelfDestruct = msg != null && msg.needDrawBluredPreview()
        val isNotLoaded = cell.buttonState == 0 || cell.buttonState == 1
        drawMediaSpoilerStyle(
            canvas, mode,
            photoImage.imageX, photoImage.imageY, photoImage.imageX2, photoImage.imageY2,
            photoImage.alpha.coerceIn(0f, 1f), cell.resourcesProvider,
            !isSelfDestruct && !isNotLoaded,
        )
        return true
    }

    @JvmStatic
    fun drawMediaSpoilerStyle(
        canvas: Canvas, mode: Int,
        left: Float, top: Float, right: Float, bottom: Float,
        clampedAlpha: Float, resourcesProvider: Theme.ResourcesProvider?,
        drawIndicator: Boolean, scrimColor: Int = Color.BLACK,
    ) {
        solidPaint.color = scrimColor
        solidPaint.alpha = (110 * clampedAlpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, top, right, bottom, solidPaint)

        if (!drawIndicator) return

        val loaderColor = Theme.getColor(Theme.key_chat_mediaLoaderPhoto, resourcesProvider)
        val iconColor = Theme.getColor(Theme.key_chat_mediaLoaderPhotoIcon, resourcesProvider)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val pad = dp(8f).toFloat()

        if (mode == InuConfig.MediaSpoilerModeItem.CIRCLE) {
            // entiny: theme disc and eye icon after stock media loader palette for proper contrast
            val radius = dp(22f).toFloat()
            if (right - left >= radius * 2 + pad && bottom - top >= radius * 2 + pad) {
                solidPaint.color = loaderColor
                solidPaint.alpha = (Color.alpha(loaderColor) * clampedAlpha).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, radius, solidPaint)
                eyeDrawable.let { eye ->
                    val s = dp(24f)
                    eye.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
                    eye.colorFilter = PorterDuffColorFilter(iconColor or 0xFF000000.toInt(), PorterDuff.Mode.SRC_IN)
                    eye.alpha = (255 * clampedAlpha).toInt().coerceIn(0, 255)
                    eye.draw(canvas)
                }
            }
            return
        }

        val label = LocaleController.getString(R.string.InuMediaSpoilerLabel).uppercase()
        val padH = dp(16f).toFloat()
        val padV = dp(7f).toFloat()
        val fm = labelPaint.fontMetrics
        val textW = labelPaint.measureText(label)
        val textH = fm.descent - fm.ascent
        val pillW = textW + padH * 2
        val pillH = textH + padV * 2
        if (right - left >= pillW + pad && bottom - top >= pillH + pad) {
            solidPaint.color = loaderColor
            solidPaint.alpha = (Color.alpha(loaderColor) * clampedAlpha).toInt().coerceIn(0, 255)
            tempRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
            canvas.drawRoundRect(tempRect, pillH / 2f, pillH / 2f, solidPaint)
            labelPaint.color = iconColor
            labelPaint.alpha = (Color.alpha(iconColor) * clampedAlpha).toInt().coerceIn(0, 255)
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, labelPaint)
        }
    }

    private val eyeDrawable: Drawable by lazy {
        val ctx = ApplicationLoader.applicationContext
        ContextCompat.getDrawable(ctx, R.drawable.menu_hide_gift)!!.mutate()
    }

    private fun isOutgoingBubble(view: View?): Boolean {
        return generateSequence(view) { it.parent as? View }
            .filterIsInstance<ChatMessageCell>()
            .firstOrNull()?.messageObject?.isOutOwner == true
    }

    private fun drawFillet(canvas: Canvas, cx: Float, cy: Float, dx: Int, dy: Int, r: Float) {
        tempPath.rewind()
        tempPath.moveTo(cx, cy + dy * r)
        val ox = cx + dx * r
        val oy = cy + dy * r
        tempRect.set(ox - r, oy - r, ox + r, oy + r)
        tempPath.arcTo(tempRect, if (dx < 0) 0f else 180f, 90f * dx * dy)
        tempPath.lineTo(cx, cy)
        tempPath.close()
        canvas.drawPath(tempPath, solidPaint)
    }

    @JvmStatic
    fun linkNeighbors(spoilers: List<SpoilerEffect>) {
        // entiny: merge adjacent same-line rects to eliminate visible seams from getSelectionPath whitespace
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler || a.bounds.isEmpty) continue
            var merged: Boolean
            do {
                merged = false
                val ab = a.bounds
                for (j in spoilers.indices) {
                    if (i == j) continue
                    val b = spoilers[j]
                    if (!b.inu_isTextSpoiler || b.bounds.isEmpty) continue
                    val bb = b.bounds
                    if (ab.top != bb.top || ab.bottom != bb.bottom) continue
                    if (ab.right < bb.left || ab.left > bb.right) continue
                    a.setBounds(min(ab.left, bb.left), ab.top, max(ab.right, bb.right), ab.bottom)
                    b.setBounds(0, 0, 0, 0)
                    merged = true
                }
            } while (merged)
        }
        // entiny: snap sub-radius edge misalignments between adjacent lines to prevent curve-mating slivers
        val snap = dp(4f).toFloat()
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler || a.bounds.isEmpty) continue
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler || b.bounds.isEmpty) continue
                val bb = b.bounds
                if (abs(ab.bottom - bb.top) > 1 && abs(bb.bottom - ab.top) > 1) continue
                if (ab.left >= bb.right || ab.right <= bb.left) continue
                val dl = abs(ab.left - bb.left).toFloat()
                if (dl in 0.001f..snap) {
                    val nl = max(ab.left, bb.left)
                    a.setBounds(nl, ab.top, ab.right, ab.bottom)
                    b.setBounds(nl, bb.top, bb.right, bb.bottom)
                }
                val dr = abs(ab.right - bb.right).toFloat()
                if (dr in 0.001f..snap) {
                    val nr = min(ab.right, bb.right)
                    a.setBounds(ab.left, ab.top, nr, ab.bottom)
                    b.setBounds(bb.left, bb.top, nr, bb.bottom)
                }
            }
        }
        for (s in spoilers) {
            if (!s.inu_isTextSpoiler || s.bounds.isEmpty) continue
            stateOf(s).apply {
                prevLeft = Float.NaN; prevRight = Float.NaN
                nextLeft = Float.NaN; nextRight = Float.NaN
            }
        }
        for (i in spoilers.indices) {
            val a = spoilers[i]
            if (!a.inu_isTextSpoiler || a.bounds.isEmpty) continue
            val ast = stateOf(a)
            val ab = a.bounds
            for (j in spoilers.indices) {
                if (i == j) continue
                val b = spoilers[j]
                if (!b.inu_isTextSpoiler || b.bounds.isEmpty) continue
                val bb = b.bounds
                if (ab.left >= bb.right || ab.right <= bb.left) continue
                if (abs(ab.bottom - bb.top) <= 1) {
                    ast.nextLeft = if (ast.nextLeft.isNaN()) bb.left.toFloat() else min(ast.nextLeft, bb.left.toFloat())
                    ast.nextRight = if (ast.nextRight.isNaN()) bb.right.toFloat() else max(ast.nextRight, bb.right.toFloat())
                }
                if (abs(bb.bottom - ab.top) <= 1) {
                    ast.prevLeft = if (ast.prevLeft.isNaN()) bb.left.toFloat() else min(ast.prevLeft, bb.left.toFloat())
                    ast.prevRight = if (ast.prevRight.isNaN()) bb.right.toFloat() else max(ast.prevRight, bb.right.toFloat())
                }
            }
        }
    }

    // entiny: fix incorrect layout calculation bug triggered by simple spoilers
    class TransparentMetricSpan(private val source: TextStyleSpan) : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) {
            source.updateMeasureState(p);
        }

        override fun updateDrawState(p: TextPaint) {
            source.updateDrawState(p);
            p.setColor(Color.TRANSPARENT);
            p.setAlpha(0);
        }
    };
}
