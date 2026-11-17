package desu.inugram.helpers.dialogs

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell

object ForumM3SectionsHelper {

    private const val SIDE_MARGIN_DP = 8f

    private val applied = java.util.WeakHashMap<View, Int>()

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
        val first = sectionOf(prev) != sec
        val last = sectionOf(next) != sec
        val code = DialogsM3SectionsHelper.appearanceKey(first, last)
        if (applied[view] != code || view.background !is InsetDrawable) {
            applied[view] = code
            view.background = DialogsM3SectionsHelper.cardBackground(first, last)
        }
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
}
