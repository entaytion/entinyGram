package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject

object VideoMessageHelper {
    @JvmStatic
    fun shouldAutoplayRoundVideo(playingMessage: MessageObject?): Boolean {
        if (InuConfig.DISABLE_VIDEO_MESSAGE_AUTOPLAY.value) return false
        return playingMessage == null || !playingMessage.isRoundVideo
    }

    @JvmStatic
    fun shouldAutoplayRoundPreview(): Boolean =
        !InuConfig.DISABLE_VIDEO_MESSAGE_AUTOPLAY.value
}
