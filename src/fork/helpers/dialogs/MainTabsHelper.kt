package desu.inugram.helpers.dialogs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.MainTabsLayout
import org.telegram.ui.Components.glass.GlassTabView
import desu.inugram.helpers.icons.ScaledIconDrawable
import desu.inugram.helpers.chat.ChatHelper
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.theme.M3MainTabsHelper
import desu.inugram.helpers.theme.MainTabsV2Helper
// #if PLUGINS
import desu.inugram.ui.settings.PluginsActivity
// #endif


object MainTabsHelper {
    const val MAIN_TABS_MARGIN_COMPACT: Int = 4
    const val MAIN_TABS_HEIGHT_COMPACT: Int = 48
    const val MAIN_TABS_HEIGHT_IOS: Int = 60
    const val TAB_WIDTH: Int = 76
    const val TAB_WIDTH_COMPACT: Int = 64
    const val TAB_PADDING: Int = 4
    private const val TAB_SCRIM_RADIUS = 28
    private const val TABS_INNER_PADDING: Int = 4
    private const val TABS_INNER_PADDING_IOS: Int = 6
    private const val TABS_SIDE_PADDING_EXTRA_IOS: Int = 8

    @JvmStatic
    val isV2: Boolean
        get() = MainTabsV2Helper.isEnabled

    @JvmStatic
    val isCompact: Boolean
        get() = InuConfig.BOTTOM_TABS_COMPACT_MODE.value && !isV2

    @JvmStatic
    val isMaterial: Boolean
        get() = InuConfig.M3_BOTTOM_TABS.value && !isV2

    @JvmStatic
    val isIos: Boolean
        get() = InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value && !isMaterial && !isV2

    @JvmStatic
    fun createTabScrimBackground(anchor: View, color: Int): Drawable {
        val radius = if (isMaterial) M3MainTabsHelper.SCRIM_RADIUS else TAB_SCRIM_RADIUS
        val bg = Theme.createRoundRectDrawable(dp(radius.toFloat()), color)
        bg.paint.setShadowLayer(dp(6f).toFloat(), 0f, dp(1f).toFloat(), Theme.multAlpha(0xFF000000.toInt(), 0.15f))
        M3MainTabsHelper.sizeScrimBackground(bg, anchor)
        return bg
    }

    @JvmStatic
    val bottomLiftDp: Int
        get() = if (isCompact && !isMaterial) InuConfig.BOTTOM_TABS_LIFT.value else 0

    @JvmStatic
    val bottomLiftPx: Int
        get() = dp(bottomLiftDp.toFloat())

    @JvmStatic
    fun getMainTabsBottomOffset(navigationBarHeight: Int): Int =
        if (isMaterial) 0 else navigationBarHeight + dp(mainTabsMargin.toFloat()) + bottomLiftPx

    @JvmStatic
    fun getMainTabsBlurHeight(navigationBarHeight: Int): Int =
        dp(mainTabsHeight.toFloat()) + if (isMaterial) navigationBarHeight else 0

    @JvmStatic
    val isHidden: Boolean
        get() = InuConfig.BOTTOM_TABS_HIDE.value

    @JvmStatic
    val isHideOnScroll: Boolean
        get() = InuConfig.BOTTOM_TABS_HIDE_ON_SCROLL.value && !isHidden

    @JvmStatic
    val showTitles: Boolean
        get() = !isCompact && InuConfig.BOTTOM_TABS_SHOW_TITLES.value

    // entiny: snapshot order for process lifetime so mid-session toggle preview does not desync ViewPager and crash
    fun configuredOrder(): List<MainTabsMenuConfig.Item> =
        InuConfig.BOTTOM_TABS_ORDER.value.filter { it.item.isFeatureOn && (it.enabled || it.item == MainTabsMenuConfig.Item.CHATS) }.map { it.item }.distinct()

    private val cachedEnabledOrder: List<MainTabsMenuConfig.Item> by lazy { configuredOrder() }

    @JvmStatic
    fun enabledOrder(): List<MainTabsMenuConfig.Item> = cachedEnabledOrder

    @JvmStatic
    fun hasSearchTab(): Boolean = MainTabsMenuConfig.Item.SEARCH in cachedEnabledOrder

    @JvmStatic
    fun isSearchTabSeparate(): Boolean = hasSearchTab() && InuConfig.BOTTOM_TABS_SEARCH_SEPARATE.value && !isMaterial

    @JvmStatic
    fun setEnabled(index: Int, enabled: Boolean) {
        val type = MainTabsMenuConfig.Item.forIndex(index) ?: return
        if (type == MainTabsMenuConfig.Item.CHATS) return
        val entries = InuConfig.BOTTOM_TABS_ORDER.value
        InuConfig.BOTTOM_TABS_ORDER.value = entries.map { if (it.item == type) it.copy(enabled = enabled) else it }
    }

    @JvmStatic
    fun isEnabled(index: Int): Boolean {
        val type = MainTabsMenuConfig.Item.forIndex(index) ?: return true
        return enabledOrder().contains(type)
    }

