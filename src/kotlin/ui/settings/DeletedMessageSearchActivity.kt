package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Search across the locally saved deleted-message and edit-history archive. */
class DeletedMessageSearchActivity : SettingsPageActivity() {

    private var query: String = ""
    private var results: List<InuDatabaseHelper.MessageSearchResult> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDeletedMessageSearch)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_SEARCH,
                LocaleController.getString(R.string.InuDeletedMessageSearch),
                query.ifBlank { LocaleController.getString(R.string.InuDeletedMessageSearchHint) },
            )
        )
        items.add(UItem.asShadow(null))
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuDeletedMessageSearchEmpty)))
            } else {
                results.forEachIndexed { index, result ->
                    items.add(
                        UItem.asButton(
                            RESULT_BASE + index,
                            dialogName(result.dialogId),
                            resultSubtitle(result),
                        )
                    )
                }
            }
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_SEARCH -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuDeletedMessageSearch),
                initialText = query,
                selectAll = true,
            ) { text ->
                query = text.trim()
                runSearch()
                true
            }

            item.id in RESULT_BASE until RESULT_BASE + results.size -> showResult(results[item.id - RESULT_BASE])
        }
    }

    private fun runSearch() {
        listView?.adapter?.update(true)
        if (query.isBlank()) {
            results = emptyList()
            listView?.adapter?.update(true)
            return
        }
        val account = UserConfig.selectedAccount
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val deleted = InuDatabaseHelper.searchDeletedMessages(db, query)
            val edited = InuDatabaseHelper.searchEditHistory(db, query)
            val merged = (deleted + edited).sortedByDescending { it.date }
            AndroidUtilities.runOnUIThread {
                results = merged
                listView?.adapter?.update(true)
            }
        }
    }

    private fun resultSubtitle(result: InuDatabaseHelper.MessageSearchResult): String {
        val date = dateFormat.format(Date(result.date * 1000L))
        val snippet = result.text.take(60).replace("\n", " ")
        val tag = if (result.isEdit) LocaleController.getString(R.string.InuEditHistory) else LocaleController.getString(R.string.InuSaveDeletedMessages)
        return "$tag • $date — $snippet"
    }

    private fun showResult(result: InuDatabaseHelper.MessageSearchResult) {
        val context = context ?: return
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(dialogName(result.dialogId))
            .setMessage(result.text)
            .setPositiveButton(LocaleController.getString(R.string.OK), null)
            .show()
    }

    private fun dialogName(dialogId: Long): String {
        if (dialogId == 0L) return LocaleController.getString(R.string.SavedMessages)
        val controller = MessagesController.getInstance(currentAccount)
        val user = controller.getUser(dialogId)
        if (user != null) return UserObject.getUserName(user)
        val chat = controller.getChat(-dialogId)
        return chat?.title ?: "ID $dialogId"
    }

    companion object {
        private val BUTTON_SEARCH = InuUtils.generateId()
        private const val RESULT_BASE = 28000
    }
}
