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
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Adapters.DialogsAdapter
import org.telegram.ui.Cells.DialogCell

object DialogsM3SectionsHelper {

    private const val OUTER_DP = 20f
    private const val INNER_DP = 4f
    private const val GAP_DP = 1f

    private val applied = java.util.WeakHashMap<View, Int>()

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.DIALOGS_M3_SECTIONS.value

    private fun sideMarginDp(collapsed: Boolean = false): Float = when {
        collapsed -> 0f
        InuConfig.DIALOGS_M3_MARGIN.value == 0 -> 0f
        InuConfig.DIALOGS_M3_MARGIN.value == 2 -> 14f
        else -> 8f
    }

    private fun sectionOf(adapter: DialogsAdapter, i: Int): Int {
        if (i < 0 || i >= adapter.itemCount) return -1
        val d = adapter.getItem(i) as? TLRPC.Dialog ?: return -1
        return when {
            d.isFolder -> 2
            InuConfig.DIALOGS_M3_SEPARATE_PINNED.value && isPinned(adapter, d) -> 1
            else -> 0
        }
    }

    // entiny: folder tabs keep pins in the filter, not in Dialog.pinned
    private fun isPinned(adapter: DialogsAdapter, d: TLRPC.Dialog): Boolean {
        val type = adapter.dialogsType
        if (type != 7 && type != 8) return d.pinned
        val account = UserConfig.selectedAccount
        val filter = MessagesController.getInstance(account).selectedDialogFilter[type - 7] ?: return d.pinned
        return filter.pinnedDialogs.indexOfKey(d.id) >= 0 || PinHelper.isLocallyPinned(account, 0, filter, d.id)
    }

    @JvmStatic
    fun sideMarginPx(): Int = AndroidUtilities.dp(sideMarginDp())

    @JvmStatic
    fun cardBackground(first: Boolean, last: Boolean): InsetDrawable {
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

    @JvmStatic
    fun style(view: View, position: Int, adapter: DialogsAdapter) {
        if (view !is DialogCell) return
        if (!isEnabled()) {
            restore(view)
            return
        }
        val sec = sectionOf(adapter, position)
        val first = sectionOf(adapter, position - 1) != sec
        val last = sectionOf(adapter, position + 1) != sec
        val code = (if (first) 1 else 0) or (if (last) 2 else 0)
        if (applied[view] != code || view.background !is InsetDrawable) {
            applied[view] = code
            view.background = cardBackground(first, last)
        }
        view.useSeparator = false
        view.fullSeparator = false
        val start = if (sideMarginDp(view.collapsed) == 0f) 11 else 8
        if (view.avatarStart != start) {
            view.avatarStart = start
            view.messagePaddingStart = start + 61
            view.buildLayout()
        }
    }

    private fun restore(view: DialogCell) {
        if (view.avatarStart == 11 && view.background !is InsetDrawable) return
        view.background = null
        view.avatarStart = 11
        view.messagePaddingStart = 72
        view.buildLayout()
    }

    @JvmStatic
    fun attach(listView: RecyclerView) {
        listView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (!isEnabled() || view !is DialogCell) {
                    outRect.set(0, 0, 0, 0)
                    return
                }
                val m = AndroidUtilities.dp(sideMarginDp(view.collapsed))
                outRect.set(m, 0, m, 0)
            }

            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                if (!isEnabled()) return
                c.drawColor(Theme.getColor(Theme.key_windowBackgroundGray))
                val adapter = parent.adapter as? DialogsAdapter ?: return
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i) as? DialogCell ?: continue
                    val pos = parent.getChildAdapterPosition(child)
                    if (pos < 0) continue
                    val sec = sectionOf(adapter, pos)
                    val code = (if (sectionOf(adapter, pos - 1) != sec) 1 else 0) or (if (sectionOf(adapter, pos + 1) != sec) 2 else 0)
                    if (applied[child] != code) style(child, pos, adapter)
                }
            }
        })
    }

    @JvmStatic
    fun refresh() {
        val acc = UserConfig.selectedAccount
        NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.dialogsNeedReload)
    }
}
