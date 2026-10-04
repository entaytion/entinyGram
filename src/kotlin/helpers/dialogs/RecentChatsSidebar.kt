package desu.inugram.helpers.dialogs

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.ChatActivity
import org.telegram.ui.DialogsActivity

object RecentChatsSidebar {
    // entiny: bars live only in the fragment's own view tree, a static map pinned every closed chat
    private inline fun <reified T : View> find(fragment: BaseFragment): T? {
        val root = fragment.fragmentView as? ViewGroup ?: return null
        for (i in 0 until root.childCount) (root.getChildAt(i) as? T)?.let { return it }
        return null
    }

    @JvmStatic
    fun stripTopPush(fragment: DialogsActivity): Int =
        if (!FolderHelper.atBottom() && find<StripView>(fragment)?.active() == true) dp(STRIP_HEIGHT_DP.toFloat()) else 0

    @JvmStatic
    fun stripBottomPush(fragment: DialogsActivity): Int =
        if (FolderHelper.atBottom() && find<StripView>(fragment)?.active() == true) dp(STRIP_HEIGHT_DP.toFloat()) else 0

    fun toggleStrip(fragment: BaseFragment): Boolean {
        val strip = find<StripView>(fragment) ?: ensure(fragment)?.let { find<StripView>(fragment) } ?: return false
        strip.setShown(!strip.shown)
        return true
    }

    @JvmStatic
    fun toggleSidebar(fragment: BaseFragment): Boolean {
        val bar = find<SidebarView>(fragment) ?: ensure(fragment)?.let { find<SidebarView>(fragment) } ?: return false
        bar.setExpanded(!bar.expanded)
        return true
    }

    private fun ensure(fragment: BaseFragment): Unit? {
        if (fragment !is ChatActivity && fragment !is DialogsActivity) return null
        attach(fragment, (fragment as? ChatActivity)?.dialogId ?: 0L)
        return Unit
    }

    fun collapse(fragment: BaseFragment) {
        find<SidebarView>(fragment)?.setExpanded(false)
        find<StripView>(fragment)?.setShown(false)
    }

    private const val WIDTH_DP = 64
    private const val STRIP_HEIGHT_DP = 56

