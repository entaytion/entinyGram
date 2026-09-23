package desu.inugram.ui.feed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManagerFixed
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.MessageObject
import org.telegram.ui.Cells.ChatMessageCell

// entiny: album rendering ported from MessagePreviewView -- 1000-span grid, sibling offsets and one bubble per group
internal object FeedAlbumLayout {

    const val SPAN_COUNT = 1000
    private val CARD_GAP = dp(6f)

    fun interface PositionLookup {
        fun at(adapterPosition: Int): Pair<MessageObject, MessageObject.GroupedMessages?>?
    }

    fun createLayoutManager(context: Context, lookup: PositionLookup): GridLayoutManagerFixed =
        object : GridLayoutManagerFixed(context, SPAN_COUNT, LinearLayoutManager.VERTICAL, false) {
            override fun shouldLayoutChildFromOpositeSide(child: View?): Boolean = false

            override fun hasSiblingChild(position: Int): Boolean {
                val (msg, group) = lookup.at(position) ?: return false
                val pos = group?.getPosition(msg) ?: return false
                if (pos.minX == pos.maxX || pos.minY != pos.maxY || pos.minY.toInt() == 0) return false
                return group.posArray.any { it !== pos && it.minY <= pos.minY && it.maxY >= pos.minY }
            }
        }.apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val (msg, group) = lookup.at(position) ?: return SPAN_COUNT
                    return group?.getPosition(msg)?.spanSize ?: SPAN_COUNT
                }
            }
        }

    class Decoration : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(0, 0, 0, 0)
            val cell = view as? ChatMessageCell ?: return
            val position = cell.currentPosition
            // even gap between cards; the whole first album row gets it so the grid stays aligned
            if (position == null || position.minY.toInt() == 0) outRect.top = CARD_GAP
            val group = cell.currentMessagesGroup ?: return
            val siblings = position?.siblingHeights ?: return
            val maxHeight = maxOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.5f
            var h = cell.extraInsetHeight
            for (s in siblings) h += Math.ceil((maxHeight * s).toDouble()).toInt()
            h += (position.maxY - position.minY) * Math.round(7 * AndroidUtilities.density)
            for (pos in group.posArray) {
                if (pos.minY != position.minY || pos.minX == position.minX && pos.maxX == position.maxX && pos.maxY == position.maxY) continue
                h -= Math.ceil((maxHeight * pos.ph).toDouble()).toInt() - dp(4f)
                break
            }
            outRect.bottom = -h
        }
    }

    private val drawingGroups = ArrayList<MessageObject.GroupedMessages>()

    // grouped cells skip their own bubble, so the list draws one background per album
    fun drawGroupBackgrounds(list: RecyclerView, canvas: Canvas) {
        drawingGroups.clear()
        for (i in 0 until list.childCount) {
            val cell = list.getChildAt(i) as? ChatMessageCell ?: continue
            if (cell.y > list.height || cell.y + cell.height < 0) continue
            val group = cell.currentMessagesGroup ?: continue
            val position = cell.currentPosition ?: continue
            if (group.messages.size < 2) continue
            val tp = group.transitionParams
            if (!drawingGroups.contains(group)) {
                tp.left = 0
                tp.top = 0
                tp.right = 0
                tp.bottom = 0
                tp.cell = cell
                drawingGroups.add(group)
            }
            tp.pinnedTop = cell.isPinnedTop
            tp.pinnedBotton = cell.isPinnedBottom
            val left = cell.left + cell.backgroundDrawableLeft
            val right = cell.left + cell.backgroundDrawableRight
            var top = cell.top + cell.paddingTop + cell.backgroundDrawableTop
            var bottom = cell.top + cell.paddingTop + cell.backgroundDrawableBottom
            if (position.flags and MessageObject.POSITION_FLAG_TOP == 0) top -= dp(10f)
            if (position.flags and MessageObject.POSITION_FLAG_BOTTOM == 0) bottom += dp(10f)
            if (tp.top == 0 || top < tp.top) tp.top = top
            if (tp.bottom == 0 || bottom > tp.bottom) tp.bottom = bottom
            if (tp.left == 0 || left < tp.left) tp.left = left
            if (tp.right == 0 || right > tp.right) tp.right = right
        }
        for (group in drawingGroups) {
            val tp = group.transitionParams
            val cell = tp.cell ?: continue
            val x = cell.getNonAnimationTranslationX(true)
            val t = maxOf(tp.top + tp.offsetTop + cell.translationY, -dp(20f).toFloat())
            val b = minOf(tp.bottom + tp.offsetBottom + cell.translationY, (list.measuredHeight + dp(20f)).toFloat())
            cell.drawBackground(canvas, (tp.left + x + tp.offsetLeft).toInt(), t.toInt(), (tp.right + x + tp.offsetRight).toInt(), b.toInt(), tp.pinnedTop, tp.pinnedBotton, false, 0)
            tp.cell = null
            tp.drawCaptionLayout = group.hasCaption
        }
        drawingGroups.clear()
    }
}
