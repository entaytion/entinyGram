package desu.inugram.helpers.profile

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import desu.inugram.InuConfig
import org.telegram.messenger.Emoji
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC

object ClownAvatarHelper {
    private val EMOJIS = arrayOf("🤡", "💩", "💀")
    private const val ICON_SCALE = 0.65f
    private val drawables = arrayOfNulls<Drawable>(EMOJIS.size)

    @JvmStatic
    fun isClown(account: Int, id: Long): Boolean {
        if (id <= 0 || !InuConfig.CLOWN_AVATAR_BLOCKED.value) return false
        return MessagesController.getInstance(account).blockePeers.indexOfKey(id) >= 0
    }

    @JvmStatic
    fun isLikelyBlockedMe(user: TLRPC.User?): Boolean {
        if (user == null || user.id <= 0 || !InuConfig.CLOWN_AVATAR_BLOCKED_ME.value) return false
        return !UserObject.isDeleted(user) && !user.self && !user.bot && !user.support && !user.min &&
            user.status == null && user.photo == null && !user.apply_min_photo && !user.stories_unavailable
    }

    @JvmStatic
    fun draw(canvas: Canvas, size: Int, alpha: Int, isProfile: Boolean, scaleSize: Float): Boolean {
        val mode = InuConfig.CLOWN_AVATAR_EMOJI.value.coerceIn(0, EMOJIS.size - 1)
        val d = drawables[mode] ?: Emoji.getEmojiDrawable(EMOJIS[mode])?.also { drawables[mode] = it } ?: return false
        val side = (size * ICON_SCALE * if (isProfile) scaleSize else 1f).toInt()
        val x = (size - side) / 2
        val y = (size - side) / 2
        d.setBounds(x, y, x + side, y + side)
        d.alpha = alpha
        d.draw(canvas)
        d.alpha = 255
        return true
    }
}
