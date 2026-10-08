package desu.inugram.helpers.profile

import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment

object ContactChangesHelper {
    const val FIELD_FIRST_NAME = "first_name"
    const val FIELD_LAST_NAME = "last_name"
    const val FIELD_USERNAME = "username"
    const val FIELD_BIO = "bio"
    const val FIELD_PHOTO = "photo"

    @JvmStatic
    fun onUserChanged(account: Int, old: TLRPC.User?, new: TLRPC.User) {
        if (!InuConfig.SAVE_USER_INFO.value || old == null || old === new || old is TLRPC.TL_userEmpty || new is TLRPC.TL_userEmpty) return
        val date = (System.currentTimeMillis() / 1000L).toInt()
        val changes = ArrayList<Triple<String, String?, String?>>()
        if (old.first_name.orEmpty() != new.first_name.orEmpty()) changes.add(Triple(FIELD_FIRST_NAME, old.first_name, new.first_name))
        if (old.last_name.orEmpty() != new.last_name.orEmpty()) changes.add(Triple(FIELD_LAST_NAME, old.last_name, new.last_name))
        if (old.username.orEmpty() != new.username.orEmpty()) changes.add(Triple(FIELD_USERNAME, old.username, new.username))
        if ((old.photo?.photo_id ?: 0L) != (new.photo?.photo_id ?: 0L)) changes.add(Triple(FIELD_PHOTO, null, null))
        write(account, new.id, changes, date)
    }

    @JvmStatic
    fun onFullUserChanged(account: Int, userId: Long, old: TLRPC.UserFull?, new: TLRPC.UserFull?) {
        if (!InuConfig.SAVE_USER_INFO.value || old == null || new == null) return
        if (old.about.orEmpty() == new.about.orEmpty()) return
        val date = (System.currentTimeMillis() / 1000L).toInt()
        write(account, userId, listOf(Triple(FIELD_BIO, old.about, new.about)), date)
    }

    private fun write(account: Int, userId: Long, changes: List<Triple<String, String?, String?>>, date: Int) {
        if (changes.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            for ((field, oldValue, newValue) in changes) {
                InuDatabaseHelper.saveContactChange(db, userId, field, oldValue, newValue, date)
            }
        }
    }

    @JvmStatic
    fun showLog(fragment: BaseFragment, account: Int, userId: Long) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val rows = InuDatabaseHelper.loadContactChanges(db, userId)
            AndroidUtilities.runOnUIThread {
                val activity = fragment.parentActivity ?: return@runOnUIThread
                val text = if (rows.isEmpty()) {
                    LocaleController.getString(R.string.InuContactChangesEmpty)
                } else {
                    rows.joinToString("\n\n") { formatRow(it) }
                }
                AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.InuContactChanges))
                    .setMessage(text)
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show()
            }
        }
    }

    private fun formatRow(row: InuDatabaseHelper.ContactChangeRow): String {
        val date = LocaleController.formatDateTime(row.date.toLong(), false)
        val body = when (row.field) {
            FIELD_PHOTO -> LocaleController.getString(R.string.InuContactChangePhoto)
            else -> LocaleController.formatString(
                R.string.InuContactChangeLine,
                LocaleController.getString(fieldLabel(row.field)),
                row.oldValue.orEmpty(),
                row.newValue.orEmpty(),
            )
        }
        return "$date\n$body"
    }

    private fun fieldLabel(field: String): Int = when (field) {
        FIELD_FIRST_NAME -> R.string.InuContactFieldFirstName
        FIELD_LAST_NAME -> R.string.InuContactFieldLastName
        FIELD_USERNAME -> R.string.InuContactFieldUsername
        else -> R.string.InuContactFieldBio
    }
}
