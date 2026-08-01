package desu.inugram.helpers.theme

import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
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
    const val ENTER_OFFSET_DP = 96f // entering screen starts this far off the left edge; closing slides this far off on commit
    const val SCRIM_ALPHA_BYTE = 77 // ~0.3 * 255 (AOSP uses 0.2 light / 0.8 dark; fixed 0.3 reads better in-app)
    const val SCRIM_FADE = 0.5f // scrim lifts by this much of commit progress (AOSP fades it over the full duration, which lingers past the motion)

    // AOSP fast_out_extra_slow_in (M3 "emphasized")
    val EMPHASIZED: Interpolator = PathInterpolator(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        }
    )

    // The fragment's own background to fill the M3 gap. ViewPagerActivity (e.g.
    // MainTabsActivity) sets hasOwnBackground but draws nothing itself — the visible color comes
    // from the current tab's inner fragment, so descend into it.
    fun getFragmentBackground(fragment: BaseFragment?): Drawable? {
        var f = fragment
        while (f is ViewPagerActivity) f = f.currentVisibleFragment
        // ProfileActivity keeps fragmentView transparent and paints via its children (gray listView),
        // so use the gray window background directly.
        if (f is ProfileActivity) return ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray))
        val bg = f?.fragmentView?.background
        // A transparent fill can't fill the gap — it would show the black window behind. Reject it so
        // the caller falls back to a solid color.
        if (bg is ColorDrawable && Color.alpha(bg.color) == 0) return null
        return bg
    }
}

// AOSP activity_{open,close}_{enter,exit}: both surfaces translate over 450ms on
// fast_out_extra_slow_in; the top (new/closing) one crossfades linearly over 83ms — from 50ms on
// open, from 35ms on close; the below one stays fully opaque and only parallaxes by 96dp.
// We run the same choreography at 300ms — fade windows are fractions of the total, so they scale
// proportionally rather than keeping AOSP's absolute offsets.
object Material3NavigationAnimation {
    const val DURATION = 300f
    private const val CLOSING_FADE_START = 35f / 450f
    private const val CLOSING_FADE_END = 118f / 450f
    private const val OPENING_FADE_START = 50f / 450f
    private const val OPENING_FADE_END = 133f / 450f

    private class BelowBackground(d: Drawable) : LayerDrawable(arrayOf(d))

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

    // Tracked swipe-back: below screen parallaxes linearly with the finger; the release animators
    // drive innerTranslationX too, so this covers the settle as well. Replaces the per-fragment
    // onSlideProgress parallax (Dialogs' 40dp slide) with the uniform 96dp one — returning false
    // hands the frame back to stock. Stock predictive back has its own transitions, keep out.
    @JvmStatic
    fun applySlideProgress(layout: ActionBarLayout, progress: Float): Boolean {
        if (layout.predictiveBackInProgress || !isEnabled()) return false
        val below = layout.containerViewBack ?: return false
        prepareBelow(layout, below)
        val belowTx = -dpf2(Material3BackMotion.ENTER_OFFSET_DP) * (1f - progress.coerceIn(0f, 1f))
        below.eachChild { it.translationX = belowTx }
        return true
    }

    // Children are translated instead of the container so the revealed strip on the right stays
    // covered by the container's own background (emulates AOSP's window <extend>). Translation is
    // a pure RenderNode matrix op — no layer promotion needed (and a HW layer would re-render per
    // frame under self-animating content like the dialogs list).
    private fun prepareBelow(layout: ActionBarLayout, below: ViewGroup) {
        if (below.background is BelowBackground) return
        below.background = BelowBackground(
            Material3BackMotion.getFragmentBackground(layout.backgroundFragment)
                ?.constantState?.newDrawable()
                ?: ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite))
        )
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
        if (container == null || container.background !is BelowBackground) return
        container.background = null
        container.eachChild { it.translationX = 0f }
    }
}
