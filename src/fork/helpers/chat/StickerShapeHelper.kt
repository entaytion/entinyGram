package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SharedConfig

object StickerShapeHelper {
    const val DEFAULT = 0
    const val ROUNDED = 1
    const val MESSAGE = 2

    @JvmStatic
    fun apply(image: ImageReceiver, message: MessageObject) {
        when (InuConfig.STICKER_SHAPE.value) {
            ROUNDED -> image.setRoundRadius(AndroidUtilities.dp(12f))
            MESSAGE -> {
                val big = AndroidUtilities.dp(SharedConfig.bubbleRadius.toFloat())
                val small = big / 3
                if (message.isOutOwner) image.setRoundRadius(big, big, small, big)
                else image.setRoundRadius(big, big, big, small)
            }
            else -> image.setRoundRadius(0)
        }
    }
}
