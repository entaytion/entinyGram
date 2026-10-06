package desu.inugram.ui.settings

import android.os.Bundle
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.BlockedMessagesHelper
import desu.inugram.helpers.chat.DoubleTapAction
import desu.inugram.helpers.chat.DoubleTapActionHelper
import desu.inugram.helpers.chat.DoubleTapContext
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.GroupCreateActivity

class MessagesSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMessages)

    private var doubleTapDelaySlider: SliderCell? = null

    private val confirmSendGroup by lazy {
        ExpandableBoolGroup(
            LocaleController.getString(R.string.InuConfirmBeforeSending),
            listOf(
                ExpandableBoolGroup.Option(R.string.InuConfirmSendVoice, InuConfig.CONFIRM_SEND_VOICE, TOGGLE_CONFIRM_SEND_VOICE),
                ExpandableBoolGroup.Option(R.string.InuConfirmSendSticker, InuConfig.CONFIRM_SEND_STICKER, TOGGLE_CONFIRM_SEND_STICKER),
                ExpandableBoolGroup.Option(R.string.InuConfirmSendGif, InuConfig.CONFIRM_SEND_GIF, TOGGLE_CONFIRM_SEND_GIF),
            ),
            sectionId = SECTION_CONFIRM_SEND,
        ).apply { expanded = true }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuReactions)))
        items.add(
            mkSubPageButton(
                BUTTON_PINNED_REACTIONS,
                R.drawable.inu_tabler_mood_smile,
                LocaleController.getString(R.string.InuPinnedReactions),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_INSTANT_MARK_REACTIONS_READ,
                LocaleController.getString(R.string.InuInstantMarkReactionsRead),
            ).setChecked(InuConfig.INSTANT_MARK_REACTIONS_READ.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuConfirmationsSection)))
        confirmSendGroup.addTo(items) { listView?.adapter?.update(true) }
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CONFIRM_REACTION_NON_MEMBER,
                R.string.InuConfirmReactionNonMember,
                R.string.InuConfirmReactionNonMemberInfo,
                InuConfig.CONFIRM_REACTION_NON_MEMBER.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSpoilers)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SHOW_SPOILERS_DIRECTLY,
                R.string.InuShowSpoilersDirectly,
                R.string.InuShowSpoilersDirectlyInfo,
                InuConfig.SHOW_SPOILERS_DIRECTLY.value,
            )
        )
        if (!InuConfig.SHOW_SPOILERS_DIRECTLY.value) {
            items.add(
                UItem.asButton(
                    BUTTON_TEXT_SPOILER_MODE,
                    LocaleController.getString(R.string.InuTextSpoilerMode),
                    textSpoilerModeLabel(InuConfig.TEXT_SPOILER_MODE.value),
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_SPOILER_EXTEND_TO_LINE_END,
                    R.string.InuSpoilerExtendToLineEnd,
                    R.string.InuSpoilerExtendToLineEndInfo,
                    InuConfig.SPOILER_EXTEND_TO_LINE_END.value,
                )
            )
            items.add(
                UItem.asButton(
                    BUTTON_MEDIA_SPOILER_MODE,
                    LocaleController.getString(R.string.InuMediaSpoilerMode),
                    mediaSpoilerModeLabel(InuConfig.MEDIA_SPOILER_MODE.value),
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_LINK_PREVIEW_SPOILER,
                    R.string.InuLinkPreviewSpoiler,
                    R.string.InuLinkPreviewSpoilerInfo,
                    InuConfig.LINK_PREVIEW_SPOILER.value,
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuBlockedMessagesSection)))
        items.add(
            UItem.asButton(
                BUTTON_BLOCKED_MESSAGES_MODE,
                LocaleController.getString(R.string.InuBlockedMessagesMode),
                blockedMessagesModeLabel(InuConfig.BLOCKED_MESSAGES_MODE.value),
            )
        )
        if (BlockedMessagesHelper.isEnabled()) {
            items.add(
                UItem.asButton(
                    BUTTON_BLOCKED_MESSAGES_EXTRA,
                    R.drawable.inu_tabler_user_x,
                    LocaleController.getString(R.string.InuBlockedMessagesExtra),
                    LocaleController.formatString(
                        R.string.InuBlockedMessagesExtraCount,
                        BlockedMessagesHelper.getExtraHidden(currentAccount).size,
                    ),
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuClownAvatarsSection)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CLOWN_AVATAR_BLOCKED,
                R.string.InuClownAvatarBlocked,
                R.string.InuClownAvatarBlockedInfo,
                InuConfig.CLOWN_AVATAR_BLOCKED.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CLOWN_AVATAR_BLOCKED_ME,
                R.string.InuClownAvatarBlockedMe,
                R.string.InuClownAvatarBlockedMeInfo,
                InuConfig.CLOWN_AVATAR_BLOCKED_ME.value
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CLOWN_AVATAR_EMOJI,
                LocaleController.getString(R.string.InuClownAvatarEmoji),
                clownAvatarEmojiLabel(InuConfig.CLOWN_AVATAR_EMOJI.value),
            )
        )

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCategoryBehavior)))
        items.add(
            UItem.asCheck(
                TOGGLE_CHAT_REMEMBER_ALL_REPLIES,
                LocaleController.getString(R.string.InuChatRememberAllReplies),
            ).setChecked(InuConfig.CHAT_REMEMBER_ALL_REPLIES.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_FORWARD_PRO,
                R.string.InuForwardPro,
                R.string.InuForwardProInfo,
                InuConfig.FORWARD_PRO.value,
                experimental = true,
            )
        )
        items.add(UItem.asShadow(null))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDoubleTapActions)))
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_INCOMING,
                LocaleController.getString(R.string.InuIncomingMessages),
                DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_INCOMING.value, DoubleTapContext.INCOMING).label()
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_OUTGOING,
                LocaleController.getString(R.string.InuOutgoingMessages),
                DoubleTapAction.fromValue(InuConfig.DOUBLE_TAP_ACTION_OUTGOING.value, DoubleTapContext.OUTGOING).label()
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DOUBLE_TAP_CHANNEL,
                LocaleController.getString(R.string.InuChannelMessages),
                channelActionLabel(),
            )
        )
        if (doubleTapDelaySlider == null) {
            doubleTapDelaySlider = SliderCell(
                this.context, min = 75f, max = 300f,
                defaultValue = InuConfig.DOUBLE_TAP_DELAY.default.toFloat(),
                initialValue = InuConfig.DOUBLE_TAP_DELAY.value.toFloat(),
                title = LocaleController.getString(R.string.InuDelay),
                format = { "${it.toInt()} ms" },
                onChanged = {
                    InuConfig.DOUBLE_TAP_DELAY.value = it.toInt()
                    InuHooks.syncDoubleTapDelay()
                },
            )
        } else {
            doubleTapDelaySlider?.updateColors()
        }
        items.add(UItem.asCustom(doubleTapDelaySlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuDoubleTapInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (confirmSendGroup.handleClick(item, view) { listView?.adapter?.update(true) }) return
        when (item.id) {
            TOGGLE_CONFIRM_REACTION_NON_MEMBER -> {
                val new = InuConfig.CONFIRM_REACTION_NON_MEMBER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CLOWN_AVATAR_BLOCKED -> {
                val new = InuConfig.CLOWN_AVATAR_BLOCKED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CLOWN_AVATAR_BLOCKED_ME -> {
                val new = InuConfig.CLOWN_AVATAR_BLOCKED_ME.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CHAT_REMEMBER_ALL_REPLIES -> {
                val new = InuConfig.CHAT_REMEMBER_ALL_REPLIES.toggle()
                setCellChecked(view, new)
            }

            TOGGLE_INSTANT_MARK_REACTIONS_READ -> {
                val new = InuConfig.INSTANT_MARK_REACTIONS_READ.toggle()
                setCellChecked(view, new)
            }

            TOGGLE_FORWARD_PRO -> {
                val new = InuConfig.FORWARD_PRO.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_SPOILERS_DIRECTLY -> {
                val new = InuConfig.SHOW_SPOILERS_DIRECTLY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
                listView.adapter.update(true)
            }

            TOGGLE_SPOILER_EXTEND_TO_LINE_END -> {
                val new = InuConfig.SPOILER_EXTEND_TO_LINE_END.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_LINK_PREVIEW_SPOILER -> {
                val new = InuConfig.LINK_PREVIEW_SPOILER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_CLOWN_AVATAR_EMOJI -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuClownAvatarEmojiClown),
                    LocaleController.getString(R.string.InuClownAvatarEmojiPoop),
                    LocaleController.getString(R.string.InuClownAvatarEmojiSkull),
                ),
                InuConfig.CLOWN_AVATAR_EMOJI.value,
            ) { which ->
                if (InuConfig.CLOWN_AVATAR_EMOJI.value == which) return@show
                InuConfig.CLOWN_AVATAR_EMOJI.value = which
                listView.adapter.update(true)
            }

            BUTTON_MEDIA_SPOILER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuMediaSpoilerModeTelegram),
                    LocaleController.getString(R.string.InuMediaSpoilerModePill),
                    LocaleController.getString(R.string.InuMediaSpoilerModeCircle),
                ),
                InuConfig.MEDIA_SPOILER_MODE.value,
            ) { which ->
                if (InuConfig.MEDIA_SPOILER_MODE.value == which) return@show
                InuConfig.MEDIA_SPOILER_MODE.value = which
            }

            BUTTON_TEXT_SPOILER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuTextSpoilerModeDefault),
                    LocaleController.getString(R.string.InuTextSpoilerModeSimple),
                    LocaleController.getString(R.string.InuTextSpoilerModeEpstein),
                ),
                InuConfig.TEXT_SPOILER_MODE.value,
            ) { which ->
                if (InuConfig.TEXT_SPOILER_MODE.value == which) return@show
                InuConfig.TEXT_SPOILER_MODE.value = which
            }

            BUTTON_BLOCKED_MESSAGES_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuBlockedMessagesModeOff),
                    LocaleController.getString(R.string.InuBlockedMessagesModeSpoiler),
                    LocaleController.getString(R.string.InuBlockedMessagesModeHide),
                ),
                InuConfig.BLOCKED_MESSAGES_MODE.value,
            ) { which ->
                if (InuConfig.BLOCKED_MESSAGES_MODE.value == which) return@show
                InuConfig.BLOCKED_MESSAGES_MODE.value = which
                listView.adapter.update(true)
            }

            BUTTON_BLOCKED_MESSAGES_EXTRA -> openBlockedExtraPicker()
            BUTTON_PINNED_REACTIONS -> presentFragment(PinnedReactionsActivity())
            BUTTON_DOUBLE_TAP_INCOMING -> showDoubleTapSelector(view, DoubleTapContext.INCOMING)
            BUTTON_DOUBLE_TAP_OUTGOING -> showDoubleTapSelector(view, DoubleTapContext.OUTGOING)
            BUTTON_DOUBLE_TAP_CHANNEL -> showDoubleTapSelector(view, DoubleTapContext.CHANNEL)
        }
    }

    private fun openBlockedExtraPicker() {
        val args = Bundle().apply {
            putBoolean("isNeverShare", true)
            putInt("chatAddType", 2)
            putBoolean("inu_allowChannels", true)
        }
        val fragment = GroupCreateActivity(args)
        fragment.select(ArrayList(BlockedMessagesHelper.getExtraHidden(currentAccount)), false, false)
        fragment.setDelegate { _, _, ids ->
            BlockedMessagesHelper.setExtraHidden(currentAccount, ids)
            listView.adapter.update(true)
        }
        presentFragment(fragment)
    }

    private fun doubleTapConfig(context: DoubleTapContext) = when (context) {
        DoubleTapContext.INCOMING -> InuConfig.DOUBLE_TAP_ACTION_INCOMING
        DoubleTapContext.OUTGOING -> InuConfig.DOUBLE_TAP_ACTION_OUTGOING
        DoubleTapContext.CHANNEL -> InuConfig.DOUBLE_TAP_ACTION_CHANNEL
    }

    private fun showDoubleTapSelector(anchor: View, context: DoubleTapContext) {
        val actions = DoubleTapAction.available(context)
        val config = doubleTapConfig(context)
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

        RadioItemOptions.show(this, anchor, labels, selected) { which ->
            config.value = if (inheritable && which == 0) {
                DoubleTapActionHelper.INHERIT_INCOMING
            } else {
                actions.getOrNull(which - offset)?.value ?: return@show
            }
        }
    }

    private fun channelActionLabel(): CharSequence {
        val value = InuConfig.DOUBLE_TAP_ACTION_CHANNEL.value
        if (value == DoubleTapActionHelper.INHERIT_INCOMING) {
            return LocaleController.getString(R.string.InuSameAsIncoming)
        }
        return DoubleTapAction.fromValue(value, DoubleTapContext.CHANNEL).label()
    }

    companion object {
        private val BUTTON_PINNED_REACTIONS = InuUtils.generateId()
        private val TOGGLE_INSTANT_MARK_REACTIONS_READ = InuUtils.generateId()
        private val TOGGLE_CONFIRM_REACTION_NON_MEMBER = InuUtils.generateId()
        private val TOGGLE_CLOWN_AVATAR_BLOCKED = InuUtils.generateId()
        private val TOGGLE_CLOWN_AVATAR_BLOCKED_ME = InuUtils.generateId()
        private val TOGGLE_CHAT_REMEMBER_ALL_REPLIES = InuUtils.generateId()
        private val TOGGLE_FORWARD_PRO = InuUtils.generateId()
        private val TOGGLE_CONFIRM_SEND_VOICE = InuUtils.generateId()
        private val TOGGLE_CONFIRM_SEND_STICKER = InuUtils.generateId()
        private val TOGGLE_CONFIRM_SEND_GIF = InuUtils.generateId()
        private val SECTION_CONFIRM_SEND = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_INCOMING = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_OUTGOING = InuUtils.generateId()
        private val BUTTON_DOUBLE_TAP_CHANNEL = InuUtils.generateId()
        private val TOGGLE_SHOW_SPOILERS_DIRECTLY = InuUtils.generateId()
        private val BUTTON_TEXT_SPOILER_MODE = InuUtils.generateId()
        private val TOGGLE_SPOILER_EXTEND_TO_LINE_END = InuUtils.generateId()
        private val TOGGLE_LINK_PREVIEW_SPOILER = InuUtils.generateId()
        private val BUTTON_CLOWN_AVATAR_EMOJI = InuUtils.generateId()
        private val BUTTON_MEDIA_SPOILER_MODE = InuUtils.generateId()
        private val BUTTON_BLOCKED_MESSAGES_MODE = InuUtils.generateId()
        private val BUTTON_BLOCKED_MESSAGES_EXTRA = InuUtils.generateId()

        private fun textSpoilerModeLabel(value: Int): String = when (value) {
            InuConfig.TextSpoilerModeItem.SIMPLE -> LocaleController.getString(R.string.InuTextSpoilerModeSimple)
            InuConfig.TextSpoilerModeItem.EPSTEIN -> LocaleController.getString(R.string.InuTextSpoilerModeEpstein)
            else -> LocaleController.getString(R.string.InuTextSpoilerModeDefault)
        }

        private fun clownAvatarEmojiLabel(value: Int): String = when (value) {
            1 -> LocaleController.getString(R.string.InuClownAvatarEmojiPoop)
            2 -> LocaleController.getString(R.string.InuClownAvatarEmojiSkull)
            else -> LocaleController.getString(R.string.InuClownAvatarEmojiClown)
        }

        private fun mediaSpoilerModeLabel(value: Int): String = when (value) {
            InuConfig.MediaSpoilerModeItem.PILL -> LocaleController.getString(R.string.InuMediaSpoilerModePill)
            InuConfig.MediaSpoilerModeItem.CIRCLE -> LocaleController.getString(R.string.InuMediaSpoilerModeCircle)
            else -> LocaleController.getString(R.string.InuMediaSpoilerModeTelegram)
        }

        private fun blockedMessagesModeLabel(value: Int): String = when (value) {
            InuConfig.BlockedMessagesModeItem.SPOILER -> LocaleController.getString(R.string.InuBlockedMessagesModeSpoiler)
            InuConfig.BlockedMessagesModeItem.HIDE -> LocaleController.getString(R.string.InuBlockedMessagesModeHide)
            else -> LocaleController.getString(R.string.InuBlockedMessagesModeOff)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "messages",
            titleRes = R.string.InuMessages,
            iconRes = R.drawable.msg_discussion,
            factory = ::MessagesSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("pinned-reactions", R.string.InuPinnedReactions, BUTTON_PINNED_REACTIONS),
                SearchRegistry.Entry("confirm-reaction-non-member", R.string.InuConfirmReactionNonMember, TOGGLE_CONFIRM_REACTION_NON_MEMBER),
                SearchRegistry.Entry("clown-avatar-blocked", R.string.InuClownAvatarBlocked, TOGGLE_CLOWN_AVATAR_BLOCKED),
                SearchRegistry.Entry("clown-avatar-blocked-me", R.string.InuClownAvatarBlockedMe, TOGGLE_CLOWN_AVATAR_BLOCKED_ME),
                SearchRegistry.Entry("chat-remember-all-replies", R.string.InuChatRememberAllReplies, TOGGLE_CHAT_REMEMBER_ALL_REPLIES),
                SearchRegistry.Entry("instant-mark-reactions-read", R.string.InuInstantMarkReactionsRead, TOGGLE_INSTANT_MARK_REACTIONS_READ),
                SearchRegistry.Entry("forward-pro", R.string.InuForwardPro, TOGGLE_FORWARD_PRO),
                SearchRegistry.Entry("confirm-send-voice", R.string.InuConfirmSendVoice, TOGGLE_CONFIRM_SEND_VOICE),
                SearchRegistry.Entry("confirm-send-sticker", R.string.InuConfirmSendSticker, TOGGLE_CONFIRM_SEND_STICKER),
                SearchRegistry.Entry("confirm-send-gif", R.string.InuConfirmSendGif, TOGGLE_CONFIRM_SEND_GIF),
                SearchRegistry.Entry("double-tap-incoming", R.string.InuIncomingMessages, BUTTON_DOUBLE_TAP_INCOMING),
                SearchRegistry.Entry("double-tap-outgoing", R.string.InuOutgoingMessages, BUTTON_DOUBLE_TAP_OUTGOING),
                SearchRegistry.Entry("double-tap-channel", R.string.InuChannelMessages, BUTTON_DOUBLE_TAP_CHANNEL),
                SearchRegistry.Entry("show-spoilers-directly", R.string.InuShowSpoilersDirectly, TOGGLE_SHOW_SPOILERS_DIRECTLY),
                SearchRegistry.Entry("text-spoiler-mode", R.string.InuTextSpoilerMode, BUTTON_TEXT_SPOILER_MODE),
                SearchRegistry.Entry("spoiler-extend-to-line-end", R.string.InuSpoilerExtendToLineEnd, TOGGLE_SPOILER_EXTEND_TO_LINE_END),
                SearchRegistry.Entry("link-preview-spoiler", R.string.InuLinkPreviewSpoiler, TOGGLE_LINK_PREVIEW_SPOILER),
                SearchRegistry.Entry("clown-avatar-emoji", R.string.InuClownAvatarEmoji, BUTTON_CLOWN_AVATAR_EMOJI),
                SearchRegistry.Entry("media-spoiler-mode", R.string.InuMediaSpoilerMode, BUTTON_MEDIA_SPOILER_MODE),
                SearchRegistry.Entry("blocked-messages-mode", R.string.InuBlockedMessagesMode, BUTTON_BLOCKED_MESSAGES_MODE),
                SearchRegistry.Entry("blocked-messages-extra", R.string.InuBlockedMessagesExtra, BUTTON_BLOCKED_MESSAGES_EXTRA),
            ),
        )
    }
}
