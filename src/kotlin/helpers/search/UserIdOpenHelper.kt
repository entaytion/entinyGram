package desu.inugram.helpers.search

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
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

    @JvmStatic
    fun showOpenByIdDialog(context: Context, fragment: BaseFragment?, currentAccount: Int = UserConfig.selectedAccount) {
        if (fragment == null) return
        val builder = AlertDialog.Builder(context)
        builder.setTitle(LocaleController.getString(R.string.InuOpenById))

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(10f), AndroidUtilities.dp(24f), AndroidUtilities.dp(10f))
        }

        val editText = EditTextBoldCursor(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setCursorColor(Theme.getColor(Theme.key_dialogTextBlack))
            setCursorSize(AndroidUtilities.dp(20f))
            setCursorWidth(1.5f)
            hint = LocaleController.getString(R.string.InuOpenByIdPrompt)
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            background = null
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
        }

        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        builder.setView(container)

        builder.setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
            val input = editText.text?.toString()
            val parsedId = parseUserId(input)
            if (parsedId != null && parsedId > 0) {
                openProfile(fragment, parsedId, currentAccount)
            }
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        val dialog = builder.create()
        fragment.showDialog(dialog)
        editText.requestFocus()
        AndroidUtilities.showKeyboard(editText)
    }
}