    @JvmStatic
    fun attach(fragment: BaseFragment, excludeDialogId: Long) {
        val style = InuConfig.RECENT_CHATS_STYLE.value
        if (style != InuConfig.RecentChatsStyleItem.SIDEBAR && style != InuConfig.RecentChatsStyleItem.STRIP) return
        val root = fragment.fragmentView as? ViewGroup ?: return
        val context = fragment.parentActivity ?: return
        if (style == InuConfig.RecentChatsStyleItem.SIDEBAR) {
            if (find<SidebarView>(fragment) != null) return
            val bar = SidebarView(context, fragment, excludeDialogId)
            val params = LayoutHelper.createFrame(WIDTH_DP, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.RIGHT or Gravity.TOP, 0f, 0f, 4f, 80f)
            root.addView(bar, params)
            val extra = if (fragment is DialogsActivity) dp(108f) else 0
            root.post {
                val barBottom = fragment.actionBar?.bottom ?: 0
                val minTop = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight
                params.topMargin = maxOf(barBottom, minTop) + extra + dp(8f)
                bar.layoutParams = params
            }
        } else if (fragment is DialogsActivity && find<StripView>(fragment) == null) {
            val strip = StripView(context, fragment, root)
            root.addView(strip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, STRIP_HEIGHT_DP.toFloat(), Gravity.TOP, 8f, 0f, 8f, 0f))
        }
    }

    private fun bubbleBackground() = GradientDrawable().apply {
        cornerRadius = dp(14f).toFloat()
        setColor((Theme.getColor(Theme.key_windowBackgroundWhite) and 0x00FFFFFF) or (0xE0 shl 24))
    }

    private fun avatarCell(context: Context, fragment: BaseFragment, dialogId: Long, obj: Any, withLabel: Boolean): View {
        val cell = LinearLayout(context)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(dp(2f), dp(6f), dp(2f), dp(4f))

        val avatar = BackupImageView(context)
        val size = if (withLabel) 40f else 44f
        avatar.setRoundRadius(dp(size / 2f))
        val drawable = AvatarDrawable()
        val name: String
        if (obj is TLRPC.Chat) {
            drawable.setInfo(obj)
            avatar.setForUserOrChat(obj, drawable)
            name = obj.title ?: ""
        } else {
            obj as TLRPC.User
            drawable.setInfo(obj)
            avatar.setForUserOrChat(obj, drawable)
            name = UserObject.getFirstName(obj)
        }
        cell.addView(avatar, LinearLayout.LayoutParams(dp(size), dp(size)))

        if (withLabel) {
            val label = TextView(context)
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            label.maxLines = 2
            label.gravity = Gravity.CENTER
            label.ellipsize = TextUtils.TruncateAt.END
            label.text = name
            cell.addView(label, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        cell.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2)
        cell.setOnClickListener {
            collapse(fragment)
            RecentChatsHelper.openChat(fragment, dialogId)
        }
        cell.setOnLongClickListener {
            RecentChatsHelper.openProfile(fragment, dialogId)
            true
        }
        return cell
    }

    private fun resolved(fragment: BaseFragment, excludeDialogId: Long): List<Pair<Long, Any>> {
        val controller = MessagesController.getInstance(fragment.currentAccount)
        return RecentChatsHelper.entries(fragment.currentAccount).mapNotNull { dialogId ->
            if (dialogId == excludeDialogId) return@mapNotNull null
            val obj: Any = (if (dialogId < 0) controller.getChat(-dialogId) else controller.getUser(dialogId)) ?: return@mapNotNull null
            dialogId to obj
        }
    }

    private class SidebarView(
        context: Context,
        private val fragment: BaseFragment,
        private val excludeDialogId: Long,
    ) : ScrollView(context) {
        private val column = LinearLayout(context)
        var expanded = false
            private set
        private val refresh = Runnable { rebuild() }

        init {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            column.orientation = LinearLayout.VERTICAL
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            rebuild()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            RecentChatsHelper.addListener(refresh)
            rebuild()
        }

        override fun onDetachedFromWindow() {
            RecentChatsHelper.removeListener(refresh)
            super.onDetachedFromWindow()
        }

        private fun rebuild() {
            column.removeAllViews()
            val items = resolved(fragment, excludeDialogId)
            for ((dialogId, obj) in items) {
                column.addView(avatarCell(context, fragment, dialogId, obj, true), LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
            hasItems = items.isNotEmpty()
            background = bubbleBackground()
            applyState()
        }

        private var hasItems = false

        fun setExpanded(value: Boolean) {
            expanded = value
            rebuild()
            applyState()
        }

        private fun applyState() {
            val on = InuConfig.RECENT_CHATS_STYLE.value == InuConfig.RecentChatsStyleItem.SIDEBAR && hasItems
            if (!on) expanded = false
            visibility = if (on && expanded) VISIBLE else GONE
        }
    }

    private class StripView(
        context: Context,
        private val fragment: DialogsActivity,
        private val root: ViewGroup,
    ) : HorizontalScrollView(context) {
        private val row = LinearLayout(context)
        var shown = false
            private set
        private val refresh = Runnable { rebuild() }
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            reposition()
            true
        }

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            rebuild()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            RecentChatsHelper.addListener(refresh)
            viewTreeObserver.addOnPreDrawListener(preDraw)
            rebuild()
        }

        override fun onDetachedFromWindow() {
            RecentChatsHelper.removeListener(refresh)
            viewTreeObserver.removeOnPreDrawListener(preDraw)
            super.onDetachedFromWindow()
        }

        private fun rebuild() {
            row.removeAllViews()
            val items = resolved(fragment, 0)
            for ((dialogId, obj) in items) {
                row.addView(avatarCell(context, fragment, dialogId, obj, false), LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT))
            }
            hasItems = items.isNotEmpty()
            background = bubbleBackground()
            applyState()
        }

        private var hasItems = false
        private var wasActive = false

        fun active(): Boolean =
            shown && hasItems && InuConfig.RECENT_CHATS_STYLE.value == InuConfig.RecentChatsStyleItem.STRIP

        fun setShown(value: Boolean) {
            shown = value
            rebuild()
            applyState()
        }

        private fun applyState() {
            if (InuConfig.RECENT_CHATS_STYLE.value != InuConfig.RecentChatsStyleItem.STRIP) shown = false
            val now = active()
            visibility = if (now) VISIBLE else GONE
            if (now != wasActive) {
                wasActive = now
                relayoutLists(root)
            }
        }

        private fun relayoutLists(view: View) {
            if (view is androidx.recyclerview.widget.RecyclerView) view.requestLayout()
            if (view is ViewGroup) for (i in 0 until view.childCount) relayoutLists(view.getChildAt(i))
        }

        private fun reposition() {
            if (visibility != VISIBLE) return
            val tabs = fragment.filterTabsView
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val y: Int
            if (tabs != null && tabs.visibility == VISIBLE && tabs.height > 0) {
                val tabsLoc = IntArray(2)
                tabs.getLocationOnScreen(tabsLoc)
                val tabsTop = tabsLoc[1] - rootLoc[1]
                y = if (InuConfig.FOLDERS_AT_BOTTOM.value) tabsTop - dp(STRIP_HEIGHT_DP.toFloat()) else tabsTop + tabs.height
            } else {
                y = (fragment.actionBar?.bottom ?: 0) + dp(60f)
            }
            translationY = y.toFloat()
        }
    }
}
