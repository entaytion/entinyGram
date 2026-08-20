package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.PresenceHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Contacts currently being presence-watched — remove, add, or view their local status-change log. */
class PresenceWatchListSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPresenceWatchList)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val watched = PresenceHelper.getWatchedUsers(currentAccount)
        if (watched.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuPresenceWatchList)))
            watched.forEachIndexed { index, userId ->
                items.add(UItem.asButton(USER_BASE + index, userName(userId), LocaleController.getString(R.string.InuPresenceViewLog)))
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuPresenceWatchListInfo)))
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuPresenceWatchListEmpty)))
        }
        items.add(UItem.asButton(BUTTON_ADD, LocaleController.getString(R.string.InuPresenceWatchAdd)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_ADD -> DialogPicker.pick(this) { dialogId ->
                if (dialogId > 0) {
                    if (!PresenceHelper.isWatched(currentAccount, dialogId)) PresenceHelper.toggleWatch(currentAccount, dialogId)
                    listView?.adapter?.update(true)
                }
            }

            item.id >= USER_BASE -> {
                val watched = PresenceHelper.getWatchedUsers(currentAccount)
                val userId = watched.getOrNull(item.id - USER_BASE) ?: return
                showRowOptions(userId)
            }
        }
    }

    private fun showRowOptions(userId: Long) {
        val context = context ?: return
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(userName(userId))
            .setItems(
                arrayOf(
                    LocaleController.getString(R.string.InuPresenceViewLog),
                    LocaleController.getString(R.string.InuPresenceUnwatch),
                )
            ) { _, which ->
                if (which == 0) showLog(userId) else unwatch(userId)
            }
            .show()
    }

    private fun unwatch(userId: Long) {
        PresenceHelper.toggleWatch(currentAccount, userId)
        listView?.adapter?.update(true)
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuPresenceWatchDisabledDone)).show()
    }

    private fun showLog(userId: Long) {
        val context = context ?: return
        val account = UserConfig.selectedAccount
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val logs = InuDatabaseHelper.loadPresenceLogs(db, userId)
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (logs.isEmpty()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuPresenceLogEmpty)).show()
                    return@runOnUIThread
                }
                val format = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())
                val lines = logs.map { (_, statusType, timestamp) ->
                    "${format.format(Date(timestamp * 1000L))} — $statusType"
                }.toTypedArray<CharSequence>()
                AlertDialog.Builder(context, resourceProvider)
                    .setTitle(userName(userId))
                    .setItems(lines) { _, _ -> }
                    .show()
            }
        }
    }

    private fun userName(userId: Long): String {
        val user = MessagesController.getInstance(currentAccount).getUser(userId)
        return if (user != null) UserObject.getUserName(user) else "ID $userId"
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val USER_BASE = 26000
    }
}
