package desu.inugram.helpers.chat

import android.text.Spannable
import desu.inugram.InuConfig
import org.telegram.messenger.Emoji
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AnimatedEmojiDrawable

object LocalCustomEmojiHelper {

    private const val LINK_PREFIX = "tg://emoji?id="

    @JvmStatic
    fun isLocalCustomEmoji(entity: TLRPC.MessageEntity?): Boolean {
        if (!InuConfig.LOCAL_CUSTOM_EMOJI.value) return false
        if (entity !is TLRPC.TL_messageEntityTextUrl) return false
        val url = entity.url ?: return false
        return url.length > LINK_PREFIX.length && url.startsWith(LINK_PREFIX)
    }

    @JvmStatic
    fun parseLocalCustomEmoji(spannable: Spannable, entity: TLRPC.MessageEntity): TLRPC.TL_messageEntityCustomEmoji? {
        if (!isLocalCustomEmoji(entity)) return null
        if (entity.offset < 0 || entity.length <= 0) return null
        if (spannable.length < entity.offset + entity.length) return null
        val documentId = entity.url.substring(LINK_PREFIX.length).toLongOrNull() ?: return null
        // entiny: validate span boundaries directly because parseEmojis emojiOnly breaks on keycap digits
        val range = spannable.subSequence(entity.offset, entity.offset + entity.length)
        val emojis = Emoji.parseEmojis(range)
        if (emojis.size != 1) return null
        val span = emojis[0]
        if (span.start != 0 || span.end != range.length) return null
        val parsed = TLRPC.TL_messageEntityCustomEmoji()
        parsed.offset = entity.offset
        parsed.length = entity.length
        parsed.document_id = documentId
        return parsed
    }

    @JvmStatic
    fun canSendLocalCustomEmoji(account: Int): Boolean {
        if (!InuConfig.LOCAL_CUSTOM_EMOJI.value) return false
        return InuConfig.LOCAL_PREMIUM.value || !UserConfig.getInstance(account).isPremium()
    }

    @JvmStatic
    fun replaceCustomEmojis(
        account: Int,
        dialogId: Long,
        entities: ArrayList<TLRPC.MessageEntity>?,
    ): ArrayList<TLRPC.MessageEntity>? {
        if (entities.isNullOrEmpty() || !canSendLocalCustomEmoji(account)) return entities
        if (dialogId == UserConfig.getInstance(account).clientUserId) return entities
        var groupEmojis: Set<Long>? = null
        var groupEmojisLoaded = false
        var result: ArrayList<TLRPC.MessageEntity>? = null
        for (i in entities.indices) {
            val entity = entities[i] as? TLRPC.TL_messageEntityCustomEmoji ?: continue
            if (!groupEmojisLoaded) {
                groupEmojis = groupEmojiIds(account, dialogId)
                groupEmojisLoaded = true
            }
            if (groupEmojis != null && groupEmojis.contains(entity.document_id)) continue
            val document = entity.document ?: AnimatedEmojiDrawable.findDocument(account, entity.document_id)
            if (MessageObject.isFreeEmoji(document)) continue
            if (result == null) result = ArrayList(entities)
            result[i] = toLink(entity)
        }
        return result ?: entities
    }

    private fun groupEmojiIds(account: Int, dialogId: Long): Set<Long>? {
        if (dialogId > 0) return null
        val chatFull = MessagesController.getInstance(account).getChatFull(-dialogId) ?: return null
        val emojiSet = chatFull.emojiset ?: return null
        val stickerSet = MediaDataController.getInstance(account).getGroupStickerSetById(emojiSet) ?: return null
        val documents = stickerSet.documents ?: return null
        return documents.mapTo(HashSet<Long>(documents.size)) { it.id }
    }

    private fun toLink(entity: TLRPC.TL_messageEntityCustomEmoji): TLRPC.TL_messageEntityTextUrl {
        val link = TLRPC.TL_messageEntityTextUrl()
        link.offset = entity.offset
        link.length = entity.length
        link.url = LINK_PREFIX + entity.document_id
        return link
    }
}
