package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig

object ChatListAvatarHelper {
    // entiny: the avatar shrinks inside its original slot, so text, badges and row height keep their places
    @JvmStatic
    fun inset(slotPx: Int): Float = slotPx * (1f - scale()) / 2f

    @JvmStatic
    fun radius(radiusPx: Int): Int = Math.round(radiusPx * scale())

    private fun scale(): Float = InuConfig.CHAT_LIST_AVATAR_PERCENT.value.coerceIn(60, 100) / 100f
}
