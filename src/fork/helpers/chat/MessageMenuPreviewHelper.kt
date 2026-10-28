package desu.inugram.helpers.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.ChatScrimPopupContainerLayout
import org.telegram.ui.Components.ReactionsContainerLayout

object MessageMenuPreviewHelper {
    @JvmStatic
    fun create(
        context: Context,
        cell: ChatMessageCell,
        menu: ChatScrimPopupContainerLayout,
        reactions: ReactionsContainerLayout?,
        dismiss: Runnable,
    ): Column? {
        if (!InuConfig.IOS_MESSAGE_MENU.value || InuConfig.DISABLE_SCRIM_BLUR.value) return null
        if (cell.messageObject == null || cell.currentMessagesGroup != null) return null
        return Column(context, cell, menu, reactions?.takeIf { it.parent != null }, dismiss)
    }

    class Column(
        context: Context,
        private val cell: ChatMessageCell,
        private val menu: ChatScrimPopupContainerLayout,
        private val reactions: ReactionsContainerLayout?,
        private val dismiss: Runnable,
    ) {
        @JvmField
        var shift = 0f

        @JvmField
        var clipTop = 0f

        @JvmField
        var onChanged: Runnable? = null

        private var scroll = 0f
        private var minScroll = 0f
        private var viewTop = 0f
        private var viewBottom = 0f
        private var postHeight = 0f
        private var postLeft = 0f
        private var postRight = 0f
        private var originalTop = 0f
        private lateinit var content: View

        init {
            menu.getChildAt(0)?.minimumWidth = dp(240f)
            for (i in 0 until menu.childCount) {
                (menu.getChildAt(i).layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 0
                    it.marginEnd = 0
                    it.leftMargin = 0
                    it.rightMargin = 0
                }
            }
            if (reactions != null) {
                (reactions.parent as? ViewGroup)?.removeView(reactions)
                menu.setReactionsLayout(null)
            }
        }

        val view =MessageMenuOverlay(context, menu, reactions, object : MessageMenuOverlay.Listener {
            private val location = IntArray(2)

            override fun contains(screenX: Float, screenY: Float): Boolean {
                content.getLocationOnScreen(location)
                val x = screenX - location[0]
                val y = screenY - location[1]
                val columnTop = viewTop + scroll
                val top = maxOf(columnTop, if (minScroll < 0f) viewTop else 0f)
                return x >= postLeft && x <= postRight && y >= top && y <= columnTop + postHeight
            }

            override fun canScroll() = minScroll < 0f

            override fun scroll(delta: Float) = scrollBy(delta)

            override fun dismiss() = dismiss.run()
        })

        private fun scrollBy(delta: Float) {
            if (minScroll >= 0f) return
            applyScroll((scroll + delta).coerceIn(minScroll, 0f))
        }

        private fun applyScroll(value: Float) {
            scroll = value
            shift = viewTop + scroll - originalTop
            view.setMenuTranslation(scroll)
            onChanged?.run()
        }

        fun show(
            window: ActionBarPopupWindow,
            list: View,
            content: View,
            viewportTop: Float,
            viewportBottom: Float,
        ) {
            this.content = content
            val message = cell.messageObject
            val cellX = list.x + cell.x
            val cellY = list.y + cell.y + cell.paddingTop
            postLeft = cellX + cell.backgroundDrawableLeft
            postRight = cellX + cell.backgroundDrawableRight
            originalTop = cellY + cell.backgroundDrawableTop
            postHeight = maxOf(cellY + cell.backgroundDrawableBottom, list.y + cell.y + cell.measuredHeight) - originalTop

            menu.setMaxHeight(0)
            val width = (list.width - dp(12f)).coerceAtLeast(1)
            menu.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val menuWidth = menu.measuredWidth
            val menuHeight = menu.measuredHeight

            val reactionsHeight = if (reactions != null) {
                view.measureReactions(Math.round(list.width - dp(12f).toFloat()))
            } else 0
            val reactionsInset = if (reactionsHeight > 0) reactionsHeight + dp(4f) else 0

            viewTop = viewportTop + reactionsInset
            viewBottom = viewportBottom
            val gap = 0f
            val total = postHeight + gap + menuHeight
            val available = viewBottom - viewTop

            if (total <= available) {
                minScroll = 0f
                clipTop = 0f
                val columnTop = originalTop.coerceIn(viewTop, viewBottom - total)
                viewTop = columnTop
                scroll = 0f
            } else {
                minScroll = -(total - available)
                clipTop = viewTop
                val visiblePost = minOf(postHeight, maxOf(available * 0.25f, dp(120f).toFloat()))
                scroll = maxOf(minScroll, -(postHeight - visiblePost))
            }
            shift = viewTop + scroll - originalTop

            val viewportLeft = list.x + dp(6f)
            val viewportRight = list.x + list.width - dp(6f)
            val card = menu.getChildAt(0)
            val menuLeft = (if (message.isOutOwner) postRight - menuWidth + card.paddingRight else postLeft - card.paddingLeft)
                .coerceIn(viewportLeft - card.paddingLeft, maxOf(viewportLeft, viewportRight - menuWidth + card.paddingRight))

            val location = IntArray(2)
            content.getLocationOnScreen(location)
            view.setMenuPosition(
                location[0] + Math.round(menuLeft),
                location[1] + Math.round(viewTop + postHeight + gap),
                menuWidth,
                menuHeight,
            )
            view.setMenuTranslation(scroll)
            if (reactionsHeight > 0) {
                val reactionsLeft = (if (message.isOutOwner) postRight - view.reactionsWidth else postLeft)
                    .coerceIn(viewportLeft, maxOf(viewportLeft, viewportRight - view.reactionsWidth))
                view.setReactionsPosition(
                    location[0] + Math.round(reactionsLeft),
                    location[1] + Math.round(viewTop) - reactionsHeight - dp(4f),
                )
            }

            window.setAnimationStyle(0)
            window.setAnimationEnabled(false)
            window.setLayoutInScreen(true)
            window.width = ViewGroup.LayoutParams.MATCH_PARENT
            window.height = ViewGroup.LayoutParams.MATCH_PARENT
            menu.alpha = 0f
            menu.animate().alpha(1f).setDuration(180).start()
        }
    }
}
