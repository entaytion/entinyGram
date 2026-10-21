package desu.inugram.helpers.dialogs

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogCell

object ForumM3SectionsHelper {

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.FORUM_M3_SECTIONS.value

    private fun sectionOf(topic: TLRPC.TL_forumTopic?): Int = when {
        topic == null -> -1
        InuConfig.DIALOGS_M3_SEPARATE_PINNED.value && topic.pinned -> 1
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
        view.background = DialogsM3SectionsHelper.cardBackground(sectionOf(prev) != sec, sectionOf(next) != sec)
    }

    @JvmStatic
    fun attach(listView: RecyclerView) {
        listView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (!isEnabled() || view !is DialogCell) {
                    outRect.set(0, 0, 0, 0)
                    return
                }
                val m = DialogsM3SectionsHelper.sideMarginPx()
                outRect.set(m, 0, m, 0)
            }

            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                if (isEnabled()) c.drawColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        })
    }

}
