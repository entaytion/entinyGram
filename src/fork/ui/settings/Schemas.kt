package desu.inugram.ui.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.helpers.LocalPremiumHelper
import desu.inugram.helpers.ProxyVpnHelper
import desu.inugram.helpers.push.UnifiedPushHelper
import desu.inugram.helpers.UrlCleanerHelper
import desu.inugram.helpers.security.BiometricHelper
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.chat.BlockedMessagesHelper
import desu.inugram.helpers.chat.DoubleTapAction
import desu.inugram.helpers.chat.DoubleTapActionHelper
import desu.inugram.helpers.chat.DoubleTapContext
import desu.inugram.helpers.chat.SideButtonAction
import desu.inugram.helpers.profile.LocalNameHelper
import desu.inugram.helpers.ShortcutHelper
import desu.inugram.helpers.chat.WebPreviewHelper
import desu.inugram.helpers.dialogs.DialogPreviewHelper
import desu.inugram.helpers.dialogs.DialogsFabHelper
import desu.inugram.helpers.dialogs.DrawerHelper
import desu.inugram.helpers.dialogs.FolderHelper
import desu.inugram.helpers.dialogs.MainTabsHelper
import desu.inugram.helpers.dialogs.UnreadFirstHelper
import desu.inugram.helpers.dialogs.RecentChatsHelper
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.ui.BookmarksManagerActivity
import desu.inugram.ui.showInputDialog
import desu.inugram.ui.spoof.DeviceSpoofSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.utils.twidget.WidgetMedia
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.HintsController
import org.telegram.ui.Components.UItem
import org.telegram.ui.GroupCreateActivity

object Schemas {
    // entiny: pre-schema deeplink slugs (tg://entinySettings/<slug>) that must keep resolving
    val LEGACY_ENTRY_SLUGS: Map<String, List<String>> = mapOf(
        "always-show-downloads" to listOf("hide-downloads-button"),
        "allow-screenshots" to listOf("disable-flag-secure"),
        "blocked-messages-hide-replies" to listOf("blocked-messages-replies"),
        "chat-presence-dots" to listOf("chat-online-dots"),
        "confirm-before-sending" to listOf("confirm-send-sticker", "confirm-send-gif"),
        "disable-call-proximity" to listOf("call-proximity"),
        "hide-bottom-bar-non-joined" to listOf("hide-join-bar"),
        "hide-reactions-entry" to listOf("hide-reaction-entry"),
        "mention-separator-for-bots" to listOf("mention-separator-bots"),
        "presence-color-dots" to listOf("online-dots"),
        "recent-chats-enabled" to listOf("recent-chats-open", "recent-chats-toggle"),
        "strip-tracking-params-on-paste" to listOf("strip-tracking-params-paste"),
        "hide-archive-from-chat-list" to listOf("hide-archive"),
        "dialogs-title-text" to listOf("title-text"),
        "dialogs-title-text-override-archive" to listOf("title-text-override-archive"),
        "bottom-tabs-hide-compact-mode" to listOf("compact-mode"),
        "bottom-tabs-show-titles" to listOf("show-tab-titles"),
        "bottom-tabs-search-separate" to listOf("separate-search-tab"),
        "bottom-tabs-reset" to listOf("customize-bottom-tabs"),
        "fab-main-action" to listOf("dialogs-fab-main-action"),
        "fab-secondary-action" to listOf("dialogs-fab-secondary-action"),
    )

    val LEGACY_PAGE_SLUGS: Map<String, List<String>> = mapOf(
        "chats-messages" to listOf("chats", "behavior", "messages", "messages-open"),
        "home" to listOf("main-screen", "side-menu", "menus", "recent-chats"),
        "privacy-freedom" to listOf("tos", "stalker-pack", "anti-censorship", "privacy-security"),
        "profile-accounts" to listOf("user-profile"),
    )

    private val DOWNLOAD_DIRECTORIES = listOf("entinyGram", "Telegram")