    @JvmStatic
    fun startPosition(): Int {
        val pos = indexToPosition(InuConfig.START_TAB.value)
        return if (pos >= 0) pos else chatsPosition()
    }

    @JvmStatic
    fun chatsPosition(): Int = indexToPosition(MainTabsMenuConfig.Item.CHATS.index)

    @JvmStatic
    fun indexToPosition(index: Int): Int {
        val type = MainTabsMenuConfig.Item.forIndex(index) ?: return -1
        if (type.isShortcut) return -1
        val pos = enabledOrder().filterNot { it.isShortcut }.indexOf(type)
        return pos
    }

    @JvmStatic
    fun indexAtPosition(position: Int): Int {
        val order = enabledOrder().filterNot { it.isShortcut }
        return if (position in order.indices) order[position].index else -1
    }

    @JvmStatic
    fun visualOrder(): IntArray {
        val enabled = enabledOrder()
        val disabled = MainTabsMenuConfig.Item.entries.filterNot { it in enabled }
        return (enabled.map { it.index } + disabled.map { it.index }).toIntArray()
    }

    @JvmStatic
    val mainTabsHeight: Int
        get() = when {
            isMaterial -> M3MainTabsHelper.barHeight
            isIos -> MAIN_TABS_HEIGHT_IOS
            isCompact -> MAIN_TABS_HEIGHT_COMPACT
            else -> DialogsActivity.MAIN_TABS_HEIGHT
        }

    @JvmStatic
    val mainTabsMargin: Int
        get() = when {
            isMaterial -> 0
            isCompact -> MAIN_TABS_MARGIN_COMPACT
            else -> DialogsActivity.MAIN_TABS_MARGIN
        }

    @JvmStatic
    val mainTabsBarHeight: Int
        get() = mainTabsHeight + mainTabsMargin * 2

    @JvmStatic
    val mainTabsHeightWithMargins: Int
        get() = mainTabsHeight + mainTabsMargin * 2 + bottomLiftDp

    @JvmStatic
    val tabsInnerPaddingVertical: Int
        get() = mainTabsMargin + if (isIos) TABS_INNER_PADDING_IOS else TABS_INNER_PADDING

    @JvmStatic
    val iosSidePaddingExtra: Int
        get() = if (isIos) dp(TABS_SIDE_PADDING_EXTRA_IOS.toFloat()) else 0

    @JvmStatic
    fun applyIosTabsLayout(tabsView: org.telegram.ui.MainTabsLayout) {
        if (!isIos) return
        tabsView.inu_materialTabs = true
        tabsView.setMaxWidth(0)
    }

    @JvmStatic
    val fragmentsCount: Int
        get() = enabledOrder().count { !it.isShortcut }

    @JvmStatic
    val tabWidth: Int
        get() = if (isCompact) TAB_WIDTH_COMPACT else TAB_WIDTH

    @JvmStatic
    val tabsViewWidth: Int
        get() = tabWidth * (enabledOrder().size - if (isSearchTabSeparate()) 1 else 0) + (mainTabsMargin + TAB_PADDING) * 2

    @JvmStatic
    val hasCustomWidth: Boolean
        get() = !isMaterial && InuConfig.BOTTOM_TABS_WIDTH.value in 1..3

    @JvmStatic
    val hideFade: Boolean
        get() = InuConfig.BOTTOM_TABS_HIDE_FADE.value || ChatHelper.shouldHideFadeView()

    @JvmStatic
    fun applyWidth(layout: MainTabsLayout) {
        layout.inu_widthMode = if (hasCustomWidth) InuConfig.BOTTOM_TABS_WIDTH.value else 0
        if (hasCustomWidth) layout.setMaxWidth(0)
    }

    @JvmStatic
    fun createTab(context: Context, provider: Theme.ResourcesProvider?, account: Int, item: MainTabsMenuConfig.Item): GlassTabView {
        val animation = when (item) {
            MainTabsMenuConfig.Item.CHATS -> GlassTabView.TabAnimation.CHATS
            MainTabsMenuConfig.Item.CONTACTS -> GlassTabView.TabAnimation.CONTACTS
            MainTabsMenuConfig.Item.SETTINGS -> GlassTabView.TabAnimation.SETTINGS
            MainTabsMenuConfig.Item.CALLS -> GlassTabView.TabAnimation.CALLS
            MainTabsMenuConfig.Item.PROFILE -> GlassTabView.TabAnimation.INU_PROFILE
            MainTabsMenuConfig.Item.FEED -> GlassTabView.TabAnimation.INU_FEED
            MainTabsMenuConfig.Item.SEARCH -> GlassTabView.TabAnimation.INU_SEARCH
            MainTabsMenuConfig.Item.SAVED -> GlassTabView.TabAnimation.INU_SAVED
            MainTabsMenuConfig.Item.ARCHIVE -> GlassTabView.TabAnimation.INU_ARCHIVE
            MainTabsMenuConfig.Item.PLUGINS -> GlassTabView.TabAnimation.INU_PLUGINS
        }
        val label = if (item == MainTabsMenuConfig.Item.CHATS) R.string.MainTabsChats else item.labelRes
        val tab = if (item == MainTabsMenuConfig.Item.PROFILE && !InuConfig.BOTTOM_TABS_HIDE_PROFILE_AVATAR.value) {
            GlassTabView.createAvatar(context, provider, account, label)
        } else {
            GlassTabView.createMainTab(context, provider, animation, label)
        }
        if (isMaterial) tab.inu_setMaterialTabs()
        if (isV2) tab.inu_setV2()
        tab.setMainTabsCompact(!showTitles)
        return tab
    }

