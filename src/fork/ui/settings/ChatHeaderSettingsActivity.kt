package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class ChatHeaderSettingsActivity : SettingsPageActivity() {

    private var previewCell: ChatHeaderPreviewCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatHeaderSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        if (previewCell == null) {
            previewCell = ChatHeaderPreviewCell(ctx, this)
        }
        items.add(UItem.asCustom(previewCell))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuChatHeaderSettingsInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatHeaderSettings)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_CHAT_AVATAR,
                R.string.InuHideChatAvatar,
                R.string.InuHideChatAvatarInfo,
                InuConfig.HIDE_CHAT_AVATAR.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_IOS_CHAT_HEADER,
                R.string.InuIosChatHeader,
                R.string.InuIosChatHeaderInfo,
                InuConfig.IOS_CHAT_HEADER.value,
            )
        )
        if (InuUtils.centerChatTitle()) {
            if (InuUtils.compactChatPill() && !InuUtils.hideChatAvatar()) {
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT,
                        R.string.InuIosChatHeaderAvatarSlot,
                        R.string.InuIosChatHeaderAvatarSlotInfo,
                        InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value,
                    )
                )
                if (InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value) {
                    items.add(
                        mkTwoLineCheckItem(
                            TOGGLE_IOS_CHAT_HEADER_AVATAR_SWAP,
                            R.string.InuIosChatHeaderAvatarSwap,
                            R.string.InuIosChatHeaderAvatarSwapInfo,
                            InuConfig.IOS_CHAT_HEADER_AVATAR_SWAP.value,
                        )
                    )
                } else {
                    items.add(
                        mkTwoLineCheckItem(
                            TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC,
                            R.string.InuIosChatHeaderAvatarStatic,
                            R.string.InuIosChatHeaderAvatarStaticInfo,
                            InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.value,
                        )
                    )
                }
            }
            if (!InuConfig.HIDE_CHAT_AVATAR.value && (!InuConfig.IOS_CHAT_HEADER.value ||
                (!InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value && !InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.value))
            ) {
                items.add(
                    UItem.asCheck(
                        TOGGLE_CENTER_TITLE_RIGHT_AVATAR,
                        LocaleController.getString(R.string.InuCenterTitleRightAvatar),
                    ).setChecked(InuConfig.CENTER_TITLE_RIGHT_AVATAR.value)
                )
            }
        }
        if (!InuConfig.NON_ISLAND_CHAT_ELEMENTS.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_CHAT_HEADER_NO_PILL,
                    R.string.InuChatHeaderNoPill,
                    R.string.InuChatHeaderNoPillInfo,
                    InuConfig.CHAT_HEADER_NO_PILL.value,
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTitleText)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_TITLE_MARQUEE,
                R.string.InuChatTitleMarquee,
                R.string.InuChatTitleMarqueeInfo,
                InuConfig.CHAT_TITLE_MARQUEE.value,
                experimental = true,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuFoldersUnreadCounter)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON,
                R.string.InuUnreadBadgeOnBackButton,
                R.string.InuUnreadBadgeOnBackButtonInfo,
                InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.value,
            )
        )
        if (InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON_IOS,
                    R.string.InuUnreadBadgeOnBackButtonIos,
                    0,
                    InuConfig.UNREAD_BADGE_ON_BACK_BUTTON_IOS.value,
                )
            )
        }
        items.add(UItem.asShadow(null))


    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_HIDE_CHAT_AVATAR -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.HIDE_CHAT_AVATAR.toggle()
                refreshAll()
            }

            TOGGLE_IOS_CHAT_HEADER -> {
                val enabled = InuConfig.IOS_CHAT_HEADER.toggle()
                if (enabled) InuConfig.CENTER_TITLE_CHATS.value = true
                (view as? NotificationsCheckCell)?.isChecked = enabled
                refreshAll()
            }

            TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.toggle()
                refreshAll()
            }

            TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.toggle()
                refreshAll()
            }

            TOGGLE_IOS_CHAT_HEADER_AVATAR_SWAP -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.IOS_CHAT_HEADER_AVATAR_SWAP.toggle()
                refreshAll()
            }

            TOGGLE_CENTER_TITLE_RIGHT_AVATAR -> {
                (view as? TextCheckCell)?.isChecked = InuConfig.CENTER_TITLE_RIGHT_AVATAR.toggle()
                refreshAll()
            }

            TOGGLE_CHAT_HEADER_NO_PILL -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_HEADER_NO_PILL.toggle()
                refreshAll()
            }

            TOGGLE_CHAT_TITLE_MARQUEE -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_TITLE_MARQUEE.toggle()
                refreshAll()
            }

            TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.UNREAD_BADGE_ON_BACK_BUTTON.toggle()
                refreshAll()
            }

            TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON_IOS -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.UNREAD_BADGE_ON_BACK_BUTTON_IOS.toggle()
                refreshAll()
            }
        }
    }

    private fun refreshAll() {
        previewCell?.refresh()
        listView.adapter.update(true)
    }

    companion object {
        private val TOGGLE_CENTER_TITLE_RIGHT_AVATAR = InuUtils.generateId()
        private val TOGGLE_HIDE_CHAT_AVATAR = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER_AVATAR_SWAP = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC = InuUtils.generateId()
        private val TOGGLE_CHAT_HEADER_NO_PILL = InuUtils.generateId()
        private val TOGGLE_CHAT_TITLE_MARQUEE = InuUtils.generateId()
        private val TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON = InuUtils.generateId()
        private val TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON_IOS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "chat-header",
            titleRes = R.string.InuChatHeaderSettings,
            iconRes = R.drawable.inu_tabler_app_window,
            factory = ::ChatHeaderSettingsActivity,
            entries = listOf(
                // entiny: slugs kept verbatim from AppearanceSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("hide-chat-avatar", R.string.InuHideChatAvatar, TOGGLE_HIDE_CHAT_AVATAR),
                SearchRegistry.Entry("center-title-right-avatar", R.string.InuCenterTitleRightAvatar, TOGGLE_CENTER_TITLE_RIGHT_AVATAR),
                SearchRegistry.Entry("ios-chat-header", R.string.InuIosChatHeader, TOGGLE_IOS_CHAT_HEADER),
                SearchRegistry.Entry("ios-chat-header-avatar-slot", R.string.InuIosChatHeaderAvatarSlot, TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT),
                SearchRegistry.Entry("ios-chat-header-avatar-swap", R.string.InuIosChatHeaderAvatarSwap, TOGGLE_IOS_CHAT_HEADER_AVATAR_SWAP),
                SearchRegistry.Entry("ios-chat-header-avatar-static", R.string.InuIosChatHeaderAvatarStatic, TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC),
                SearchRegistry.Entry("chat-header-no-pill", R.string.InuChatHeaderNoPill, TOGGLE_CHAT_HEADER_NO_PILL),
                SearchRegistry.Entry("chat-title-marquee", R.string.InuChatTitleMarquee, TOGGLE_CHAT_TITLE_MARQUEE),
                SearchRegistry.Entry("unread-badge-on-back-button", R.string.InuUnreadBadgeOnBackButton, TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON),
                SearchRegistry.Entry("unread-badge-on-back-button-ios", R.string.InuUnreadBadgeOnBackButtonIos, TOGGLE_UNREAD_BADGE_ON_BACK_BUTTON_IOS),
            ),
        )
    }
}
