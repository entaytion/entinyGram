package desu.inugram.helpers

import android.content.Context
import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.tgnet.TLRPC

/**
 * Local (fake) Telegram Premium state layer.
 *
 * When [desu.inugram.InuConfig.LOCAL_PREMIUM] is on, the server still rejects
 * premium-only writes (emoji status, profile/name color), so anything the user
 * sets is stored here per-account and re-applied to the self user object every
 * time it arrives from the server (`MessagesController.putUser`) or is loaded
 * from config (`UserConfig.setCurrentUser`).
 *
 * All methods are no-ops when local premium is disabled — the app stays
 * stock-identical.
 */
public object LocalPremiumHelper {

    private const val PREFS = "inupremium_local"

    private const val KEY_EMOJI_DOC = "emoji_doc_"
    private const val KEY_EMOJI_UNTIL = "emoji_until_"
    private const val KEY_EMOJI_CLEARED = "emoji_cleared_"
    private const val KEY_NAME_COLOR = "name_color_"
    private const val KEY_NAME_BG_EMOJI = "name_bg_emoji_"
    private const val KEY_PROFILE_COLOR = "profile_color_"
    private const val KEY_PROFILE_BG_EMOJI = "profile_bg_emoji_"

    private data class UserOverrides(
        /** >0 document id; -1 explicit clear; 0 nothing stored */
        val emojiDocId: Long = 0L,
        val emojiUntil: Int = 0,
        /** -1 not stored */
        val nameColor: Int = -1,
        val nameBgEmoji: Long = 0L,
        /** -1 not stored */
        val profileColor: Int = -1,
        val profileBgEmoji: Long = 0L,
    )

    private val overrides = HashMap<Int, UserOverrides>()

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun cached(accountId: Int): UserOverrides {
        overrides[accountId]?.let { return it }
        val p = prefs()
        val o = UserOverrides(
            emojiDocId = if (p.getBoolean(KEY_EMOJI_CLEARED + accountId, false)) -1L else p.getLong(KEY_EMOJI_DOC + accountId, 0L),
            emojiUntil = p.getInt(KEY_EMOJI_UNTIL + accountId, 0),
            nameColor = p.getInt(KEY_NAME_COLOR + accountId, -1),
            nameBgEmoji = p.getLong(KEY_NAME_BG_EMOJI + accountId, 0L),
            profileColor = p.getInt(KEY_PROFILE_COLOR + accountId, -1),
            profileBgEmoji = p.getLong(KEY_PROFILE_BG_EMOJI + accountId, 0L),
        )
        overrides[accountId] = o
        return o
    }

    /**
     * Re-applies the local premium state onto the self user whenever it arrives
     * from the server or is loaded from config. Hot path — keep it allocation-free
     * for non-self users.
     */
    @JvmStatic
    fun applyToSelfUser(user: TLRPC.User?, accountId: Int) {
        if (user == null || !user.self) return
        if (!InuConfig.LOCAL_PREMIUM.value) return
        user.premium = true
        val o = cached(accountId)

        val storedDoc = o.emojiDocId
        if (storedDoc > 0L) {
            val s = TLRPC.TL_emojiStatus()
            s.document_id = storedDoc
            if (o.emojiUntil > 0) {
                s.flags = 1
                s.until = o.emojiUntil
            }
            user.emoji_status = s
        } else if (storedDoc == -1L) {
            user.emoji_status = TLRPC.TL_emojiStatusEmpty()
        }

        if (o.nameColor >= 0) {
            val c = TLRPC.TL_peerColor()
            c.flags = 1
            c.color = o.nameColor
            if (o.nameBgEmoji > 0L) {
                c.flags = c.flags or 2
                c.background_emoji_id = o.nameBgEmoji
            }
            user.color = c
            user.flags2 = user.flags2 or 256
        }

        if (o.profileColor >= 0) {
            val c = TLRPC.TL_peerColor()
            c.flags = 1
            c.color = o.profileColor
            if (o.profileBgEmoji > 0L) {
                c.flags = c.flags or 2
                c.background_emoji_id = o.profileBgEmoji
            }
            user.profile_color = c
            user.flags2 = user.flags2 or 512
        }
    }

    /** Persists the emoji status the user just picked so it survives reloads. */
    @JvmStatic
    fun persistEmojiStatus(status: TLRPC.EmojiStatus?, accountId: Int) {
        if (!InuConfig.LOCAL_PREMIUM.value) return
        val docId = status?.let { DialogObject.getEmojiStatusDocumentId(it) } ?: 0L
        val until = status?.let { DialogObject.getEmojiStatusUntil(it) } ?: 0
        val editor = prefs().edit()
        if (docId == 0L) {
            editor.putBoolean(KEY_EMOJI_CLEARED + accountId, true)
            editor.remove(KEY_EMOJI_DOC + accountId)
        } else {
            editor.putBoolean(KEY_EMOJI_CLEARED + accountId, false)
            editor.putLong(KEY_EMOJI_DOC + accountId, docId)
            editor.putInt(KEY_EMOJI_UNTIL + accountId, until)
        }
        editor.apply()
        synchronized(this) {
            overrides.remove(accountId)
        }
    }

    /** Persists the name/profile colors the user just applied in PeerColorActivity. */
    @JvmStatic
    fun onSelfColorsApplied(me: TLRPC.User?, accountId: Int) {
        if (me == null || !InuConfig.LOCAL_PREMIUM.value) return
        val editor = prefs().edit()
        val color = me.color
        if (color is TLRPC.TL_peerColor && (color.flags and 1) != 0) {
            editor.putInt(KEY_NAME_COLOR + accountId, color.color)
            if ((color.flags and 2) != 0) {
                editor.putLong(KEY_NAME_BG_EMOJI + accountId, color.background_emoji_id)
            } else {
                editor.putLong(KEY_NAME_BG_EMOJI + accountId, 0L)
            }
        } else {
            editor.remove(KEY_NAME_COLOR + accountId)
            editor.remove(KEY_NAME_BG_EMOJI + accountId)
        }
        val profileColor = me.profile_color
        if (profileColor is TLRPC.TL_peerColor && (profileColor.flags and 1) != 0) {
            editor.putInt(KEY_PROFILE_COLOR + accountId, profileColor.color)
            if ((profileColor.flags and 2) != 0) {
                editor.putLong(KEY_PROFILE_BG_EMOJI + accountId, profileColor.background_emoji_id)
            } else {
                editor.putLong(KEY_PROFILE_BG_EMOJI + accountId, 0L)
            }
        } else {
            editor.remove(KEY_PROFILE_COLOR + accountId)
            editor.remove(KEY_PROFILE_BG_EMOJI + accountId)
        }
        editor.apply()
        synchronized(this) {
            overrides.remove(accountId)
        }
    }
}
