package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.tgnet.TLRPC

// entiny: separator appended after an inserted @mention; empty config = stock space; bots are opt-in.
object MentionHelper {

    @JvmStatic
    fun separator(user: TLRPC.User?): String {
        var sep = InuConfig.MENTION_SEPARATOR.value
        if (user != null && user.bot && !InuConfig.MENTION_SEPARATOR_FOR_BOTS.value) return " "
        if (sep.isEmpty()) return " "
        if (!sep.endsWith(" ")) sep += " "
        return sep
    }
}