    val CHATS = schema("chats-messages", R.string.InuChatsAndMessages, R.drawable.msg_discussion) {
        section(R.string.InuMessageInput) {
            custom(
                "formatting-popup", R.string.InuFormattingPopup,
                build = { page, id ->
                    page.splitCheck(id, R.string.InuFormattingPopup, R.string.InuFormattingPopupInfo, InuConfig.FORMATTING_POPUP.value)
                },
                clickAt = { page, view, x ->
                    val isSwitch = if (LocaleController.isRTL) x < AndroidUtilities.dp(76f) else x > view.measuredWidth - AndroidUtilities.dp(76f)
                    if (isSwitch) {
                        val new = InuConfig.FORMATTING_POPUP.toggle()
                        (view as? NotificationsCheckCell)?.isChecked = new
                    } else {
                        page.presentFragment(FormattingPopupActivity())
                    }
                },
            )
            slider(InuConfig.CHAT_INPUT_MAX_LINES, R.string.InuChatInputMaxLines, 5f, 15f)
            toggle(InuConfig.HIDE_KEYBOARD_ON_SCROLL, R.string.InuHideKeyboardOnScroll)
            toggle(InuConfig.KEEP_KEYBOARD_WHILE_TYPING, R.string.InuKeepKeyboardWhileTyping, R.string.InuKeepKeyboardWhileTypingInfo)
            toggle(InuConfig.DISABLE_BOT_DRAFT_TOP, R.string.InuDisableBotDraftTop)
            toggle(InuConfig.DISABLE_LINK_PREVIEWS, R.string.InuDisableLinkPreviews, R.string.InuDisableLinkPreviewsInfo)
        }
        section(R.string.InuChatNavigation) {
            toggle(InuConfig.DISABLE_PULL_TO_NEXT, R.string.InuDisablePullToNext)
            toggle(InuConfig.CHAT_ALWAYS_SHOW_DOWN, R.string.InuChatAlwaysShowDown, R.string.InuChatAlwaysShowDownInfo)
            toggle(InuConfig.PINNED_JUMP_RETURN, R.string.InuPinnedJumpReturn, R.string.InuPinnedJumpReturnInfo)
            toggle(InuConfig.CHAT_TWO_FINGER_SELECT, R.string.InuChatTwoFingerSelect, R.string.InuChatTwoFingerSelectInfo)
            toggle(InuConfig.SELECTION_BOTTOM_NO_QUOTE, R.string.InuSelectionBottomNoQuote, R.string.InuSelectionBottomNoQuoteInfo)
            toggle(InuConfig.SHORTER_SWIPE_GESTURES, R.string.InuShorterSwipeGestures, R.string.InuShorterSwipeGesturesInfo)
            toggle(InuConfig.KEEP_CHATS_IN_STACK, R.string.InuKeepChatsInStack, R.string.InuKeepChatsInStackInfo)
            toggle(InuConfig.DISABLE_INTRO_STICKER, R.string.InuDisableIntroSticker, R.string.InuDisableIntroStickerInfo)
        }
        section(R.string.InuMentionsAndSearch) {
            custom(
                "mention-separator", R.string.InuMentionSeparator,
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuMentionSeparator), mentionSeparatorLabel(InuConfig.MENTION_SEPARATOR.value))
                },
                click = { page, view ->
                    RadioItemOptions.show(
                        page, view,
                        listOf(
                            LocaleController.getString(R.string.InuMentionSepComma),
                            LocaleController.getString(R.string.InuMentionSepPeriod),
                            LocaleController.getString(R.string.InuMentionSepColon),
                            LocaleController.getString(R.string.InuMentionSepSpace),
                            LocaleController.getString(R.string.InuMentionSepCustom),
                        ),
                        MENTION_SEPARATORS.indexOf(InuConfig.MENTION_SEPARATOR.value).let { if (it < 0) 4 else it },
                    ) { which ->
                        if (which == 4) {
                            showInputDialog(
                                page,
                                LocaleController.getString(R.string.InuMentionSeparator),
                                LocaleController.getString(R.string.InuMentionSepCustomHint),
                                InuConfig.MENTION_SEPARATOR.value.ifEmpty { null },
                            ) { text ->
                                InuConfig.MENTION_SEPARATOR.value = if (text.isEmpty() || text == " ") "" else text
                                page.refresh()
                                true
                            }
                        } else {
                            InuConfig.MENTION_SEPARATOR.value = MENTION_SEPARATORS[which]
                            page.refresh()
                        }
                    }
                },
            )
            toggle(
                InuConfig.MENTION_SEPARATOR_FOR_BOTS, R.string.InuMentionSeparatorBots, R.string.InuMentionSeparatorBotsInfo,
                visible = { InuConfig.MENTION_SEPARATOR.value.isNotEmpty() },
            )
            toggle(InuConfig.SEARCH_FROM_GLOBAL, R.string.InuSearchFromGlobal, R.string.InuSearchFromGlobalInfo)
            toggle(InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN, R.string.InuSendToDiscussWithoutJoin, R.string.InuSendToDiscussWithoutJoinInfo)
        }
        section(R.string.InuStickers) {
            toggle(InuConfig.SHOW_ALL_RECENT_STICKERS, R.string.InuShowAllRecentStickers)
            toggle(InuConfig.SUGGEST_CUSTOM_EMOJI_AFTER, R.string.InuSuggestCustomEmojiAfter, R.string.InuSuggestCustomEmojiAfterInfo)
            toggle(InuConfig.SORT_ALBUMS_BY_SIZE, R.string.InuSortAlbumsBySize, R.string.InuSortAlbumsBySizeInfo)
            toggle(
                InuConfig.USE_SYSTEM_EMOJI, R.string.InuUseSystemEmoji, R.string.InuUseSystemEmojiInfo,
                onChange = { _, value ->
                    SharedConfig.useSystemEmoji = value
                    MessagesController.getGlobalMainSettings().edit().putBoolean("useSystemEmoji", value).apply()
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.emojiLoaded)
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
                },
            )
        }
        section(R.string.CompactMode) {
            group("compact-mode-chat-types", R.string.CompactMode) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.CompactMode),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuCompactModeChannels, InuConfig.COMPACT_MODE_CHANNELS),
                        ExpandableBoolGroup.Option(R.string.InuCompactModeGroups, InuConfig.COMPACT_MODE_GROUPS),
                        ExpandableBoolGroup.Option(R.string.InuCompactModeDms, InuConfig.COMPACT_MODE_DMS),
                    ),
                    sectionId = id,
                ).apply { expanded = true }
            }
        }
        section(R.string.InuChatDecor) {
            toggle(InuConfig.CHAT_SNOWFLAKES, R.string.InuChatSnowflakes, onChange = { page, _ -> page.showRestartBulletin() })
            toggle(InuConfig.ACTION_BAR_SNOWFLAKES, R.string.InuActionBarSnowflakes, onChange = { page, _ -> page.showRestartBulletin() })
        }
        section(R.string.InuLinksAndBrowser) {
            toggle(InuConfig.CONFIRM_INTERNAL_LINKS, R.string.InuConfirmInternalLinks, R.string.InuConfirmInternalLinksInfo)
            toggle(InuConfig.SKIP_LINK_CONFIRM, R.string.InuSkipLinkConfirm, R.string.InuSkipLinkConfirmInfo)
            toggle(InuConfig.DISABLE_BROWSER_SWIPE_COLLAPSE, R.string.InuDisableBrowserSwipeCollapse)
            link(
                R.string.InuWebPreviewReplacements,
                badge = {
                    if (InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value) WebPreviewHelper.load().size.toString()
                    else null
                },
                value = {
                    if (InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value) null
                    else LocaleController.getString(R.string.SlowmodeOff)
                },
                slug = "web-preview-replacements",
            ) { WebPreviewReplacementsActivity() }
        }
        section(R.string.InuMapsHeader) {
            val providers = buildList {
                add(InuConfig.MapProviderItem.GOOGLE to R.string.InuMapProviderGoogle)
                if (MapsHelper.hasOsmdroid) add(InuConfig.MapProviderItem.OSM_LITE to R.string.InuMapProviderOsmLite)
            }
            choice(
                InuConfig.MAP_PROVIDER, R.string.InuMapProvider,
                providers.map { it.second },
                providers.map { it.first }.toIntArray(),
                visible = { providers.size > 1 },
            ) { ApplicationLoader.inu_resetMapsProvider() }
            choice(
                InuConfig.MAP_PREVIEW_PROVIDER, R.string.InuMapPreviewProvider,
                listOf(R.string.Default, R.string.InuMapPreviewProviderTelegram, R.string.InuMapPreviewProviderGoogle, R.string.Disable),
                intArrayOf(
                    InuConfig.MapPreviewProviderItem.DEFAULT,
                    InuConfig.MapPreviewProviderItem.TELEGRAM,
                    InuConfig.MapPreviewProviderItem.GOOGLE,
                    InuConfig.MapPreviewProviderItem.DISABLED,
                ),
            ) { page -> MapsHelper.syncMapProvider(page.messagesController) }
        }
        section(R.string.InuReactions) {
            link(R.string.InuPinnedReactions, R.drawable.inu_tabler_mood_smile, slug = "pinned-reactions") { PinnedReactionsActivity() }
            toggle(InuConfig.INSTANT_MARK_REACTIONS_READ, R.string.InuInstantMarkReactionsRead)
            toggle(InuConfig.HIDE_REACTIONS_DISPLAY, R.string.InuHideReactionsDisplay, R.string.InuHideReactionsDisplayInfo)
            toggle(InuConfig.HIDE_REACTIONS_SET, R.string.InuHideReactionsSet, R.string.InuHideReactionsSetInfo)
            toggle(InuConfig.HIDE_REACTIONS_BADGES, R.string.InuHideReactionsBadges, R.string.InuHideReactionsBadgesInfo)
            toggle(InuConfig.CONFIRM_REACTION_NON_MEMBER, R.string.InuConfirmReactionNonMember, R.string.InuConfirmReactionNonMemberInfo)
        }
        section(R.string.InuConfirmationsSection) {
            group("confirm-before-sending", R.string.InuConfirmBeforeSending) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuConfirmBeforeSending),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuConfirmSendSticker, InuConfig.CONFIRM_SEND_STICKER),
                        ExpandableBoolGroup.Option(R.string.InuConfirmSendGif, InuConfig.CONFIRM_SEND_GIF),
                    ),
                    sectionId = id,
                ).apply { expanded = true }
            }
        }
        section(R.string.InuSpoilers) {
            toggle(
                InuConfig.SHOW_SPOILERS_DIRECTLY, R.string.InuShowSpoilersDirectly, R.string.InuShowSpoilersDirectlyInfo,
                onChange = { page, _ ->
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
                    page.refresh()
                },
            )
            choice(
                InuConfig.TEXT_SPOILER_MODE, R.string.InuTextSpoilerMode,
                listOf(R.string.InuTextSpoilerModeDefault, R.string.InuTextSpoilerModeSimple, R.string.InuTextSpoilerModeEpstein),
                visible = { !InuConfig.SHOW_SPOILERS_DIRECTLY.value },
            )
            toggle(
                InuConfig.SPOILER_EXTEND_TO_LINE_END, R.string.InuSpoilerExtendToLineEnd, R.string.InuSpoilerExtendToLineEndInfo,
                visible = { !InuConfig.SHOW_SPOILERS_DIRECTLY.value },
            )
            choice(
                InuConfig.MEDIA_SPOILER_MODE, R.string.InuMediaSpoilerMode,
                listOf(R.string.InuMediaSpoilerModeTelegram, R.string.InuMediaSpoilerModePill, R.string.InuMediaSpoilerModeCircle),
                visible = { !InuConfig.SHOW_SPOILERS_DIRECTLY.value },
            )
            toggle(
                InuConfig.LINK_PREVIEW_SPOILER, R.string.InuLinkPreviewSpoiler, R.string.InuLinkPreviewSpoilerInfo,
                visible = { !InuConfig.SHOW_SPOILERS_DIRECTLY.value },
            )
        }
        section(R.string.InuBlockedMessagesSection) {
            choice(
                InuConfig.BLOCKED_MESSAGES_MODE, R.string.InuBlockedMessagesMode,
                listOf(R.string.InuBlockedMessagesModeOff, R.string.InuBlockedMessagesModeSpoiler, R.string.InuBlockedMessagesModeHide),
                onChange = { page -> page.refresh() },
            )
            custom(
                "blocked-messages-extra", R.string.InuBlockedMessagesExtra,
                visible = { BlockedMessagesHelper.isEnabled() },
                build = { page, id ->
                    UItem.asButton(
                        id,
                        R.drawable.inu_tabler_user_x,
                        LocaleController.getString(R.string.InuBlockedMessagesExtra),
                        LocaleController.formatString(R.string.InuBlockedMessagesExtraCount, BlockedMessagesHelper.getExtraHidden(page.currentAccount).size),
                    )
                },
                click = { page, _ -> openBlockedExtraPicker(page) },
            )
            toggle(
                InuConfig.BLOCKED_MESSAGES_HIDE_REPLIES, R.string.InuBlockedMessagesHideReplies, R.string.InuBlockedMessagesHideRepliesInfo,
                visible = { BlockedMessagesHelper.isEnabled() },
                onChange = { page, _ -> BlockedMessagesHelper.notifyChanged(page.currentAccount) },
            )
        }
        section(R.string.InuClownAvatarsSection) {
            toggle(InuConfig.CLOWN_AVATAR_BLOCKED, R.string.InuClownAvatarBlocked, R.string.InuClownAvatarBlockedInfo)
            toggle(InuConfig.CLOWN_AVATAR_BLOCKED_ME, R.string.InuClownAvatarBlockedMe, R.string.InuClownAvatarBlockedMeInfo)
            choice(
                InuConfig.CLOWN_AVATAR_EMOJI, R.string.InuClownAvatarEmoji,
                listOf(R.string.InuClownAvatarEmojiClown, R.string.InuClownAvatarEmojiPoop, R.string.InuClownAvatarEmojiSkull),
                onChange = { page -> page.refresh() },
            )
        }
        section(R.string.InuForwardAndReplies) {
            toggle(InuConfig.CHAT_REMEMBER_ALL_REPLIES, R.string.InuChatRememberAllReplies)
            toggle(InuConfig.FORWARD_PRO, R.string.InuForwardPro, R.string.InuForwardProInfo, experimental = true)
            toggle(InuConfig.FORWARD_PRO_REPLACE, R.string.InuForwardProReplace, R.string.InuForwardProReplaceInfo)
        }
        section(R.string.InuDoubleTapActions, R.string.InuDoubleTapInfo) {
            custom(
                "double-tap-incoming", R.string.InuIncomingMessages,
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuIncomingMessages), DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_INCOMING.value, DoubleTapContext.INCOMING).label())
                },
                click = { page, view -> showDoubleTapSelector(page, view, DoubleTapContext.INCOMING) },
            )
            custom(
                "double-tap-outgoing", R.string.InuOutgoingMessages,
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuOutgoingMessages), DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_OUTGOING.value, DoubleTapContext.OUTGOING).label())
                },
                click = { page, view -> showDoubleTapSelector(page, view, DoubleTapContext.OUTGOING) },
            )
            custom(
                "double-tap-channel", R.string.InuChannelMessages,
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuChannelMessages), channelActionLabel())
                },
                click = { page, view -> showDoubleTapSelector(page, view, DoubleTapContext.CHANNEL) },
            )
            slider(
                InuConfig.DOUBLE_TAP_DELAY, R.string.InuDelay, 75f, 300f,
                format = { "${it.toInt()} ms" },
                onChanged = { InuHooks.syncDoubleTapDelay() },
            )
        }
        section(R.string.InuSideButtonAction, R.string.InuSideButtonActionInfo) {
            custom(
                "side-button-action", R.string.InuSideButtonAction,
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuSideButtonAction), SideButtonAction.fromValue(InuConfig.SIDE_BUTTON_ACTION.value).label())
                },
                click = { page, view ->
                    val actions = SideButtonAction.entries
                    RadioItemOptions.show(
                        page, view,
                        actions.map { it.label() },
                        actions.indexOfFirst { it.value == InuConfig.SIDE_BUTTON_ACTION.value }.coerceAtLeast(0),
                    ) { which ->
                        InuConfig.SIDE_BUTTON_ACTION.value = actions.getOrNull(which)?.value ?: return@show
                        page.refresh()
                    }
                },
            )
        }
        section(R.string.InuChatActions) {
            group("delete-for-both", R.string.InuDeleteForBoth) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuDeleteForBoth),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuDeleteForBothMessages, InuConfig.DELETE_FOR_BOTH_MESSAGES),
                        ExpandableBoolGroup.Option(R.string.InuDeleteForBothDms, InuConfig.DELETE_FOR_BOTH_DMS),
                        ExpandableBoolGroup.Option(R.string.InuDeleteForBothGroups, InuConfig.DELETE_FOR_BOTH_GROUPS),
                    ),
                    sectionId = id,
                )
            }
            toggle(InuConfig.CHAT_EXPORT, R.string.InuChatExport, R.string.InuChatExportInfo, experimental = true)
            toggle(InuConfig.NOTIFY_BELL_IN_MENU, R.string.InuNotifyBellInMenu, R.string.InuNotifyBellInMenuInfo)
        }
        section(R.string.InuFormatting) {
            choice(
                InuConfig.CLOCK_FORMAT, R.string.InuClockFormat,
                listOf(R.string.InuClockFormatSystem, R.string.InuClockFormat12Hour, R.string.InuClockFormat24Hour),
            ) { page ->
                InuHooks.syncClockFormat()
                page.refresh()
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }
            toggle(InuConfig.SHOW_SECONDS, R.string.InuShowSeconds)
            toggle(InuConfig.DISABLE_ROUNDING, R.string.InuDisableRounding, R.string.InuDisableRoundingInfo)
        }
        section(R.string.InuMenus) {
            link(R.string.InuChatMenuOrder, R.drawable.inu_tabler_arrows_sort, slug = "chat-menu-order") { ChatMenuOrderActivity() }
            link(R.string.InuMessageMenuOrder, R.drawable.inu_tabler_message_dots, slug = "message-menu-order") { MessageMenuOrderActivity() }
        }
    }

    val MEDIA = schema("media-calls", R.string.InuMediaAndCalls, R.drawable.msg_calls) {
        section {
            link(R.string.InuCameraSound, R.drawable.msg_camera, slug = "camera-sound-open") { CameraSoundSettingsActivity() }
        }
        section(R.string.InuMediaVideoGif) {
            toggle(InuConfig.GIF_SEEKBAR, R.string.InuGifSeekbar, R.string.InuGifSeekbarInfo)
            toggle(InuConfig.ALTERNATIVE_GIF_BEHAVIOR, R.string.InuAlternativeGifBehavior, R.string.InuAlternativeGifBehaviorInfo)
            toggle(InuConfig.PLAY_ORIGINAL_VIDEO, R.string.InuPlayOriginalVideo, R.string.InuPlayOriginalVideoInfo)
            toggle(
                InuConfig.PLAY_ORIGINAL_VIDEO_INLINE,
                R.string.InuPlayOriginalVideoInline, R.string.InuPlayOriginalVideoInlineInfo,
                visible = { InuConfig.PLAY_ORIGINAL_VIDEO.value },
            )
            toggle(InuConfig.SEND_MP4_DOCUMENT_AS_VIDEO, R.string.InuSendMp4DocumentAsVideo, R.string.InuSendMp4DocumentAsVideoInfo)
            toggle(InuConfig.DISABLE_MOTION_PHOTOS, R.string.InuDisableMotionPhotos, R.string.InuDisableMotionPhotosInfo)
            toggle(InuConfig.DISABLE_VOLUME_PLAY_VIDEO, R.string.InuDisableVolumePlayVideo, R.string.InuDisableVolumePlayVideoInfo)
            toggle(
                InuConfig.HDR_IMAGES, R.string.InuHdrImages, R.string.InuHdrImagesInfo,
                visible = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            )
        }
        section(R.string.InuMediaVoice) {
            toggle(InuConfig.QUIET_VOICE_WHEN_LOCKED, R.string.InuQuietVoiceWhenLocked, R.string.InuQuietVoiceWhenLockedInfo)
            toggle(InuConfig.DISABLE_PROFILE_MUSIC_AUTOPLAY, R.string.InuDisableProfileMusicAutoplay, R.string.InuDisableProfileMusicAutoplayInfo)
            toggle(InuConfig.DISABLE_NEXT_AUDIO_PRELOAD, R.string.InuDisableNextAudioPreload, R.string.InuDisableNextAudioPreloadInfo)
            toggle(InuConfig.DISABLE_NEXT_MUSIC_AUTOPLAY, R.string.InuDisableNextMusicAutoplay, R.string.InuDisableNextMusicAutoplayInfo)
            toggle(InuConfig.DISABLE_NEXT_VOICE_AUTOPLAY, R.string.InuDisableNextVoiceAutoplay, R.string.InuDisableNextVoiceAutoplayInfo)
            toggle(InuConfig.VOICE_SKIP_IN_SESSION, R.string.InuVoiceSkipInSession, R.string.InuVoiceSkipInSessionInfo)
            toggle(InuConfig.REMEMBER_SHORT_POSITION, R.string.InuRememberShortPosition, R.string.InuRememberShortPositionInfo)
        }
        section(R.string.InuMediaSaving) {
            toggle(InuConfig.SAVE_SELECTED_FILES, R.string.InuSaveSelectedFiles, R.string.InuSaveSelectedFilesInfo)
            toggle(InuConfig.DISABLE_QUICK_SHARE, R.string.InuDisableQuickShare, R.string.InuDisableQuickShareInfo)
        }
        section(R.string.InuCallsSection) {
            toggle(InuConfig.CALL_CONFIRMATION, R.string.InuCallConfirmation)
            toggle(InuConfig.HD_BLUETOOTH_CALL_AUDIO, R.string.InuHdBluetoothCallAudio, R.string.InuHdBluetoothCallAudioInfo)
            toggle(InuConfig.CALL_ROTATION, R.string.InuCallRotation, R.string.InuCallRotationInfo)
            toggle(InuConfig.DISABLE_CALL_PROXIMITY, R.string.InuDisableCallProximity, R.string.InuDisableCallProximityInfo)
        }
    }

    val PROFILE = schema("profile-accounts", R.string.InuProfileAndAccounts, R.drawable.msg_contacts) {
        section(R.string.InuUserProfile) {
            toggle(InuConfig.PROFILE_PHOTO_GRADIENT_FADE, R.string.InuProfilePhotoGradientFade)
            toggle(InuConfig.REDUCE_PROFILE_MOTION, R.string.InuReduceProfileMotion)
            toggle(InuConfig.DISABLE_PROFILE_SCROLL_SNAP, R.string.InuDisableProfileScrollSnap)
            toggle(InuConfig.PROFILE_PREFER_MEDIA_TAB, R.string.InuProfilePreferMediaTab)
            toggle(InuConfig.SHOW_PROFILE_GIFT_BUTTON, R.string.InuShowProfileGiftButton)
            choice(
                InuConfig.PROFILE_ID_MODE, R.string.InuProfileIdMode,
                listOf(R.string.InuProfileIdModeOff, R.string.InuProfileIdModeTelegram, R.string.InuProfileIdModeBotApi),
            )
            toggle(InuConfig.SHOW_PROFILE_REG_DATE, R.string.InuShowProfileRegDate)
            toggle(InuConfig.DISABLE_CHAT_TITLE_PHONE, R.string.InuDisableChatTitlePhone)
            toggle(InuConfig.OPEN_BY_USER_ID, R.string.InuOpenByUserId, R.string.InuOpenByUserIdInfo)
            link(
                R.string.InuLocalNames,
                badge = {
                    if (InuConfig.LOCAL_NAMES.value) LocalNameHelper.count(UserConfig.selectedAccount).toString()
                    else null
                },
                value = {
                    if (InuConfig.LOCAL_NAMES.value) null
                    else LocaleController.getString(R.string.PasswordOff)
                },
                slug = "local-names",
            ) { LocalNamesSettingsActivity() }
        }
        section(R.string.InuAccountsSection) {
            toggle(InuConfig.SETTINGS_SHOW_ACCOUNTS, R.string.InuSettingsShowAccounts)
            toggle(
                InuConfig.ACCOUNT_SWITCH_SHORTCUT, R.string.InuAccountSwitchShortcut, R.string.InuAccountSwitchShortcutInfo,
                visible = { UserConfig.getActivatedAccountsCount() > 1 },
                onChange = { page, _ -> page.parentActivity?.let { ShortcutHelper.sync(it) } },
            )
        }
        section(R.string.InuMenus) {
            link(R.string.InuProfileSettingsRowsOrder, R.drawable.inu_tabler_arrows_sort, slug = "profile-settings-rows-order") { ProfileSettingsMenuOrderActivity() }
            link(R.string.InuProfileInfoRowsOrder, R.drawable.inu_tabler_arrows_sort, slug = "profile-info-rows-order") { ProfileInfoMenuOrderActivity() }
        }
    }

    val PRIVACY = schema("privacy-freedom", R.string.InuPrivacyAndFreedom, R.drawable.inu_tabler_shield_check) {
        section {
            link(R.string.InuPerAccountPasscode, R.drawable.inu_tabler_key, visible = { !PasscodeHelper.isSettingsHidden() }) { PasscodeSettingsActivity() }
            link(R.string.InuParanoiaMode, R.drawable.inu_tabler_spy, visible = { !ParanoiaHelper.isParanoia() }) { ParanoiaActivity() }
        }
        section(R.string.PrivacyTitle) {
            toggle(InuConfig.HIDE_MY_PHONE_NUMBER, R.string.InuHideMyPhoneNumber)
            toggle(InuConfig.NO_JOINED_NOTIFICATIONS, R.string.InuNoJoinedNotifications, R.string.InuNoJoinedNotificationsInfo)
            toggle(InuConfig.DISABLE_DRAFT_UPLOAD, R.string.InuDisableDraftUpload, R.string.InuDisableDraftUploadInfo)
            toggle(InuConfig.DISABLE_SENSITIVE, R.string.InuDisableSensitive, R.string.InuDisableSensitiveInfo)
            toggle(
                InuConfig.DONT_AUTOPLAY_WIDGETS, R.string.InuDontAutoplayWidgets, R.string.InuDontAutoplayWidgetsInfo,
                onChange = { _, new -> if (new) WidgetMedia.inu_onAutoplayDisabled() },
            )
            toggle(InuConfig.FORCE_RELAY_CALLS, R.string.InuForceRelayCalls, R.string.InuForceRelayCallsInfo)
        }
        section(R.string.InuStripTrackingParams) {
            toggle(InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN, R.string.InuStripTrackingParamsOnOpen, onChange = { page, _ -> page.refresh() })
            toggle(InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE, R.string.InuStripTrackingParamsOnPaste, onChange = { page, _ -> page.refresh() })
            toggle(
                InuConfig.RESOLVE_TRACKABLE_REDIRECTS, R.string.InuResolveTrackableRedirects, R.string.InuResolveTrackableRedirectsInfo,
                visible = { InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value || InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value },
            )
            custom(
                "strip-tracking-params-source", R.string.InuStripTrackingParamsSourceRow,
                build = { page, id -> UItem.asCustom(id, trackingSourceRow(page, id)) },
            )
        }
        section(R.string.InuBiometricConfirm, visible = { BiometricHelper.isSupported() }) {
            toggle(InuConfig.BIOMETRIC_CONFIRM_DELETE_CHAT, R.string.InuBiometricConfirmDeleteChat, R.string.InuBiometricConfirmDeleteChatInfo)
            toggle(InuConfig.BIOMETRIC_CONFIRM_LOGOUT, R.string.InuBiometricConfirmLogout, R.string.InuBiometricConfirmLogoutInfo)
            toggle(InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL, R.string.InuBiometricAllowDeviceCredential, R.string.InuBiometricAllowDeviceCredentialInfo)
            toggle(
                InuConfig.BIOMETRIC_LOCK_ARCHIVE, R.string.InuBiometricLockArchive, R.string.InuBiometricLockArchiveInfo,
                onChange = { page, _ -> page.refresh() },
            )
            toggle(
                InuConfig.BIOMETRIC_LOCK_ARCHIVE_EVERY_TIME, R.string.InuBiometricLockArchiveEveryTime, R.string.InuBiometricLockArchiveEveryTimeInfo,
                visible = { InuConfig.BIOMETRIC_LOCK_ARCHIVE.value },
            )
        }
        section(0, R.string.InuBiometricExplicitConfirmationInfo, visible = { BiometricHelper.isSupported() && BiometricHelper.hasPassiveBiometricSensor() }) {
            group("biometric-require-confirmation", R.string.InuBiometricExplicitConfirmation) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuBiometricExplicitConfirmation),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuBiometricRequireConfirmationPasscode, InuConfig.BIOMETRIC_REQUIRE_CONFIRMATION_PASSCODE),
                        ExpandableBoolGroup.Option(R.string.InuBiometricRequireConfirmationBots, InuConfig.BIOMETRIC_REQUIRE_CONFIRMATION_BOTS),
                        ExpandableBoolGroup.Option(R.string.InuBiometricRequireConfirmationActions, InuConfig.BIOMETRIC_REQUIRE_CONFIRMATION_ACTIONS),
                    ),
                    sectionId = id,
                )
            }
        }
    }

    val RESTRICTED = schema("restricted-features", R.string.InuRestrictedFeatures, R.drawable.inu_ghost_filled) {
        section {
            link(R.string.InuGhostMode, R.drawable.inu_ghost_filled) { GhostModeSettingsActivity() }
            link(R.string.InuAntiDeletion, R.drawable.inu_tabler_trash_off) { AntiDeletionSettingsActivity() }
            link(R.string.InuRegexFilter, R.drawable.inu_tabler_filter) { RegexFilterSettingsActivity() }
            link(R.string.InuDeviceSpoof, R.drawable.inu_tabler_device_mobile) { DeviceSpoofSettingsActivity() }
            link(R.string.InuTypingSpoof, R.drawable.inu_tabler_keyboard) { TypingSpoofQuickListSettingsActivity() }
        }
        section(R.string.InuContentProtectionBypass) {
            toggle(InuConfig.SAVE_ANY_STORY, R.string.InuSaveAnyStory, R.string.InuSaveAnyStoryInfo)
            toggle(InuConfig.AUTO_SAVE_STORIES, R.string.InuAutoSaveStories, R.string.InuAutoSaveStoriesInfo)
            toggle(
                InuConfig.ALLOW_FORWARD_RESTRICTED, R.string.InuAllowForwardRestricted, R.string.InuAllowForwardRestrictedInfo,
                onChange = { page, on ->
                    val ctx = page.parentActivity
                    if (on && ctx != null) {
                        page.showDialog(
                            AlertDialog.Builder(ctx, page.resourceProvider)
                                .setTitle(LocaleController.getString(R.string.InuAllowForwardRestricted))
                                .setMessage(LocaleController.getString(R.string.InuAllowForwardRestrictedWarning))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                                .create()
                        )
                    }
                },
            )
            toggle(InuConfig.ALLOW_COPY_RESTRICTED, R.string.InuAllowCopyRestricted, R.string.InuAllowCopyRestrictedInfo)
            toggle(InuConfig.ALLOW_SCREENSHOTS, R.string.InuDisableFlagSecure, R.string.InuDisableFlagSecureInfo)
            toggle(InuConfig.SUPPRESS_SCREENSHOT_NOTIFICATION, R.string.InuSuppressScreenshotNotification, R.string.InuSuppressScreenshotNotificationInfo)
            toggle(InuConfig.BYPASS_GIF_RESTRICTIONS, R.string.InuBypassGifRestrictions, R.string.InuBypassGifRestrictionsInfo)
        }
        section(R.string.InuStalkerSaved) {
            toggle(InuConfig.SAVE_USER_INFO, R.string.InuSaveUserInfo, R.string.InuSaveUserInfoInfo, experimental = true)
            toggle(InuConfig.SAVE_READ_TIME, R.string.InuSaveReadTime, R.string.InuSaveReadTimeInfo, experimental = true)
            toggle(InuConfig.SAVE_LAST_SEEN, R.string.InuSaveLastSeen, R.string.InuSaveLastSeenInfo, experimental = true)
        }
        section(R.string.InuStalkerPresence) {
            link(R.string.InuPresenceWatchList, R.drawable.inu_tabler_user_search) { PresenceWatchListSettingsActivity() }
            toggle(InuConfig.PRESENCE_LOGGER_NOTIFY, R.string.InuPresenceLoggerNotify, R.string.InuPresenceLoggerNotifyInfo)
        }
        section(R.string.InuPremiumUnlock) {
            toggle(
                InuConfig.LOCAL_PREMIUM, R.string.InuLocalPremium, R.string.InuLocalPremiumInfo,
                onChange = { page, on ->
                    val account = page.currentAccount
                    val userConfig = UserConfig.getInstance(account)
                    if (on) {
                        LocalPremiumHelper.applyToSelfUser(userConfig.getCurrentUser(), account)
                    } else {
                        LocalPremiumHelper.clearSelfUser(userConfig.getCurrentUser())
                    }
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged)
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.premiumStatusChangedGlobal)
                },
            )
            toggle(InuConfig.LOCAL_CUSTOM_EMOJI, R.string.InuLocalCustomEmoji, R.string.InuLocalCustomEmojiInfo)
        }
        section(R.string.InuUnlimitedLimits) {
            toggle(
                InuConfig.UNLIMITED_PINNED_CHATS, R.string.InuUnlimitedPinnedChats, R.string.InuUnlimitedPinnedChatsInfo,
                onChange = { page, _ -> page.refresh() },
            )
            slider(
                InuConfig.UNLIMITED_PINNED_CHATS_COUNT, R.string.InuUnlimitedPinnedChatsCount, 5f, 500f,
                visible = { InuConfig.UNLIMITED_PINNED_CHATS.value },
            )
            toggle(InuConfig.UNLIMITED_FAVORITE_STICKERS, R.string.InuUnlimitedFavoriteStickers, R.string.InuUnlimitedFavoriteStickersInfo)
            toggle(InuConfig.UNLIMITED_FOLDER_CHATS, R.string.InuUnlimitedFolderChats, R.string.InuUnlimitedFolderChatsInfo)
            toggle(InuConfig.LOCAL_FOLDERS, R.string.InuLocalFolders, R.string.InuLocalFoldersInfo)
        }
        section(R.string.InuAdBlockSection) {
            toggle(InuConfig.HIDE_SPONSORED_MESSAGES, R.string.InuHideSponsoredMessages, R.string.InuHideSponsoredMessagesInfo)
            toggle(InuConfig.HIDE_PROXY_SPONSOR_CHAT, R.string.InuHideProxySponsorChat, R.string.InuHideProxySponsorChatInfo)
            toggle(InuConfig.HIDE_BUSINESS_BOT_BANNER, R.string.InuHideBusinessBotBanner, R.string.InuHideBusinessBotBannerInfo)
        }
    }

    val DIALOGS = schema("dialogs", R.string.InuChatList, R.drawable.msg_viewchats) {
        section(R.string.InuFolders) {
            custom(
                "folders-preview", R.string.InuFolders,
                build = { page, id -> DialogsSettingsHelper.filterPreviewItem(page, id) },
            )
            custom(
                "folders-display-mode", R.string.InuShowOnTabs,
                build = { _, id -> UItem.asButton(id, LocaleController.getString(R.string.InuShowOnTabs), DialogsSettingsHelper.foldersDisplayLabel()) },
                click = { page, view -> DialogsSettingsHelper.showFoldersDisplayMode(page, view) },
            )
            toggle(
                InuConfig.TAB_INDICATOR_STROKE, R.string.InuTabIndicatorStroke,
                onChange = { page, _ ->
                    DialogsSettingsHelper.filterPreview(page)?.refreshVisuals()
                    page.notifyAllAccounts(NotificationCenter.dialogFiltersUpdated)
                },
            )
            choice(
                InuConfig.FOLDERS_UNREAD_COUNTER_MODE, R.string.InuFoldersUnreadCounter,
                listOf(
                    R.string.InuFoldersUnreadCounterHide,
                    R.string.InuFoldersUnreadCounterRegular,
                    R.string.InuFoldersUnreadCounterExcludeMuted,
                    R.string.InuFoldersUnreadCounterExcludeMutedNonDms,
                ),
                onChange = { page ->
                    DialogsSettingsHelper.filterPreview(page)?.refreshCounters()
                    DialogsSettingsHelper.filterPreview(page)?.refresh()
                    DialogsSettingsHelper.resetUnreadCounters()
                    page.rebuildAll()
                },
            )
            toggle(
                InuConfig.HIDE_ALL_CHATS_TAB, R.string.InuHideAllChatsTab, R.string.InuHideAllChatsTabInfo,
                onChange = { page, enabled ->
                    DialogsSettingsHelper.filterPreview(page)?.refresh()
                    page.notifyAllAccounts(NotificationCenter.dialogFiltersUpdated)
                    if (enabled && !FolderHelper.shouldSkipDefaultTab(MessagesController.getInstance(UserConfig.selectedAccount).dialogFilters.size)) {
                        BulletinFactory.of(page)
                            .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.InuHideAllChatsTabKept))
                            .show()
                    }
                },
            )
            toggle(
                InuConfig.FOLDER_TABS_AT_BOTTOM, R.string.InuFolderTabsAtBottom,
                visible = { FolderHelper.canShowTabsAtBottom() },
                onChange = { page, _ -> page.rebuildAll() },
            )
            toggle(InuConfig.REMEMBER_SELECTED_FOLDER, R.string.InuRememberSelectedFolder, R.string.InuRememberSelectedFolderInfo)
            toggle(InuConfig.FOLDERS_INFINITE_SWIPE, R.string.InuFoldersInfiniteSwipe, R.string.InuFoldersInfiniteSwipeInfo)
            toggle(InuConfig.ADD_TO_FOLDER_IN_FOLDER, R.string.InuAddToFolderInFolder, R.string.InuAddToFolderInFolderInfo)
            toggle(InuConfig.HIDE_ARCHIVE_FROM_CHAT_LIST, R.string.InuHideArchive, onChange = { page, _ -> page.rebuildAll() })
            toggle(InuConfig.UNREAD_CHATS_FIRST, R.string.InuUnreadChatsFirst, onChange = { _, _ -> UnreadFirstHelper.refresh() })
            toggle(InuConfig.UNREAD_CHATS_FIRST_PINNED, R.string.InuUnreadChatsFirstPinned, onChange = { _, _ -> UnreadFirstHelper.refresh() })
        }
        section(R.string.InuChatList) {
            custom(
                "dialogs-title-text", R.string.InuTitleText,
                build = { _, id -> UItem.asButton(id, LocaleController.getString(R.string.InuTitleText), DialogsSettingsHelper.titleTextLabel(InuConfig.DIALOGS_TITLE_TEXT.value)) },
                click = { page, view -> DialogsSettingsHelper.showTitleText(page, view) },
            )
            toggle(InuConfig.DIALOGS_TITLE_TEXT_OVERRIDE_ARCHIVE, R.string.InuTitleTextOverrideArchive, R.string.InuTitleTextOverrideArchiveInfo)
            toggle(InuConfig.OLD_MENTION_INDICATOR, R.string.InuOldMentionIndicator, onChange = { page, _ -> page.rebuildAll() })
            custom(
                "pull-down-action", R.string.InuPullDownAction,
                build = { _, id -> UItem.asButton(id, LocaleController.getString(R.string.InuPullDownAction), DialogsSettingsHelper.pullDownActionLabel(InuConfig.PULL_DOWN_ACTION.value)) },
                click = { page, _ -> DialogsSettingsHelper.showPullDownAction(page) },
            )
            toggle(InuConfig.DISABLE_SWIPE_TO_UNARCHIVE, R.string.InuDisableSwipeToUnarchive)
            toggle(InuConfig.DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC, R.string.InuDisableSwipeToHideGeneralTopic)
            toggle(InuConfig.HIDE_BOT_WEBVIEW_DIALOGS, R.string.InuHideBotWebView, onChange = { page, _ -> page.rebuildAll() })
            toggle(InuConfig.INTERACTIVE_CHAT_PREVIEW, R.string.InuDisableChatPreviewExpand, R.string.InuDisableChatPreviewExpandInfo)
            toggle(InuConfig.CHAT_PREVIEW_BUTTONS, R.string.InuChatPreviewButtons, R.string.InuChatPreviewButtonsInfo)
            custom(
                "community-display-mode", R.string.InuCommunityDisplayMode,
                build = { _, id ->
                    UItem.asButton(
                        id,
                        R.drawable.inu_tabler_users_group,
                        LocaleController.getString(R.string.InuCommunityDisplayMode),
                        DialogsSettingsHelper.communityDisplayModeLabel(InuConfig.COMMUNITY_DISPLAY_MODE.value),
                    )
                },
                click = { page, _ -> DialogsSettingsHelper.showCommunityDisplayMode(page) },
            )
            toggle(InuConfig.LINKED_CHATS, R.string.InuLinkedChats, R.string.InuLinkedChatsInfo, experimental = true, onChange = { page, _ -> page.rebuildAll() })
            toggle(InuConfig.DIALOG_AVATAR_OPENS_PROFILE, R.string.InuDialogAvatarOpensProfile, R.string.InuDialogAvatarOpensProfileInfo)
            custom(
                "always-show-downloads", R.string.InuAlwaysShowDownloads,
                build = { _, id ->
                    val mode = if (InuConfig.HIDE_DOWNLOADS_BUTTON.value) 2 else if (InuConfig.ALWAYS_SHOW_DOWNLOADS.value) 1 else 0
                    val labels = listOf(R.string.InuMonetThemeAuto, R.string.InuAlwaysShowDownloadsInfo, R.string.InuHideDownloadsButtonInfo)
                    UItem.asButton(id, R.drawable.inu_download_arrow, LocaleController.getString(R.string.InuAlwaysShowDownloads), LocaleController.getString(labels[mode]))
                },
                click = { page, view ->
                    val mode = if (InuConfig.HIDE_DOWNLOADS_BUTTON.value) 2 else if (InuConfig.ALWAYS_SHOW_DOWNLOADS.value) 1 else 0
                    RadioItemOptions.show(page, view, listOf(R.string.InuMonetThemeAuto, R.string.InuAlwaysShowDownloadsInfo, R.string.InuHideDownloadsButtonInfo).map { LocaleController.getString(it) }, mode) { which ->
                        InuConfig.ALWAYS_SHOW_DOWNLOADS.value = which == 1
                        InuConfig.HIDE_DOWNLOADS_BUTTON.value = which == 2
                        page.refresh()
                    }
                },
            )
            toggle(
                InuConfig.HIDE_DIALOG_PREVIEW, R.string.InuHideDialogPreview, R.string.InuHideDialogPreviewInfo,
                onChange = { _, _ -> DialogPreviewHelper.refresh() },
            )
        }
        section(R.string.InuBottomTabs) {
            toggle(
                InuConfig.BOTTOM_TABS_HIDE, R.string.InuBottomTabsHide, R.string.InuBottomTabsHideInfo,
                onChange = { page, _ ->
                    page.refresh()
                    page.rebuildAll()
                },
            )
            toggle(
                InuConfig.BOTTOM_TABS_COMPACT_MODE, R.string.InuCompactMode, R.string.InuCompactModeInfo,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value },
                onChange = { page, _ ->
                    page.refresh()
                    page.rebuildAll()
                    DialogsSettingsHelper.refreshMainTabsPreview(page)
                    page.showRestart()
                },
            )
            slider(
                InuConfig.BOTTOM_TABS_LIFT, R.string.InuBottomTabsLift, 0f, 48f,
                format = { "${it.toInt()} dp" },
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value && InuConfig.BOTTOM_TABS_COMPACT_MODE.value && !MainTabsHelper.isMaterial },
                onReleased = { page -> page.showRestart() },
            )
            toggle(
                InuConfig.BOTTOM_TABS_SHOW_TITLES, R.string.InuShowTabTitles,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value && !InuConfig.BOTTOM_TABS_COMPACT_MODE.value },
                onChange = { page, _ ->
                    DialogsSettingsHelper.refreshMainTabsPreview(page)
                    page.showRestart()
                },
            )
            custom(
                "bottom-tabs-preview", R.string.InuBottomTabs,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value },
                build = { page, id -> DialogsSettingsHelper.mainTabsPreviewItem(page, id) },
            )
            toggle(
                InuConfig.BOTTOM_TABS_SEARCH_SEPARATE, R.string.InuBottomTabsSeparateSearch, R.string.InuBottomTabsSeparateSearchInfo,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value && DialogsSettingsHelper.searchTabEnabled() },
                onChange = { page, _ ->
                    DialogsSettingsHelper.refreshMainTabsPreview(page)
                    page.showRestart()
                },
            )
            toggle(
                InuConfig.BOTTOM_TABS_HIDE_ON_SCROLL, R.string.InuBottomTabsHideOnScroll, R.string.InuBottomTabsHideOnScrollInfo,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value },
            )
            toggle(
                InuConfig.BOTTOM_TABS_DISABLE_PROFILE_SWIPE, R.string.InuBottomTabsDisableProfileSwipe, R.string.InuBottomTabsDisableProfileSwipeInfo,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value },
            )
            custom(
                "start-tab", R.string.InuStartTab,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value },
                build = { page, id -> UItem.asButton(id, LocaleController.getString(R.string.InuStartTab), DialogsSettingsHelper.startTabLabel(page)) },
                click = { page, _ -> DialogsSettingsHelper.showStartTab(page) },
            )
            custom(
                "bottom-tabs-reset", R.string.InuMainTabsCustomizeReset,
                visible = { !InuConfig.BOTTOM_TABS_HIDE.value },
                build = { _, id -> UItem.asButton(id, R.drawable.msg_reset, LocaleController.getString(R.string.InuMainTabsCustomizeReset)) },
                click = { page, _ -> DialogsSettingsHelper.resetMainTabs(page) },
            )
            toggle(
                InuConfig.BOTTOM_TABS_HIDE_TOP_SEARCH, R.string.InuBottomTabsHideTopSearch, R.string.InuBottomTabsHideTopSearchInfo,
                onChange = { page, _ -> page.showRestart() },
            )
        }
        section(R.string.InuDialogsFab) {
            toggle(InuConfig.DIALOGS_FAB_HIDE, R.string.InuDialogsFabHide, onChange = { page, _ -> page.rebuildAll() })
            custom(
                "fab-main-action", R.string.InuDialogsFabMainAction,
                build = { _, id ->
                    UItem.asButton(
                        id,
                        R.drawable.inu_tabler_circle_plus,
                        LocaleController.getString(R.string.InuDialogsFabMainAction),
                        DialogsFabHelper.Action.fromValue(InuConfig.DIALOGS_FAB_MAIN_ACTION.value).label(),
                    )
                },
                click = { page, view -> DialogsSettingsHelper.showFabAction(page, view, InuConfig.DIALOGS_FAB_MAIN_ACTION) },
            )
            custom(
                "fab-secondary-action", R.string.InuDialogsFabSecondaryAction,
                build = { _, id ->
                    UItem.asButton(
                        id,
                        LocaleController.getString(R.string.InuDialogsFabSecondaryAction),
                        DialogsFabHelper.Action.fromValue(InuConfig.DIALOGS_FAB_SECONDARY_ACTION.value).label(),
                    )
                },
                click = { page, view -> DialogsSettingsHelper.showFabAction(page, view, InuConfig.DIALOGS_FAB_SECONDARY_ACTION) },
            )
            toggle(InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL, R.string.InuDialogsFabHideOnScroll)
            toggle(
                InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR, R.string.InuDialogsFabOffsetForBottomBar, R.string.InuDialogsFabOffsetForBottomBarInfo,
                onChange = { page, _ -> page.rebuildAll() },
            )
            toggle(InuConfig.DIALOGS_FAB_LEFT_SIDE, R.string.InuDialogsFabLeftSide, onChange = { page, _ -> page.rebuildAll() })
            toggle(InuConfig.DIALOGS_FAB_RAISE, R.string.InuDialogsFabRaise, onChange = { page, _ -> page.showRestart() })
        }
    }

    val HOME = schema("home", R.string.InuHomeAndChatList, R.drawable.msg_home_solar) {
        section {
            link(R.string.InuChatList, R.drawable.msg_viewchats) { SchemaPageActivity(DIALOGS) }
            link(R.string.InuPillStack, R.drawable.inu_tabler_stack_2, slug = "pill-stack-open") { PillStackSettingsActivity() }
            link(R.string.InuBookmarks, R.drawable.msg_fave, slug = "bookmarks-open") { BookmarksManagerActivity() }
            link(R.string.InuFeed, R.drawable.msg_channel, slug = "feed-open") { FeedExcludedChannelsSettingsActivity() }
        }
        section(R.string.InuRecentChats, R.string.InuRecentChatsStyleInfo) {
            toggle(
                InuConfig.RECENT_CHATS_ENABLED, R.string.InuRecentChats,
                onChange = { page, on ->
                    if (on) RecentChatsHelper.showInMenus()
                    page.refresh()
                    RecentChatsHelper.notifyChanged()
                },
            )
            choice(
                InuConfig.RECENT_CHATS_STYLE, R.string.InuRecentChatsStyle,
                listOf(
                    R.string.InuRecentChatsStylePopup,
                    R.string.InuRecentChatsStyleSidebar,
                    R.string.InuRecentChatsStylePage,
                    R.string.InuRecentChatsStyleStrip,
                ),
                visible = { InuConfig.RECENT_CHATS_ENABLED.value },
                onChange = { RecentChatsHelper.notifyChanged() },
            )
            custom(
                "recent-chats-limit", R.string.InuRecentChatsLimit,
                visible = { InuConfig.RECENT_CHATS_ENABLED.value },
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuRecentChatsLimit), recentLimitLabel(InuConfig.RECENT_CHATS_LIMIT.value))
                },
                click = { page, view ->
                    RadioItemOptions.show(
                        page, view,
                        RECENT_LIMITS.map { recentLimitLabel(it) },
                        RECENT_LIMITS.indexOf(InuConfig.RECENT_CHATS_LIMIT.value).coerceAtLeast(0),
                    ) { which -> InuConfig.RECENT_CHATS_LIMIT.value = RECENT_LIMITS[which] }
                },
            )
            toggle(
                InuConfig.HIDE_RECENT_CHATS_SHORTCUTS, R.string.InuHideRecentChatsShortcuts, R.string.InuHideRecentChatsShortcutsInfo,
                visible = { InuConfig.RECENT_CHATS_ENABLED.value },
                onChange = { page, _ -> MediaDataController.getInstance(page.currentAccount).buildShortcuts() },
            )
            custom(
                "clear-recent-chats", R.string.InuClearRecentChats,
                visible = { InuConfig.RECENT_CHATS_ENABLED.value },
                build = { _, id ->
                    UItem.asButton(id, R.drawable.msg_delete, LocaleController.getString(R.string.InuClearRecentChats)).also { it.red = true }
                },
                click = { page, _ ->
                    val ctx = page.parentActivity
                    if (ctx != null) {
                        AlertDialog.Builder(ctx, page.resourceProvider)
                            .setTitle(LocaleController.getString(R.string.InuClearRecentChats))
                            .setMessage(LocaleController.getString(R.string.InuClearRecentChatsAlert))
                            .setPositiveButton(LocaleController.getString(R.string.ClearButton).uppercase()) { _, _ ->
                                RecentChatsHelper.clearRecentDialogs(page.currentAccount)
                            }
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .makeRed(AlertDialog.BUTTON_POSITIVE)
                            .show()
                    }
                },
            )
        }
        section(R.string.InuOnlineDotsSection) {
            toggle(InuConfig.SHOW_ONLINE_INDICATOR, R.string.InuShowOnlineIndicator, R.string.InuShowOnlineIndicatorInfo)
            toggle(
                InuConfig.PRESENCE_COLOR_DOTS, R.string.InuOnlineDots, R.string.InuOnlineDotsInfo,
                visible = { InuConfig.SHOW_ONLINE_INDICATOR.value },
            )
            toggle(
                InuConfig.CHAT_PRESENCE_DOTS, R.string.InuChatOnlineDots, R.string.InuChatOnlineDotsInfo,
                visible = { InuConfig.SHOW_ONLINE_INDICATOR.value && InuConfig.PRESENCE_COLOR_DOTS.value },
            )
        }
        section(R.string.InuMutualContactSection) {
            toggle(InuConfig.SHOW_MUTUAL_CONTACT_ICON, R.string.InuShowMutualContactIcon, R.string.InuShowMutualContactIconInfo)
            toggle(
                InuConfig.SHOW_MUTUAL_CONTACT_IN_CHATS, R.string.InuShowMutualContactInChats, R.string.InuShowMutualContactInChatsInfo,
                visible = { InuConfig.SHOW_MUTUAL_CONTACT_ICON.value },
            )
            toggle(
                InuConfig.MUTUAL_CONTACT_ICON_LEFT, R.string.InuMutualContactIconLeft, R.string.InuMutualContactIconLeftInfo,
                visible = { InuConfig.SHOW_MUTUAL_CONTACT_ICON.value },
            )
        }
        section(R.string.InuSideMenu) {
            toggle(
                InuConfig.NAVIGATION_DRAWER, R.string.InuNavigationDrawer, R.string.InuNavigationDrawerInfo,
                onChange = { page, _ ->
                    page.refresh()
                    page.showRestart()
                },
            )
            toggle(
                InuConfig.DRAWER_BACK_GESTURE, R.string.InuDrawerBackGesture, R.string.InuDrawerBackGestureInfo,
                visible = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && InuConfig.NAVIGATION_DRAWER.value },
                onChange = { page, _ -> page.showRestart() },
            )
            toggle(
                InuConfig.DRAWER_KEEP_OPTIONS_MENU, R.string.InuDrawerKeepOptionsMenu, R.string.InuDrawerKeepOptionsMenuInfo,
                visible = { InuConfig.NAVIGATION_DRAWER.value },
                onChange = { page, _ -> page.showRestart() },
            )
            toggle(
                InuConfig.DRAWER_M3_SECTIONS, R.string.InuDrawerM3Sections, R.string.InuDrawerM3SectionsInfo,
                visible = { InuConfig.NAVIGATION_DRAWER.value },
                onChange = { _, _ -> DrawerHelper.reloadItems() },
            )
            toggle(
                InuConfig.SHOW_DRAWER_ACCOUNTS, R.string.InuShowDrawerAccounts, R.string.InuShowDrawerAccountsInfo,
                visible = { InuConfig.NAVIGATION_DRAWER.value },
                onChange = { _, _ -> DrawerHelper.reloadItems() },
            )
            toggle(
                InuConfig.DRAWER_SHOW_BADGE, R.string.InuDrawerShowBadge, R.string.InuDrawerShowBadgeInfo,
                visible = { InuConfig.NAVIGATION_DRAWER.value },
                onChange = { _, _ -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged) },
            )
        }
        section(R.string.InuMenus) {
            link(R.string.InuSideMenu, R.drawable.inu_tabler_layout_sidebar) { DrawerMenuOrderActivity() }
            link(R.string.InuDialogsMenuOrder, R.drawable.inu_tabler_list_details, slug = "dialogs-menu-order") { DialogsMenuOrderActivity() }
        }
    }

    val SYSTEM = schema("system", R.string.InuRootSystem, R.drawable.inu_tabler_device_desktop_cog) {
        section {
            link(R.string.InuBackupSettings, R.drawable.inu_tabler_cloud) { BackupSettingsActivity() }
            toggle(InuConfig.SYSTEM_MESSAGE_FONT_SCALE, R.string.InuSystemMessageFontScale, R.string.InuSystemMessageFontScaleInfo)
            link(R.string.InuCacheManagement, R.drawable.inu_tabler_trash_x, slug = "additional-cache-management") { CacheManagementSettingsActivity() }
        }
        section(R.string.InuDeviceSection) {
            toggle(InuConfig.MASK_SERVER_APP_NAME, R.string.InuMaskServerAppName, R.string.InuMaskServerAppNameInfo)
            toggle(InuConfig.FORCE_NO_VIBRATION, R.string.InuForceNoVibration, R.string.InuForceNoVibrationInfo)
            toggle(InuConfig.MEMORY_TRIM, R.string.InuMemoryTrim, R.string.InuMemoryTrimInfo)
            toggle(
                InuConfig.LOCK_MAX_REFRESH_RATE, R.string.InuLockMaxRefreshRate, R.string.InuLockMaxRefreshRateInfo,
                onChange = { _, _ -> desu.inugram.helpers.RefreshRateHelper.sync(org.telegram.ui.LaunchActivity.instance) },
            )
            custom(
                "performance-class", R.string.InuPerformanceClass,
                build = { _, id ->
                    UItem.asButton(
                        id,
                        LocaleController.getString(R.string.InuPerformanceClass),
                        performanceClassLabel(SharedConfig.getDevicePerformanceClass()),
                    )
                },
                click = { page, _ -> showPerformanceClassSelector(page) },
            )
            custom(
                "text-classifier-mode", R.string.InuTextClassifierMode,
                visible = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P },
                build = { _, id ->
                    UItem.asButton(
                        id,
                        LocaleController.getString(R.string.InuTextClassifierMode),
                        textClassifierModeLabel(InuConfig.TEXT_CLASSIFIER_MODE.value),
                    )
                },
                click = { page, _ -> showTextClassifierModeSelector(page) },
            )
            custom(
                "selection-process-text", R.string.InuSelectionProcessText,
                build = { page, id ->
                    page.splitCheck(id, R.string.InuSelectionProcessText, R.string.InuSelectionProcessTextInfo, InuConfig.SELECTION_PROCESS_TEXT.value)
                },
                clickAt = { page, view, x ->
                    val isSwitch = if (LocaleController.isRTL) x < AndroidUtilities.dp(76f) else x > view.measuredWidth - AndroidUtilities.dp(76f)
                    if (isSwitch) {
                        val new = InuConfig.SELECTION_PROCESS_TEXT.toggle()
                        (view as? NotificationsCheckCell)?.isChecked = new
                    } else {
                        page.presentFragment(ProcessTextActivity())
                    }
                },
            )
        }
    }

    val NETWORK = schema("network-notifications", R.string.InuNetworkAndNotifications, R.drawable.inu_tabler_cloud) {
        section(R.string.InuPushHeader) {
            custom(
                "additional-unified-push", R.string.InuUnifiedPush,
                build = { page, id ->
                    page.twoLineCheck(id, R.string.InuUnifiedPush, R.string.InuUnifiedPushInfo, UnifiedPushHelper.isEnabled(), true)
                },
                click = { page, _ -> PushSettingsHelper.toggleUnifiedPush(page) },
            )
            custom(
                "additional-background-service", R.string.InuBackgroundService,
                build = { page, id ->
                    page.twoLineCheck(id, R.string.InuBackgroundService, R.string.InuBackgroundServiceInfo, InuConfig.FOREGROUND_PUSH_SERVICE.value, false)
                },
                click = { page, view -> PushSettingsHelper.toggleBackgroundService(page, view) },
            )
            custom(
                "background-service-channel", R.string.InuBackgroundServiceHideNotification,
                visible = { InuConfig.FOREGROUND_PUSH_SERVICE.value && Build.VERSION.SDK_INT >= 26 },
                build = { _, id ->
                    UItem.asButton(
                        id,
                        LocaleController.getString(R.string.InuBackgroundServiceHideNotification),
                        LocaleController.getString(R.string.InuBackgroundServiceHideNotificationInfo),
                    )
                },
                click = { page, _ -> PushSettingsHelper.openServiceChannelSettings(page) },
            )
            custom(
                "unified-push-distributor", R.string.InuUnifiedPushDistributor,
                visible = { UnifiedPushHelper.isEnabled() },
                build = { _, id ->
                    UItem.asButton(
                        id,
                        LocaleController.getString(R.string.InuUnifiedPushDistributor),
                        UnifiedPushHelper.currentDistributor()?.let(PushSettingsHelper::appLabel)
                            ?: LocaleController.getString(R.string.InuUnifiedPushNoDistributor),
                    )
                },
                click = { page, view -> PushSettingsHelper.pickDistributor(page, view) },
            )
            custom(
                "additional-unified-push-gateway", R.string.InuUnifiedPushGateway,
                visible = { UnifiedPushHelper.isEnabled() },
                build = { _, id ->
                    val gw = UnifiedPushHelper.getGateway()
                    val subtitle = when {
                        gw.isEmpty() -> LocaleController.getString(R.string.InuUnifiedPushGatewayDirect)
                        gw == UnifiedPushHelper.DEFAULT_GATEWAY -> "${LocaleController.getString(R.string.InuUnifiedPushGatewayDefault)} ($gw)"
                        else -> gw
                    }
                    UItem.asButton(id, LocaleController.getString(R.string.InuUnifiedPushGateway), subtitle)
                },
                click = { page, _ -> PushSettingsHelper.editGateway(page) },
            )

        }
        section(R.string.InuFasterDownloadsSection) {
            toggle(InuConfig.FASTER_DOWNLOADS, R.string.InuFasterDownloads)
            toggle(InuConfig.FASTER_UPLOADS, R.string.InuFasterUploads)
            toggle(InuConfig.DOWNLOAD_QUEUE, R.string.InuDownloadQueue, R.string.InuDownloadQueueInfo)
        }
        section(R.string.InuDownloadsAndNetwork) {
            toggle(
                InuConfig.AUTO_DISABLE_PROXY_ON_VPN, R.string.InuAutoDisableProxyOnVpn,
                onChange = { _, _ -> ProxyVpnHelper.reconcile() },
            )
            toggle(
                InuConfig.KEEP_DOWNLOADS_IN_BACKGROUND, R.string.InuKeepDownloadsInBackground, R.string.InuKeepDownloadsInBackgroundInfo,
                onChange = { page, _ -> page.refresh() },
            )
            toggle(
                InuConfig.BLOCK_SLEEP_WHILE_DOWNLOADING, R.string.InuBlockSleepWhileDownloading, R.string.InuBlockSleepWhileDownloadingInfo,
                visible = { InuConfig.KEEP_DOWNLOADS_IN_BACKGROUND.value },
                onChange = { _, on -> if (on) desu.inugram.helpers.media.DownloadKeepAliveHelper.startPolling() },
            )
            toggle(InuConfig.ALWAYS_SEND_VIDEOS_HD, R.string.InuAlwaysSendVideosHd, R.string.InuAlwaysSendVideosHdInfo)
            custom(
                "download-directory", R.string.InuDownloadDirectory,
                build = { _, id ->
                    UItem.asButton(id, LocaleController.getString(R.string.InuDownloadDirectory), InuConfig.DOWNLOAD_DIRECTORY.value)
                },
                click = { page, view ->
                    RadioItemOptions.show(
                        page, view, DOWNLOAD_DIRECTORIES,
                        DOWNLOAD_DIRECTORIES.indexOf(InuConfig.DOWNLOAD_DIRECTORY.value),
                    ) { which ->
                        InuConfig.DOWNLOAD_DIRECTORY.value = DOWNLOAD_DIRECTORIES[which]
                        org.telegram.messenger.ImageLoader.getInstance().checkMediaPaths()
                        BulletinFactory.of(page)
                            .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.InuDownloadDirectoryApplied))
                            .show()
                    }
                },
            )
            toggle(InuConfig.SAVE_FILENAME_DATE, R.string.InuSaveFilenameDate, R.string.InuSaveFilenameDateInfo)
        }
    }

    val ANNOYANCES = schema("annoyances", R.string.InuAnnoyances, R.drawable.inu_tabler_mood_annoyed) {
        section(R.string.InuAnnoyancesChats) {
            group("hide-bottom-bar", R.string.InuHideBottomBar) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuHideBottomBar),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuHideBottomBarJoined, InuConfig.HIDE_BOTTOM_BAR_JOINED),
                        ExpandableBoolGroup.Option(R.string.InuHideBottomBarNonJoinedGroups, InuConfig.HIDE_BOTTOM_BAR_NON_JOINED_GROUPS),
                        ExpandableBoolGroup.Option(R.string.InuHideBottomBarReplies, InuConfig.HIDE_BOTTOM_BAR_REPLIES),
                        ExpandableBoolGroup.Option(R.string.InuHideBottomBarPinned, InuConfig.HIDE_BOTTOM_BAR_PINNED),
                    ),
                    sectionId = id,
                )
            }
            toggle(InuConfig.HIDE_BOTTOM_BAR_NON_JOINED, R.string.InuHideJoinBar, R.string.InuHideJoinBarInfo)
            group("hide-bot-slash", R.string.InuHideBotSlash) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuHideBotSlash),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuHideBotSlashGroups, InuConfig.HIDE_BOT_SLASH_GROUPS),
                        ExpandableBoolGroup.Option(R.string.InuHideBotSlashBots, InuConfig.HIDE_BOT_SLASH_BOTS),
                    ),
                    sectionId = id,
                )
            }
            toggle(InuConfig.HIDE_BOT_WEBVIEW_INPUT, R.string.InuHideBotWebView)
            toggle(InuConfig.HIDE_SEND_AS_PICKER, R.string.InuHideSendAsPicker, R.string.InuHideSendAsPickerInfo)
            toggle(InuConfig.HIDE_CALL_ACTION_BUTTON, R.string.InuHideCallActionButton)
            toggle(InuConfig.HIDE_REACTIONS_ENTRY, R.string.InuHideReactionEntry, R.string.InuHideReactionEntryInfo)
        }
        section(R.string.InuCustomizeMessages) {
            toggle(InuConfig.HIDE_REPLY_BACKGROUND, R.string.InuHideReplyBackground, R.string.InuHideReplyBackgroundInfo)
            toggle(InuConfig.DISABLE_REPLY_CUSTOM_COLORS, R.string.InuDisableReplyCustomColors, R.string.InuDisableReplyCustomColorsInfo)
            toggle(InuConfig.HIDE_REPLY_BACKGROUND_EMOJI, R.string.InuHideReplyBackgroundEmoji, R.string.InuHideReplyBackgroundEmojiInfo)
        }
        section(R.string.InuAnnoyancesStories) {
            toggle(InuConfig.HIDE_STORIES, R.string.InuHideStories, R.string.InuHideStoriesInfo, onChange = { page, _ -> page.notifyAllAccounts(NotificationCenter.storiesUpdated) })
            toggle(InuConfig.HIDE_STORY_AVATARS, R.string.InuHideStoryAvatars, R.string.InuHideStoryAvatarsInfo, onChange = { page, _ -> page.notifyAllAccounts(NotificationCenter.storiesUpdated) })
            toggle(InuConfig.DISABLE_OVERSCROLL_STORY, R.string.InuDisableOverscrollStory, R.string.InuDisableOverscrollStoryInfo)
            toggle(InuConfig.HIDE_REPOST_TO_STORY, R.string.InuHideRepostToStory)
            toggle(InuConfig.HIDE_PROFILE_STORY_BUTTON, R.string.InuHideProfileStoryButton)
            toggle(InuConfig.HIDE_PROFILE_MENU_ARCHIVED_STORIES, R.string.InuHideProfileMenuArchivedStories)
        }
        section(R.string.InuAnnoyancesProfile) {
            toggle(InuConfig.HIDE_STARS_RATING, R.string.InuHideStarsRating)
            toggle(InuConfig.HIDE_VERIFICATION_BADGE, R.string.InuHideVerificationBadge)
            toggle(InuConfig.HIDE_PROFILE_COLORFUL_BACKGROUND, R.string.InuHideProfileColorfulBackground)
            toggle(InuConfig.HIDE_GIFTS_AROUND_AVATAR, R.string.InuHideGiftsAroundAvatar, R.string.InuHideGiftsAroundAvatarInfo)
            toggle(InuConfig.HIDE_PROFILE_ICONS, R.string.InuHideProfileIcons)
            toggle(InuConfig.HIDE_PROFILE_LIVE_ACTIONS_BUTTON, R.string.InuHideProfileLiveActionsButton)
        }
        section(R.string.InuAnnoyancesGifts) {
            toggle(InuConfig.HIDE_PROFILE_GIFTS_TAB, R.string.InuHideProfileGiftsTab, R.string.InuHideProfileGiftsTabInfo)
            toggle(InuConfig.HIDE_PROFILE_GIFT_BUTTON, R.string.InuHideProfileGiftButton)
            toggle(InuConfig.HIDE_PROFILE_MENU_SEND_GIFT, R.string.InuHideProfileMenuSendGift)
            toggle(InuConfig.HIDE_GIFT_BUTTON_INPUT, R.string.InuHideGiftButtonInput, R.string.InuHideGiftButtonInputInfo)
            toggle(InuConfig.HIDE_GIFT_CARDS_IN_CHAT, R.string.InuHideGiftCardsInChat, R.string.InuHideGiftCardsInChatInfo, onChange = { page, _ -> page.showRestart() })
            toggle(InuConfig.HIDE_GIFT_AUCTIONS_HINT, R.string.InuHideGiftAuctionsHint)
            toggle(InuConfig.HIDE_GIVEAWAYS, R.string.InuHideGiveaways, onChange = { page, _ -> page.showRestart() })
        }
        section(R.string.InuAnnoyancesPremium) {
            toggle(InuConfig.HIDE_PREMIUM_BADGE, R.string.InuHidePremiumBadge)
            toggle(InuConfig.HIDE_COLLECTIBLE_STATUS, R.string.InuHideCollectibleStatus, R.string.InuHideCollectibleStatusInfo)
            toggle(InuConfig.HIDE_ATTACH_PREMIUM_BADGES, R.string.InuHideAttachPremiumBadges)
            toggle(InuConfig.HIDE_EMOJI_PREMIUM_UPSELL, R.string.InuHideEmojiPremiumUpsell)
            toggle(InuConfig.HIDE_CAPTION_LIMIT_UPSELL, R.string.InuHideCaptionLimitUpsell, R.string.InuHideCaptionLimitUpsellInfo)
            toggle(InuConfig.HIDE_PAID_REACTION_UPSELL, R.string.InuHidePaidReactionUpsell)
        }
        section(R.string.InuAnnoyancesInterface) {
            toggle(InuConfig.HIDE_TRENDING_STICKERS, R.string.InuHideTrendingStickers)
            toggle(InuConfig.HIDE_GROUP_STICKER_PACK, R.string.InuHideGroupStickerPack)
            toggle(InuConfig.HIDE_HASHTAG_SUGGESTIONS, R.string.InuHideHashtagSuggestions)
            toggle(InuConfig.HIDE_RICH_EDITOR_BUTTON, R.string.InuHideRichEditorButton)
        }
        section(R.string.InuAnnoyancesChannels) {
            toggle(InuConfig.HIDE_SIMILAR_CHANNELS_TAB, R.string.InuHideSimilarChannelsTab)
            toggle(InuConfig.HIDE_CHANNEL_RECOMMENDATIONS, R.string.InuHideChannelRecommendations, onChange = { page, _ -> page.showRestart() })
            toggle(InuConfig.HIDE_CHANNEL_SHARE_BUTTON, R.string.InuHideChannelShareButton, R.string.InuHideChannelShareButtonInfo)
            toggle(InuConfig.HIDE_PSA_PROMO_CHAT, R.string.InuHidePsaPromoChat)
        }
        section(R.string.InuAnnoyancesAi) {
            group("hide-ai-features", R.string.InuHideAiFeatures) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuHideAiFeatures),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuHideMessageSummary, InuConfig.HIDE_MESSAGE_SUMMARY),
                        ExpandableBoolGroup.Option(R.string.InuHideIvSummary, InuConfig.HIDE_IV_SUMMARY),
                    ),
                    sectionId = id,
                )
            }
        }
        section(R.string.InuAnnoyancesHints) {
            group("hide-suggestions", R.string.InuHideSuggestions) { id ->
                ExpandableBoolGroup(
                    LocaleController.getString(R.string.InuHideSuggestions),
                    listOf(
                        ExpandableBoolGroup.Option(R.string.InuHideSuggestionBirthdaySetup, InuConfig.HIDE_SUGGESTION_BIRTHDAY_SETUP),
                        ExpandableBoolGroup.Option(R.string.InuHideSuggestionBirthdayContacts, InuConfig.HIDE_SUGGESTION_BIRTHDAY_CONTACTS),
                        ExpandableBoolGroup.Option(R.string.InuHideSuggestionPassword, InuConfig.HIDE_SUGGESTION_PASSWORD),
                        ExpandableBoolGroup.Option(R.string.InuHideSuggestionPhone, InuConfig.HIDE_SUGGESTION_PHONE),
                        ExpandableBoolGroup.Option(R.string.InuHideSuggestionPremium, InuConfig.HIDE_SUGGESTION_PREMIUM),
                        ExpandableBoolGroup.Option(R.string.InuHideSuggestionCustom, InuConfig.HIDE_SUGGESTION_CUSTOM),
                    ),
                    sectionId = id,
                )
            }
            toggle(InuConfig.HIDE_CACHE_HINT, R.string.InuHideCacheHint)
            toggle(InuConfig.DISABLE_CONTACTS_PERMISSION_NAG, R.string.InuDisableContactsPermissionNag, R.string.InuDisableContactsPermissionNagInfo)
            toggle(InuConfig.DISABLE_LOCKSCREEN_PERMISSION_NAG, R.string.InuDisableLockscreenPermissionNag, R.string.InuDisableLockscreenPermissionNagInfo)
            toggle(InuConfig.DISABLE_CALL_RATING, R.string.InuDisableCallRating, R.string.InuDisableCallRatingInfo)
            custom(
                "clear-hints", R.string.InuClearHints,
                build = { _, id -> UItem.asButton(id, LocaleController.getString(R.string.InuClearHints)) },
                click = { page, _ -> clearHints(page) },
            )
        }
    }

    private fun clearHints(page: SchemaPageActivity) {
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
        BulletinFactory.of(page)
            .createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.InuClearHintsDone)
            )
            .show()

    }

    private val MENTION_SEPARATORS = listOf(", ", ". ", ": ", "")

    private fun mentionSeparatorLabel(value: String): String = when (value) {
        ", " -> LocaleController.getString(R.string.InuMentionSepComma)
        ". " -> LocaleController.getString(R.string.InuMentionSepPeriod)
        ": " -> LocaleController.getString(R.string.InuMentionSepColon)
        "" -> LocaleController.getString(R.string.InuMentionSepSpace)
        else -> value.trim()
    }

    private fun openBlockedExtraPicker(page: SchemaPageActivity) {
        val args = Bundle().apply {
            putBoolean("isNeverShare", true)
            putInt("chatAddType", 2)
            putBoolean("inu_allowChannels", true)
        }
        val fragment = GroupCreateActivity(args)
        fragment.select(ArrayList(BlockedMessagesHelper.getExtraHidden(page.currentAccount)), false, false)
        fragment.setDelegate { _, _, ids ->
            BlockedMessagesHelper.setExtraHidden(page.currentAccount, ids)
            page.refresh()
        }
        page.presentFragment(fragment)
    }

    private fun showDoubleTapSelector(page: SchemaPageActivity, anchor: View, context: DoubleTapContext) {
        val actions = DoubleTapAction.available(context)
        val config = when (context) {
            DoubleTapContext.INCOMING -> InuConfig.DOUBLE_TAP_ACTION_INCOMING
            DoubleTapContext.OUTGOING -> InuConfig.DOUBLE_TAP_ACTION_OUTGOING
            DoubleTapContext.CHANNEL -> InuConfig.DOUBLE_TAP_ACTION_CHANNEL
        }
        val inheritable = context == DoubleTapContext.CHANNEL
        val offset = if (inheritable) 1 else 0
        val labels = ArrayList<CharSequence>()
        if (inheritable) labels.add(LocaleController.getString(R.string.InuSameAsIncoming))
        actions.mapTo(labels) { it.label() }
        val selected = if (inheritable && config.value == DoubleTapActionHelper.INHERIT_INCOMING) {
            0
        } else {
            actions.indexOfFirst { it.value == config.value }.coerceAtLeast(0) + offset
        }
        RadioItemOptions.show(page, anchor, labels, selected) { which ->
            config.value = if (inheritable && which == 0) {
                DoubleTapActionHelper.INHERIT_INCOMING
            } else {
                actions.getOrNull(which - offset)?.value ?: return@show
            }
        }
    }

    private fun channelActionLabel(): CharSequence {
        val value = InuConfig.DOUBLE_TAP_ACTION_CHANNEL.value
        if (value == DoubleTapActionHelper.INHERIT_INCOMING) return LocaleController.getString(R.string.InuSameAsIncoming)
        return DoubleTapAction.fromValue(value, DoubleTapContext.CHANNEL).label()
    }

    private fun trackingSourceRow(page: SchemaPageActivity, key: Int): TextDetailSettingsCell {
        val cell = page.remember(key) {
            TextDetailSettingsCell(page.context!!).also { c ->
                c.setBackground(
                    Theme.createSelectorWithBackgroundDrawable(
                        Theme.getColor(Theme.key_windowBackgroundWhite),
                        Theme.getColor(Theme.key_listSelector),
                    )
                )
                c.setOnClickListener { showStripTrackingParamsOptions(page, key, it) }
                c.setOnLongClickListener { showStripTrackingParamsOptions(page, key, it); true }
            }
        }
        cell.setTextAndValue(
            LocaleController.getString(R.string.InuStripTrackingParamsSourceRow),
            LocaleController.formatString(R.string.InuStripTrackingParamsSource, UrlCleanerHelper.lastUpdated ?: "?"),
            false,
        )
        return cell
    }

    private fun showStripTrackingParamsOptions(page: SchemaPageActivity, key: Int, anchor: View) {
        val scrim = (anchor.parent as? View) ?: anchor
        val opts = ItemOptions.makeOptions(page, scrim)
            .setLongPressSelectionEnabled(false)
            .add(R.drawable.msg_download, LocaleController.getString(R.string.InuStripTrackingParamsUpdate)) {
                fetchLatestStripTrackingParams(page)
            }
        if (UrlCleanerHelper.isUsingOverride) {
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuStripTrackingParamsRevert)) {
                UrlCleanerHelper.resetToBundled()
                Utilities.globalQueue.postRunnable {
                    UrlCleanerHelper.preload()
                    AndroidUtilities.runOnUIThread { page.refresh() }
                }
            }
        }
        opts.show()
    }

    private fun fetchLatestStripTrackingParams(page: SchemaPageActivity) {
        Utilities.globalQueue.postRunnable {
            val result = runCatching { UrlCleanerHelper.fetchLatest() }
            UrlCleanerHelper.preload()
            AndroidUtilities.runOnUIThread {
                page.refresh()
                val bulletin = BulletinFactory.of(page)
                result.fold(
                    onSuccess = { updated ->
                        val msg = if (updated) R.string.InuStripTrackingParamsUpdated else R.string.InuStripTrackingParamsAlreadyLatest
                        bulletin.createSimpleBulletin(R.raw.contact_check, LocaleController.getString(msg)).show()
                    },
                    onFailure = { err ->
                        bulletin.createSimpleBulletin(
                            R.raw.error,
                            LocaleController.getString(R.string.InuStripTrackingParamsUpdateFailed),
                            err.message ?: "",
                        ).show()
                    },
                )
            }
        }
    }

    private val RECENT_LIMITS = listOf(25, 100, 500, 0)

    private fun recentLimitLabel(limit: Int): String =
        if (limit <= 0) LocaleController.getString(R.string.InuRecentChatsLimitUnlimited) else limit.toString()

    private fun performanceClassLabel(value: Int): String = when (value) {
        SharedConfig.PERFORMANCE_CLASS_HIGH -> LocaleController.getString(R.string.InuPerformanceClassHigh)
        SharedConfig.PERFORMANCE_CLASS_AVERAGE -> LocaleController.getString(R.string.InuPerformanceClassAverage)
        else -> LocaleController.getString(R.string.InuPerformanceClassLow)
    }

    private fun textClassifierModeLabel(value: Int): String = when (value) {
        InuConfig.TextClassifierModeItem.NATIVE -> LocaleController.getString(R.string.InuTextClassifierModeNative)
        InuConfig.TextClassifierModeItem.OFF -> LocaleController.getString(R.string.InuTextClassifierModeOff)
        else -> LocaleController.getString(R.string.InuTextClassifierModeImproved)
    }

    private fun showPerformanceClassSelector(page: SchemaPageActivity) {
        val context = page.context ?: return
        val measuredClass = SharedConfig.measureDevicePerformanceClass()
        val values = intArrayOf(
            SharedConfig.PERFORMANCE_CLASS_HIGH,
            SharedConfig.PERFORMANCE_CLASS_AVERAGE,
            SharedConfig.PERFORMANCE_CLASS_LOW,
        )
        page.showDialog(
            RadioDialogBuilder(context, page.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuPerformanceClass))
                .setSubtitle(LocaleController.getString(R.string.InuPerformanceClassInfo))
                .setItems(
                    values.map {
                        RadioDialogBuilder.Item(
                            performanceClassLabel(it),
                            if (it == measuredClass) LocaleController.getString(R.string.InuPerformanceClassMeasured) else null,
                        )
                    },
                    values.indexOf(SharedConfig.getDevicePerformanceClass()),
                ) { _, which ->
                    val newClass = values[which]
                    SharedConfig.overrideDevicePerformanceClass(if (newClass == measuredClass) -1 else newClass)
                    page.refresh()
                }.create()
        )
    }

    private fun showTextClassifierModeSelector(page: SchemaPageActivity) {
        val context = page.context ?: return
        val values = intArrayOf(
            InuConfig.TextClassifierModeItem.NATIVE,
            InuConfig.TextClassifierModeItem.IMPROVED,
            InuConfig.TextClassifierModeItem.OFF,
        )
        val items = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTextClassifierModeNative)),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuTextClassifierModeImproved),
                LocaleController.getString(R.string.InuTextClassifierModeImprovedInfo),
            ),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuTextClassifierModeOff),
                LocaleController.getString(R.string.InuTextClassifierModeOffInfo),
            ),
        )
        page.showDialog(
            RadioDialogBuilder(context, page.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuTextClassifierMode))
                .setSubtitle(LocaleController.getString(R.string.InuTextClassifierModeInfo))
                .setItems(items, values.indexOf(InuConfig.TEXT_CLASSIFIER_MODE.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.TEXT_CLASSIFIER_MODE.value == newValue) return@setItems
                    InuConfig.TEXT_CLASSIFIER_MODE.value = newValue
                    page.refresh()
                    page.showRestart()
                }.create()
        )
    }
}
