package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.VideoPlayer

object VideoPlaybackHelper {
    private fun forceOriginal(): Boolean = InuConfig.PLAY_ORIGINAL_VIDEO.value

    @JvmStatic
    fun forceInlineOriginal(): Boolean =
        InuConfig.PLAY_ORIGINAL_VIDEO.value && InuConfig.PLAY_ORIGINAL_VIDEO_INLINE.value

    @JvmStatic
    fun inu_initialQuality(
        qualities: ArrayList<VideoPlayer.Quality>?,
        messageObject: MessageObject?,
    ): VideoPlayer.Quality? {
        if (qualities == null || qualities.isEmpty()) return null
        val saved = if (messageObject == null) null else VideoPlayer.getSavedQuality(qualities, messageObject)
        if (saved != null) return saved
        if (!forceOriginal()) return null
        for (q in qualities) {
            if (q.original) return q
        }
        return null
    }

    @JvmStatic
    fun inu_inlineDocument(messageObject: MessageObject?, fallback: TLRPC.Document?): TLRPC.Document? {
        if (!forceInlineOriginal() || messageObject?.messageOwner?.media?.document == null) return fallback
        return messageObject.messageOwner.media.document
    }
}
