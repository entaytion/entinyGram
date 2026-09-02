package desu.inugram.ui.settings

import android.view.View
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.HintsController
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AnnoyancesSettingsActivity : SettingsPageActivity() {

    private val aiFeaturesGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideAiFeatures),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuHideMessageSummary, InuConfig.HIDE_MESSAGE_SUMMARY),
            ExpandableBoolGroup.Option(R.string.InuHideIvSummary, InuConfig.HIDE_IV_SUMMARY),
        ),
        sectionId = SECTION_HIDE_AI_FEATURES,
    )

    private val hideSuggestionsGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideSuggestions),
        listOf(
            ExpandableBoolGroup.Option(
                R.string.InuHideSuggestionBirthdaySetup,
                InuConfig.HIDE_SUGGESTION_BIRTHDAY_SETUP
            ),
            ExpandableBoolGroup.Option(
                R.string.InuHideSuggestionBirthdayContacts,
                InuConfig.HIDE_SUGGESTION_BIRTHDAY_CONTACTS
            ),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionPassword, InuConfig.HIDE_SUGGESTION_PASSWORD),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionPhone, InuConfig.HIDE_SUGGESTION_PHONE),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionPremium, InuConfig.HIDE_SUGGESTION_PREMIUM),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionCustom, InuConfig.HIDE_SUGGESTION_CUSTOM),
        ),
        sectionId = SECTION_HIDE_SUGGESTIONS,
    )

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAnnoyances)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // 1. Stories
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesStories)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_STORIES,
                R.string.InuHideStories,
                R.string.InuHideStoriesInfo,
                InuConfig.HIDE_STORIES.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_REPOST_TO_STORY,
                LocaleController.getString(R.string.InuHideRepostToStory),
            ).setChecked(InuConfig.HIDE_REPOST_TO_STORY.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_STORY_BUTTON,
                LocaleController.getString(R.string.InuHideProfileStoryButton),
            ).setChecked(InuConfig.HIDE_PROFILE_STORY_BUTTON.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_MENU_ARCHIVED_STORIES,
                LocaleController.getString(R.string.InuHideProfileMenuArchivedStories),
            ).setChecked(InuConfig.HIDE_PROFILE_MENU_ARCHIVED_STORIES.value)
        )
        items.add(UItem.asShadow(null))

        // 2. Profile
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesProfile)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PREMIUM_BADGE,
                LocaleController.getString(R.string.InuHidePremiumBadge),
            ).setChecked(InuConfig.HIDE_PREMIUM_BADGE.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_COLLECTIBLE_STATUS,
                R.string.InuHideCollectibleStatus,
                R.string.InuHideCollectibleStatusInfo,
                InuConfig.HIDE_COLLECTIBLE_STATUS.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_STARS_RATING,
                LocaleController.getString(R.string.InuHideStarsRating),
            ).setChecked(InuConfig.HIDE_STARS_RATING.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_VERIFICATION_BADGE,
                LocaleController.getString(R.string.InuHideVerificationBadge),
            ).setChecked(InuConfig.HIDE_VERIFICATION_BADGE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_COLORFUL_BACKGROUND,
                LocaleController.getString(R.string.InuHideProfileColorfulBackground),
            ).setChecked(InuConfig.HIDE_PROFILE_COLORFUL_BACKGROUND.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_GIFTS_AROUND_AVATAR,
                R.string.InuHideGiftsAroundAvatar,
                R.string.InuHideGiftsAroundAvatarInfo,
                InuConfig.HIDE_GIFTS_AROUND_AVATAR.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_ICONS,
                LocaleController.getString(R.string.InuHideProfileIcons),
            ).setChecked(InuConfig.HIDE_PROFILE_ICONS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_LIVE_ACTIONS_BUTTON,
                LocaleController.getString(R.string.InuHideProfileLiveActionsButton),
            ).setChecked(InuConfig.HIDE_PROFILE_LIVE_ACTIONS_BUTTON.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_PROFILE_MUSIC_AUTOPLAY,
                R.string.InuDisableProfileMusicAutoplay,
                R.string.InuDisableProfileMusicAutoplayInfo,
                InuConfig.DISABLE_PROFILE_MUSIC_AUTOPLAY.value
            )
        )
        items.add(mkSubPageButton(BUTTON_PROFILE_SETTINGS_ROWS_ORDER, R.drawable.inu_tabler_menu_2, LocaleController.getString(R.string.InuProfileSettingsRowsOrder)))
        items.add(mkSubPageButton(BUTTON_PROFILE_INFO_ROWS_ORDER, R.drawable.inu_tabler_menu_2, LocaleController.getString(R.string.InuProfileInfoRowsOrder)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuProfileSettingsRowsOrderInfo)))

        // 3. Gifts & Premium
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesGifts)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_PROFILE_GIFTS_TAB,
                R.string.InuHideProfileGiftsTab,
                R.string.InuHideProfileGiftsTabInfo,
                InuConfig.HIDE_PROFILE_GIFTS_TAB.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_GIFT_BUTTON,
                LocaleController.getString(R.string.InuHideProfileGiftButton),
            ).setChecked(InuConfig.HIDE_PROFILE_GIFT_BUTTON.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PROFILE_MENU_SEND_GIFT,
                LocaleController.getString(R.string.InuHideProfileMenuSendGift),
            ).setChecked(InuConfig.HIDE_PROFILE_MENU_SEND_GIFT.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_GIFT_BUTTON_INPUT,
                R.string.InuHideGiftButtonInput,
                R.string.InuHideGiftButtonInputInfo,
                InuConfig.HIDE_GIFT_BUTTON_INPUT.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_GIFT_CARDS_IN_CHAT,
                R.string.InuHideGiftCardsInChat,
                R.string.InuHideGiftCardsInChatInfo,
                InuConfig.HIDE_GIFT_CARDS_IN_CHAT.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_GIFT_AUCTIONS_HINT,
                LocaleController.getString(R.string.InuHideGiftAuctionsHint),
            ).setChecked(InuConfig.HIDE_GIFT_AUCTIONS_HINT.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PAID_REACTION_UPSELL,
                LocaleController.getString(R.string.InuHidePaidReactionUpsell),
            ).setChecked(InuConfig.HIDE_PAID_REACTION_UPSELL.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_GIVEAWAYS,
                LocaleController.getString(R.string.InuHideGiveaways),
            ).setChecked(InuConfig.HIDE_GIVEAWAYS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_ATTACH_PREMIUM_BADGES,
                LocaleController.getString(R.string.InuHideAttachPremiumBadges),
            ).setChecked(InuConfig.HIDE_ATTACH_PREMIUM_BADGES.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_EMOJI_PREMIUM_UPSELL,
                LocaleController.getString(R.string.InuHideEmojiPremiumUpsell),
            ).setChecked(InuConfig.HIDE_EMOJI_PREMIUM_UPSELL.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_CAPTION_LIMIT_UPSELL,
                R.string.InuHideCaptionLimitUpsell,
                R.string.InuHideCaptionLimitUpsellInfo,
                InuConfig.HIDE_CAPTION_LIMIT_UPSELL.value
            )
        )
        items.add(UItem.asShadow(null))

        // 4. Chats & Interface
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesInterface)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_TRENDING_STICKERS,
                LocaleController.getString(R.string.InuHideTrendingStickers),
            ).setChecked(InuConfig.HIDE_TRENDING_STICKERS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_GROUP_STICKER_PACK,
                LocaleController.getString(R.string.InuHideGroupStickerPack),
            ).setChecked(InuConfig.HIDE_GROUP_STICKER_PACK.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_HASHTAG_SUGGESTIONS,
                LocaleController.getString(R.string.InuHideHashtagSuggestions),
            ).setChecked(InuConfig.HIDE_HASHTAG_SUGGESTIONS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_RICH_EDITOR_BUTTON,
                LocaleController.getString(R.string.InuHideRichEditorButton),
            ).setChecked(InuConfig.HIDE_RICH_EDITOR_BUTTON.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_SENSITIVE,
                LocaleController.getString(R.string.InuDisableSensitive),
            ).setChecked(InuConfig.DISABLE_SENSITIVE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BACKGROUNDS,
                LocaleController.getString(R.string.InuDisableChatBackgrounds),
            ).setChecked(InuConfig.DISABLE_CHAT_BACKGROUNDS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_THEMES,
                LocaleController.getString(R.string.InuDisableChatThemes),
            ).setChecked(InuConfig.DISABLE_CHAT_THEMES.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_BG_PARALLAX,
                LocaleController.getString(R.string.InuDisableBgParallax),
            ).setChecked(InuConfig.DISABLE_BG_PARALLAX.value)
        )
        items.add(UItem.asShadow(null))

        // 5. Channels & Recommendations
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesChannels)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_SIMILAR_CHANNELS_TAB,
                LocaleController.getString(R.string.InuHideSimilarChannelsTab),
            ).setChecked(InuConfig.HIDE_SIMILAR_CHANNELS_TAB.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_CHANNEL_RECOMMENDATIONS,
                LocaleController.getString(R.string.InuHideChannelRecommendations),
            ).setChecked(InuConfig.HIDE_CHANNEL_RECOMMENDATIONS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_CHANNEL_SHARE_BUTTON,
                R.string.InuHideChannelShareButton,
                R.string.InuHideChannelShareButtonInfo,
                InuConfig.HIDE_CHANNEL_SHARE_BUTTON.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PSA_PROMO_CHAT,
                LocaleController.getString(R.string.InuHidePsaPromoChat),
            ).setChecked(InuConfig.HIDE_PSA_PROMO_CHAT.value)
        )
        items.add(UItem.asShadow(null))

        // 6. Media
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesMedia)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_MOTION_PHOTOS,
                R.string.InuDisableMotionPhotos,
                R.string.InuDisableMotionPhotosInfo,
                InuConfig.DISABLE_MOTION_PHOTOS.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_INTRO_STICKER,
                R.string.InuDisableIntroSticker,
                R.string.InuDisableIntroStickerInfo,
                InuConfig.DISABLE_INTRO_STICKER.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_VOLUME_PLAY_VIDEO,
                R.string.InuDisableVolumePlayVideo,
                R.string.InuDisableVolumePlayVideoInfo,
                InuConfig.DISABLE_VOLUME_PLAY_VIDEO.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_QUICK_SHARE,
                R.string.InuDisableQuickShare,
                R.string.InuDisableQuickShareInfo,
                InuConfig.DISABLE_QUICK_SHARE.value
            )
        )
        items.add(UItem.asShadow(null))

        // 7. Artificial Intelligence
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesAi)))
        aiFeaturesGroup.addTo(items) { listView.adapter.update(true) }
        items.add(UItem.asShadow(null))

        // 8. Hints & Prompts
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAnnoyancesHints)))
        hideSuggestionsGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_CACHE_HINT,
                LocaleController.getString(R.string.InuHideCacheHint),
            ).setChecked(InuConfig.HIDE_CACHE_HINT.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_CONTACTS_PERMISSION_NAG,
                R.string.InuDisableContactsPermissionNag,
                R.string.InuDisableContactsPermissionNagInfo,
                InuConfig.DISABLE_CONTACTS_PERMISSION_NAG.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_LOCKSCREEN_PERMISSION_NAG,
                R.string.InuDisableLockscreenPermissionNag,
                R.string.InuDisableLockscreenPermissionNagInfo,
                InuConfig.DISABLE_LOCKSCREEN_PERMISSION_NAG.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_CALL_RATING,
                R.string.InuDisableCallRating,
                R.string.InuDisableCallRatingInfo,
                InuConfig.DISABLE_CALL_RATING.value
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CLEAR_HINTS,
                LocaleController.getString(R.string.InuClearHints),
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (aiFeaturesGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        if (hideSuggestionsGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        when (item.id) {
            BUTTON_PROFILE_SETTINGS_ROWS_ORDER -> presentFragment(ProfileSettingsMenuOrderActivity())

            BUTTON_PROFILE_INFO_ROWS_ORDER -> presentFragment(ProfileInfoMenuOrderActivity())

            TOGGLE_HIDE_STORIES -> {
                val new = InuConfig.HIDE_STORIES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                postNotificationForAllAccounts(NotificationCenter.storiesUpdated)
            }

            TOGGLE_HIDE_TRENDING_STICKERS -> {
                val new = InuConfig.HIDE_TRENDING_STICKERS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_REPOST_TO_STORY -> {
                val new = InuConfig.HIDE_REPOST_TO_STORY.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_SENSITIVE -> {
                val new = InuConfig.DISABLE_SENSITIVE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_BACKGROUNDS -> {
                val new = InuConfig.DISABLE_CHAT_BACKGROUNDS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_THEMES -> {
                val new = InuConfig.DISABLE_CHAT_THEMES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_BG_PARALLAX -> {
                val new = InuConfig.DISABLE_BG_PARALLAX.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }


            TOGGLE_HIDE_PAID_REACTION_UPSELL -> {
                val new = InuConfig.HIDE_PAID_REACTION_UPSELL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_HASHTAG_SUGGESTIONS -> {
                val new = InuConfig.HIDE_HASHTAG_SUGGESTIONS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_RICH_EDITOR_BUTTON -> {
                val new = InuConfig.HIDE_RICH_EDITOR_BUTTON.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_MOTION_PHOTOS -> {
                val new = InuConfig.DISABLE_MOTION_PHOTOS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_INTRO_STICKER -> {
                val new = InuConfig.DISABLE_INTRO_STICKER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_VOLUME_PLAY_VIDEO -> {
                val new = InuConfig.DISABLE_VOLUME_PLAY_VIDEO.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_QUICK_SHARE -> {
                val new = InuConfig.DISABLE_QUICK_SHARE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_CHANNEL_SHARE_BUTTON -> {
                val new = InuConfig.HIDE_CHANNEL_SHARE_BUTTON.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PROFILE_MUSIC_AUTOPLAY -> {
                val new = InuConfig.DISABLE_PROFILE_MUSIC_AUTOPLAY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_GIFT_AUCTIONS_HINT -> {
                val new = InuConfig.HIDE_GIFT_AUCTIONS_HINT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_CACHE_HINT -> {
                val new = InuConfig.HIDE_CACHE_HINT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PSA_PROMO_CHAT -> {
                val new = InuConfig.HIDE_PSA_PROMO_CHAT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_GIFTS_TAB -> {
                val new = InuConfig.HIDE_PROFILE_GIFTS_TAB.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_SIMILAR_CHANNELS_TAB -> {
                val new = InuConfig.HIDE_SIMILAR_CHANNELS_TAB.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_CHANNEL_RECOMMENDATIONS -> {
                val new = InuConfig.HIDE_CHANNEL_RECOMMENDATIONS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_DISABLE_CONTACTS_PERMISSION_NAG -> {
                val new = InuConfig.DISABLE_CONTACTS_PERMISSION_NAG.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_LOCKSCREEN_PERMISSION_NAG -> {
                val new = InuConfig.DISABLE_LOCKSCREEN_PERMISSION_NAG.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CALL_RATING -> {
                val new = InuConfig.DISABLE_CALL_RATING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_CAPTION_LIMIT_UPSELL -> {
                val new = InuConfig.HIDE_CAPTION_LIMIT_UPSELL.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_ATTACH_PREMIUM_BADGES -> {
                val new = InuConfig.HIDE_ATTACH_PREMIUM_BADGES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_EMOJI_PREMIUM_UPSELL -> {
                val new = InuConfig.HIDE_EMOJI_PREMIUM_UPSELL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_STORY_BUTTON -> {
                val new = InuConfig.HIDE_PROFILE_STORY_BUTTON.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_GIFT_BUTTON -> {
                val new = InuConfig.HIDE_PROFILE_GIFT_BUTTON.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_LIVE_ACTIONS_BUTTON -> {
                val new = InuConfig.HIDE_PROFILE_LIVE_ACTIONS_BUTTON.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_MENU_SEND_GIFT -> {
                val new = InuConfig.HIDE_PROFILE_MENU_SEND_GIFT.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_MENU_ARCHIVED_STORIES -> {
                val new = InuConfig.HIDE_PROFILE_MENU_ARCHIVED_STORIES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PREMIUM_BADGE -> {
                val new = InuConfig.HIDE_PREMIUM_BADGE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_COLLECTIBLE_STATUS -> {
                val new = InuConfig.HIDE_COLLECTIBLE_STATUS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_STARS_RATING -> {
                val new = InuConfig.HIDE_STARS_RATING.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_VERIFICATION_BADGE -> {
                val new = InuConfig.HIDE_VERIFICATION_BADGE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_COLORFUL_BACKGROUND -> {
                val new = InuConfig.HIDE_PROFILE_COLORFUL_BACKGROUND.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_GIFTS_AROUND_AVATAR -> {
                val new = InuConfig.HIDE_GIFTS_AROUND_AVATAR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_PROFILE_ICONS -> {
                val new = InuConfig.HIDE_PROFILE_ICONS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_GIFT_BUTTON_INPUT -> {
                val new = InuConfig.HIDE_GIFT_BUTTON_INPUT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_GIFT_CARDS_IN_CHAT -> {
                val new = InuConfig.HIDE_GIFT_CARDS_IN_CHAT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_HIDE_GIVEAWAYS -> {
                val new = InuConfig.HIDE_GIVEAWAYS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_HIDE_GROUP_STICKER_PACK -> {
                val new = InuConfig.HIDE_GROUP_STICKER_PACK.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_CLEAR_HINTS -> {
                // holy fucking shit how is it so inconsistent
                SharedConfig.dayNightWallpaperSwitchHint = 99
                SharedConfig.increaseTextSelectionHintShowed()
                SharedConfig.increaseDayNightWallpaperSiwtchHint()
                SharedConfig.increaseScheduledOrNoSoundHintShowed()
                SharedConfig.increaseScheduledHintShowed()
                SharedConfig.incrementCallEncryptionHintDisplayed(99)
                SharedConfig.forwardingOptionsHintHintShowed()
                SharedConfig.setStickersReorderingHintUsed(true)
                SharedConfig.removeTextSelectionHint()
                SharedConfig.replyingOptionsHintHintShowed()
                SharedConfig.removeScheduledOrNoSoundHint()
                SharedConfig.removeScheduledHint()
                SharedConfig.removeLockRecordAudioVideoHint()
                SharedConfig.setStoriesReactionsLongPressHintUsed(true)
                SharedConfig.setStoriesIntroShown(true)
                SharedConfig.updateMessageSeenHintCount(0)
                SharedConfig.updateEmojiInteractionsHintCount(0)
                SharedConfig.updateDayNightThemeSwitchHintCount(0)
                SharedConfig.updateStealthModeSendMessageConfirm(0)
                MessagesController.getGlobalMainSettings().edit {
                    putInt("channelsuggesthint2", 99)
                    putInt("hidecallshint", 99)
                    putInt("savedsearchhint", 99)
                    putInt("savedhint", 99)
                    putInt("voicepausehint", 99)
                    putInt("aihintshown", 99)
                    putInt("voiceoncehint", 99)
                    putInt("viewoncehint", 99)
                    putInt("multistorieshint", 99)
                    putInt("taptostoryhighlighthint", 99)
                    putInt("searchpostsnew", 99)
                    putInt("storydualhint", 99)
                    putInt("storysvddualhint", 99)
                    putInt("storyhint2", 99)
                    putInt("proximityhint", 99)
                    putInt("transcribeButtonPressed", 99)
                    putInt("taptostorysoundhint", 99)
                    putInt("showchattagsinfo", 0)
                    putInt("speedhint", -15)
                    putBoolean("monetizationadshint", true)
                    putBoolean("groupEmojiPackHintShown", true)
                    putBoolean("seekSpeedHintShowed", true)
                    putBoolean("storyprvhint", true)
                    putBoolean("gifhint", true)
                    putBoolean("archivehint_l", true)
                    putBoolean("reminderhint", true)
                    putBoolean("bganimationhint", true)
                    putBoolean("themehint", true)
                    putBoolean("filterhint", true)
                    putBoolean("bizbothint", true)
                    putBoolean("privacyAlertShowed", true)
                    putBoolean("archivehint", false)
                    putBoolean("storyhint", false)
                    putBoolean("trimvoicehint", false)
                }
                HintsController.Hint.ChannelGiftHint.doNotShowAgain()
                HintsController.Hint.AccountSwitchHint.doNotShowAgain()
                HintsController.Hint.RoundHintChannel2.doNotShowAgain()
                HintsController.Hint.ChannelSuggestHint.doNotShowAgain()
                HintsController.Hint.GroupEmojiPackHintShown.doNotShowAgain()
                HintsController.Hint.RoundHint2.doNotShowAgain()
                InuConfig.VOICE_HINT_SHOWN.value = true;
                BulletinFactory.of(this)
                    .createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.InuClearHintsDone)
                    )
                    .show()
            }
        }
    }

    companion object {
        private val TOGGLE_HIDE_STORIES = InuUtils.generateId()
        private val TOGGLE_HIDE_TRENDING_STICKERS = InuUtils.generateId()
        private val TOGGLE_HIDE_REPOST_TO_STORY = InuUtils.generateId()
        private val TOGGLE_DISABLE_SENSITIVE = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BACKGROUNDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_THEMES = InuUtils.generateId()
        private val TOGGLE_DISABLE_BG_PARALLAX = InuUtils.generateId()
        private val TOGGLE_HIDE_PAID_REACTION_UPSELL = InuUtils.generateId()
        private val TOGGLE_HIDE_HASHTAG_SUGGESTIONS = InuUtils.generateId()
        private val TOGGLE_HIDE_RICH_EDITOR_BUTTON = InuUtils.generateId()
        private val TOGGLE_DISABLE_MOTION_PHOTOS = InuUtils.generateId()
        private val TOGGLE_DISABLE_INTRO_STICKER = InuUtils.generateId()
        private val TOGGLE_DISABLE_VOLUME_PLAY_VIDEO = InuUtils.generateId()
        private val TOGGLE_DISABLE_QUICK_SHARE = InuUtils.generateId()
        private val TOGGLE_HIDE_CHANNEL_SHARE_BUTTON = InuUtils.generateId()
        private val TOGGLE_DISABLE_PROFILE_MUSIC_AUTOPLAY = InuUtils.generateId()
        private val BUTTON_CLEAR_HINTS = InuUtils.generateId()
        private val BUTTON_PROFILE_SETTINGS_ROWS_ORDER = InuUtils.generateId()
        private val BUTTON_PROFILE_INFO_ROWS_ORDER = InuUtils.generateId()
        private val TOGGLE_HIDE_GIFT_AUCTIONS_HINT = InuUtils.generateId()
        private val TOGGLE_HIDE_CACHE_HINT = InuUtils.generateId()
        private val TOGGLE_HIDE_PSA_PROMO_CHAT = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_GIFTS_TAB = InuUtils.generateId()
        private val TOGGLE_HIDE_SIMILAR_CHANNELS_TAB = InuUtils.generateId()
        private val TOGGLE_HIDE_CHANNEL_RECOMMENDATIONS = InuUtils.generateId()
        private val TOGGLE_DISABLE_CONTACTS_PERMISSION_NAG = InuUtils.generateId()
        private val TOGGLE_DISABLE_LOCKSCREEN_PERMISSION_NAG = InuUtils.generateId()
        private val TOGGLE_DISABLE_CALL_RATING = InuUtils.generateId()
        private val TOGGLE_HIDE_CAPTION_LIMIT_UPSELL = InuUtils.generateId()
        private val TOGGLE_HIDE_ATTACH_PREMIUM_BADGES = InuUtils.generateId()
        private val TOGGLE_HIDE_EMOJI_PREMIUM_UPSELL = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_STORY_BUTTON = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_GIFT_BUTTON = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_LIVE_ACTIONS_BUTTON = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_MENU_SEND_GIFT = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_MENU_ARCHIVED_STORIES = InuUtils.generateId()
        private val TOGGLE_HIDE_PREMIUM_BADGE = InuUtils.generateId()
        private val TOGGLE_HIDE_COLLECTIBLE_STATUS = InuUtils.generateId()
        private val TOGGLE_HIDE_STARS_RATING = InuUtils.generateId()
        private val TOGGLE_HIDE_VERIFICATION_BADGE = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_COLORFUL_BACKGROUND = InuUtils.generateId()
        private val TOGGLE_HIDE_GIFTS_AROUND_AVATAR = InuUtils.generateId()
        private val TOGGLE_HIDE_PROFILE_ICONS = InuUtils.generateId()
        private val TOGGLE_HIDE_GIFT_BUTTON_INPUT = InuUtils.generateId()
        private val TOGGLE_HIDE_GIFT_CARDS_IN_CHAT = InuUtils.generateId()
        private val TOGGLE_HIDE_GIVEAWAYS = InuUtils.generateId()
        private val TOGGLE_HIDE_GROUP_STICKER_PACK = InuUtils.generateId()
        private val SECTION_HIDE_AI_FEATURES = InuUtils.generateId()
        private val SECTION_HIDE_SUGGESTIONS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "annoyances",
            titleRes = R.string.InuAnnoyances,
            iconRes = R.drawable.inu_tabler_shield_cancel,
            factory = ::AnnoyancesSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-stories", R.string.InuHideStories, TOGGLE_HIDE_STORIES),
                SearchRegistry.Entry("hide-repost-to-story", R.string.InuHideRepostToStory, TOGGLE_HIDE_REPOST_TO_STORY),
                SearchRegistry.Entry("hide-trending-stickers", R.string.InuHideTrendingStickers, TOGGLE_HIDE_TRENDING_STICKERS),
                SearchRegistry.Entry("disable-sensitive", R.string.InuDisableSensitive, TOGGLE_DISABLE_SENSITIVE),
                SearchRegistry.Entry("disable-chat-backgrounds", R.string.InuDisableChatBackgrounds, TOGGLE_DISABLE_CHAT_BACKGROUNDS),
                SearchRegistry.Entry("disable-chat-themes", R.string.InuDisableChatThemes, TOGGLE_DISABLE_CHAT_THEMES),
                SearchRegistry.Entry("disable-bg-parallax", R.string.InuDisableBgParallax, TOGGLE_DISABLE_BG_PARALLAX),
                SearchRegistry.Entry("hide-paid-reaction-upsell", R.string.InuHidePaidReactionUpsell, TOGGLE_HIDE_PAID_REACTION_UPSELL),
                SearchRegistry.Entry("hide-hashtag-suggestions", R.string.InuHideHashtagSuggestions, TOGGLE_HIDE_HASHTAG_SUGGESTIONS),
                SearchRegistry.Entry("hide-rich-editor-button", R.string.InuHideRichEditorButton, TOGGLE_HIDE_RICH_EDITOR_BUTTON),
                SearchRegistry.Entry("disable-motion-photos", R.string.InuDisableMotionPhotos, TOGGLE_DISABLE_MOTION_PHOTOS),
                SearchRegistry.Entry("disable-intro-sticker", R.string.InuDisableIntroSticker, TOGGLE_DISABLE_INTRO_STICKER),
                SearchRegistry.Entry("disable-volume-play-video", R.string.InuDisableVolumePlayVideo, TOGGLE_DISABLE_VOLUME_PLAY_VIDEO),
                SearchRegistry.Entry("disable-quick-share", R.string.InuDisableQuickShare, TOGGLE_DISABLE_QUICK_SHARE),
                SearchRegistry.Entry("hide-channel-share-button", R.string.InuHideChannelShareButton, TOGGLE_HIDE_CHANNEL_SHARE_BUTTON),
                SearchRegistry.Entry("disable-profile-music-autoplay", R.string.InuDisableProfileMusicAutoplay, TOGGLE_DISABLE_PROFILE_MUSIC_AUTOPLAY),
                SearchRegistry.Entry("clear-hints", R.string.InuClearHints, BUTTON_CLEAR_HINTS),
                SearchRegistry.Entry("hide-gift-auctions-hint", R.string.InuHideGiftAuctionsHint, TOGGLE_HIDE_GIFT_AUCTIONS_HINT),
                SearchRegistry.Entry("hide-cache-hint", R.string.InuHideCacheHint, TOGGLE_HIDE_CACHE_HINT),
                SearchRegistry.Entry("hide-psa-promo-chat", R.string.InuHidePsaPromoChat, TOGGLE_HIDE_PSA_PROMO_CHAT),
                SearchRegistry.Entry("hide-profile-gifts-tab", R.string.InuHideProfileGiftsTab, TOGGLE_HIDE_PROFILE_GIFTS_TAB),
                SearchRegistry.Entry("hide-similar-channels-tab", R.string.InuHideSimilarChannelsTab, TOGGLE_HIDE_SIMILAR_CHANNELS_TAB),
                SearchRegistry.Entry("hide-channel-recommendations", R.string.InuHideChannelRecommendations, TOGGLE_HIDE_CHANNEL_RECOMMENDATIONS),
                SearchRegistry.Entry("disable-contacts-permission-nag", R.string.InuDisableContactsPermissionNag, TOGGLE_DISABLE_CONTACTS_PERMISSION_NAG),
                SearchRegistry.Entry("disable-lockscreen-permission-nag", R.string.InuDisableLockscreenPermissionNag, TOGGLE_DISABLE_LOCKSCREEN_PERMISSION_NAG),
                SearchRegistry.Entry("disable-call-rating", R.string.InuDisableCallRating, TOGGLE_DISABLE_CALL_RATING),
                SearchRegistry.Entry("hide-caption-limit-upsell", R.string.InuHideCaptionLimitUpsell, TOGGLE_HIDE_CAPTION_LIMIT_UPSELL),
                SearchRegistry.Entry("hide-attach-premium-badges", R.string.InuHideAttachPremiumBadges, TOGGLE_HIDE_ATTACH_PREMIUM_BADGES),
                SearchRegistry.Entry("hide-emoji-premium-upsell", R.string.InuHideEmojiPremiumUpsell, TOGGLE_HIDE_EMOJI_PREMIUM_UPSELL),
                SearchRegistry.Entry("hide-profile-story-button", R.string.InuHideProfileStoryButton, TOGGLE_HIDE_PROFILE_STORY_BUTTON),
                SearchRegistry.Entry("hide-profile-gift-button", R.string.InuHideProfileGiftButton, TOGGLE_HIDE_PROFILE_GIFT_BUTTON),
                SearchRegistry.Entry("hide-profile-live-actions-button", R.string.InuHideProfileLiveActionsButton, TOGGLE_HIDE_PROFILE_LIVE_ACTIONS_BUTTON),
                SearchRegistry.Entry("hide-profile-menu-send-gift", R.string.InuHideProfileMenuSendGift, TOGGLE_HIDE_PROFILE_MENU_SEND_GIFT),
                SearchRegistry.Entry("hide-profile-menu-archived-stories", R.string.InuHideProfileMenuArchivedStories, TOGGLE_HIDE_PROFILE_MENU_ARCHIVED_STORIES),
                SearchRegistry.Entry("hide-premium-badge", R.string.InuHidePremiumBadge, TOGGLE_HIDE_PREMIUM_BADGE),
                SearchRegistry.Entry("hide-collectible-status", R.string.InuHideCollectibleStatus, TOGGLE_HIDE_COLLECTIBLE_STATUS),
                SearchRegistry.Entry("hide-stars-rating", R.string.InuHideStarsRating, TOGGLE_HIDE_STARS_RATING),
                SearchRegistry.Entry("hide-verification-badge", R.string.InuHideVerificationBadge, TOGGLE_HIDE_VERIFICATION_BADGE),
                SearchRegistry.Entry("hide-profile-colorful-background", R.string.InuHideProfileColorfulBackground, TOGGLE_HIDE_PROFILE_COLORFUL_BACKGROUND),
                SearchRegistry.Entry("hide-gifts-around-avatar", R.string.InuHideGiftsAroundAvatar, TOGGLE_HIDE_GIFTS_AROUND_AVATAR),
                SearchRegistry.Entry("hide-profile-icons", R.string.InuHideProfileIcons, TOGGLE_HIDE_PROFILE_ICONS),
                SearchRegistry.Entry("hide-gift-button-input", R.string.InuHideGiftButtonInput, TOGGLE_HIDE_GIFT_BUTTON_INPUT),
                SearchRegistry.Entry("hide-gift-cards-in-chat", R.string.InuHideGiftCardsInChat, TOGGLE_HIDE_GIFT_CARDS_IN_CHAT),
                SearchRegistry.Entry("hide-giveaways", R.string.InuHideGiveaways, TOGGLE_HIDE_GIVEAWAYS),
                SearchRegistry.Entry("hide-group-sticker-pack", R.string.InuHideGroupStickerPack, TOGGLE_HIDE_GROUP_STICKER_PACK),
                SearchRegistry.Entry("profile-settings-rows-order", R.string.InuProfileSettingsRowsOrder, BUTTON_PROFILE_SETTINGS_ROWS_ORDER),
                SearchRegistry.Entry("profile-info-rows-order", R.string.InuProfileInfoRowsOrder, BUTTON_PROFILE_INFO_ROWS_ORDER),
                SearchRegistry.Entry("hide-ai-features", R.string.InuHideAiFeatures, SECTION_HIDE_AI_FEATURES),
                SearchRegistry.Entry("hide-suggestions", R.string.InuHideSuggestions, SECTION_HIDE_SUGGESTIONS),
            ),
        )
    }
}
