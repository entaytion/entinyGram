package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MainScreenSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMainScreen)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineEntry(
                BUTTON_PILL_STACK,
                R.drawable.inu_tabler_stack_2,
                addExperimentalSpan(LocaleController.getString(R.string.InuPillStack)),
                LocaleController.getString(R.string.InuPillStackSummary)
            )
        )
        items.add(
            mkTwoLineEntry(
                BUTTON_RECENT_CHATS,
                R.drawable.msg_recent,
                addExperimentalSpan(LocaleController.getString(R.string.InuRecentChats)),
                LocaleController.getString(R.string.InuRecentChatsSummary)
            )
        )
        items.add(
            mkTwoLineEntry(
                BUTTON_BOOKMARKS,
                R.drawable.msg_fave,
                LocaleController.getString(R.string.InuBookmarks),
                LocaleController.getString(R.string.InuBookmarksSummary)
            )
        )
        items.add(
            mkTwoLineEntry(
                BUTTON_FEED,
                R.drawable.msg_channel,
                addExperimentalSpan(LocaleController.getString(R.string.InuFeed)),
                LocaleController.getString(R.string.InuFeedSummary)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ONLINE_DOTS,
                R.string.InuOnlineDots,
                R.string.InuOnlineDotsInfo,
                InuConfig.PRESENCE_COLOR_DOTS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_ONLINE_DOTS,
                R.string.InuChatOnlineDots,
                R.string.InuChatOnlineDotsInfo,
                InuConfig.CHAT_PRESENCE_DOTS.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SHOW_MUTUAL_CONTACT_ICON,
                R.string.InuShowMutualContactIcon,
                R.string.InuShowMutualContactIconInfo,
                InuConfig.SHOW_MUTUAL_CONTACT_ICON.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SHOW_MUTUAL_CONTACT_IN_CHATS,
                R.string.InuShowMutualContactInChats,
                R.string.InuShowMutualContactInChatsInfo,
                InuConfig.SHOW_MUTUAL_CONTACT_IN_CHATS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_MUTUAL_CONTACT_ICON_LEFT,
                R.string.InuMutualContactIconLeft,
                R.string.InuMutualContactIconLeftInfo,
                InuConfig.MUTUAL_CONTACT_ICON_LEFT.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PILL_STACK -> presentFragment(PillStackSettingsActivity())
            BUTTON_RECENT_CHATS -> presentFragment(RecentChatsSettingsActivity())
            BUTTON_BOOKMARKS -> presentFragment(desu.inugram.ui.BookmarksManagerActivity())
            BUTTON_FEED -> presentFragment(FeedExcludedChannelsSettingsActivity())
            TOGGLE_ONLINE_DOTS -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.PRESENCE_COLOR_DOTS.toggle()
            TOGGLE_CHAT_ONLINE_DOTS -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_PRESENCE_DOTS.toggle()
            TOGGLE_SHOW_MUTUAL_CONTACT_ICON -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SHOW_MUTUAL_CONTACT_ICON.toggle()
            TOGGLE_SHOW_MUTUAL_CONTACT_IN_CHATS -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SHOW_MUTUAL_CONTACT_IN_CHATS.toggle()
            TOGGLE_MUTUAL_CONTACT_ICON_LEFT -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.MUTUAL_CONTACT_ICON_LEFT.toggle()
        }
    }

    companion object {
        private val BUTTON_PILL_STACK = InuUtils.generateId()
        private val BUTTON_RECENT_CHATS = InuUtils.generateId()
        private val BUTTON_BOOKMARKS = InuUtils.generateId()
        private val BUTTON_FEED = InuUtils.generateId()
        private val TOGGLE_ONLINE_DOTS = InuUtils.generateId()
        private val TOGGLE_CHAT_ONLINE_DOTS = InuUtils.generateId()
        private val TOGGLE_SHOW_MUTUAL_CONTACT_ICON = InuUtils.generateId()
        private val TOGGLE_SHOW_MUTUAL_CONTACT_IN_CHATS = InuUtils.generateId()
        private val TOGGLE_MUTUAL_CONTACT_ICON_LEFT = InuUtils.generateId()

        val PAGE = SearchRegistry.Page(
            slug = "main-screen",
            titleRes = R.string.InuMainScreen,
            iconRes = R.drawable.msg_home_solar,
            factory = ::MainScreenSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("pill-stack-open", R.string.InuPillStack, BUTTON_PILL_STACK),
                SearchRegistry.Entry("recent-chats-open", R.string.InuRecentChats, BUTTON_RECENT_CHATS),
                SearchRegistry.Entry("bookmarks-open", R.string.InuBookmarks, BUTTON_BOOKMARKS),
                SearchRegistry.Entry("feed-open", R.string.InuFeed, BUTTON_FEED),
                SearchRegistry.Entry("online-dots", R.string.InuOnlineDots, TOGGLE_ONLINE_DOTS),
                SearchRegistry.Entry("chat-online-dots", R.string.InuChatOnlineDots, TOGGLE_CHAT_ONLINE_DOTS),
                SearchRegistry.Entry("show-mutual-contact-icon", R.string.InuShowMutualContactIcon, TOGGLE_SHOW_MUTUAL_CONTACT_ICON),
                SearchRegistry.Entry("show-mutual-contact-in-chats", R.string.InuShowMutualContactInChats, TOGGLE_SHOW_MUTUAL_CONTACT_IN_CHATS),
                SearchRegistry.Entry("mutual-contact-icon-left", R.string.InuMutualContactIconLeft, TOGGLE_MUTUAL_CONTACT_ICON_LEFT),
            )
        )
    }
}

