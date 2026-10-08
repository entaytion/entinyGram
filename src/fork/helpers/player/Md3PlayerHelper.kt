package desu.inugram.helpers.player

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.FolderHelper
import desu.inugram.helpers.dialogs.MainTabsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AudioPlayerAlert
import org.telegram.ui.Components.FragmentContextView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.UpdateLayoutWrapper
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min

object Md3PlayerHelper {
    const val BAR_HEIGHT_DP = 52

    private class BarState {
        var active = false
        private var viewRef: WeakReference<Md3PlayerBarView>? = null
        val hidden = ArrayList<WeakReference<View>>()
        var view: Md3PlayerBarView?
            get() = viewRef?.get()
            set(value) {
                viewRef = if (value != null) WeakReference(value) else null
            }
    }

    private val bars = WeakHashMap<FragmentContextView, BarState>()
    private var miniRef: WeakReference<Md3MiniPlayerView>? = null

    @JvmStatic
    fun enabled(): Boolean = InuConfig.MD3_PLAYER.value

    private fun handles(messageObject: MessageObject?): Boolean =
        enabled() && messageObject != null && messageObject.isMusic

    @JvmStatic
    fun create(context: Context, resourcesProvider: Theme.ResourcesProvider?): BottomSheet {
        if (!handles(MediaController.getInstance().playingMessageObject)) {
            return AudioPlayerAlert(context, resourcesProvider)
        }
        Md3PlayerSheet.instance?.dismissImmediately()
        return Md3PlayerSheet(context, resourcesProvider)
    }

    fun open(fragment: BaseFragment, source: Md3MiniPlayerView?) {
        val activity = fragment.parentActivity ?: return
        if (MediaController.getInstance().playingMessageObject == null) return
        val sheet = create(activity, fragment.resourceProvider)
        if (sheet is Md3PlayerSheet && source != null && source.canTransition()) {
            sheet.setTransitionSource(source)
        }
        fragment.showDialog(sheet)
    }

    @JvmStatic
    fun togglePlay() {
        val controller = MediaController.getInstance()
        val messageObject = controller.playingMessageObject ?: return
        if (controller.isDownloadingCurrentMessage) return
        if (controller.isMessagePaused) {
            controller.playMessage(messageObject)
        } else {
            controller.pauseMessage(messageObject)
        }
    }

    @JvmStatic
    fun hidesContextPlayer(fragment: BaseFragment?, messageObject: MessageObject?): Boolean {
        if (!handles(messageObject) || fragment == null || miniRef?.get() == null) return false
        return fragment.arguments?.getBoolean("hasMainTabs", false) == true
    }

    @JvmStatic
    fun barChanged(fcv: FragmentContextView): Boolean {
        val want = handles(MediaController.getInstance().playingMessageObject)
        val state = bars.getOrPut(fcv) { BarState() }
        if (state.active == want) return false
        state.active = want
        return true
    }

    @JvmStatic
    fun onStyle(fcv: FragmentContextView, audio: Boolean) {
        val state = bars[fcv] ?: return
        if (!audio) state.active = false
        if (!state.active) {
            state.view?.visibility = View.GONE
            restoreHidden(state)
        }
    }

    @JvmStatic
    fun isBarActive(fcv: FragmentContextView): Boolean = bars[fcv]?.active == true

    @JvmStatic
    fun syncBar(fcv: FragmentContextView) {
        val state = bars[fcv] ?: return
        if (!state.active) {
            state.view?.visibility = View.GONE
            restoreHidden(state)
            return
        }
        var bar = state.view
        if (bar == null) {
            bar = Md3PlayerBarView(fcv.context, null, { fcv.performClick() }, { MediaController.getInstance().cleanupPlayer(true, true) })
            fcv.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(BAR_HEIGHT_DP.toFloat()), Gravity.TOP or Gravity.LEFT))
            state.view = bar
        }
        bar.visibility = View.VISIBLE
        for (i in 0 until fcv.childCount) {
            val child = fcv.getChildAt(i)
            if (child !== bar && child.visibility == View.VISIBLE) {
                child.visibility = View.INVISIBLE
                state.hidden.add(WeakReference(child))
            }
        }
        bar.update()
    }

    private fun restoreHidden(state: BarState) {
        for (ref in state.hidden) {
            val child = ref.get() ?: continue
            if (child.visibility == View.INVISIBLE) child.visibility = View.VISIBLE
        }
        state.hidden.clear()
    }

    @JvmStatic
    fun attachMini(host: MainTabsActivity, content: FrameLayout) {
        if (!enabled()) return
        val view = Md3MiniPlayerView(content.context, host, host.resourceProvider)
        view.offsetListener = { notifyDialogs(host) }
        content.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp((Md3MiniPlayerView.HEIGHT_DP + 16).toFloat()), Gravity.BOTTOM))
        miniRef = WeakReference(view)
    }

    @JvmStatic
    fun layoutMini(host: MainTabsActivity, navigationBarHeight: Int, updateVisible: Boolean, tabsFactor: Float, position: Float) {
        val mini = miniRef?.get() ?: return
        if (mini.host !== host) return
        val hidden = MainTabsHelper.isHidden
        val tabs = when {
            hidden -> 0
            MainTabsHelper.isMaterial -> AndroidUtilities.dp(MainTabsHelper.mainTabsHeight.toFloat())
            else -> AndroidUtilities.dp((MainTabsHelper.mainTabsHeight + MainTabsHelper.mainTabsMargin).toFloat())
        }
        val update = if (updateVisible) AndroidUtilities.dp(UpdateLayoutWrapper.HEIGHT.toFloat()) else 0
        val folders = if (FolderHelper.atBottom()) AndroidUtilities.dp(FolderHelper.bottomReservedHeightDp().toFloat()) else 0
        val chats = 1f - min(1f, abs(position))
        val factor = (if (hidden) 1f else tabsFactor) * chats
        mini.setHostPosition(-(navigationBarHeight + update + tabs + folders + AndroidUtilities.dp(8f) - AndroidUtilities.dp(12f)).toFloat(), factor)
    }

    private fun notifyDialogs(host: MainTabsActivity) {
        val dialogs = host.dialogsActivity ?: return
        dialogs.updateFloatingButtonOffset()
        dialogs.checkUi_chatListViewPaddingsBottom()
    }

    private fun miniFor(fragment: DialogsActivity): Md3MiniPlayerView? {
        val mini = miniRef?.get() ?: return null
        return if (mini.host.dialogsActivity === fragment) mini else null
    }

    @JvmStatic
    fun miniFabInset(fragment: DialogsActivity): Float = miniFor(fragment)?.visibleOffset ?: 0f

    @JvmStatic
    fun miniListPadding(fragment: DialogsActivity): Int = miniFor(fragment)?.targetOffset ?: 0
}
