package desu.inugram.helpers.dialogs

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell

object ForumM3SectionsHelper {

    private const val OUTER_DP = 20f
    private const val INNER_DP = 4f
    private const val GAP_DP = 1f
    private const val SIDE_MARGIN_DP = 8f

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.FORUM_M3_SECTIONS.value

    private fun sectionOf(topic: TLRPC.TL_forumTopic?): Int = when {
        topic == null -> -1
        topic.pinned -> 1
        else -> 0
    }

    @JvmStatic
    fun style(view: View, prev: TLRPC.TL_forumTopic?, topic: TLRPC.TL_forumTopic?, next: TLRPC.TL_forumTopic?) {
        if (view !is DialogCell) return
        if (!isEnabled()) {
            if (view.background is InsetDrawable) view.background = null
            return
        }
        val sec = sectionOf(topic)
        view.background = cardBackground(sectionOf(prev) != sec, sectionOf(next) != sec)
    }

    @JvmStatic
    fun attach(listView: RecyclerView) {
        listView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (!isEnabled() || view !is DialogCell) {
                    outRect.set(0, 0, 0, 0)
                    return
                }
                val m = AndroidUtilities.dp(SIDE_MARGIN_DP)
                outRect.set(m, 0, m, 0)
            }

            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                if (isEnabled()) c.drawColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        })
    }

    private fun cardBackground(first: Boolean, last: Boolean): InsetDrawable {
        val gap = AndroidUtilities.dp(GAP_DP)
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            cornerRadii = radii(first, last)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(-1)
            cornerRadii = radii(first, last)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(Theme.getColor(Theme.key_listSelector)), card, mask)
        return InsetDrawable(ripple, 0, if (first) gap * 3 else gap, 0, if (last) gap * 3 else 0)
    }

    private fun radii(first: Boolean, last: Boolean): FloatArray {
        val o = AndroidUtilities.dp(OUTER_DP).toFloat()
        val n = AndroidUtilities.dp(INNER_DP).toFloat()
        val t = if (first) o else n
        val b = if (last) o else n
        return floatArrayOf(t, t, t, t, b, b, b, b)
    }
}
