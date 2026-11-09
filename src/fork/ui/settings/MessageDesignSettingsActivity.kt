package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MessageDesignSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessageDesign)

    private var stickerSizePreview: StickerSizePreviewMessagesCell? = null
    private var miscPreview: MiscPreviewMessagesCell? = null
    private var stickerSizeSlider: SliderCell? = null
    private var reactionsInRowSlider: SliderCell? = null
    private var chatInputTextSizeSlider: SliderCell? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val context = context ?: return

        if (stickerSizePreview == null) stickerSizePreview = StickerSizePreviewMessagesCell(context, this)
        if (stickerSizeSlider == null) {
            stickerSizeSlider = SliderCell(
                context,
                min = 4f,
                max = 20f,
                defaultValue = InuConfig.STICKER_SIZE.default,
                initialValue = InuConfig.STICKER_SIZE.value,
                title = LocaleController.getString(R.string.InuStickerSize),
                format = { "%.1f".format(it) },
                onChanged = {
                    InuConfig.STICKER_SIZE.value = it
                    stickerSizePreview?.invalidate()
                },
            )
        } else {
            stickerSizeSlider?.updateColors()
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStickers)))
        items.add(UItem.asCustom(stickerSizePreview))
        items.add(UItem.asCustom(stickerSizeSlider))
        items.add(
            UItem.asButton(
                BUTTON_STICKER_TIME_MODE,
                LocaleController.getString(R.string.InuStickerTimeMode),
                when (InuConfig.STICKER_TIME_MODE.value) {
                    InuConfig.StickerTimeModeItem.HIDE_TIME -> LocaleController.getString(R.string.InuStickerTimeModeHideTime)
                    InuConfig.StickerTimeModeItem.HIDE_FULL -> LocaleController.getString(R.string.InuStickerTimeModeHideCompletely)
                    InuConfig.StickerTimeModeItem.HIDE_INCOMING -> LocaleController.getString(R.string.InuStickerTimeModeHideIncoming)
                    else -> LocaleController.getString(R.string.InuStickerTimeModeShow)
                }
            )
        )
        items.add(UItem.asShadow(null))

        if (miscPreview == null) miscPreview = MiscPreviewMessagesCell(context, this)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuBubbles)))
        items.add(UItem.asCustom(miscPreview))
        items.add(
            UItem.asCheck(
                TOGGLE_BUBBLE_TAILS,
                LocaleController.getString(R.string.InuBubbleTails),
            ).setChecked(InuConfig.BUBBLE_TAILS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BUBBLES,
                LocaleController.getString(R.string.InuDisableChatBubbles),
            ).setChecked(InuConfig.DISABLE_CHAT_BUBBLES.value)
        )
        items.add(UItem.asButton(BUTTON_CUSTOM_MARK, R.drawable.msg_edit, LocaleController.getString(R.string.InuCustomEditedMark), InuConfig.CUSTOM_EDITED_MARK.value))
        items.add(
            UItem.asCheck(
                TOGGLE_COMPACT_EDITED,
                LocaleController.getString(R.string.InuCompactEdited),
            ).setChecked(InuConfig.COMPACT_EDITED.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_EDITED_MESSAGE_DATE_MODE,
                LocaleController.getString(R.string.InuEditedMessageDateMode),
                editedMessageDateModeLabel(InuConfig.EDITED_MESSAGE_DATE_MODE.value),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_FORWARD_TIME,
                LocaleController.getString(R.string.InuShowForwardTime),
            ).setChecked(InuConfig.SHOW_FORWARD_TIME.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_FORWARD_HEADER_MODE,
                LocaleController.getString(R.string.InuForwardHeaderMode),
                forwardHeaderModeLabel(InuConfig.FORWARD_HEADER_MODE.value),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_FORWARDS_COUNT,
                LocaleController.getString(R.string.InuShowForwardsCount),
            ).setChecked(InuConfig.SHOW_FORWARDS_COUNT.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_MEDIA_SIZE,
                LocaleController.getString(R.string.InuShowMediaSize),
            ).setChecked(InuConfig.SHOW_MEDIA_SIZE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SMALL_GIFS,
                LocaleController.getString(R.string.InuSmallGifs),
            ).setChecked(InuConfig.SMALL_GIFS.value)
        )
        items.add(UItem.asShadow(null))

        if (reactionsInRowSlider == null) reactionsInRowSlider = SliderCell(
            context,
            min = 6f,
            max = InuConfig.REACTIONS_IN_ROW.maxFit().toFloat(),
            defaultValue = InuConfig.REACTIONS_IN_ROW.default.toFloat(),
            initialValue = InuConfig.REACTIONS_IN_ROW.value.toFloat(),
            step = 1f,
            title = LocaleController.getString(R.string.InuReactionsInRow),
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.REACTIONS_IN_ROW.value = it.toInt()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuReactions)))
        items.add(UItem.asCustom(reactionsInRowSlider))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REACTION_BAR_BELOW,
                R.string.InuReactionBarBelow,
                R.string.InuReactionBarBelowInfo,
                InuConfig.REACTION_BAR_BELOW.value,
                experimental = true
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CHAT_VIEWS_BOTTOM,
                R.string.InuChatViewsBottom,
                R.string.InuChatViewsBottomInfo,
                InuConfig.CHAT_VIEWS_BOTTOM.value,
                experimental = true
            )
        )
        items.add(UItem.asShadow(null))

        if (chatInputTextSizeSlider == null) chatInputTextSizeSlider = SliderCell(
            context,
            min = 12f,
            max = 24f,
            defaultValue = InuConfig.CHAT_INPUT_TEXT_SIZE.default.toFloat(),
            initialValue = InuConfig.CHAT_INPUT_TEXT_SIZE.value.toFloat(),
            step = 1f,
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.CHAT_INPUT_TEXT_SIZE.value = it.toInt()
            },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatInputTextSize)))
        items.add(UItem.asCustom(chatInputTextSizeSlider))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_CUSTOM_MARK -> editMessageMark(R.string.InuCustomEditedMark, InuConfig.CUSTOM_EDITED_MARK) {
                if (InuConfig.CUSTOM_EDITED_MARK.value.isNotEmpty()) InuConfig.COMPACT_EDITED.value = false
                listView?.adapter?.update(true)
            }
            BUTTON_STICKER_TIME_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuStickerTimeModeShow),
                    LocaleController.getString(R.string.InuStickerTimeModeHideTime),
                    LocaleController.getString(R.string.InuStickerTimeModeHideIncoming),
                    LocaleController.getString(R.string.InuStickerTimeModeHideCompletely),
                ),
                InuConfig.STICKER_TIME_MODE.value - 1,
            ) { which ->
                InuConfig.STICKER_TIME_MODE.value = which + 1
                stickerSizePreview?.invalidate()
            }

            TOGGLE_REACTION_BAR_BELOW -> {
                val new = InuConfig.REACTION_BAR_BELOW.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_VIEWS_BOTTOM -> {
                val new = InuConfig.CHAT_VIEWS_BOTTOM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_FORWARD_TIME -> {
                val new = InuConfig.SHOW_FORWARD_TIME.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            BUTTON_FORWARD_HEADER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuForwardHeaderModeRegular),
                    LocaleController.getString(R.string.InuForwardHeaderModeIcon),
                    LocaleController.getString(R.string.InuForwardHeaderModeCompact),
                ),
                InuConfig.FORWARD_HEADER_MODE.value,
            ) { which ->
                if (InuConfig.FORWARD_HEADER_MODE.value == which) return@show
                InuConfig.FORWARD_HEADER_MODE.value = which
                miscPreview?.invalidate()
                listView.adapter.update(false)
            }

            TOGGLE_SHOW_FORWARDS_COUNT -> {
                val new = InuConfig.SHOW_FORWARDS_COUNT.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            TOGGLE_SHOW_MEDIA_SIZE -> {
                val new = InuConfig.SHOW_MEDIA_SIZE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_BUBBLES -> {
                val new = InuConfig.DISABLE_CHAT_BUBBLES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_COMPACT_EDITED -> {
                val new = InuConfig.COMPACT_EDITED.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            BUTTON_EDITED_MESSAGE_DATE_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuEditedMessageDateModeTelegram),
                    LocaleController.getString(R.string.InuEditedMessageDateModeEdit),
                    LocaleController.getString(R.string.InuEditedMessageDateModeSend),
                ),
                InuConfig.EDITED_MESSAGE_DATE_MODE.value,
            ) { which ->
                if (InuConfig.EDITED_MESSAGE_DATE_MODE.value == which) return@show
                InuConfig.EDITED_MESSAGE_DATE_MODE.value = which
                miscPreview?.invalidate()
                listView.adapter.update(false)
            }

            TOGGLE_BUBBLE_TAILS -> {
                val new = InuConfig.BUBBLE_TAILS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                miscPreview?.invalidate()
            }

            TOGGLE_SMALL_GIFS -> {
                val new = InuConfig.SMALL_GIFS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }
        }
    }

    companion object {
        private val BUTTON_CUSTOM_MARK = InuUtils.generateId()
        private val BUTTON_STICKER_TIME_MODE = InuUtils.generateId()
        private val TOGGLE_REACTION_BAR_BELOW = InuUtils.generateId()
        private val TOGGLE_CHAT_VIEWS_BOTTOM = InuUtils.generateId()
        private val TOGGLE_SHOW_FORWARD_TIME = InuUtils.generateId()
        private val BUTTON_FORWARD_HEADER_MODE = InuUtils.generateId()
        private val TOGGLE_SHOW_FORWARDS_COUNT = InuUtils.generateId()
        private val TOGGLE_SHOW_MEDIA_SIZE = InuUtils.generateId()
        private val TOGGLE_COMPACT_EDITED = InuUtils.generateId()
        private val BUTTON_EDITED_MESSAGE_DATE_MODE = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BUBBLES = InuUtils.generateId()
        private val TOGGLE_BUBBLE_TAILS = InuUtils.generateId()
        private val TOGGLE_SMALL_GIFS = InuUtils.generateId()

        private fun editedMessageDateModeLabel(value: Int): String = when (value) {
            InuConfig.EditedMessageDateModeItem.EDIT_DATE -> LocaleController.getString(R.string.InuEditedMessageDateModeEdit)
            InuConfig.EditedMessageDateModeItem.SEND_DATE -> LocaleController.getString(R.string.InuEditedMessageDateModeSend)
            else -> LocaleController.getString(R.string.InuEditedMessageDateModeTelegram)
        }

        private fun forwardHeaderModeLabel(value: Int): String = when (value) {
            InuConfig.ForwardHeaderModeItem.COMPACT -> LocaleController.getString(R.string.InuForwardHeaderModeCompact)
            InuConfig.ForwardHeaderModeItem.ICON -> LocaleController.getString(R.string.InuForwardHeaderModeIcon)
            else -> LocaleController.getString(R.string.InuForwardHeaderModeRegular)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "message-design",
            titleRes = R.string.InuMessageDesign,
            iconRes = R.drawable.msg_discussion,
            factory = ::MessageDesignSettingsActivity,
            entries = listOf(
                // entiny: slugs kept verbatim from MessagesSettingsActivity / CategoryChatsSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("sticker-time-mode", R.string.InuStickerTimeMode, BUTTON_STICKER_TIME_MODE),
                SearchRegistry.Entry("reaction-bar-below", R.string.InuReactionBarBelow, TOGGLE_REACTION_BAR_BELOW),
                SearchRegistry.Entry("chat-views-bottom", R.string.InuChatViewsBottom, TOGGLE_CHAT_VIEWS_BOTTOM),
                SearchRegistry.Entry("show-forward-time", R.string.InuShowForwardTime, TOGGLE_SHOW_FORWARD_TIME),
                SearchRegistry.Entry("compact-forwarded", R.string.InuForwardHeaderMode, BUTTON_FORWARD_HEADER_MODE),
                SearchRegistry.Entry("show-forwards-count", R.string.InuShowForwardsCount, TOGGLE_SHOW_FORWARDS_COUNT),
                SearchRegistry.Entry("show-media-size", R.string.InuShowMediaSize, TOGGLE_SHOW_MEDIA_SIZE),
                SearchRegistry.Entry("compact-edited", R.string.InuCompactEdited, TOGGLE_COMPACT_EDITED),
                SearchRegistry.Entry("custom-edited-mark", R.string.InuCustomEditedMark, BUTTON_CUSTOM_MARK),
                SearchRegistry.Entry("edited-message-date", R.string.InuEditedMessageDateMode, BUTTON_EDITED_MESSAGE_DATE_MODE),
                SearchRegistry.Entry("disable-chat-bubbles", R.string.InuDisableChatBubbles, TOGGLE_DISABLE_CHAT_BUBBLES),
                SearchRegistry.Entry("bubble-tails", R.string.InuBubbleTails, TOGGLE_BUBBLE_TAILS),
                SearchRegistry.Entry("small-gifs", R.string.InuSmallGifs, TOGGLE_SMALL_GIFS),
            ),
        )
    }
}
