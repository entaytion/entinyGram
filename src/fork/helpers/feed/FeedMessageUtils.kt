package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import java.util.Calendar

object FeedMessageUtils {

    const val SEARCH_TYPE_FEED = 4

    private const val CONTENT_TYPE_ACTION = 1
    private const val CONTENT_TYPE_UNREAD_DIVIDER = 2

    private const val PRIVATE_CHANNEL_LINK_MARKER = "/c/"

    private val EXTRA_ALLOWED_FEED_OPTIONS = intArrayOf(36, 200, 203, 206)

    @JvmStatic
    fun copyFeedPostLink(chatActivity: ChatActivity?, message: MessageObject?) {
        if (chatActivity == null || message == null) {
            return
        }
        val chat = chatActivity.getMessagesController().getChat(-message.dialogId)
        if (!ChatObject.isChannel(chat)) {
            return
        }
        val request = TLRPC.TL_channels_exportMessageLink()
        request.id = message.realId
        request.channel = MessagesController.getInputChannel(chat)
        chatActivity.getConnectionsManager().sendRequest(request, RequestDelegate { response, _ ->
            AndroidUtilities.runOnUIThread { onPostLinkExported(response, chatActivity) }
        })
    }

    @JvmStatic
    fun copyTranslationState(from: MessageObject?, to: MessageObject?) {
        if (from == null || to == null || from === to) {
            return
        }
        val source = from.messageOwner
        val target = to.messageOwner
        if (source == null || target == null) {
            return
        }
        target.translatedText = source.translatedText
        target.translatedToLanguage = source.translatedToLanguage
        target.translatedVoiceTranscription = source.translatedVoiceTranscription
        target.translatedPoll = source.translatedPoll
        target.summaryText = source.summaryText
        target.summarizedOpen = source.summarizedOpen
        target.translatedSummaryText = source.translatedSummaryText
        target.translatedSummaryLanguage = source.translatedSummaryLanguage
    }

    @JvmStatic
    fun createDateHeader(currentAccount: Int, message: MessageObject, stableId: Int): MessageObject {
        val header = TLRPC.TL_message()
        header.message = LocaleController.formatDateChat(message.messageOwner.date.toLong())
        header.id = 0

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = message.messageOwner.date * 1000L
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        header.date = (calendar.timeInMillis / 1000L).toInt()

        val result = MessageObject(currentAccount, header, false, false)
        result.type = MessageObject.TYPE_DATE
        result.contentType = CONTENT_TYPE_ACTION
        result.isDateObject = true
        result.stableId = stableId
        return result
    }

    @JvmStatic
    fun createReplacement(currentAccount: Int, dialogId: Long, original: MessageObject?): MessageObject? {
        if (original == null) {
            return null
        }
        val feedController = FeedController.getInstance(currentAccount)
        val stored = feedController.getMessage(dialogId, original.realId) ?: return null
        val copy = copyMessage(original.messageOwner)
        copy.id = stored.id
        copy.realId = stored.realId
        copy.dialog_id = stored.dialogId

        val replacement = MessageObject(
            currentAccount, copy, stored.replyMessageObject,
            null, null, null, null, true, true, 0L, false, false, false, SEARCH_TYPE_FEED
        )
        replacement.isPrimaryGroupMessage = stored.isPrimaryGroupMessage
        replacement.localGroupId = stored.localGroupId
        replacement.copyStableParams(stored)
        feedController.replaceMessage(stored, replacement)
        return replacement
    }

    @JvmStatic
    fun createReplacements(currentAccount: Int, dialogId: Long, originals: ArrayList<MessageObject>): ArrayList<MessageObject> {
        val result = ArrayList<MessageObject>()
        for (i in originals.indices) {
            val replacement = createReplacement(currentAccount, dialogId, originals[i])
            if (replacement != null) {
                result.add(replacement)
            }
        }
        return result
    }

