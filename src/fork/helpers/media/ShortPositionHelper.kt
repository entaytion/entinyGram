package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject

object ShortPositionHelper {
    private const val SHORT_SECONDS = 10

    @JvmStatic
    fun minSeconds(stock: Int): Int = if (InuConfig.REMEMBER_SHORT_POSITION.value) minOf(stock, SHORT_SECONDS) else stock

    @JvmStatic
    fun remembersShortVoice(message: MessageObject): Boolean =
        InuConfig.REMEMBER_SHORT_POSITION.value && message.isVoice && message.duration >= SHORT_SECONDS && message.duration < 5 * 60
}
