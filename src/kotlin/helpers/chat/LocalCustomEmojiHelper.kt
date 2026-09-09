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

/**
 * Premium custom emoji for non-premium accounts, carried as message metadata.
 *
 * The server refuses `messageEntityCustomEmoji` from a non-premium account, but it
 * happily accepts a plain text link. So on send every premium custom emoji entity is
 * rewritten into a `tg://emoji?id=<document_id>` text-url over the same fallback emoji
 * character, and on render any such link is turned back into an animated emoji span.
 *
 * Stock clients see the fallback emoji as a harmless link; entinyGram/Nekogram clients
 * see the animated emoji. Nothing about it is server-side, hence "local".
 *
 * All entry points no-op when [desu.inugram.InuConfig.LOCAL_CUSTOM_EMOJI] is off, so
 * default-off is stock-identical.
 */
object LocalCustomEmojiHelper {

    private const val LINK_PREFIX = "tg://emoji?id="

    /** True for a text-url entity that carries a custom emoji id instead of a real link. */
    @JvmStatic
    fun isLocalCustomEmoji(entity: TLRPC.MessageEntity?): Boolean {
        if (!InuConfig.LOCAL_CUSTOM_EMOJI.value) return false
        if (entity !is TLRPC.TL_messageEntityTextUrl) return false
        val url = entity.url ?: return false
        return url.length > LINK_PREFIX.length && url.startsWith(LINK_PREFIX)
    }

    /**
     * Converts a local custom emoji link back into the custom emoji entity it encodes.
     *
     * Returns null unless the link spans exactly one emoji character — that keeps a
     * hand-written `tg://emoji?id=` link over arbitrary text from swallowing that text
     * into an emoji span.
     */
    @JvmStatic
    fun parseLocalCustomEmoji(spannable: Spannable, entity: TLRPC.MessageEntity): TLRPC.TL_messageEntityCustomEmoji? {
        if (!isLocalCustomEmoji(entity)) return null
        if (entity.offset < 0 || entity.length <= 0) return null
        if (spannable.length < entity.offset + entity.length) return null
        val documentId = entity.url.substring(LINK_PREFIX.length).toLongOrNull() ?: return null
        val emojiOnly = IntArray(1)
        val emojis = Emoji.parseEmojis(spannable.subSequence(entity.offset, entity.offset + entity.length), emojiOnly)
        if (emojiOnly[0] <= 0 || emojis.size != 1) return null
        val parsed = TLRPC.TL_messageEntityCustomEmoji()
        parsed.offset = entity.offset
        parsed.length = entity.length
        parsed.document_id = documentId
        return parsed
    }

    /**
     * Whether outgoing custom emoji have to be smuggled as links for [account].
     *
     * A genuinely premium account sends them natively, so it is left alone. Local premium
     * spoofs [UserConfig.isPremium], so while that toggle is on the account is treated as
     * non-premium here — that is the whole point of the pairing.
     */
    @JvmStatic
    fun canSendLocalCustomEmoji(account: Int): Boolean {
        if (!InuConfig.LOCAL_CUSTOM_EMOJI.value) return false
        return InuConfig.LOCAL_PREMIUM.value || !UserConfig.getInstance(account).isPremium()
    }

    /**
     * Rewrites premium custom emoji entities into local custom emoji links.
     *
     * Returns [entities] itself when nothing needs rewriting, so the common path stays
     * allocation-free. Free emoji, group-pack emoji and Saved Messages are left alone —
     * the server accepts real custom emoji entities there from anyone.
     */
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

    /** Document ids of the emoji pack a group has attached, if any — those are free to send there. */
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