    @JvmStatic
    fun createUnreadDivider(currentAccount: Int, stableId: Int): MessageObject {
        val divider = TLRPC.TL_message()
        divider.message = ""
        divider.id = 0

        val result = MessageObject(currentAccount, divider, false, false)
        result.type = MessageObject.TYPE_LOADING
        result.contentType = CONTENT_TYPE_UNREAD_DIVIDER
        result.stableId = stableId
        return result
    }

    @JvmStatic
    fun filterAllowedOptions(items: ArrayList<CharSequence>, options: ArrayList<Int>, icons: ArrayList<Int>) {
        for (i in options.size - 1 downTo 0) {
            if (!isAllowedFeedOption(options[i])) {
                icons.removeAt(i)
                items.removeAt(i)
                options.removeAt(i)
            }
        }
    }

    @JvmStatic
    fun getForwardingMessageObject(currentAccount: Int, isFeedSearch: Boolean, message: MessageObject?): MessageObject? {
        if (!isFeedSearch || message == null || message.id == message.realId) {
            return message
        }
        val copy = copyMessage(message.messageOwner)
        copy.id = message.realId
        copy.realId = 0
        copy.dialog_id = message.dialogId

        val result = MessageObject(
            currentAccount, copy, message.replyMessageObject,
            null, null, null, null, false, true, 0L, false, false, false
        )
        result.isPrimaryGroupMessage = message.isPrimaryGroupMessage
        result.localGroupId = message.localGroupId
        result.copyStableParams(message)
        return result
    }

    @JvmStatic
    fun getInputPeerForMessageRequest(
        messagesController: MessagesController,
        dialogId: Long,
        isFeedSearch: Boolean,
        message: MessageObject?,
    ): TLRPC.InputPeer? {
        var resolvedDialogId = dialogId
        if (isFeedSearch && message != null) {
            resolvedDialogId = message.dialogId
        }
        return messagesController.getInputPeer(resolvedDialogId)
    }

    @JvmStatic
    fun getPlaybackScrollMessageId(isFeedSearch: Boolean, dialogId: Long, playingMessage: MessageObject?): Int {
        if (playingMessage == null) {
            return 0
        }
        if (!isFeedSearch && playingMessage.searchType == SEARCH_TYPE_FEED && playingMessage.dialogId == dialogId) {
            return playingMessage.realId
        }
        return playingMessage.id
    }

    @JvmStatic
    fun isAllowedFeedOption(option: Int): Boolean = when (option) {
        ChatActivity.OPTION_FORWARD,
        ChatActivity.OPTION_COPY,
        ChatActivity.OPTION_SAVE_TO_GALLERY,
        ChatActivity.OPTION_SHARE,
        ChatActivity.OPTION_SAVE_TO_GALLERY2,
        ChatActivity.OPTION_REPLY,
        ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC,
        ChatActivity.OPTION_COPY_PHONE_NUMBER,
        ChatActivity.OPTION_COPY_LINK,
        ChatActivity.OPTION_TRANSLATE -> true
        else -> EXTRA_ALLOWED_FEED_OPTIONS.contains(option)
    }

    @JvmStatic
    fun isPostRow(message: MessageObject?): Boolean =
        message != null &&
            !message.isDateObject &&
            message.type != MessageObject.TYPE_LOADING &&
            !message.isSponsored

    @JvmStatic
    fun matchesPlaybackNotification(currentAccount: Int, message: MessageObject?, messageId: Int): Boolean {
        if (message == null) {
            return false
        }
        if (message.id == messageId) {
            return true
        }
        val feedController = FeedController.peekInstance(currentAccount) ?: return false
        val realDialogId = feedController.resolveRealDialogId(messageId)
        return realDialogId != 0L &&
            realDialogId == message.dialogId &&
            feedController.resolveRealMessageId(realDialogId, messageId) == getFeedRealId(message)
    }

    private fun getFeedRealId(message: MessageObject): Int =
        if (message.searchType == SEARCH_TYPE_FEED) message.realId else message.id

