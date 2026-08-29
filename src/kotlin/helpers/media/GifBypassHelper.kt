package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessageChatArguments
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.ChatActivity

// GIFs sent through Telegram are always silent, already-encoded mp4 documents - there's nothing
// to transcode. Chats that ban GIFs (send_stickers, per stock's own bundling of the two) but
// allow video can be sent the exact same file re-flagged as a regular video document instead.
object GifBypassHelper {
    @JvmStatic
    fun shouldBypass(account: Int, peer: Long, document: TLRPC.Document?): Boolean {
        if (!InuConfig.BYPASS_GIF_RESTRICTIONS.value) return false
        if (document == null || !MessageObject.isGifDocument(document)) return false
        if (peer >= 0 || DialogObject.isEncryptedDialog(peer)) return false
        val chat = MessagesController.getInstance(account).getChat(-peer) ?: return false
        return !ChatObject.canSendStickers(chat) && ChatObject.canSendVideo(chat)
    }

    @JvmStatic
    fun sendAsVideo(
        accountInstance: AccountInstance,
        document: TLRPC.Document,
        peer: Long,
        caption: CharSequence?,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        storyItem: TL_stories.StoryItem?,
        quote: ChatActivity.ReplyQuote?,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        sendMessageChatArguments: SendMessageChatArguments?,
        stars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams?,
    ): Boolean {
        val path = resolveLocalPath(accountInstance.currentAccount, document) ?: return false
        SendMessagesHelper.prepareSendingVideo(
            accountInstance, path, null, null, null, peer, replyToMsg, replyToTopMsg, storyItem, quote,
            null, 0, null, notify, scheduleDate, scheduleRepeatPeriod, false, false, caption,
            sendMessageChatArguments, 0L, stars, monoForumPeerId, suggestionParams, false,
        )
        return true
    }

    private fun resolveLocalPath(account: Int, document: TLRPC.Document): String? {
        val loader = FileLoader.getInstance(account)
        for (cache in booleanArrayOf(false, true)) {
            val file = loader.getPathToAttach(document, cache)
            if (file != null && file.exists() && file.length() > 0) return file.absolutePath
        }
        return null
    }
}
