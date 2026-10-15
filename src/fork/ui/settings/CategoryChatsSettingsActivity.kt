package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.messenger.BuildConfig

class CategoryChatsSettingsActivity : DialogsSettingsActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChats)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    private var chatInputMaxLinesSlider: SliderCell? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineEntry(
                BUTTON_MESSAGES,
                R.drawable.msg_discussion,
                LocaleController.getString(R.string.InuMessages),
                LocaleController.getString(R.string.InuMessagesSummary),
            )
        )
        items.add(
            mkTwoLineEntry(
                BUTTON_CAMERA_SOUND,
                R.drawable.msg_camera,
                LocaleController.getString(R.string.InuCameraSound),
                LocaleController.getString(R.string.InuCameraSoundSummary),
            )
        )
        items.add(UItem.asShadow(null))

        super.fillItems(items, adapter)

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuGeneral)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_KEYBOARD_ON_SCROLL,
                LocaleController.getString(R.string.InuHideKeyboardOnScroll),
            ).setChecked(InuConfig.HIDE_KEYBOARD_ON_SCROLL.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_KEEP_KEYBOARD_WHILE_TYPING,
                R.string.InuKeepKeyboardWhileTyping,
                R.string.InuKeepKeyboardWhileTypingInfo,
                InuConfig.KEEP_KEYBOARD_WHILE_TYPING.value,
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_PULL_TO_NEXT,
                LocaleController.getString(R.string.InuDisablePullToNext),
            ).setChecked(InuConfig.DISABLE_PULL_TO_NEXT.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_ALWAYS_SHOW_DOWN,
                R.string.InuChatAlwaysShowDown,
                R.string.InuChatAlwaysShowDownInfo,
                InuConfig.CHAT_ALWAYS_SHOW_DOWN.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_TWO_FINGER_SELECT,
                R.string.InuChatTwoFingerSelect,
                R.string.InuChatTwoFingerSelectInfo,
                InuConfig.CHAT_TWO_FINGER_SELECT.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SELECTION_BOTTOM_NO_QUOTE,
                R.string.InuSelectionBottomNoQuote,
                R.string.InuSelectionBottomNoQuoteInfo,
                InuConfig.SELECTION_BOTTOM_NO_QUOTE.value,
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_BOT_DRAFT_TOP,
                LocaleController.getString(R.string.InuDisableBotDraftTop),
            ).setChecked(InuConfig.DISABLE_BOT_DRAFT_TOP.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_MENTION_SEPARATOR,
                LocaleController.getString(R.string.InuMentionSeparator),
                mentionSeparatorLabel(InuConfig.MENTION_SEPARATOR.value),
            )
        )
        if (InuConfig.MENTION_SEPARATOR.value.isNotEmpty()) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_MENTION_SEPARATOR_BOTS,
                    R.string.InuMentionSeparatorBots,
                    R.string.InuMentionSeparatorBotsInfo,
                    InuConfig.MENTION_SEPARATOR_FOR_BOTS.value,
                )
            )
        }
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SEARCH_FROM_GLOBAL,
                R.string.InuSearchFromGlobal,
                R.string.InuSearchFromGlobalInfo,
                InuConfig.SEARCH_FROM_GLOBAL.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN,
                R.string.InuSendToDiscussWithoutJoin,
                R.string.InuSendToDiscussWithoutJoinInfo,
                InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN.value,
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_CHAT_SNOWFLAKES,
                LocaleController.getString(R.string.InuChatSnowflakes),
            ).setChecked(InuConfig.CHAT_SNOWFLAKES.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_ACTION_BAR_SNOWFLAKES,
                LocaleController.getString(R.string.InuActionBarSnowflakes),
            ).setChecked(InuConfig.ACTION_BAR_SNOWFLAKES.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAttachmentSheet)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SORT_ALBUMS_BY_SIZE,
                R.string.InuSortAlbumsBySize,
                R.string.InuSortAlbumsBySizeInfo,
                InuConfig.SORT_ALBUMS_BY_SIZE.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMessageInput)))
        items.add(
            mkSplitCheckItem(
                BUTTON_FORMATTING_POPUP,
                R.string.InuFormattingPopup,
                R.string.InuFormattingPopupInfo,
                InuConfig.FORMATTING_POPUP.value,
                experimental = true
            )
        )
        if (chatInputMaxLinesSlider == null) chatInputMaxLinesSlider = SliderCell(
            context,
            min = 5f,
            max = 15f,
            defaultValue = InuConfig.CHAT_INPUT_MAX_LINES.default.toFloat(),
            initialValue = InuConfig.CHAT_INPUT_MAX_LINES.value.toFloat(),
            step = 1f,
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.CHAT_INPUT_MAX_LINES.value = it.toInt()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatInputMaxLines)))
        items.add(UItem.asCustom(chatInputMaxLinesSlider))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStickers)))
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_ALL_RECENT_STICKERS,
                LocaleController.getString(R.string.InuShowAllRecentStickers),
            ).setChecked(InuConfig.SHOW_ALL_RECENT_STICKERS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER,
                R.string.InuSuggestCustomEmojiAfter,
                R.string.InuSuggestCustomEmojiAfterInfo,
                InuConfig.SUGGEST_CUSTOM_EMOJI_AFTER.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    private fun showCustomMentionSeparatorDialog() {
        showInputDialog(
            this,
            LocaleController.getString(R.string.InuMentionSeparator),
            LocaleController.getString(R.string.InuMentionSepCustomHint),
            InuConfig.MENTION_SEPARATOR.value.ifEmpty { null },
        ) { text ->
            InuConfig.MENTION_SEPARATOR.value = if (text.isEmpty() || text == " ") "" else text
            listView.adapter.update(true)
            true
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        super.onClick(item, view, position, x, y)
        when (item.id) {
            BUTTON_MESSAGES -> presentFragment(MessagesSettingsActivity())
            BUTTON_CAMERA_SOUND -> presentFragment(CameraSoundSettingsActivity())
            TOGGLE_HIDE_KEYBOARD_ON_SCROLL -> (view as? TextCheckCell)?.isChecked = InuConfig.HIDE_KEYBOARD_ON_SCROLL.toggle()
            TOGGLE_KEEP_KEYBOARD_WHILE_TYPING -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.KEEP_KEYBOARD_WHILE_TYPING.toggle()
            TOGGLE_DISABLE_PULL_TO_NEXT -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_PULL_TO_NEXT.toggle()
            TOGGLE_CHAT_ALWAYS_SHOW_DOWN -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_ALWAYS_SHOW_DOWN.toggle()
            TOGGLE_CHAT_TWO_FINGER_SELECT -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_TWO_FINGER_SELECT.toggle()
            TOGGLE_SELECTION_BOTTOM_NO_QUOTE -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SELECTION_BOTTOM_NO_QUOTE.toggle()
            TOGGLE_DISABLE_BOT_DRAFT_TOP -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_BOT_DRAFT_TOP.toggle()
            BUTTON_MENTION_SEPARATOR -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuMentionSepComma),
                    LocaleController.getString(R.string.InuMentionSepPeriod),
                    LocaleController.getString(R.string.InuMentionSepColon),
                    LocaleController.getString(R.string.InuMentionSepSpace),
                    LocaleController.getString(R.string.InuMentionSepCustom),
                ),
                when (InuConfig.MENTION_SEPARATOR.value) {
                    ", " -> 0
                    ". " -> 1
                    ": " -> 2
                    "" -> 3
                    else -> 4
                },
            ) { which ->
                if (which == 4) {
                    showCustomMentionSeparatorDialog()
                } else {
                    InuConfig.MENTION_SEPARATOR.value = listOf(", ", ". ", ": ", "")[which]
                    listView.adapter.update(true)
                }
            }
            TOGGLE_MENTION_SEPARATOR_BOTS -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.MENTION_SEPARATOR_FOR_BOTS.toggle()
            TOGGLE_SHOW_ALL_RECENT_STICKERS -> (view as? TextCheckCell)?.isChecked = InuConfig.SHOW_ALL_RECENT_STICKERS.toggle()
            TOGGLE_SORT_ALBUMS_BY_SIZE -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SORT_ALBUMS_BY_SIZE.toggle()
            TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.SEND_TO_DISCUSS_WITHOUT_JOIN.toggle()
            TOGGLE_CHAT_SNOWFLAKES -> (view as? TextCheckCell)?.isChecked = InuConfig.CHAT_SNOWFLAKES.toggle()
            TOGGLE_ACTION_BAR_SNOWFLAKES -> (view as? TextCheckCell)?.isChecked = InuConfig.ACTION_BAR_SNOWFLAKES.toggle()
            TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SUGGEST_CUSTOM_EMOJI_AFTER.toggle()

            BUTTON_FORMATTING_POPUP -> {
                val isSwitch = if (LocaleController.isRTL)
                    x < AndroidUtilities.dp(76f)
                else
                    x > view.measuredWidth - AndroidUtilities.dp(76f)
                if (isSwitch) {
                    val new = InuConfig.FORMATTING_POPUP.toggle()
                    (view as? NotificationsCheckCell)?.isChecked = new
                } else {
                    presentFragment(FormattingPopupActivity())
                }
            }

            TOGGLE_SEARCH_FROM_GLOBAL -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SEARCH_FROM_GLOBAL.toggle()
        }
    }

    companion object {
        private val BUTTON_MESSAGES = InuUtils.generateId()
        private val TOGGLE_HIDE_KEYBOARD_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_KEEP_KEYBOARD_WHILE_TYPING = InuUtils.generateId()
        private val TOGGLE_DISABLE_PULL_TO_NEXT = InuUtils.generateId()
        private val TOGGLE_CHAT_ALWAYS_SHOW_DOWN = InuUtils.generateId()
        private val TOGGLE_CHAT_TWO_FINGER_SELECT = InuUtils.generateId()
        private val TOGGLE_SELECTION_BOTTOM_NO_QUOTE = InuUtils.generateId()
        private val TOGGLE_DISABLE_BOT_DRAFT_TOP = InuUtils.generateId()
        private val BUTTON_MENTION_SEPARATOR = InuUtils.generateId()
        private val TOGGLE_MENTION_SEPARATOR_BOTS = InuUtils.generateId()
        private val TOGGLE_SHOW_ALL_RECENT_STICKERS = InuUtils.generateId()
        private val TOGGLE_SORT_ALBUMS_BY_SIZE = InuUtils.generateId()
        private val BUTTON_CAMERA_SOUND = InuUtils.generateId()
        private val TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER = InuUtils.generateId()
        private val BUTTON_FORMATTING_POPUP = InuUtils.generateId()
        private val TOGGLE_SEARCH_FROM_GLOBAL = InuUtils.generateId()
        private val TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN = InuUtils.generateId()
        private val TOGGLE_CHAT_SNOWFLAKES = InuUtils.generateId()
        private val TOGGLE_ACTION_BAR_SNOWFLAKES = InuUtils.generateId()

        private fun mentionSeparatorLabel(value: String): String = when (value) {
            ", " -> LocaleController.getString(R.string.InuMentionSepComma)
            ". " -> LocaleController.getString(R.string.InuMentionSepPeriod)
            ": " -> LocaleController.getString(R.string.InuMentionSepColon)
            "" -> LocaleController.getString(R.string.InuMentionSepSpace)
            else -> value.trim()
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "chats",
            titleRes = R.string.InuCategoryChats,
            iconRes = R.drawable.msg_discussion,
            factory = ::CategoryChatsSettingsActivity,
            aliases = listOf("dialogs"),
            entries = DialogsSettingsActivity.PAGE.entries + listOf(
                SearchRegistry.Entry("messages-open", R.string.InuMessages, BUTTON_MESSAGES),
                SearchRegistry.Entry("mention-separator-bots", R.string.InuMentionSeparatorBots, TOGGLE_MENTION_SEPARATOR_BOTS),
                SearchRegistry.Entry("hide-keyboard-on-scroll", R.string.InuHideKeyboardOnScroll, TOGGLE_HIDE_KEYBOARD_ON_SCROLL),
                SearchRegistry.Entry("keep-keyboard-while-typing", R.string.InuKeepKeyboardWhileTyping, TOGGLE_KEEP_KEYBOARD_WHILE_TYPING),
                SearchRegistry.Entry("disable-pull-to-next", R.string.InuDisablePullToNext, TOGGLE_DISABLE_PULL_TO_NEXT),
                SearchRegistry.Entry("chat-always-show-down", R.string.InuChatAlwaysShowDown, TOGGLE_CHAT_ALWAYS_SHOW_DOWN),
                SearchRegistry.Entry("chat-two-finger-select", R.string.InuChatTwoFingerSelect, TOGGLE_CHAT_TWO_FINGER_SELECT),
                SearchRegistry.Entry("selection-bottom-no-quote", R.string.InuSelectionBottomNoQuote, TOGGLE_SELECTION_BOTTOM_NO_QUOTE),
                SearchRegistry.Entry("disable-bot-draft-top", R.string.InuDisableBotDraftTop, TOGGLE_DISABLE_BOT_DRAFT_TOP),
                SearchRegistry.Entry("mention-separator", R.string.InuMentionSeparator, BUTTON_MENTION_SEPARATOR),
                SearchRegistry.Entry("show-all-recent-stickers", R.string.InuShowAllRecentStickers, TOGGLE_SHOW_ALL_RECENT_STICKERS),
                SearchRegistry.Entry("camera-sound-open", R.string.InuCameraSound, BUTTON_CAMERA_SOUND),
                SearchRegistry.Entry("sort-albums-by-size", R.string.InuSortAlbumsBySize, TOGGLE_SORT_ALBUMS_BY_SIZE),
                SearchRegistry.Entry("suggest-custom-emoji-after", R.string.InuSuggestCustomEmojiAfter, TOGGLE_SUGGEST_CUSTOM_EMOJI_AFTER),
                SearchRegistry.Entry("formatting-popup", R.string.InuFormattingPopup, BUTTON_FORMATTING_POPUP),
                SearchRegistry.Entry("search-from-global", R.string.InuSearchFromGlobal, TOGGLE_SEARCH_FROM_GLOBAL),
                SearchRegistry.Entry("send-to-discuss-without-join", R.string.InuSendToDiscussWithoutJoin, TOGGLE_SEND_TO_DISCUSS_WITHOUT_JOIN),
                SearchRegistry.Entry("chat-snowflakes", R.string.InuChatSnowflakes, TOGGLE_CHAT_SNOWFLAKES),
                SearchRegistry.Entry("action-bar-snowflakes", R.string.InuActionBarSnowflakes, TOGGLE_ACTION_BAR_SNOWFLAKES),
            ),
        )
    }
}
