package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import desu.inugram.helpers.InuDatabaseHelper
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

class DeletedAuthorsActivity(
    private val account: Int = UserConfig.selectedAccount,
    private val dialogId: Long? = null,
) : SettingsPageActivity() {

    private var authors: List<InuDatabaseHelper.DeletedAuthor> = emptyList()
    private var chatMessages: List<InuDatabaseHelper.DeletedMessage> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    init {
        setCurrentAccount(account)
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDeletedArchive)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (dialogId != null) {
            if (chatMessages.isEmpty()) {
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuDeletedArchiveEmpty)))
                return
            }
            chatMessages.forEachIndexed { index, message ->
                val date = dateFormat.format(Date(message.date * 1000L))
                val snippet = message.text.take(80).replace("\n", " ")
                items.add(UItem.asButton(AUTHOR_BASE + index, "#${message.msgId}", if (snippet.isEmpty()) date else "$date — $snippet"))
            }
            return
        }
        if (authors.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuDeletedArchiveEmpty)))
            return
        }
        authors.forEachIndexed { index, author ->
            val subtitle = LocaleController.formatPluralString("messages", author.count) +
                " • " + dateFormat.format(Date(author.lastDate * 1000L))
            items.add(UItem.asButton(AUTHOR_BASE + index, authorName(author.fromId), subtitle))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val count = if (dialogId != null) chatMessages.size else authors.size
        if (item.id in AUTHOR_BASE until AUTHOR_BASE + count) {
            if (dialogId != null) {
                val message = chatMessages.getOrNull(item.id - AUTHOR_BASE) ?: return
                AlertDialog.Builder(context, resourceProvider)
                    .setTitle("#${message.msgId}")
                    .setMessage(if (message.text.isEmpty()) LocaleController.getString(R.string.InuDeletedMessage) else message.text)
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show()
                return
            }
            val authorId = authors[item.id - AUTHOR_BASE].fromId
            presentFragment(DeletedByAuthorActivity(authorId))
        }
    }

    override fun createView(context: Context): View = super.createView(context).also {
        loadAuthors()
    }

    private fun loadAuthors() {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val result = if (dialogId != null) emptyList() else InuDatabaseHelper.deletedAuthors(db)
            val messages = dialogId?.let { InuDatabaseHelper.deletedMessagesInDialog(db, it) } ?: emptyList()
            AndroidUtilities.runOnUIThread {
                authors = result
                chatMessages = messages
                listView?.adapter?.update(true)
            }
        }
    }

    private fun authorName(fromId: Long): String {
        val controller = MessagesController.getInstance(currentAccount)
        val user = controller.getUser(fromId)
        return if (user != null) UserObject.getUserName(user) else "ID $fromId"
    }

    companion object {
        private const val AUTHOR_BASE = 29000
    }
}

class DeletedByAuthorActivity(private val fromId: Long) : SettingsPageActivity() {

    private var messages: List<InuDatabaseHelper.MessageSearchResult> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun getTitle(): CharSequence {
        val controller = MessagesController.getInstance(currentAccount)
        val user = controller.getUser(fromId)
        return if (user != null) UserObject.getUserName(user) else "ID $fromId"
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (messages.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuDeletedArchiveEmpty)))
            return
        }
        messages.forEachIndexed { index, result ->
            val date = dateFormat.format(Date(result.date * 1000L))
            val snippet = result.text.take(60).replace("\n", " ")
            items.add(
                UItem.asButton(MSG_BASE + index, dialogName(result.dialogId), if (snippet.isEmpty()) date else "$date — $snippet")
            )
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id in MSG_BASE until MSG_BASE + messages.size) {
            val result = messages[item.id - MSG_BASE]
            AlertDialog.Builder(context, resourceProvider)
                .setTitle(dialogName(result.dialogId))
                .setMessage(if (result.text.isEmpty()) LocaleController.getString(R.string.InuDeletedMessage) else result.text)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show()
        }
    }

    override fun createView(context: Context): View = super.createView(context).also {
        val storage = MessagesStorage.getInstance(UserConfig.selectedAccount) ?: return@also
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val result = InuDatabaseHelper.deletedByAuthor(db, fromId)
            AndroidUtilities.runOnUIThread {
                messages = result
                listView?.adapter?.update(true)
            }
        }
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
        private const val MSG_BASE = 30000
    }
}
