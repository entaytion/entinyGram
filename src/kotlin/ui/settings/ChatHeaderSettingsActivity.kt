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

class ChatHeaderSettingsActivity : SettingsPageActivity() {

    private var previewCell: ChatHeaderPreviewCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatHeaderSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        if (previewCell == null) {
            previewCell = ChatHeaderPreviewCell(ctx)
        }
        items.add(UItem.asCustom(previewCell))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuChatHeaderSettingsInfo)))

        items.add(
            mkIconCheckItem(
                TOGGLE_CENTER_TITLE_CHATS,
                R.drawable.inu_tabler_layout_align_center,
                R.string.InuCenterTitleChats,
                R.string.InuCenterTitleChatsInfo,
                InuConfig.CENTER_TITLE_CHATS.value,
            )
        )
        if (InuConfig.CENTER_TITLE_CHATS.value) {
            items.add(
                mkIconCheckItem(
                    TOGGLE_IOS_CHAT_HEADER,
                    R.drawable.inu_tabler_focus_2,
                    R.string.InuIosChatHeader,
                    R.string.InuIosChatHeaderInfo,
                    InuConfig.IOS_CHAT_HEADER.value,
                )
            )
            if (InuConfig.IOS_CHAT_HEADER.value) {
                items.add(
                    mkIconCheckItem(
                        TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT,
                        R.drawable.inu_tabler_dots_vertical,
                        R.string.InuIosChatHeaderAvatarSlot,
                        R.string.InuIosChatHeaderAvatarSlotInfo,
                        InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value,
                    )
                )
                if (!InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value) {
                    items.add(
                        mkIconCheckItem(
                            TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC,
                            R.drawable.inu_tabler_pin,
                            R.string.InuIosChatHeaderAvatarStatic,
                            R.string.InuIosChatHeaderAvatarStaticInfo,
                            InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.value,
                        )
                    )
                }
            }
            if (!InuConfig.IOS_CHAT_HEADER.value ||
                (!InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value && !InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.value)
            ) {
                items.add(
                    mkIconCheckItem(
                        TOGGLE_CENTER_TITLE_RIGHT_AVATAR,
                        R.drawable.inu_tabler_arrow_bar_to_right,
                        R.string.InuCenterTitleRightAvatar,
                        0,
                        InuConfig.CENTER_TITLE_RIGHT_AVATAR.value,
                    )
                )
            }
        }
        items.add(UItem.asShadow(null))

        items.add(
            mkIconCheckItem(
                TOGGLE_CHAT_TITLE_MARQUEE,
                R.drawable.inu_tabler_marquee_2,
                R.string.InuChatTitleMarquee,
                R.string.InuChatTitleMarqueeInfo,
                InuConfig.CHAT_TITLE_MARQUEE.value,
                experimental = true,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_CENTER_TITLE_CHATS -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.CENTER_TITLE_CHATS.toggle()
                refreshAll()
            }

            TOGGLE_IOS_CHAT_HEADER -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.IOS_CHAT_HEADER.toggle()
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

            TOGGLE_CENTER_TITLE_RIGHT_AVATAR -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.CENTER_TITLE_RIGHT_AVATAR.toggle()
                refreshAll()
            }

            TOGGLE_CHAT_TITLE_MARQUEE -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_TITLE_MARQUEE.toggle()
                refreshAll()
            }
        }
    }

    private fun refreshAll() {
        previewCell?.refresh()
        listView.adapter.update(true)
    }

    companion object {
        private val TOGGLE_CENTER_TITLE_CHATS = InuUtils.generateId()
        private val TOGGLE_CENTER_TITLE_RIGHT_AVATAR = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC = InuUtils.generateId()
        private val TOGGLE_CHAT_TITLE_MARQUEE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "chat-header",
            titleRes = R.string.InuChatHeaderSettings,
            iconRes = R.drawable.inu_tabler_app_window,
            factory = ::ChatHeaderSettingsActivity,
            entries = listOf(
                // entiny: slugs kept verbatim from AppearanceSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("center-title-chats", R.string.InuCenterTitleChats, TOGGLE_CENTER_TITLE_CHATS),
                SearchRegistry.Entry("center-title-right-avatar", R.string.InuCenterTitleRightAvatar, TOGGLE_CENTER_TITLE_RIGHT_AVATAR),
                SearchRegistry.Entry("ios-chat-header", R.string.InuIosChatHeader, TOGGLE_IOS_CHAT_HEADER),
                SearchRegistry.Entry("ios-chat-header-avatar-slot", R.string.InuIosChatHeaderAvatarSlot, TOGGLE_IOS_CHAT_HEADER_AVATAR_SLOT),
                SearchRegistry.Entry("ios-chat-header-avatar-static", R.string.InuIosChatHeaderAvatarStatic, TOGGLE_IOS_CHAT_HEADER_AVATAR_STATIC),
                SearchRegistry.Entry("chat-title-marquee", R.string.InuChatTitleMarquee, TOGGLE_CHAT_TITLE_MARQUEE),
            ),
        )
    }
}
