package desu.inugram.ui.feed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.FolderHelper
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.feed.FeedChannelSet
import desu.inugram.helpers.feed.FeedController
import desu.inugram.helpers.feed.FeedScope
import desu.inugram.helpers.feed.FeedStore
import desu.inugram.helpers.theme.NonIslandHelper
import desu.inugram.ui.settings.FeedExcludedChannelsSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_update
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BackDrawable
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatAvatarContainer
import org.telegram.ui.Components.ItemOptions
import android.graphics.drawable.Drawable
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MotionBackgroundDrawable
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped
import org.telegram.ui.Components.chat.WallpaperBitmapProvider
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView
import org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout
import java.util.concurrent.Executors

class FeedActivity @JvmOverloads constructor(
    private val scope: FeedScope = FeedScope.Global,
    private val hasMainTabs: Boolean = false,
) : BaseFragment(), FeedController.Listener {

    private sealed class Item {
        class Post(
            val message: MessageObject,
            val group: MessageObject.GroupedMessages?,
            val revision: Int,
            val groupRevision: Int,
        ) : Item() {
            val key = FeedStore.keyOf(message)
        }

        object Divider : Item()
    }

    private var newestOnTop = InuConfig.FEED_NEWEST_ON_TOP.value
    private val markReadOnScroll: Boolean get() = InuConfig.FEED_MARK_READ_ON_SCROLL.value

    private val controller: FeedController by lazy {
        val folder = scope as? FeedScope.Folder
        if (folder != null) FeedController.forFolder(currentAccount, folder.filterId) else FeedController.get(currentAccount)
    }
    private val store: FeedStore get() = controller.store

    private var items: List<Item> = emptyList()
    @Volatile private var diffGeneration = 0
    private var pendingNewPosts = false
    private var timelineDiffPending = false
    private val groups = HashMap<Long, MessageObject.GroupedMessages>()
    private val groupSignatures = HashMap<Long, List<Int>>()
    private val groupRevisions = HashMap<Long, Int>()

    // entiny: pinned on open so the divider doesn't chase the reader while posts get marked read
    private var dividerKey: FeedStore.Key? = null

    private var listView: RecyclerListView? = null
    private var layoutManager: LinearLayoutManager? = null
    private var adapter: FeedAdapter? = null
    private var emptyView: View? = null
    private var indicatorMode = -1
    private var newPostsPill: TextView? = null
    private var sideControls: ChatActivitySideControlsButtonsLayout? = null
    private var fadeView: ChatActivityFadeView? = null
    private val wallpaperBitmapProvider = WallpaperBitmapProvider()
    private val navbarContentSourceWallpaper = BlurredBackgroundSourceWrapped()
    private val navbarContentDrawableFactory = BlurredBackgroundDrawableViewFactory(navbarContentSourceWallpaper)
    private var loadingOlder = false
    private var reachedEnd = false
    private var firstShown = false

    private val reactionsCheckedAt = HashMap<FeedStore.Key, Long>()
    private val reactionTapSeq = HashMap<FeedStore.Key, Int>()
    private val reactionPollRequests = ArrayList<Int>()

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inu_feed", Context.MODE_PRIVATE)
    }
    private val positionPrefKey: String
        get() = "pos$currentAccount-" + ((scope as? FeedScope.Folder)?.let { "f${it.filterId}" } ?: "g")

    private val additionNavigationBarHeight: Int
        get() = if (hasMainTabs && !MainTabsHelper.isHidden) dp(MainTabsHelper.mainTabsHeightWithMargins.toFloat()) else 0
    private var navigationBarHeight = 0

    override fun createView(context: Context): View {
        setupActionBar(context)

        val frameLayout = object : SizeNotifierFrameLayout(context) {
            override fun useRootView(): Boolean = false

            override fun onUpdateBackgroundDrawable(drawable: Drawable?) {
                super.onUpdateBackgroundDrawable(drawable)
                if (drawable is MotionBackgroundDrawable) {
                    drawable.setFastRenderAllowed()
                }
                val source = wallpaperBitmapProvider.updateSourceFromBackgroundViewDrawable(drawable)
                navbarContentSourceWallpaper.source = source
                (source as? BlurredBackgroundSourceBitmap)?.setParentSize(width, height, 0)
                fadeView?.invalidate()
                avatarContainer?.updateColors()
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                (navbarContentSourceWallpaper.source as? BlurredBackgroundSourceBitmap)?.setParentSize(w, h, 0)
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                updateTopFade()
            }
        }
        frameLayout.setOccupyStatusBar(false)
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion())

        val feedAdapter = FeedAdapter(context)
        adapter = feedAdapter
        val lm = FeedAlbumLayout.createLayoutManager(context) { position ->
            (items.getOrNull(position) as? Item.Post)?.let { it.message to it.group }
        }
        layoutManager = lm
        val recycler = object : RecyclerListView(context) {
            override fun dispatchDraw(canvas: Canvas) {
                FeedAlbumLayout.drawGroupBackgrounds(this, canvas)
                super.dispatchDraw(canvas)
            }
        }
        recycler.setItemAnimator(null)
        recycler.setLayoutAnimation(null)
        // entiny: post cells are expensive to bind, so keep a few more around for scrolling back and forth
        recycler.setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE)
        recycler.recycledViewPool.setMaxRecycledViews(VIEW_TYPE_POST, POST_POOL_SIZE)
        recycler.layoutManager = lm
        recycler.addItemDecoration(FeedAlbumLayout.Decoration())
        recycler.setVerticalScrollBarEnabled(true)
        recycler.clipToPadding = false
        recycler.adapter = feedAdapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && markReadOnScroll) markVisibleRead()
                if (isAtNewest()) hidePill()
                if (isNearOlderEnd()) loadOlder()
                updatePageDownButton()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) refreshVisibleReactions()
            }
        })
        listView = recycler
        frameLayout.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val fade = ChatActivityFadeView(context).apply {
            setup(navbarContentDrawableFactory)
            setFadeHeightTop(dp(48f))
        }
        fadeView = fade
        frameLayout.addView(fade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        emptyView = createEmptyView(context).also {
            frameLayout.addView(it, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        }
        indicatorMode = -1
        installIndicator(frameLayout)
        frameLayout.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        applyInsets()

        fragmentView = frameLayout
        // entiny: tab pages receive consumed insets from ViewPagerActivity so bottom padding is applied manually
        if (hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { _, insets ->
                navigationBarHeight = AndroidUtilities.getDefaultWindowInsets(insets, false).bottom
                applyInsets()
                WindowInsetsCompat.CONSUMED
            }
        }

        controller.attach(this)
        if (store.size == 0) loadOlder() else showInitial()
        return frameLayout
    }

    // entiny: pill ("%d new", only appears when a post streams in while scrolled away) vs a persistent
    // scroll-to-bottom button with unread badge (same control regular chats use) -- user's choice
    private fun installIndicator(frame: FrameLayout) {
        val mode = InuConfig.FEED_NEW_POSTS_INDICATOR.value
        if (mode == indicatorMode) return
        indicatorMode = mode
        newPostsPill?.let { frame.removeView(it) }
        sideControls?.let { frame.removeView(it) }
        newPostsPill = null
        sideControls = null
        val context = frame.context
        val index = frame.indexOfChild(actionBar).takeIf { it >= 0 } ?: frame.childCount
        if (mode == INDICATOR_BUTTON) {
            val controls = ChatActivitySideControlsButtonsLayout(
                context, resourceProvider,
                BlurredBackgroundColorProviderThemed(resourceProvider, Theme.key_chat_messagePanelBackground),
                BlurredBackgroundDrawableViewFactory(BlurredBackgroundSourceColor().apply { setColor(getThemedColor(Theme.key_chat_messagePanelBackground)) }),
            ).apply {
                setGravity(Gravity.RIGHT or Gravity.BOTTOM)
                setOnClickListener { buttonId, _ ->
                    if (buttonId == ChatActivitySideControlsButtonsLayout.BUTTON_PAGE_DOWN) {
                        scrollToNewest()
                        showButton(ChatActivitySideControlsButtonsLayout.BUTTON_PAGE_DOWN, false, true)
                    }
                }
            }
            sideControls = controls
            frame.addView(controls, index, LayoutHelper.createFrame(57, 300, Gravity.RIGHT or Gravity.BOTTOM))
        } else {
            val pill = createPill(context)
            newPostsPill = pill
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_HORIZONTAL or if (newestOnTop) Gravity.TOP else Gravity.BOTTOM
            frame.addView(pill, index, lp)
        }
    }

    override fun onResume() {
        super.onResume()
        applySettingsChanges()
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        applySettingsChanges()
    }

    // entiny: feed settings are edited on another screen, so pick them up when we come back
    private fun applySettingsChanges() {
        val frame = fragmentView as? FrameLayout ?: return
        if (InuConfig.FEED_NEW_POSTS_INDICATOR.value != indicatorMode) {
            installIndicator(frame)
            applyInsets()
            if (firstShown) {
                if (!isAtNewest()) showPill(countUnread())
                updatePageDownButton()
            }
        }
        if (!firstShown) return
        val orderChanged = newestOnTop != InuConfig.FEED_NEWEST_ON_TOP.value
        newestOnTop = InuConfig.FEED_NEWEST_ON_TOP.value
        val channelsChanged = store.ensureChannelGeneration()
        if (!orderChanged && !channelsChanged) return
        (newPostsPill?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.CENTER_HORIZONTAL or if (newestOnTop) Gravity.TOP else Gravity.BOTTOM
            it.topMargin = 0
            it.bottomMargin = 0
        }
        applyInsets()
        hidePill()
        // order flips every index, so a full rebind is cheaper than a diff here
        diffGeneration++
        timelineDiffPending = false
        items = buildItems()
        adapter?.notifyDataSetChanged()
        if (channelsChanged) {
            updateFeedSubtitle()
            reachedEnd = false
            loadOlder()
        } else {
            scrollToDivider()
        }
        updatePageDownButton()
    }

    override fun onFragmentDestroy() {
        diffGeneration++
        timelineDiffPending = false
        pendingNewPosts = false
        cancelReactionPolls()
        avatarContainer?.onDestroy()
        avatarContainer = null
        fadeView = null
        savePosition()
        controller.detach(this)
        super.onFragmentDestroy()
    }

    // region timeline

    override fun onTimelineChanged(hasNewPosts: Boolean) {
        updateTimeline(hasNewPosts)
    }

    private fun updateTimeline(hasNewPosts: Boolean) {
        if (fragmentView == null) return
        if (!firstShown) {
            showInitial()
            return
        }
        pendingNewPosts = pendingNewPosts || hasNewPosts
        submitItems { wasAtNewest ->
            val hasPendingNewPosts = pendingNewPosts
            pendingNewPosts = false
            if (hasPendingNewPosts && wasAtNewest) scrollToNewest()
            if (isAtNewest()) hidePill() else if (hasPendingNewPosts) showPill(countUnread()) else refreshPill()
            updatePageDownButton()
            if (!loadingOlder && !reachedEnd && (store.size < MIN_INITIAL_ROWS || isNearOlderEnd())) loadOlder()
        }
    }

    override fun onBackfilled() {
        if (fragmentView == null) return
        reachedEnd = false
        if (store.size < MIN_INITIAL_ROWS || isNearOlderEnd()) loadOlder()
    }

    private fun loadOlder() {
        if (loadingOlder || reachedEnd || timelineDiffPending) return
        loadingOlder = true
        controller.loadOlder { added ->
            loadingOlder = false
            if (fragmentView == null) return@loadOlder
            if (added == 0) reachedEnd = true
            updateTimeline(false)
        }
    }

    private fun showInitial() {
        if (firstShown) return
        firstShown = true
        dividerKey = oldestUnread()?.let { FeedStore.keyOf(it) }
        submitItems(asynchronously = false)
        if (!restorePosition()) scrollToDivider()
        val oldestLoaded = store.snapshot().lastOrNull()
        // context above the first unread post
        if (store.size < MIN_INITIAL_ROWS || oldestLoaded != null && dividerKey == FeedStore.keyOf(oldestLoaded)) loadOlder()
        val unread = countUnread()
        if (unread > 0 && !isAtNewest()) showPill(unread)
        updatePageDownButton()
        emptyView?.visibility = if (store.size == 0) View.VISIBLE else View.GONE
    }

    private fun submitItems(asynchronously: Boolean = true, onApplied: ((wasAtNewest: Boolean) -> Unit)? = null) {
        unreadCount = -1
        val newItems = buildItems()
        val old = items
        val generation = ++diffGeneration
        val callback = object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newItems[n]
                if (a is Item.Post && b is Item.Post) return a.key == b.key
                return a === b
            }
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newItems[n]
                if (a !is Item.Post || b !is Item.Post) return a === b
                return a.message === b.message && a.revision == b.revision && a.group === b.group && a.groupRevision == b.groupRevision
            }
        }
        val apply: (DiffUtil.DiffResult) -> Unit = { diff ->
            if (generation == diffGeneration && fragmentView != null) {
                timelineDiffPending = false
                // entiny: sampled right before the swap -- the user may have scrolled while the diff ran
                val wasAtNewest = isAtNewest()
                items = newItems
                adapter?.let { diff.dispatchUpdatesTo(it) }
                emptyView?.visibility = if (firstShown && newItems.isEmpty()) View.VISIBLE else View.GONE
                onApplied?.invoke(wasAtNewest)
            }
        }
        if (!asynchronously) {
            timelineDiffPending = false
            apply(DiffUtil.calculateDiff(callback, false))
        } else {
            timelineDiffPending = true
            DIFF_EXECUTOR.execute {
                if (generation != diffGeneration) return@execute
                val diff = DiffUtil.calculateDiff(callback, false)
                AndroidUtilities.runOnUIThread { apply(diff) }
            }
        }
    }

    private fun buildItems(): List<Item> {
        val snapshot = store.snapshot()
        refreshGroups(snapshot)
        val ordered = if (newestOnTop) snapshot else snapshot.asReversed()
        val out = ArrayList<Item>(ordered.size + 1)
        val emitted = HashSet<Long>()
        for (msg in ordered) {
            val group = validGroup(msg)
            if (group != null) {
                if (!emitted.add(group.groupId)) continue
                if (group.messages.any { FeedStore.keyOf(it) == dividerKey }) out.add(Item.Divider)
                val groupRevision = groupRevisions[group.groupId] ?: 0
                for (part in group.messages) out.add(Item.Post(part, group, store.revision(part), groupRevision))
            } else {
                if (FeedStore.keyOf(msg) == dividerKey) out.add(Item.Divider)
                out.add(Item.Post(msg, null, store.revision(msg), 0))
            }
        }
        return out
    }

    // entiny: reuse group objects and only relayout when the album's members change
    private fun refreshGroups(snapshot: List<MessageObject>) {
        val members = HashMap<Long, ArrayList<MessageObject>>()
        for (msg in snapshot) {
            if (msg.hasValidGroupId()) members.getOrPut(msg.getGroupId()) { ArrayList() }.add(msg)
        }
        groups.keys.retainAll(members.keys)
        groupSignatures.keys.retainAll(members.keys)
        groupRevisions.keys.retainAll(members.keys)
        for ((groupId, parts) in members) {
            if (parts.size < 2) {
                groups.remove(groupId)
                groupSignatures.remove(groupId)
                continue
            }
            parts.sortBy { it.id }
            val signature = parts.map { System.identityHashCode(it) }
            if (groupSignatures[groupId] == signature) continue
            val group = groups.getOrPut(groupId) { MessageObject.GroupedMessages().apply { this.groupId = groupId } }
            group.messages.clear()
            group.messages.addAll(parts)
            group.calculate()
            groupSignatures[groupId] = signature
            groupRevisions[groupId] = (groupRevisions[groupId] ?: 0) + 1
        }
    }

    private fun validGroup(msg: MessageObject): MessageObject.GroupedMessages? {
        if (!msg.hasValidGroupId()) return null
        return groups[msg.getGroupId()]?.takeIf { it.getPosition(msg) != null }
    }

    // endregion

    // region unread

    private fun isUnread(msg: MessageObject) = controller.unreadTracker.isUnread(msg.getDialogId(), msg.id)

    // entiny: cached because the button badge asks on every scroll; reset whenever rows or read state change
    private var unreadCount = -1

    private fun countUnread(): Int {
        if (unreadCount < 0) unreadCount = store.snapshot().count { isUnread(it) }
        return unreadCount
    }

    private fun oldestUnread(): MessageObject? = store.snapshot().lastOrNull { isUnread(it) }

    private fun markVisibleRead() {
        val visible = ArrayList<MessageObject>()
        forEachVisiblePost { visible.add(it) }
        if (controller.markRowsSeen(visible)) {
            unreadCount = -1
            refreshPill()
        }
    }

    // entiny: posts from the local db carry stale reaction counts, so refresh what's on screen (throttled)
    private fun refreshVisibleReactions() {
        val now = SystemClock.elapsedRealtime()
        reactionsCheckedAt.keys.removeAll { !store.contains(it) }
        val byDialog = HashMap<Long, ArrayList<Int>>()
        forEachVisiblePost { msg ->
            val key = FeedStore.keyOf(msg)
            if (now - (reactionsCheckedAt[key] ?: 0L) < REACTIONS_RECHECK_MS) return@forEachVisiblePost
            reactionsCheckedAt[key] = now
            byDialog.getOrPut(msg.getDialogId()) { ArrayList() }.add(msg.id)
        }
        val messagesController = MessagesController.getInstance(currentAccount)
        for ((dialogId, ids) in byDialog) {
            val req = TLRPC.TL_messages_getMessagesReactions()
            req.peer = messagesController.getInputPeer(dialogId)
            req.id.addAll(ids)
            reactionPollRequests.add(ConnectionsManager.getInstance(currentAccount).sendRequest(req) { response, error ->
                val updates = response as? TLRPC.Updates ?: return@sendRequest
                if (error != null) return@sendRequest
                controller.applyReactionSnapshot(updates)
            })
        }
    }

    // entiny: a poll answered after a tap would overwrite the fresh local choice (same race inugram fixes in chats)
    private fun cancelReactionPolls() {
        val connections = ConnectionsManager.getInstance(currentAccount)
        for (id in reactionPollRequests) connections.cancelRequest(id, true)
        reactionPollRequests.clear()
    }

    private fun markAllRead() {
        val marked = controller.markAllRead()
        unreadCount = -1
        dividerKey = null
        submitItems(asynchronously = false)
        hidePill()
        val text = if (marked > 0) LocaleController.formatString(R.string.InuFeedMarkedAllRead, marked)
        else LocaleController.getString(R.string.InuFeedAlreadyRead)
        // entiny: switch to global bulletin factory when hosted inside main tabs
        val factory = if (hasMainTabs) BulletinFactory.global() else BulletinFactory.of(this)
        factory.createSimpleBulletin(R.raw.chats_infotip, text).show()
    }

    // endregion

    // region scrolling

    private inline fun forEachVisiblePost(action: (MessageObject) -> Unit) {
        val lm = layoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        for (i in first..last) (items.getOrNull(i) as? Item.Post)?.let { action(it.message) }
    }

    private fun isAtNewest(): Boolean {
        val lm = layoutManager ?: return true
        if (items.isEmpty()) return true
        return if (newestOnTop) lm.findFirstVisibleItemPosition() <= 0 else lm.findLastVisibleItemPosition() >= items.size - 1
    }

    private fun isNearOlderEnd(): Boolean {
        val lm = layoutManager ?: return false
        if (items.isEmpty()) return true
        return if (newestOnTop) lm.findLastVisibleItemPosition() >= items.size - 1 - LOAD_MORE_THRESHOLD
        else lm.findFirstVisibleItemPosition() in 0..LOAD_MORE_THRESHOLD
    }

    private fun scrollToDivider() {
        val idx = items.indexOf(Item.Divider)
        if (idx >= 0) layoutManager?.scrollToPositionWithOffset(idx, dp(8f)) else if (!newestOnTop) scrollToNewest()
    }

    private fun scrollToNewest() {
        if (items.isEmpty()) return
        layoutManager?.scrollToPositionWithOffset(if (newestOnTop) 0 else items.size - 1, 0)
    }

    private fun indexOfKey(dialogId: Long, messageId: Int): Int =
        items.indexOfFirst { it is Item.Post && it.message.id == messageId && it.message.getDialogId() == dialogId }

    private fun savePosition() {
        val lm = layoutManager ?: return
        val rv = listView ?: return
        var idx = lm.findFirstVisibleItemPosition()
        while (idx >= 0 && idx < items.size && items[idx] !is Item.Post) idx++
        val msg = (items.getOrNull(idx) as? Item.Post)?.message ?: return
        val view = lm.findViewByPosition(idx) ?: return
        prefs.edit { putString(positionPrefKey, "${msg.getDialogId()}:${msg.id}:${view.top - rv.paddingTop}") }
    }

    private fun restorePosition(): Boolean {
        val parts = prefs.getString(positionPrefKey, null)?.split(":") ?: return false
        if (parts.size != 3) return false
        val idx = indexOfKey(parts[0].toLongOrNull() ?: return false, parts[1].toIntOrNull() ?: return false)
        if (idx < 0) return false
        layoutManager?.scrollToPositionWithOffset(idx, parts[2].toIntOrNull() ?: 0)
        return true
    }

    // endregion

    // region chrome

    private var avatarContainer: ChatAvatarContainer? = null

    private fun setupActionBar(context: Context) {
        actionBar.setAddToContainer(false)
        actionBar.setCastShadows(false)
        actionBar.setBackground(null)
        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet())
        actionBar.inu_nonIsland = NonIslandHelper.chatElements()

        actionBar.setupGlass(navbarContentDrawableFactory, BlurredBackgroundProviderImpl.topPanelChatActivity(resourceProvider))

        if (!hasMainTabs) {
            actionBar.setBackButtonDrawable(BackDrawable(false))
        }

        val avatar = object : ChatAvatarContainer(context, this@FeedActivity, false, resourceProvider) {
            override fun onAvatarClick(): Boolean {
                if (pickableFolders().isNotEmpty()) {
                    showFolderPicker(this)
                } else {
                    presentFragment(FeedExcludedChannelsSettingsActivity())
                }
                return true
            }
        }
        avatar.setGlassMode()
        avatar.setOccupyStatusBar(!AndroidUtilities.isTablet())
        avatar.allowDrawStories = false
        avatar.setClipChildren(false)

        val avatarDrawable = AvatarDrawable()
        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED)
        ContextCompat.getDrawable(context, R.drawable.ic_feed_filled)?.let {
            avatarDrawable.setCustomIcon(it)
        }
        avatar.avatarImageView.setImage(null, null, avatarDrawable, null)
        avatar.avatarImageView.setRoundRadius(dp(21f))

        avatar.setTitle(LocaleController.getString(R.string.InuFeed))
        updateFeedSubtitle(avatar)

        avatar.setOnClickListener {
            if (pickableFolders().isNotEmpty()) {
                showFolderPicker(avatar)
            } else {
                presentFragment(FeedExcludedChannelsSettingsActivity())
            }
        }

        avatarContainer = avatar
        actionBar.setChatAvatarContainer(avatar)
        avatar.setActionBar(actionBar)

        val leftMargin = if (!hasMainTabs) 54f else 12f
        val rightMargin = 104f
        actionBar.addView(avatar, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.TOP or Gravity.LEFT, leftMargin, 0f, rightMargin, 0f))

        val menu = actionBar.createMenu()
        menu.addItem(MENU_MARK_READ, R.drawable.msg_markread)
            .setContentDescription(LocaleController.getString(R.string.InuFeedMarkAllRead))
        menu.addItem(MENU_OVERFLOW, R.drawable.ic_ab_other)
            .setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions))
        menu.bringToFront()

        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_MARK_READ -> markAllRead()
                    MENU_OVERFLOW -> showOverflowMenu()
                }
            }
        })
    }

    private fun updateFeedSubtitle(avatar: ChatAvatarContainer? = avatarContainer) {
        val target = avatar ?: return
        val folderName = FeedChannelSet.folderName(currentAccount, scope)
        val channelCount = FeedChannelSet.eligibleChannels(currentAccount, scope).size
        val channelsStr = LocaleController.formatPluralString("Channels", channelCount)
        val subtitle = if (folderName != null) "$folderName • $channelsStr" else channelsStr
        target.setSubtitle(subtitle)
        target.subtitleTextView?.visibility = View.VISIBLE
    }

    private fun applyInsets() {
        val statusBar = if (!AndroidUtilities.isTablet()) AndroidUtilities.statusBarHeight else 0
        val top = statusBar + ActionBar.getCurrentActionBarHeight() + dp(8f)
        val bottom = dp(8f) + navigationBarHeight + additionNavigationBarHeight
        listView?.setPadding(0, top, 0, bottom)
        (newPostsPill?.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (newestOnTop) it.topMargin = top else it.bottomMargin = bottom
            newPostsPill?.requestLayout()
        }
        (sideControls?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = bottom
            sideControls?.requestLayout()
        }
        updateTopFade()
    }

    private fun updateTopFade() {
        if (NonIslandHelper.chatElements()) {
            fadeView?.setFadeZoneTop(0)
            return
        }
        val statusBar = if (!AndroidUtilities.isTablet()) AndroidUtilities.statusBarHeight else 0
        val top = statusBar + ActionBar.getCurrentActionBarHeight() + dp(2f)
        fadeView?.setFadeZoneTop(top)
    }

    override fun isLightStatusBar(): Boolean {
        val source = navbarContentSourceWallpaper.source
        if (source != null) {
            val statusBarColor = wallpaperBitmapProvider.getStatusBarColor(source)
            return AndroidUtilities.computePerceivedBrightness(statusBarColor) > 0.721f
        }
        return super.isLightStatusBar()
    }

    private fun createEmptyView(context: Context): View {
        val empty = object : TextView(context) {
            private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                backgroundPaint.color = Theme.getColor(Theme.key_chat_serviceBackground)
                AndroidUtilities.rectTmp.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(14f).toFloat(), dp(14f).toFloat(), backgroundPaint)
                super.onDraw(canvas)
            }
        }
        empty.text = LocaleController.getString(R.string.InuFeedEmpty)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        empty.typeface = AndroidUtilities.bold()
        empty.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(16f), dp(6f), dp(16f), dp(8f))
        empty.visibility = View.GONE
        return empty
    }

    private fun createPill(context: Context): TextView {
        val pill = TextView(context)
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        pill.typeface = AndroidUtilities.bold()
        pill.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        pill.gravity = Gravity.CENTER
        pill.setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(Theme.getColor(Theme.key_chat_serviceBackground))
        }
        pill.elevation = dp(3f).toFloat()
        pill.visibility = View.GONE
        pill.setOnClickListener {
            scrollToNewest()
            hidePill()
        }
        return pill
    }

    private fun showPill(count: Int) {
        val pill = newPostsPill ?: return
        if (count <= 0) {
            hidePill()
            return
        }
        pill.text = LocaleController.formatString(R.string.InuFeedNewPosts, count)
        if (pill.visibility == View.VISIBLE) return
        pill.alpha = 0f
        pill.visibility = View.VISIBLE
        pill.animate().alpha(1f).setDuration(150).start()
    }

    // entiny: a visible pill follows the unread count as posts get read or arrive
    private fun refreshPill() {
        if (newPostsPill?.visibility == View.VISIBLE) showPill(countUnread())
    }

    private fun hidePill() {
        newPostsPill?.visibility = View.GONE
    }

    private fun updatePageDownButton() {
        val controls = sideControls ?: return
        val show = !isAtNewest()
        controls.showButton(ChatActivitySideControlsButtonsLayout.BUTTON_PAGE_DOWN, show, true)
        if (show) controls.setButtonCount(ChatActivitySideControlsButtonsLayout.BUTTON_PAGE_DOWN, countUnread(), true)
    }

    private fun showOverflowMenu() {
        val anchor = actionBar.createMenu().getItem(MENU_OVERFLOW) ?: return
        val options = ItemOptions.makeOptions(this, anchor)
        if (pickableFolders().isNotEmpty()) {
            options.add(R.drawable.msg_folders, LocaleController.getString(R.string.InuFeedFolders)) {
                // entiny: delay showing folder picker until popup dismiss animation finishes
                AndroidUtilities.runOnUIThread({ showFolderPicker(anchor) }, 100)
            }
        }
        options
            .add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedManageChannels)) {
                presentFragment(FeedExcludedChannelsSettingsActivity())
            }
            .show()
    }

    private fun pickableFolders(): List<MessagesController.DialogFilter> =
        MessagesController.getInstance(currentAccount).dialogFilters?.filter { !it.isDefault }.orEmpty()

    private fun showFolderPicker(anchorView: View? = null) {
        if (fragmentView == null) return
        val anchor = anchorView ?: avatarContainer ?: actionBar.createMenu().getItem(MENU_OVERFLOW) ?: return
        val currentFolder = scope as? FeedScope.Folder
        val options = ItemOptions.makeOptions(this, anchor)
        if (anchor is ChatAvatarContainer || anchor === avatarContainer) {
            options.setGravity(Gravity.LEFT)
        }
        if (currentFolder != null) {
            options.add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedAllChannels)) {
                presentFragment(FeedActivity(), !hasMainTabs)
            }
        }
        for (filter in pickableFolders()) {
            val info = FolderHelper.getTabInfo(filter)
            val name = info.first.takeIf { it.isNotEmpty() } ?: continue
            val filterId = filter.id
            if (currentFolder != null && currentFolder.filterId == filterId) continue
            options.add(FolderHelper.getTabIcon(info.second), name) {
                presentFragment(FeedActivity(FeedScope.Folder(filterId)), !hasMainTabs)
            }
        }
        options.show()
    }

    private fun openChannel(dialogId: Long) {
        presentFragment(ChatActivity(Bundle().apply { putLong("chat_id", -dialogId) }))
    }

    private fun openPost(msg: MessageObject) {
        if (controller.unreadTracker.onRowSeen(msg.getDialogId(), msg.id)) unreadCount = -1
        presentFragment(ChatActivity(Bundle().apply {
            putLong("chat_id", -msg.getDialogId())
            putInt("message_id", msg.id)
        }))
    }

    private fun hideChannel(dialogId: Long) {
        InuConfig.FEED_EXCLUDED_CHANNELS.value = InuConfig.FEED_EXCLUDED_CHANNELS.value.toMutableSet().apply { add(dialogId.toString()) }
        FeedChannelSet.invalidate()
        controller.hideChannel(dialogId)
    }

    private fun showPostMenu(cell: FeedMessageCell) {
        val msg = cell.messageObject ?: return
        val dialogId = msg.getDialogId()
        ItemOptions.makeOptions(this, cell)
            .add(R.drawable.msg_channel, LocaleController.getString(R.string.InuFeedOpenChannel)) { openChannel(dialogId) }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.InuFeedHideChannel)) { hideChannel(dialogId) }
            .setGravity(Gravity.LEFT)
            .show()
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> = ArrayList()

    // endregion

    private inner class FeedAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemViewType == VIEW_TYPE_POST
        override fun getItemCount(): Int = items.size
        override fun getItemViewType(position: Int): Int = if (items[position] is Item.Post) VIEW_TYPE_POST else VIEW_TYPE_DIVIDER

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            RecyclerListView.Holder(if (viewType == VIEW_TYPE_DIVIDER) DividerCell(context) else FeedMessageCell(context))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val post = items[position] as? Item.Post ?: return
            (holder.itemView as FeedMessageCell).bind(post.message, post.group)
        }
    }

    private inner class DividerCell(context: Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 11f, resources.displayMetrics)
            typeface = AndroidUtilities.bold()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(26f), MeasureSpec.EXACTLY))
        }

        override fun onDraw(canvas: Canvas) {
            linePaint.color = Theme.getColor(Theme.key_windowBackgroundGrayShadow)
            textPaint.color = Theme.getColor(Theme.key_windowBackgroundWhiteHintText)
            val label = LocaleController.getString(R.string.InuFeedUnreadDivider)
            val textWidth = textPaint.measureText(label)
            val centerY = height / 2f
            val centerX = width / 2f
            val pad = dp(12f).toFloat()
            canvas.drawLine(pad, centerY, centerX - textWidth / 2f - pad, centerY, linePaint)
            canvas.drawText(label, centerX - textWidth / 2f, centerY - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            canvas.drawLine(centerX + textWidth / 2f + pad, centerY, width - pad, centerY, linePaint)
        }
    }

    private inner class FeedMessageCell(context: Context) : ChatMessageCell(context, currentAccount) {
        init {
            setFullyDraw(true)
            isChat = false
            // entiny: delegate only consumes reaction and avatar taps; everything else falls through to the row click
            setDelegate(object : ChatMessageCellDelegate {
                override fun didPressReaction(cell: ChatMessageCell?, reaction: TLRPC.ReactionCount?, longpress: Boolean, x: Float, y: Float) {
                    if (!longpress && reaction != null) toggleReaction(reaction)
                }

                override fun didPressChannelAvatar(cell: ChatMessageCell?, chat: TLRPC.Chat?, postId: Int, touchX: Float, touchY: Float, asForward: Boolean) {
                    if (chat != null) openChannel(-chat.id)
                }
            })
            setOnClickListener { messageObject?.let { openPost(it) } }
            setOnLongClickListener {
                showPostMenu(this)
                true
            }
        }

        fun bind(msg: MessageObject, group: MessageObject.GroupedMessages?) {
            setMessageObject(msg, group, false, false, false)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (messageObject != null && reactionsLayout?.checkTouchEvent(event) == true) return true
            return super.onTouchEvent(event)
        }

        override fun invalidate() {
            super.invalidate()
            // album background is drawn by the list, so repaint it with the cell
            if (currentMessagesGroup != null) listView?.invalidate()
        }

        private fun toggleReaction(reaction: TLRPC.ReactionCount) {
            // entiny: album reactions live on the primary message, not on the tapped cell's own message
            val msg = primaryMessageObject ?: return
            val key = FeedStore.keyOf(msg)
            cancelReactionPolls()
            reactionsCheckedAt[key] = SystemClock.elapsedRealtime()
            val seq = (reactionTapSeq[key] ?: 0) + 1
            reactionTapSeq[key] = seq
            val visible = ReactionsLayoutInBubble.VisibleReaction.fromTL(reaction.reaction) ?: return
            if (visible.isStar) return
            val peer = MessagesController.getInstance(currentAccount).getInputPeer(msg.getDialogId())
                ?: return
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            } catch (_: Exception) {
            }
            val added = msg.selectReaction(visible, false, false)
            val request = TLRPC.TL_messages_sendReaction().apply {
                this.peer = peer
                msg_id = msg.id
                big = false
                add_to_recent = false
                for (selected in msg.choosenReactions) {
                    requestReaction(selected)?.let { this.reaction.add(it) }
                }
                if (this.reaction.isNotEmpty()) flags = flags or 1
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(request) { response, error ->
                // entiny: only the latest tap's response may overwrite the local choice
                if (error == null && response is TLRPC.Updates && reactionTapSeq[key] == seq) controller.applyReactionSnapshot(response)
            }
            // entiny: deferred rebind -- setMessageObject must not run inside touch dispatch
            post { messageObject?.let { bind(it, currentMessagesGroup) } }
        }

        private fun requestReaction(visible: ReactionsLayoutInBubble.VisibleReaction): TLRPC.Reaction? = when {
            visible.documentId != 0L -> TLRPC.TL_reactionCustomEmoji().apply { document_id = visible.documentId }
            !visible.emojicon.isNullOrEmpty() -> TLRPC.TL_reactionEmoji().apply { emoticon = visible.emojicon }
            else -> null
        }
    }

    companion object {
        private val MENU_MARK_READ = InuUtils.generateId()
        private val MENU_OVERFLOW = InuUtils.generateId()
        private const val LOAD_MORE_THRESHOLD = 6
        private const val MIN_INITIAL_ROWS = 20
        private const val REACTIONS_RECHECK_MS = 15_000L
        private const val ITEM_VIEW_CACHE_SIZE = 4
        private const val POST_POOL_SIZE = 10
        private const val VIEW_TYPE_POST = 0
        private const val VIEW_TYPE_DIVIDER = 1
        private val DIFF_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FeedDiff").apply { isDaemon = true }
        }

        const val INDICATOR_PILL = 0
        const val INDICATOR_BUTTON = 1

    }
}
