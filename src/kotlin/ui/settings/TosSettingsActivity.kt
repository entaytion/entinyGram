package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.LocalPremiumHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TosSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTOS)

    private var unlimitedPinsSlider: SliderCell? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(mkSubPageButton(CAT_GHOST_MODE, R.drawable.inu_ghost_filled, LocaleController.getString(R.string.InuGhostMode)))
        items.add(mkSubPageButton(CAT_ANTI_DELETION, R.drawable.inu_tabler_trash_off, LocaleController.getString(R.string.InuAntiDeletion)))
        items.add(mkSubPageButton(CAT_STALKER_PACK, R.drawable.inu_tabler_radar, LocaleController.getString(R.string.InuStalkerPack)))
        items.add(mkSubPageButton(CAT_REGEX_FILTER, R.drawable.inu_tabler_filter, LocaleController.getString(R.string.InuRegexFilter)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPremiumUnlock)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LOCAL_PREMIUM,
                R.string.InuLocalPremium,
                R.string.InuLocalPremiumInfo,
                InuConfig.LOCAL_PREMIUM.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LOCAL_CUSTOM_EMOJI,
                R.string.InuLocalCustomEmoji,
                R.string.InuLocalCustomEmojiInfo,
                InuConfig.LOCAL_CUSTOM_EMOJI.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuContentProtectionBypass)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_ANY_STORY,
                R.string.InuSaveAnyStory,
                R.string.InuSaveAnyStoryInfo,
                InuConfig.SAVE_ANY_STORY.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ALLOW_FORWARD_RESTRICTED,
                R.string.InuAllowForwardRestricted,
                R.string.InuAllowForwardRestrictedInfo,
                InuConfig.ALLOW_FORWARD_RESTRICTED.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ALLOW_SCREENSHOTS,
                R.string.InuAllowScreenshots,
                R.string.InuAllowScreenshotsInfo,
                InuConfig.ALLOW_SCREENSHOTS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION,
                R.string.InuSuppressScreenshotNotification,
                R.string.InuSuppressScreenshotNotificationInfo,
                InuConfig.SUPPRESS_SCREENSHOT_NOTIFICATION.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUnlimitedLimits)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNLIMITED_PINNED_CHATS,
                R.string.InuUnlimitedPinnedChats,
                R.string.InuUnlimitedPinnedChatsInfo,
                InuConfig.UNLIMITED_PINNED_CHATS.value,
            )
        )
        if (InuConfig.UNLIMITED_PINNED_CHATS.value) {
            if (unlimitedPinsSlider == null) {
                unlimitedPinsSlider = SliderCell(
                    context,
                    min = 5f,
                    max = 100f,
                    defaultValue = InuConfig.UNLIMITED_PINNED_CHATS_COUNT.default.toFloat(),
                    initialValue = InuConfig.UNLIMITED_PINNED_CHATS_COUNT.value.toFloat(),
                    step = 1f,
                    title = LocaleController.getString(R.string.InuUnlimitedPinnedChatsCount),
                    format = { it.toInt().toString() },
                    onChanged = { InuConfig.UNLIMITED_PINNED_CHATS_COUNT.value = it.toInt() },
                )
            } else {
                unlimitedPinsSlider?.updateColors()
            }
            items.add(UItem.asCustom(unlimitedPinsSlider))
        }
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNLIMITED_FAVORITE_STICKERS,
                R.string.InuUnlimitedFavoriteStickers,
                R.string.InuUnlimitedFavoriteStickersInfo,
                InuConfig.UNLIMITED_FAVORITE_STICKERS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNLIMITED_FOLDER_CHATS,
                R.string.InuUnlimitedFolderChats,
                R.string.InuUnlimitedFolderChatsInfo,
                InuConfig.UNLIMITED_FOLDER_CHATS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LOCAL_FOLDERS,
                R.string.InuLocalFolders,
                R.string.InuLocalFoldersInfo,
                InuConfig.LOCAL_FOLDERS.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            CAT_GHOST_MODE -> presentFragment(GhostModeSettingsActivity())
            CAT_ANTI_DELETION -> presentFragment(AntiDeletionSettingsActivity())
            CAT_STALKER_PACK -> presentFragment(StalkerPackSettingsActivity())
            CAT_REGEX_FILTER -> presentFragment(RegexFilterSettingsActivity())

            TOGGLE_LOCAL_PREMIUM -> {
                val new = InuConfig.LOCAL_PREMIUM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                val userConfig = UserConfig.getInstance(currentAccount)
                if (new) {
                    LocalPremiumHelper.applyToSelfUser(userConfig.getCurrentUser(), currentAccount)
                } else {
                    LocalPremiumHelper.clearSelfUser(userConfig.getCurrentUser())
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged)
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.premiumStatusChangedGlobal)
            }
            TOGGLE_LOCAL_CUSTOM_EMOJI -> {
                val new = InuConfig.LOCAL_CUSTOM_EMOJI.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_ANY_STORY -> {
                val new = InuConfig.SAVE_ANY_STORY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_ALLOW_FORWARD_RESTRICTED -> {
                val new = InuConfig.ALLOW_FORWARD_RESTRICTED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_ALLOW_SCREENSHOTS -> {
                val new = InuConfig.ALLOW_SCREENSHOTS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION -> {
                val new = InuConfig.SUPPRESS_SCREENSHOT_NOTIFICATION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_UNLIMITED_PINNED_CHATS -> {
                val new = InuConfig.UNLIMITED_PINNED_CHATS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_UNLIMITED_FAVORITE_STICKERS -> {
                val new = InuConfig.UNLIMITED_FAVORITE_STICKERS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_UNLIMITED_FOLDER_CHATS -> {
                val new = InuConfig.UNLIMITED_FOLDER_CHATS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_LOCAL_FOLDERS -> {
                val new = InuConfig.LOCAL_FOLDERS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val CAT_GHOST_MODE = InuUtils.generateId()
        private val CAT_ANTI_DELETION = InuUtils.generateId()
        private val CAT_STALKER_PACK = InuUtils.generateId()
        private val CAT_REGEX_FILTER = InuUtils.generateId()

        private val TOGGLE_LOCAL_PREMIUM = InuUtils.generateId()
        private val TOGGLE_LOCAL_CUSTOM_EMOJI = InuUtils.generateId()
        private val TOGGLE_SAVE_ANY_STORY = InuUtils.generateId()
        private val TOGGLE_ALLOW_FORWARD_RESTRICTED = InuUtils.generateId()
        private val TOGGLE_ALLOW_SCREENSHOTS = InuUtils.generateId()
        private val TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION = InuUtils.generateId()

        private val TOGGLE_UNLIMITED_PINNED_CHATS = InuUtils.generateId()
        private val TOGGLE_UNLIMITED_FAVORITE_STICKERS = InuUtils.generateId()
        private val TOGGLE_UNLIMITED_FOLDER_CHATS = InuUtils.generateId()
        private val TOGGLE_LOCAL_FOLDERS = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "tos",
            titleRes = R.string.InuTOS,
            iconRes = R.drawable.inu_tabler_lock_open,
            factory = ::TosSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("local-premium", R.string.InuLocalPremium, TOGGLE_LOCAL_PREMIUM),
                SearchRegistry.Entry("local-custom-emoji", R.string.InuLocalCustomEmoji, TOGGLE_LOCAL_CUSTOM_EMOJI),
                SearchRegistry.Entry("save-any-story", R.string.InuSaveAnyStory, TOGGLE_SAVE_ANY_STORY),
                SearchRegistry.Entry("allow-forward-restricted", R.string.InuAllowForwardRestricted, TOGGLE_ALLOW_FORWARD_RESTRICTED),
                SearchRegistry.Entry("allow-screenshots", R.string.InuAllowScreenshots, TOGGLE_ALLOW_SCREENSHOTS),
                SearchRegistry.Entry("suppress-screenshot-notification", R.string.InuSuppressScreenshotNotification, TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION),
                SearchRegistry.Entry("unlimited-pinned-chats", R.string.InuUnlimitedPinnedChats, TOGGLE_UNLIMITED_PINNED_CHATS),
                SearchRegistry.Entry("unlimited-favorite-stickers", R.string.InuUnlimitedFavoriteStickers, TOGGLE_UNLIMITED_FAVORITE_STICKERS),
                SearchRegistry.Entry("unlimited-folder-chats", R.string.InuUnlimitedFolderChats, TOGGLE_UNLIMITED_FOLDER_CHATS),
                SearchRegistry.Entry("local-folders", R.string.InuLocalFolders, TOGGLE_LOCAL_FOLDERS),
            ),
        )
    }
}
