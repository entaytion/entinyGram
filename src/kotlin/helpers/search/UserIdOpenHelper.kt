package desu.inugram.helpers.search

import android.os.Bundle
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ProfileActivity

object UserIdOpenHelper {

    private val ID_PREFIX_REGEX = Regex("^(?:id[:= ]?|tg://user\\?id=)?(\\d{5,15})$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun parseUserId(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val match = ID_PREFIX_REGEX.matchEntire(trimmed)
        if (match != null) {
            val idStr = match.groupValues[1]
            return idStr.toLongOrNull()?.takeIf { it in 1000L..999_999_999_999L }
        }
        if (trimmed.all { it.isDigit() } && trimmed.length in 5..14) {
            return trimmed.toLongOrNull()?.takeIf { it in 1000L..999_999_999_999L }
        }
        return null
    }

    @JvmStatic
    fun resolveUser(currentAccount: Int, userId: Long): TLRPC.User {
        val controller = MessagesController.getInstance(currentAccount)
        val cached = controller.getUser(userId)
        if (cached != null) return cached

        val storage = MessagesStorage.getInstance(currentAccount)
        val stored = storage.getUser(userId)
        if (stored != null) {
            controller.putUser(stored, true)
            return stored
        }

        val synthetic = TLRPC.TL_user().apply {
            id = userId
            first_name = "ID: $userId"
            status = TLRPC.TL_userStatusEmpty()
            access_hash = 0L
        }
        controller.putUser(synthetic, true)
        return synthetic
    }

    @JvmStatic
    fun openProfile(fragment: BaseFragment?, userId: Long, currentAccount: Int = UserConfig.selectedAccount) {
        if (fragment == null || userId <= 0) return
        resolveUser(currentAccount, userId)
        val args = Bundle().apply {
            putLong("user_id", userId)
        }
        fragment.presentFragment(ProfileActivity(args))
    }

}
