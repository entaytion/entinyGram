package desu.inugram.helpers.chat

import desu.inugram.InuConfig

object ReplyBackgroundHelper {

    @JvmStatic
    fun shouldDrawBackground(): Boolean = !InuConfig.HIDE_REPLY_BACKGROUND.value

    @JvmStatic
    fun shouldDrawEmoji(): Boolean = !InuConfig.HIDE_REPLY_BACKGROUND_EMOJI.value

    @JvmStatic
    fun shouldAllowCustomColors(): Boolean = !InuConfig.DISABLE_REPLY_CUSTOM_COLORS.value
}

