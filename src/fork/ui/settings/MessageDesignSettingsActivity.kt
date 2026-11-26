package desu.inugram.ui.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.WidePostLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MessageDesignSettingsActivity : SettingsPageActivity() {

    private enum class Tab(val title: Int) {
        BUBBLES(R.string.InuBubbles),
        STICKERS(R.string.InuStickers),
        TEXT(R.string.InuMessageDesignTextAndTime),
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessageDesign)

    private var messagePreview: MessageDesignPreviewCell? = null
    private var stickerShapeCell: StickerShapeCell? = null
    private var widePostsPreview: WideChannelPostsPreviewCell? = null
    private var widePostsInsetSlider: SliderCell? = null
    private var stickerSizeSlider: SliderCell? = null
    private var reactionsInRowSlider: SliderCell? = null
    private var chatInputTextSizeSlider: SliderCell? = null
    private var messageTimeSizeSlider: SliderCell? = null
    private var selectedTab = Tab.TEXT
    private var tabs: FrameLayout? = null
    private var textDivider: View? = null

    override fun withHighlight(itemId: Int): SettingsPageActivity {
        selectedTab = when (itemId) {
            BUTTON_STICKER_TIME_MODE, SLIDER_STICKER_SIZE, STICKER_SHAPE -> Tab.STICKERS
            TOGGLE_BUBBLE_TAILS, TOGGLE_DISABLE_CHAT_BUBBLES, TOGGLE_COMPACT_EDITED,
            BUTTON_EDITED_MESSAGE_DATE_MODE, TOGGLE_SHOW_FORWARD_TIME, BUTTON_FORWARD_HEADER_MODE,
            TOGGLE_SHOW_FORWARDS_COUNT, TOGGLE_SHOW_MEDIA_SIZE, TOGGLE_SMALL_GIFS -> Tab.BUBBLES
            else -> Tab.TEXT
        }
        return super.withHighlight(itemId)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val context = context ?: return

        if (messagePreview == null) messagePreview = MessageDesignPreviewCell(context, this)
        items.add(UItem.asCustom(messagePreview))
        items.add(UItem.asCustomShadow(createTabs(context)))

        when (selectedTab) {
            Tab.BUBBLES -> addBubbleControls(items)
            Tab.STICKERS -> addStickerControls(context, items)
            Tab.TEXT -> addTextControls(context, items)
        }
        addAdditionalControls(context, items)
    }

    private fun createTabs(context: Context): View {
        val existing = tabs
        if (existing != null) {
            updateTabs(existing.getChildAt(0) as LinearLayout)
            return existing
        }
        val row = LinearLayout(context).apply {
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            layoutDirection = if (LocaleController.isRTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            for (tab in Tab.entries) {
                val button = TextView(context).apply {
                    text = LocaleController.getString(tab.title)
                    textSize = 14f
                    typeface = AndroidUtilities.bold()
                    gravity = Gravity.CENTER
                    minHeight = dp(44f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                    isFocusable = true
                    setOnClickListener {
                        if (selectedTab != tab) {
                            selectedTab = tab
                            listView.adapter.update(false)
                        }
                    }
                }
                addView(button, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))
            }
        }
        updateTabs(row)
        return FrameLayout(context).apply {
            addView(
                row,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 12f, 8f, 12f, 8f)
            )
        }.also { tabs = it }
    }

    private fun updateTabs(row: LinearLayout) {
        val provider = resourceProvider
        val surface = Theme.getColor(Theme.key_windowBackgroundWhite, provider)
        val ripple = Theme.getColor(Theme.key_listSelector, provider)
        val track = Theme.blendOver(surface, ripple)
        row.background = Theme.createRoundRectDrawable(dp(14f), track)
        Tab.entries.forEachIndexed { index, tab ->
            val button = row.getChildAt(index) as TextView
            val selected = tab == selectedTab
            button.isSelected = selected
            button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider))
            val backgroundColor = if (selected) surface else track
            button.background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(11f), backgroundColor, Theme.blendOver(backgroundColor, ripple)
            )
        }
    }

    private fun addStickerControls(context: Context, items: ArrayList<UItem>) {
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
                    messagePreview?.invalidate()
                },
            )
        } else {
            stickerSizeSlider?.updateColors()
        }
        if (stickerShapeCell == null) stickerShapeCell = StickerShapeCell(context) {
            messagePreview?.invalidate()
            reloadChats()
        }
        items.add(UItem.asCustom(SLIDER_STICKER_SIZE, stickerSizeSlider))
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
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStickerShape)))
        items.add(UItem.asCustom(STICKER_SHAPE, stickerShapeCell))
        items.add(UItem.asShadow(null))
    }

    private fun addBubbleControls(items: ArrayList<UItem>) {
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
    }

    private fun addAdditionalControls(context: Context, items: ArrayList<UItem>) {
        if (widePostsPreview == null) widePostsPreview = WideChannelPostsPreviewCell(context, this)
        if (widePostsInsetSlider == null) {
            widePostsInsetSlider = SliderCell(
                context,
                min = 0f,
                max = 24f,
                step = 1f,
                defaultValue = InuConfig.WIDE_CHANNEL_POSTS_INSET.default,
                initialValue = InuConfig.WIDE_CHANNEL_POSTS_INSET.value,
                title = LocaleController.getString(R.string.InuWideChannelPostsInset),
                format = { "${it.toInt()}dp" },
                onChanged = {
                    InuConfig.WIDE_CHANNEL_POSTS_INSET.value = it
                    widePostsPreview?.refreshInset()
                },
                onReleased = { reloadChats() },
            )
        } else {
            widePostsInsetSlider?.updateColors()
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuWideChannelPosts)))
        items.add(UItem.asCustom(widePostsPreview))
        items.add(
            UItem.asCheck(
                TOGGLE_WIDE_CHANNEL_POSTS,
                LocaleController.getString(R.string.InuWideChannelPosts),
            ).setChecked(InuConfig.WIDE_CHANNEL_POSTS.value)
        )
        if (InuConfig.WIDE_CHANNEL_POSTS.value) items.add(UItem.asCustom(widePostsInsetSlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuWideChannelPostsFooter)))

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
    }

    private fun addTextControls(context: Context, items: ArrayList<UItem>) {
        if (chatInputTextSizeSlider == null) chatInputTextSizeSlider = SliderCell(
            context,
            min = 12f,
            max = 24f,
            defaultValue = InuConfig.CHAT_INPUT_TEXT_SIZE.default.toFloat(),
            initialValue = InuConfig.CHAT_INPUT_TEXT_SIZE.value.toFloat(),
            step = 1f,
            title = LocaleController.getString(R.string.InuChatInputTextSize),
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.CHAT_INPUT_TEXT_SIZE.value = it.toInt()
                messagePreview?.updateInputTextSize()
            },
        )
        chatInputTextSizeSlider?.updateColors()
        items.add(UItem.asCustom(SLIDER_CHAT_INPUT_TEXT_SIZE, chatInputTextSizeSlider))
        if (textDivider == null) textDivider = View(context)
        textDivider?.setBackgroundColor(Theme.getColor(Theme.key_divider, resourceProvider))
        items.add(UItem.asCustom(textDivider, 1))

        if (messageTimeSizeSlider == null) messageTimeSizeSlider = SliderCell(
            context,
            min = 10f,
            max = 18f,
            defaultValue = InuConfig.MESSAGE_TIME_SIZE.default.toFloat(),
            initialValue = InuConfig.MESSAGE_TIME_SIZE.value.toFloat(),
            step = 1f,
            title = LocaleController.getString(R.string.InuMessageTimeSize),
            format = { it.toInt().toString() },
            onChanged = {
                InuConfig.MESSAGE_TIME_SIZE.value = it.toInt()
                messagePreview?.invalidate()
            },
        )
        messageTimeSizeSlider?.updateColors()
        items.add(UItem.asCustom(SLIDER_MESSAGE_TIME_SIZE, messageTimeSizeSlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuMessageTimeSizeInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
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
                messagePreview?.invalidate()
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
                messagePreview?.invalidate()
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
                messagePreview?.invalidate()
                listView.adapter.update(false)
            }

            TOGGLE_SHOW_FORWARDS_COUNT -> {
                val new = InuConfig.SHOW_FORWARDS_COUNT.toggle()
                (view as? TextCheckCell)?.isChecked = new
                messagePreview?.invalidate()
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
                messagePreview?.invalidate()
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
                messagePreview?.invalidate()
                listView.adapter.update(false)
            }

            TOGGLE_BUBBLE_TAILS -> {
                val new = InuConfig.BUBBLE_TAILS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                messagePreview?.invalidate()
            }

            TOGGLE_SMALL_GIFS -> {
                val new = InuConfig.SMALL_GIFS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }

            TOGGLE_WIDE_CHANNEL_POSTS -> {
                val new = InuConfig.WIDE_CHANNEL_POSTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                widePostsPreview?.setWide(new)
                reloadChats()
                listView.adapter.update(true)
            }
        }
    }

    private fun reloadChats() {
        WidePostLayout.invalidate()
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
    }

    companion object {
        private val SLIDER_STICKER_SIZE = InuUtils.generateId()
        private val STICKER_SHAPE = InuUtils.generateId()
        private val SLIDER_CHAT_INPUT_TEXT_SIZE = InuUtils.generateId()
        private val SLIDER_MESSAGE_TIME_SIZE = InuUtils.generateId()
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
        private val TOGGLE_WIDE_CHANNEL_POSTS = InuUtils.generateId()

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
                SearchRegistry.Entry("sticker-size", R.string.InuStickerSize, SLIDER_STICKER_SIZE),
                SearchRegistry.Entry("sticker-shape", R.string.InuStickerShape, STICKER_SHAPE),
                SearchRegistry.Entry("chat-input-text-size", R.string.InuChatInputTextSize, SLIDER_CHAT_INPUT_TEXT_SIZE),
                SearchRegistry.Entry("message-time-size", R.string.InuMessageTimeSize, SLIDER_MESSAGE_TIME_SIZE),
                SearchRegistry.Entry("reaction-bar-below", R.string.InuReactionBarBelow, TOGGLE_REACTION_BAR_BELOW),
                SearchRegistry.Entry("chat-views-bottom", R.string.InuChatViewsBottom, TOGGLE_CHAT_VIEWS_BOTTOM),
                SearchRegistry.Entry("show-forward-time", R.string.InuShowForwardTime, TOGGLE_SHOW_FORWARD_TIME),
                SearchRegistry.Entry("compact-forwarded", R.string.InuForwardHeaderMode, BUTTON_FORWARD_HEADER_MODE),
                SearchRegistry.Entry("show-forwards-count", R.string.InuShowForwardsCount, TOGGLE_SHOW_FORWARDS_COUNT),
                SearchRegistry.Entry("show-media-size", R.string.InuShowMediaSize, TOGGLE_SHOW_MEDIA_SIZE),
                SearchRegistry.Entry("compact-edited", R.string.InuCompactEdited, TOGGLE_COMPACT_EDITED),
                SearchRegistry.Entry("edited-message-date", R.string.InuEditedMessageDateMode, BUTTON_EDITED_MESSAGE_DATE_MODE),
                SearchRegistry.Entry("disable-chat-bubbles", R.string.InuDisableChatBubbles, TOGGLE_DISABLE_CHAT_BUBBLES),
                SearchRegistry.Entry("bubble-tails", R.string.InuBubbleTails, TOGGLE_BUBBLE_TAILS),
                SearchRegistry.Entry("small-gifs", R.string.InuSmallGifs, TOGGLE_SMALL_GIFS),
                SearchRegistry.Entry("wide-channel-posts", R.string.InuWideChannelPosts, TOGGLE_WIDE_CHANNEL_POSTS),
                SearchRegistry.Entry("wide-channel-posts-inset", R.string.InuWideChannelPostsInset, TOGGLE_WIDE_CHANNEL_POSTS),
            ),
        )
    }
}
