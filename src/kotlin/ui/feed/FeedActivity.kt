package desu.inugram.ui.feed

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.feed.FeedConfig
import desu.inugram.helpers.feed.FeedController
import desu.inugram.helpers.dialogs.FolderHelper
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.feed.FeedScope
import desu.inugram.ui.settings.FeedExcludedChannelsSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.ChatActivityContainer
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode
import org.telegram.ui.MainTabsActivity

// entiny: feed as an embedded ChatActivity in search mode, ported from exteraless (https://github.com/exteraless/exteraless)
class FeedActivity @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") scope: FeedScope = FeedScope.Global,
    private val hasMainTabs: Boolean = false,
) : BaseFragment(), NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private var chatContainer: ChatActivityContainer? = null
    private var embeddedChatCreated = false
    private var lastConfigGeneration = 0
    private var lastWindowInsets: WindowInsetsCompat? = null
    private var parentTabsGlassInvalidationCallback: Runnable? = null
    private var resumedOnce = false
    private var uiActiveHeld = false
    private var uiResumedHeld = false
    private var viewportFullyVisible = false

    private val loadNewPosts = Runnable {
        if (uiResumedHeld) chatContainer?.chatActivity?.loadNewerFeed(true)
    }

    private val chat: ChatActivity? get() = chatContainer?.chatActivity

    override fun onFragmentCreate(): Boolean {
        viewportFullyVisible = !hasMainTabs
        val nc = NotificationCenter.getInstance(currentAccount)
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages)
        nc.addObserver(this, NotificationCenter.feedNeedReload)
        lastConfigGeneration = FeedConfig.getInstance(currentAccount).generation
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(loadNewPosts)
        destroyEmbeddedChat()
        if (uiResumedHeld) {
            uiResumedHeld = false
        }
        if (uiActiveHeld) {
            uiActiveHeld = false
            FeedController.getInstance(currentAccount).setUiActive(false)
        }
        Bulletin.removeDelegate(this)
        val nc = NotificationCenter.getInstance(currentAccount)
        nc.removeObserver(this, NotificationCenter.didReceiveNewMessages)
        nc.removeObserver(this, NotificationCenter.feedNeedReload)
        super.onFragmentDestroy()
    }

    override fun createView(context: Context): View {
        destroyEmbeddedChat()
        lastWindowInsets = null
        actionBar.setAddToContainer(false)
        actionBar.visibility = View.GONE

        val rootLayout = FrameLayout(context)
        fragmentView = rootLayout
        rootLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite))
        if (hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets -> extendInsetsByTabsHeight(insets) }
        }
        rootLayout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                val insets = lastWindowInsets
                if (insets != null) ViewCompat.dispatchApplyWindowInsets(view, insets) else view.requestApplyInsets()
                invalidateEmbeddedActionBar()
            }

            override fun onViewDetachedFromWindow(view: View) {}
        })

        val chatLayout = FrameLayout(context)
        rootLayout.addView(chatLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL))

        val chatArgs = Bundle().apply {
            putInt("chatMode", ChatActivity.MODE_SEARCH)
            putInt("searchType", FEED_SEARCH_TYPE)
            putBoolean(ARG_HAS_MAIN_TABS, hasMainTabs)
        }
        val container = object : ChatActivityContainer(context, parentLayout, chatArgs) {
            private var activityCreated = false

            override fun initChatActivity() {
                if (activityCreated) return
                activityCreated = true
                embeddedChatCreated = true
                chatActivity.reversed = InuConfig.FEED_NEWEST_ON_TOP.value
                super.initChatActivity()
                applyFloatingWindowLayout()
                setupChatActionBar()
                setupChatTitle()
                val insets = lastWindowInsets
                val view = fragmentView
                if (insets != null && view != null) ViewCompat.dispatchApplyWindowInsets(view, insets)
                invalidateParentTabsGlass()
            }
        }
        chatContainer = container
        container.chatActivity.isInsideContainer = false
        container.chatActivity.setFeedChannelsChangedCallback { updateFeedSubtitle() }
        container.chatActivity.setGlassSourceInvalidationCallback { invalidateParentTabsGlass() }
        updateFeedViewportActive(viewportFullyVisible)
        if (!uiResumedHeld) container.onPause()
        chatLayout.addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL))

        if (!uiActiveHeld) {
            uiActiveHeld = true
            FeedController.getInstance(currentAccount).setUiActive(true)
        }

        Bulletin.addDelegate(this, object : Bulletin.Delegate {
            override fun getBottomOffset(tag: Int): Int = chat?.bulletinBottomOffset ?: 0

            override fun getTopOffset(tag: Int): Int =
                chat?.bulletinTopOffset ?: (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())
        })
        return rootLayout
    }

    private fun extendInsetsByTabsHeight(windowInsets: WindowInsetsCompat): WindowInsetsCompat {
        lastWindowInsets = windowInsets
        val tabsHeight = if (MainTabsHelper.isHidden) 0 else AndroidUtilities.dp(MainTabsHelper.mainTabsHeightWithMargins.toFloat())
        if (tabsHeight == 0) return windowInsets
        val types = WindowInsetsCompat.Type.systemBars()
        val nav = WindowInsetsCompat.Type.navigationBars()
        return WindowInsetsCompat.Builder(windowInsets)
            .setInsets(types, extendBottom(windowInsets.getInsets(types), tabsHeight))
            .setInsets(nav, extendBottom(windowInsets.getInsets(nav), tabsHeight))
            .setInsetsIgnoringVisibility(types, extendBottom(windowInsets.getInsetsIgnoringVisibility(types), tabsHeight))
            .setInsetsIgnoringVisibility(nav, extendBottom(windowInsets.getInsetsIgnoringVisibility(nav), tabsHeight))
            .build()
    }

    private fun extendBottom(insets: Insets, extra: Int): Insets =
        Insets.of(insets.left, insets.top, insets.right, insets.bottom + extra)

    private fun applyFloatingWindowLayout() {
        val layout = parentLayout
        val chatActivity = chat ?: return
        if (layout == null || !layout.isLayersLayout) return
        chatActivity.actionBar?.setOccupyStatusBar(false)
        chatActivity.avatarContainer?.setOccupyStatusBar(false)
        chatActivity.contentView?.setOccupyStatusBar(false)
    }

    private fun setupChatActionBar() {
        val chatActivity = chat ?: return
        val chatActionBar = chatActivity.actionBar ?: return
        val menu = chatActionBar.createMenu()
        if (menu.getItem(MENU_MARK_ALL_READ) == null) {
            menu.addItem(MENU_MARK_ALL_READ, R.drawable.msg_markread, chatActivity.themeDelegate)
                .contentDescription = LocaleController.getString(R.string.InuFeedMarkAllRead)
        }
        if (menu.getItem(MENU_FEED_SETTINGS) == null) {
            menu.addItem(MENU_FEED_SETTINGS, R.drawable.msg_settings, chatActivity.themeDelegate)
                .contentDescription = LocaleController.getString(R.string.InuFeedManageChannels)
        }
        val chatItemClick = chatActionBar.actionBarMenuOnItemClick
        chatActionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun canOpenMenu(): Boolean = chatItemClick == null || chatItemClick.canOpenMenu()

            override fun onItemClick(id: Int) {
                if (id == -1 && hasMainTabs && !chatActionBar.isActionModeShowed) return
                when (id) {
                    MENU_MARK_ALL_READ -> showMarkAllReadDialog()
                    MENU_FEED_SETTINGS -> presentFragment(FeedExcludedChannelsSettingsActivity())
                    else -> chatItemClick?.onItemClick(id)
                }
            }
        })
    }

    private fun setupChatTitle() {
        val avatarContainer = chat?.avatarContainer ?: return
        avatarContainer.setTitle(LocaleController.getString(R.string.InuFeed))
        avatarContainer.setFeedAvatar()
        avatarContainer.setOnClickListener { showFolderPicker(avatarContainer) }
        updateFeedSubtitle()
    }

    private fun updateFeedSubtitle() {
        val controller = FeedController.getInstance(currentAccount)
        setFeedSubtitle(controller.includedChannelCount)
        controller.loadChannels { _, includedCount -> setFeedSubtitle(includedCount) }
    }

    private fun setFeedSubtitle(channelCount: Int) {
        val avatarContainer = chat?.avatarContainer ?: return
        val channels = LocaleController.formatPluralString("Channels", channelCount)
        val folderName = currentFolderName()
        avatarContainer.setSubtitle(if (folderName != null) "$folderName • $channels" else channels)
        avatarContainer.subtitleTextView?.visibility = View.VISIBLE
    }

    private fun currentFolderName(): String? {
        val filterId = FeedConfig.getInstance(currentAccount).folderFilterId
        if (filterId == 0) return null
        val filter = MessagesController.getInstance(currentAccount).dialogFilters?.firstOrNull { it.id == filterId } ?: return null
        if (filter.isDefault) return null
        return FolderHelper.getTabInfo(filter).first.takeIf { it.isNotEmpty() }
    }

    private fun pickableFolders(): List<MessagesController.DialogFilter> =
        MessagesController.getInstance(currentAccount).dialogFilters?.filter { !it.isDefault }.orEmpty()

    private fun showFolderPicker(anchor: View) {
        val folders = pickableFolders()
        if (folders.isEmpty()) {
            presentFragment(FeedExcludedChannelsSettingsActivity())
            return
        }
        val current = FeedConfig.getInstance(currentAccount).folderFilterId
        val options = ItemOptions.makeOptions(this, anchor)
        options.setGravity(Gravity.LEFT)
        if (current != 0) {
            options.add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedAllChannels)) { selectFolder(0) }
        }
        for (filter in folders) {
            if (filter.id == current) continue
            val info = FolderHelper.getTabInfo(filter)
            val name = info.first.takeIf { it.isNotEmpty() } ?: continue
            options.add(FolderHelper.getTabIcon(info.second), name) { selectFolder(filter.id) }
        }
        options.show()
    }

    private fun selectFolder(filterId: Int) {
        val config = FeedConfig.getInstance(currentAccount)
        config.setFolder(filterId)
        lastConfigGeneration = config.generation
        chat?.applyFeedConfigChange()
        updateFeedSubtitle()
    }

    private fun showMarkAllReadDialog() {
        val activity = parentActivity ?: return
        val builder = AlertDialog.Builder(activity, resourceProvider)
        builder.setTitle(LocaleController.getString(R.string.InuFeedMarkAllRead))
        builder.setMessage(LocaleController.getString(R.string.InuFeedMarkAllReadConfirm))
        builder.setPositiveButton(LocaleController.getString(R.string.MarkAsRead)) { _, _ ->
            markAllRead()
            BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.InuFeedMarkAllReadDone))
                .show()
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        showDialog(builder.create())
    }

    fun markAllRead() {
        val chatActivity = chat
        if (chatActivity == null) FeedController.getInstance(currentAccount).markAllRead() else chatActivity.markFeedAsRead()
    }

    private fun destroyEmbeddedChat() {
        val chatActivity = chat
        if (chatActivity != null) {
            if (!hasMainTabs && embeddedChatCreated) chatActivity.saveFeedScrollPosition()
            chatActivity.setFeedChannelsChangedCallback(null)
            chatActivity.setGlassSourceInvalidationCallback(null)
            if (embeddedChatCreated) chatActivity.onFragmentDestroy()
        }
        embeddedChatCreated = false
        chatContainer = null
    }

    private fun updateFeedViewportActive(active: Boolean) {
        chat?.setFeedViewportActive(active)
    }

    private fun reattachCurrentFeedVideoTexture() {
        chat?.reattachCurrentFeedVideoTexture()
    }

    private fun invalidateEmbeddedActionBar() {
        val chatActionBar = chat?.actionBar ?: return
        chatActionBar.invalidate()
        for (i in 0 until chatActionBar.childCount) chatActionBar.getChildAt(i).invalidate()
    }

    private fun invalidateParentTabsGlass() {
        parentTabsGlassInvalidationCallback?.run()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            val scheduled = args[2] as Boolean
            if (scheduled || chatContainer == null || !FeedController.getInstance(currentAccount).isIncludedChannelPost(args[0] as Long)) return
            AndroidUtilities.cancelRunOnUIThread(loadNewPosts)
            AndroidUtilities.runOnUIThread(loadNewPosts, LOAD_NEW_POSTS_DELAY)
        } else if (id == NotificationCenter.feedNeedReload) {
            val truncated = args.isNotEmpty() && args[0] == true
            chat?.onFeedChannelsChanged(truncated)
            updateFeedSubtitle()
        }
    }

    override fun onResume() {
        super.onResume()
        chatContainer?.let {
            it.onResume()
            updateFeedViewportActive(viewportFullyVisible)
        }
        uiResumedHeld = true
        fragmentView?.let {
            val insets = lastWindowInsets
            if (insets != null) ViewCompat.dispatchApplyWindowInsets(it, insets) else it.requestApplyInsets()
        }
        reattachCurrentFeedVideoTexture()
        invalidateEmbeddedActionBar()

        val generation = FeedConfig.getInstance(currentAccount).generation
        if (generation != lastConfigGeneration) {
            lastConfigGeneration = generation
            chat?.applyFeedConfigChange()
        } else if (resumedOnce) {
            chat?.let {
                it.reconcileFeedList()
                it.refreshFeedUnreadDivider()
                if (FeedController.getInstance(currentAccount).messages.isNotEmpty()) it.loadNewerFeed(true)
            }
        }
        resumedOnce = true
        updateFeedSubtitle()
    }

    override fun onPause() {
        super.onPause()
        chatContainer?.let {
            updateFeedViewportActive(false)
            it.onPause()
        }
        uiResumedHeld = false
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        viewportFullyVisible = true
        updateFeedViewportActive(true)
        reattachCurrentFeedVideoTexture()
        invalidateEmbeddedActionBar()
    }

    override fun onBecomeFullyHidden() {
        viewportFullyVisible = false
        updateFeedViewportActive(false)
        super.onBecomeFullyHidden()
    }

    override fun onTransitionAnimationStart(isOpen: Boolean, backward: Boolean) {
        invalidateEmbeddedActionBar()
        if (hasMainTabs) {
            viewportFullyVisible = false
            updateFeedViewportActive(false)
        }
        super.onTransitionAnimationStart(isOpen, backward)
    }

    override fun onTransitionAnimationProgress(isOpen: Boolean, progress: Float) {
        super.onTransitionAnimationProgress(isOpen, progress)
        invalidateEmbeddedActionBar()
    }

    override fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) {
        super.onTransitionAnimationEnd(isOpen, backward)
        invalidateEmbeddedActionBar()
        if (hasMainTabs) {
            viewportFullyVisible = isOpen
            updateFeedViewportActive(isOpen)
        }
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        val chatActivity = chat
        val bar = chatActivity?.actionBar
        if (chatActivity == null || bar == null || !bar.isActionModeShowed) return super.onBackPressed(invoked)
        if (invoked) chatActivity.clearSelectionMode()
        return false
    }

    override fun isLightStatusBar(): Boolean = chat?.isLightStatusBar ?: !Theme.isCurrentThemeDark()

    override fun isSupportEdgeToEdge(): Boolean = true

    override fun drawEdgeNavigationBar(): Boolean = false

    override fun canParentTabsSlide(ev: MotionEvent, forward: Boolean): Boolean =
        chat?.actionBar?.isActionModeShowed != true

    override fun onParentScrollToTop() {
        chat?.onPageDownClicked()
    }

    override fun getGlassSource(): BlurredBackgroundSourceRenderNode? = chat?.glassSource

    override fun onParentBecomeFullyVisible() {
        reattachCurrentFeedVideoTexture()
    }

    override fun setParentTabsGlassInvalidationCallback(callback: Runnable?) {
        parentTabsGlassInvalidationCallback = callback
    }

    private companion object {
        const val ARG_HAS_MAIN_TABS = "hasMainTabs"
        const val FEED_SEARCH_TYPE = 4
        const val MENU_FEED_SETTINGS = 75
        const val MENU_MARK_ALL_READ = 76
        const val LOAD_NEW_POSTS_DELAY = 1000L
    }
}
