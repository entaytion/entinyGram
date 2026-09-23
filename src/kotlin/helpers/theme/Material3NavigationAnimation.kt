package desu.inugram.helpers.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ProfileActivity
import org.telegram.ui.ViewPagerActivity

internal inline fun ViewGroup.eachChild(action: (View) -> Unit) {
    for (i in 0 until childCount) action(getChildAt(i))
}

object Material3BackMotion {
    const val ENTER_OFFSET_DP = 96f
    const val SCRIM_ALPHA_BYTE = 77
    const val SCRIM_FADE = 0.5f

    val EMPHASIZED: Interpolator = PathInterpolator(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        }
    )

    fun getFragmentBackground(fragment: BaseFragment?): Drawable? {
        var f = fragment
        while (f is ViewPagerActivity) f = f.currentVisibleFragment
        // entiny: ProfileActivity fragmentView is transparent so resolve window background gray directly
        if (f is ProfileActivity) return ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray))
        val bg = f?.fragmentView?.background
        if (bg is ColorDrawable && Color.alpha(bg.color) == 0) return null
        return bg
    }
}

private interface ReleasableDrawable {
    fun release()
}

object Material3NavigationAnimation {
    const val DURATION = 300f
    private const val CLOSING_FADE_START = 35f / 450f
    private const val CLOSING_FADE_END = 118f / 450f
    private const val OPENING_FADE_START = 50f / 450f
    private const val OPENING_FADE_END = 133f / 450f

    private class BelowBackground(layers: List<Drawable>) : LayerDrawable(layers.toTypedArray()) {
        fun release() {
            for (i in 0 until numberOfLayers) (getDrawable(i) as? ReleasableDrawable)?.release()
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.M3_NAVIGATION_ANIMATION.value

    @JvmStatic
    fun applyOpenFrame(layout: ActionBarLayout, progress: Float, preview: Boolean): Boolean {
        if (preview || !isEnabled()) return false
        val top = layout.containerView ?: return false
        val below = layout.containerViewBack ?: return false
        prepareBelow(layout, below)

        val offset = dpf2(Material3BackMotion.ENTER_OFFSET_DP)
        val spatial = Material3BackMotion.EMPHASIZED.getInterpolation(progress)
        top.translationX = offset * (1f - spatial)
        top.alpha = ((progress - OPENING_FADE_START) / (OPENING_FADE_END - OPENING_FADE_START)).coerceIn(0f, 1f)
        val belowTx = -offset * spatial
        below.eachChild { it.translationX = belowTx }
        return true
    }

    @JvmStatic
    fun applyCloseFrame(layout: ActionBarLayout, progress: Float, preview: Boolean): Boolean {
        if (preview || !isEnabled()) return false
        val below = layout.containerView ?: return false
        val top = layout.containerViewBack ?: return false
        prepareBelow(layout, below)

        val offset = dpf2(Material3BackMotion.ENTER_OFFSET_DP)
        val spatial = Material3BackMotion.EMPHASIZED.getInterpolation(progress)
        top.translationX = offset * spatial
        top.alpha = 1f - ((progress - CLOSING_FADE_START) / (CLOSING_FADE_END - CLOSING_FADE_START)).coerceIn(0f, 1f)
        val belowTx = -offset * (1f - spatial)
        below.eachChild { it.translationX = belowTx }
        return true
    }

    @JvmStatic
    fun applySlideProgress(layout: ActionBarLayout, progress: Float): Boolean {
        if (layout.predictiveBackInProgress || !isEnabled()) return false
        val below = layout.containerViewBack ?: return false
        prepareBelow(layout, below)
        val belowTx = -dpf2(Material3BackMotion.ENTER_OFFSET_DP) * (1f - progress.coerceIn(0f, 1f))
        below.eachChild { it.translationX = belowTx }
        return true
    }

    private fun prepareBelow(layout: ActionBarLayout, below: ViewGroup) {
        if (below.background is BelowBackground) return
        val fill = Material3BackMotion.getFragmentBackground(layout.backgroundFragment)?.constantState?.newDrawable()
            ?: ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite))
        below.background = BelowBackground(listOfNotNull(fill, captureEdgeExtension(below)))
    }

