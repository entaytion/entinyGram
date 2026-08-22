package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.PresenceHelper
import org.telegram.messenger.AndroidUtilities
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
            items.add(UItem.asButton(BUTTON_CLEAR_ALL_LOGS, LocaleController.getString(R.string.InuPresenceClearAllLogs)))
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

            item.id == BUTTON_CLEAR_ALL_LOGS -> confirmClearAllLogs()

            item.id >= USER_BASE -> {
                val watched = PresenceHelper.getWatchedUsers(currentAccount)
                val userId = watched.getOrNull(item.id - USER_BASE) ?: return
                showRowOptions(userId)
            }
        }
    }

    /** Bottom sheet letting the user pick exactly which watched contacts' logs to wipe. */
    private fun confirmClearAllLogs() {
        val context = context ?: return
        PresenceHelper.getLogsStatsByUser(currentAccount) { stats ->
            if (stats.isEmpty()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuPresenceLogEmpty)).show()
                return@getLogsStatsByUser
            }

            val selected = BooleanArray(stats.size) { true }

            val builder = org.telegram.ui.ActionBar.BottomSheet.Builder(context)
            builder.setTitle(LocaleController.getString(R.string.InuPresenceClearAllLogs))

            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
            }
            val linearLayout = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }

            val buttonTextView = android.widget.TextView(context).apply {
                setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
                setGravity(android.view.Gravity.CENTER)
                setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_buttonText))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
                setTypeface(AndroidUtilities.bold())
                background = org.telegram.ui.ActionBar.Theme.createSimpleSelectorRoundRectDrawable(
                    AndroidUtilities.dp(8f),
                    org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButton),
                    org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButtonPressed),
                )
            }

            fun updateButtonText() {
                val selectedCount = selected.count { it }
                buttonTextView.text = LocaleController.getString(R.string.Delete) + " (" + selectedCount + ")"
                buttonTextView.isEnabled = selectedCount > 0
                buttonTextView.alpha = if (selectedCount > 0) 1f else 0.5f
            }

            stats.forEachIndexed { i, stat ->
                val name = userName(stat.dialogId)
                val value = LocaleController.formatString(R.string.InuPresenceLogEntries, stat.count)
                val cell = org.telegram.ui.Cells.CheckBoxCell(context, 1, resourceProvider).apply {
                    setText(name, value, true, true)
                    setChecked(selected[i], false)
                    setTag(i)
                    setOnClickListener {
                        val idx = tag as Int
                        selected[idx] = !selected[idx]
                        setChecked(selected[idx], true)
                        updateButtonText()
                    }
                }
                linearLayout.addView(cell)
            }

            val scrollView = android.widget.ScrollView(context).apply { addView(linearLayout) }
            val scrollParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f,
            ).apply { bottomMargin = AndroidUtilities.dp(12f) }
            container.addView(scrollView, scrollParams)
            container.addView(
                buttonTextView,
                org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT),
            )
            updateButtonText()

            builder.setCustomView(container)
            val sheet = builder.create()
            buttonTextView.setOnClickListener {
                val toClear = stats.filterIndexed { index, _ -> selected[index] }.map { it.dialogId }
                if (toClear.isNotEmpty()) {
                    sheet.dismiss()
                    PresenceHelper.clearLogs(currentAccount, if (toClear.size == stats.size) null else toClear) {
                        listView?.adapter?.update(true)
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuPresenceClearAllLogsDone)).show()
                    }
                }
            }
            showDialog(sheet)
        }
    }

    private fun showRowOptions(userId: Long) {
        val context = context ?: return
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(userName(userId))
            .setItems(
                arrayOf(
                    LocaleController.getString(R.string.InuPresenceViewLog),
                    LocaleController.getString(R.string.InuPresenceClearLog),
                    LocaleController.getString(R.string.InuPresenceUnwatch),
                )
            ) { _, which ->
                when (which) {
                    0 -> showLog(userId)
                    1 -> clearLog(userId)
                    else -> unwatch(userId)
                }
            }
            .show()
    }

    private fun clearLog(userId: Long) {
        PresenceHelper.clearLog(currentAccount, userId) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuPresenceClearLogDone)).show()
        }
    }

    private fun unwatch(userId: Long) {
        PresenceHelper.toggleWatch(currentAccount, userId)
        listView?.adapter?.update(true)
        val context = context
        if (context == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuPresenceWatchDisabledDone)).show()
            return
        }
        // Ask instead of silently keeping (surprising leftover data) or silently
        // deleting (surprising data loss) — the log outlives the watch toggle either way.
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuPresenceWatchDisabledDone))
            .setMessage(LocaleController.getString(R.string.InuPresenceUnwatchClearLogPrompt))
            .setPositiveButton(LocaleController.getString(R.string.InuPresenceClearLog)) { _, _ ->
                PresenceHelper.clearLog(currentAccount, userId)
            }
            .setNegativeButton(LocaleController.getString(R.string.InuPresenceKeepLog), null)
            .show()
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
        private val BUTTON_CLEAR_ALL_LOGS = InuUtils.generateId()
        private const val USER_BASE = 26000
    }
}