    @JvmStatic
    fun chatsBadge(account: Int): String? {
        if (InuConfig.BOTTOM_TABS_HIDE_BADGES.value || InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value == InuConfig.FoldersUnreadCounterModeItem.HIDE) return null
        val count = MessagesStorage.getInstance(account).mainUnreadCount
        return if (count > 0) LocaleController.formatNumber(count, ',') else null
    }

    @JvmStatic
    fun openShortcut(fragment: BaseFragment, index: Int): Boolean {
        when (MainTabsMenuConfig.Item.forIndex(index)) {
            MainTabsMenuConfig.Item.SAVED -> fragment.presentFragment(ChatActivity(Bundle().apply {
                putLong("user_id", UserConfig.getInstance(fragment.currentAccount).clientUserId)
            }))
            MainTabsMenuConfig.Item.ARCHIVE -> fragment.presentFragment(DialogsActivity(Bundle().apply { putInt("folderId", 1) }))
            else -> return false
        }
        return true
    }

    @JvmStatic
    fun createPluginsTab(): BaseFragment? {
        // #if PLUGINS
        return PluginsActivity(hasMainTabs = true)
        // #else
        return null
        // #endif
    }


    private const val MENU_ICON_SIZE_DP = 28

    @JvmStatic
    fun openChatsLongPressMenu(fragment: MainTabsActivity, button: View): ItemOptions? {
        val filters = MessagesController.getInstance(fragment.currentAccount).dialogFilters
        if (filters.size <= 1) return null

        val context = fragment.context ?: return null
        val o = ItemOptions.makeOptions(fragment, button)
        for (i in filters.indices) {
            val filter = filters[i]
            val name: String
            val emoticon: String?
            if (filter.isDefault) {
                name = getString(R.string.FilterAllChats)
                emoticon = "💬"
            } else {
                val info = FolderHelper.getTabInfo(filter)
                name = info.first
                emoticon = info.second
            }
            val index = i
            val icon = scaledIcon(context, FolderHelper.getTabIcon(emoticon)) ?: continue
            o.add(icon, name) {
                fragment.inu_openChatsAtFilter(index)
            }
        }

        o.addGap()
        o.add(R.drawable.msg_saved, getString(R.string.SavedMessages)) { openShortcut(fragment, MainTabsMenuConfig.Item.SAVED.index) }
        o.add(R.drawable.msg_archive, getString(R.string.ArchivedChats)) { openShortcut(fragment, MainTabsMenuConfig.Item.ARCHIVE.index) }

        o.setBlur(true)
        o.translate(0f, -dp(4f).toFloat())
        o.setGravity(Gravity.CENTER_HORIZONTAL)
        o.setScrimViewBackground(createTabScrimBackground(button, fragment.getThemedColor(Theme.key_windowBackgroundWhite)))

        return o
    }

    private fun scaledIcon(context: Context, resId: Int): Drawable? {
        val src = ContextCompat.getDrawable(context, resId) ?: return null
        return ScaledIconDrawable(src, dp(MENU_ICON_SIZE_DP.toFloat()))
    }

    private var lastProfileTapMs = 0L

    @JvmStatic
    fun onProfileTabTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        val isDoubleTap = now - lastProfileTapMs < 500
        lastProfileTapMs = now
        if (!isDoubleTap) return false
        if (!switchToNextAccount()) return false
        lastProfileTapMs = 0L
        return true
    }

    @JvmStatic
    fun switchToNextAccount(): Boolean {
        val current = UserConfig.selectedAccount
        val accounts = mutableListOf<Int>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated && (a == current || !PasscodeHelper.isAccountHidden(a))) accounts.add(a)
        }
        if (accounts.size < 2) return false
        AccountOrderHelper.sort(accounts)
        val idx = accounts.indexOf(current)
        val target = accounts[(idx + 1) % accounts.size]
        if (target == current) return false
        LaunchActivity.instance?.switchToAccount(target, true) ?: return false
        return true
    }

    @JvmStatic
    fun resolveBulletinContainer(fragment: BaseFragment?): FrameLayout? {
        if (fragment is DialogsActivity && fragment.hasMainTabs && fragment.bulletinDelegate == null) {
            fragment.bulletinDelegate = object : Bulletin.Delegate {
                override fun getBottomOffset(tag: Int): Int {
                    return if (isHidden) 0 else dp(mainTabsHeightWithMargins.toFloat())
                }
            }
        }
        return null
    }
}