    private fun captureEdgeExtension(below: ViewGroup): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return EdgeExtensionDrawable.capture(below)
    }

    @JvmStatic
    fun cleanup(layout: ActionBarLayout) {
        val cv = layout.containerView
        val cvb = layout.containerViewBack
        if (cv?.background !is BelowBackground && cvb?.background !is BelowBackground) return
        resetBelow(cv)
        resetBelow(cvb)
        cv?.translationX = 0f
        cvb?.translationX = 0f
    }

    private fun resetBelow(container: ViewGroup?) {
        val background = container?.background as? BelowBackground ?: return
        background.release()
        container.background = null
        container.eachChild { it.translationX = 0f }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private class EdgeExtensionDrawable(private val node: RenderNode) : Drawable(), ReleasableDrawable {
    override fun draw(canvas: Canvas) {
        if (canvas !is RecordingCanvas || !node.hasDisplayList()) return
        node.pivotX = 0f
        node.pivotY = 0f
        node.scaleX = bounds.width().toFloat() / STRIP_PX
        node.translationX = bounds.left.toFloat()
        node.translationY = bounds.top.toFloat()
        canvas.drawRenderNode(node)
    }

    override fun release() {
        node.setUseCompositingLayer(false, null)
        node.discardDisplayList()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    companion object {
        private const val STRIP_PX = 1
        private const val MAX_SLICES = 8

        private class Band(val x: IntRange, val y: IntRange)
        private class Slice(val y: IntRange, val sampleX: Int)

        private fun collectScrollBarBands(view: View, offsetX: Int, offsetY: Int, bands: MutableList<Band>) {
            addScrollBarBand(view, offsetX, offsetY, bands)
            if (view !is ViewGroup) return
            view.eachChild {
                if (it.visibility != View.VISIBLE) return@eachChild
                collectScrollBarBands(
                    it,
                    offsetX + it.left + it.translationX.toInt() - view.scrollX,
                    offsetY + it.top + it.translationY.toInt() - view.scrollY,
                    bands,
                )
            }
        }

        private fun addScrollBarBand(view: View, offsetX: Int, offsetY: Int, bands: MutableList<Band>) {
            if (!view.isVerticalScrollBarEnabled) return
            // entiny: check thumb drawable alpha because scrollbar cache state flag has no public accessor
            if (view.verticalScrollbarThumbDrawable?.alpha == 0) return

            val size = if (view.scrollBarSize > 0) view.scrollBarSize else ViewConfiguration.get(view.context).scaledScrollBarSize
            val style = view.scrollBarStyle
            val outside = style == View.SCROLLBARS_OUTSIDE_OVERLAY || style == View.SCROLLBARS_OUTSIDE_INSET
            val right = offsetX + view.width - if (outside) 0 else view.paddingRight
            val trackTop = offsetY + if (outside) 0 else view.paddingTop
            val trackBottom = offsetY + view.height - if (outside) 0 else view.paddingBottom
            if (trackBottom <= trackTop) return

            val rows = if (view is RecyclerView) {
                computeThumbRows(view, trackTop, trackBottom - trackTop, size) ?: return
            } else {
                trackTop until trackBottom
            }
            bands += Band((right - size)..right, rows)
        }

        private fun computeThumbRows(view: RecyclerView, trackTop: Int, trackSize: Int, thickness: Int): IntRange? {
            val range = view.computeVerticalScrollRange()
            val extent = view.computeVerticalScrollExtent()
            if (extent <= 0 || range <= extent) return null

            var length = Math.round(trackSize.toFloat() * extent / range)
            var offset = Math.round((trackSize - length).toFloat() * view.computeVerticalScrollOffset() / (range - extent))
            val minLength = thickness * 2
            if (length < minLength) length = minLength
            if (offset + length > trackSize) offset = trackSize - length
            return (trackTop + offset) until (trackTop + offset + length)
        }

        private fun findSampleX(bands: List<Band>, rows: IntRange, width: Int): Int {
            var x = width - 1
            while (true) {
                val hit = bands.firstOrNull { x in it.x && it.y.first <= rows.last && rows.first <= it.y.last } ?: break
                x = hit.x.first - 1
                if (x < 0) return width - 1
            }
            return x
        }

        private fun buildSlices(below: ViewGroup): List<Slice> {
            val width = below.width
            val height = below.height
            val bands = ArrayList<Band>()
            collectScrollBarBands(below, 0, 0, bands)
            if (bands.isEmpty()) return listOf(Slice(0 until height, width - 1))

            val edges = sortedSetOf(0, height)
            bands.forEach {
                edges += it.y.first.coerceIn(0, height)
                edges += (it.y.last + 1).coerceIn(0, height)
            }
            val bounds = edges.toList()
            val slices = ArrayList<Slice>()
            for (i in 0 until bounds.size - 1) {
                val rows = bounds[i] until bounds[i + 1]
                if (rows.isEmpty()) continue
                val sampleX = findSampleX(bands, rows, width)
                val last = slices.lastOrNull()
                if (last != null && last.sampleX == sampleX) {
                    slices[slices.size - 1] = Slice(last.y.first..rows.last, sampleX)
                } else {
                    slices += Slice(rows, sampleX)
                }
            }
            if (slices.size > MAX_SLICES) return listOf(Slice(0 until height, findSampleX(bands, 0 until height, width)))
            return slices
        }

        private fun clipToRows(canvas: Canvas, slices: List<Slice>, height: Int) {
            var top = 0
            slices.forEach { slice ->
                if (slice.y.first > top) canvas.clipOutRect(0, top, STRIP_PX, slice.y.first)
                top = slice.y.last + 1
            }
            if (top < height) canvas.clipOutRect(0, top, STRIP_PX, height)
        }

        fun capture(below: ViewGroup): EdgeExtensionDrawable? {
            val width = below.width
            val height = below.height
            if (width <= STRIP_PX || height <= 0) return null

            val slices = buildSlices(below)
            val node = RenderNode("inu-m3-edge-extension")
            node.setPosition(0, 0, STRIP_PX, height)
            val canvas = node.beginRecording(STRIP_PX, height)
            try {
                slices.groupBy { it.sampleX }.forEach { (sampleX, group) ->
                    canvas.save()
                    clipToRows(canvas, group, height)
                    canvas.translate(-sampleX.toFloat(), 0f)
                    // entiny: use public draw() because dispatchDraw emits drawRenderNode for already-parented nodes
                    below.eachChild { child ->
                        if (child.visibility != View.VISIBLE) return@eachChild
                        canvas.save()
                        canvas.translate(child.left.toFloat(), child.top.toFloat())
                        child.draw(canvas)
                        canvas.restore()
                    }
                    canvas.restore()
                }
            } finally {
                node.endRecording()
            }
            if (!node.hasDisplayList()) return null
            node.setUseCompositingLayer(true, null)
            return EdgeExtensionDrawable(node)
        }
    }
}
