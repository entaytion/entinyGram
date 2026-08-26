package desu.inugram.helpers.dialogs

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.MainTabsActivity
import desu.inugram.helpers.icons.ScaledIconDrawable
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.security.PasscodeHelper


object MainTabsHelper {
    const val MAIN_TABS_MARGIN_COMPACT: Int = 4
    const val MAIN_TABS_HEIGHT_COMPACT: Int = 48
    const val TAB_WIDTH: Int = 80
    const val TAB_WIDTH_COMPACT: Int = 64
    const val TAB_PADDING: Int = 4

    @JvmStatic
    val isCompact: Boolean
        get() = InuConfig.BOTTOM_TABS_COMPACT_MODE.value

    @JvmStatic
    val isHidden: Boolean
        get() = InuConfig.BOTTOM_TABS_HIDE.value || InuConfig.NAVIGATION_DRAWER.value

    @JvmStatic
    val showTitles: Boolean
        get() = !isCompact && InuConfig.BOTTOM_TABS_SHOW_TITLES.value
    // index scheme matches MainTabsActivity's INDEX_* constants: 0=Chats,1=Contacts,2=Settings,3=Calls,4=Profile

    // snapshotted once per process: MainTabsActivity builds its tab bar/ViewPager positions once at
    // creation and never rebuilds them live, so re-reading InuConfig on every call would let a mid-session
    // toggle (from DialogsSettingsActivity's bottom tabs preview, before the user actually restarts)
    // desync the ViewPager's position count from the already-built tab bar and crash in
    // ViewPagerFixed.scrollToPosition with a null fragment. Changes to BOTTOM_TABS_ORDER only take effect
    // on the next process restart anyway (see DialogsSettingsActivity's showRestartBulletin calls), so a
    // process-lifetime cache is exactly correct.
    private val cachedEnabledOrder: List<MainTabsMenuConfig.Item> by lazy {
        InuConfig.BOTTOM_TABS_ORDER.value.filter { it.enabled }.map { it.item }
    }

    /** ordered list of enabled non-Chats tabs from [InuConfig.BOTTOM_TABS_ORDER]. Chats is always first and implicit. */
    @JvmStatic
    fun enabledOrder(): List<MainTabsMenuConfig.Item> = cachedEnabledOrder

    @JvmStatic
    fun setEnabled(index: Int, enabled: Boolean) {
        val type = MainTabsMenuConfig.Item.forIndex(index) ?: return // Chats can't be disabled
        val entries = InuConfig.BOTTOM_TABS_ORDER.value
        InuConfig.BOTTOM_TABS_ORDER.value = entries.map { if (it.item == type) it.copy(enabled = enabled) else it }
    }

    @JvmStatic
    fun isEnabled(index: Int): Boolean {
        val type = MainTabsMenuConfig.Item.forIndex(index) ?: return true // Chats (index 0) is always enabled
        return enabledOrder().contains(type)
    }

    @JvmStatic
    fun indexToPosition(index: Int): Int {
        if (index == 0) return 0
        val type = MainTabsMenuConfig.Item.forIndex(index) ?: return -1
        val pos = enabledOrder().indexOf(type)
        return if (pos < 0) -1 else pos + 1
    }

    /** reverse of [indexToPosition]: tab type index visible at ViewPager [position], or -1 */
    @JvmStatic
    fun indexAtPosition(position: Int): Int {
        if (position == 0) return 0
        val order = enabledOrder()
        val i = position - 1
        return if (i in order.indices) order[i].index else -1
    }

    /** view-add order for [tabs] array: enabled types first (in user order), disabled ones appended (hidden but still instantiated) */
    @JvmStatic
    fun visualOrder(): IntArray {
        val enabled = enabledOrder()
        val disabled = MainTabsMenuConfig.Item.entries.filterNot { it in enabled }
        return (listOf(0) + enabled.map { it.index } + disabled.map { it.index }).toIntArray()
    }

    @JvmStatic
    val mainTabsHeight: Int
        get() = if (isCompact) MAIN_TABS_HEIGHT_COMPACT else DialogsActivity.MAIN_TABS_HEIGHT

    @JvmStatic
    val mainTabsMargin: Int
        get() = if (isCompact) MAIN_TABS_MARGIN_COMPACT else DialogsActivity.MAIN_TABS_MARGIN

    @JvmStatic
    val mainTabsHeightWithMargins: Int
        get() = mainTabsHeight + mainTabsMargin * 2

    @JvmStatic
    val fragmentsCount: Int
        get() = 1 + enabledOrder().size

    @JvmStatic
    val tabWidth: Int
        get() = if (isCompact) TAB_WIDTH_COMPACT else TAB_WIDTH

    @JvmStatic
    val tabsViewWidth: Int
        get() = tabWidth * fragmentsCount + (mainTabsMargin + TAB_PADDING) * 2

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
        o.add(R.drawable.msg_saved, getString(R.string.SavedMessages)) {
            val args = Bundle()
            args.putLong("user_id", UserConfig.getInstance(fragment.currentAccount).clientUserId)
            fragment.presentFragment(ChatActivity(args))
        }
        o.add(R.drawable.msg_archive, getString(R.string.ArchivedChats)) {
            val args = Bundle()
            args.putInt("folderId", 1)
            fragment.presentFragment(DialogsActivity(args))
        }

        o.setBlur(true)
        o.translate(0f, -dp(4f).toFloat())
        o.setGravity(Gravity.CENTER_HORIZONTAL)
        val bg = Theme.createRoundRectDrawable(dp(28f), fragment.getThemedColor(Theme.key_windowBackgroundWhite))
        bg.paint.setShadowLayer(dp(6f).toFloat(), 0f, dp(1f).toFloat(), Theme.multAlpha(0xFF000000.toInt(), 0.15f))
        o.setScrimViewBackground(bg)

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

    // BulletinWindow (the old approach here) is its own top-level Dialog/Window, outside the
    // single-bulletin-per-fragment queue every other bulletin in the app uses (e.g. the updater's
    // "Hotfix deployed!"). Two independent bulletin mechanisms showing at once don't dedupe or
    // account for each other's position — that's what produced the garbled double-bulletin overlap.
    // `BaseFragment.setBulletinDelegate` is already public stock API: registering our offset there
    // routes everything through the normal `Bulletin.make(fragment, ...)` path instead.
    @JvmStatic
    fun resolveBulletinContainer(fragment: BaseFragment?): FrameLayout? {
        Log.d(
            "InuBulletin", "resolveBulletinContainer: fragment=${fragment?.javaClass?.simpleName} " +
                "isDialogs=${fragment is DialogsActivity} hasMainTabs=${(fragment as? DialogsActivity)?.hasMainTabs} " +
                "isHidden=$isHidden offsetDp=$mainTabsHeightWithMargins existingDelegate=${fragment?.bulletinDelegate}"
        )
        if (fragment is DialogsActivity && fragment.hasMainTabs && fragment.bulletinDelegate == null) {
            fragment.bulletinDelegate = object : Bulletin.Delegate {
                override fun getBottomOffset(tag: Int): Int {
                    val offset = if (isHidden) 0 else dp(mainTabsHeightWithMargins.toFloat())
                    Log.d("InuBulletin", "getBottomOffset -> $offset (isHidden=$isHidden)")
                    return offset
                }
            }
            Log.d("InuBulletin", "attached delegate to $fragment")
        }
        return null
    }
}