    private fun onPostLinkExported(response: TLObject?, chatActivity: ChatActivity) {
        if (response !is TLRPC.TL_exportedMessageLink) {
            return
        }
        val link = response.link
        if (AndroidUtilities.addToClipboard(link) && BulletinFactory.canShowBulletin(chatActivity)) {
            BulletinFactory.of(chatActivity).createCopyLinkBulletin(link.contains(PRIVATE_CHANNEL_LINK_MARKER)).show()
        }
    }

    private fun copyMessage(message: TLRPC.Message): TLRPC.TL_message {
        val copy = TLRPC.TL_message()
        copy.id = message.id
        copy.from_id = message.from_id
        copy.from_boosts_applied = message.from_boosts_applied
        copy.peer_id = message.peer_id
        copy.saved_peer_id = message.saved_peer_id
        copy.date = message.date
        copy.expire_date = message.expire_date
        copy.action = message.action
        copy.message = message.message
        copy.media = message.media
        copy.flags = message.flags
        copy.flags2 = message.flags2
        copy.mentioned = message.mentioned
        copy.media_unread = message.media_unread
        copy.out = message.out
        copy.unread = message.unread
        copy.entities = message.entities
        copy.via_bot_name = message.via_bot_name
        copy.reply_markup = message.reply_markup
        copy.views = message.views
        copy.forwards = message.forwards
        copy.replies = message.replies
        copy.edit_date = message.edit_date
        copy.silent = message.silent
        copy.post = message.post
        copy.from_scheduled = message.from_scheduled
        copy.legacy = message.legacy
        copy.edit_hide = message.edit_hide
        copy.pinned = message.pinned
        copy.fwd_from = message.fwd_from
        copy.via_bot_id = message.via_bot_id
        copy.via_business_bot_id = message.via_business_bot_id
        copy.reply_to = message.reply_to
        copy.post_author = message.post_author
        copy.grouped_id = message.grouped_id
        copy.reactions = message.reactions
        copy.restriction_reason = message.restriction_reason
        copy.ttl_period = message.ttl_period
        copy.quick_reply_shortcut_id = message.quick_reply_shortcut_id
        copy.effect = message.effect
        copy.noforwards = message.noforwards
        copy.invert_media = message.invert_media
        copy.offline = message.offline
        copy.factcheck = message.factcheck
        copy.send_state = message.send_state
        copy.fwd_msg_id = message.fwd_msg_id
        copy.params = message.params
        copy.random_id = message.random_id
        copy.local_id = message.local_id
        copy.attachPath = message.attachPath
        copy.dialog_id = message.dialog_id
        copy.ttl = message.ttl
        copy.destroyTime = message.destroyTime
        copy.destroyTimeMillis = message.destroyTimeMillis
        copy.layer = message.layer
        copy.seq_in = message.seq_in
        copy.seq_out = message.seq_out
        copy.with_my_score = message.with_my_score
        copy.replyMessage = message.replyMessage
        copy.reqId = message.reqId
        copy.realId = message.realId
        copy.stickerVerified = message.stickerVerified
        copy.isThreadMessage = message.isThreadMessage
        copy.voiceTranscription = message.voiceTranscription
        copy.voiceTranscriptionOpen = message.voiceTranscriptionOpen
        copy.voiceTranscriptionRated = message.voiceTranscriptionRated
        copy.voiceTranscriptionFinal = message.voiceTranscriptionFinal
        copy.voiceTranscriptionForce = message.voiceTranscriptionForce
        copy.voiceTranscriptionId = message.voiceTranscriptionId
        copy.premiumEffectWasPlayed = message.premiumEffectWasPlayed
        copy.originalLanguage = message.originalLanguage
        copy.translatedToLanguage = message.translatedToLanguage
        copy.translatedText = message.translatedText
        copy.replyStory = message.replyStory
        copy.quick_reply_shortcut = message.quick_reply_shortcut
        return copy
    }
}
