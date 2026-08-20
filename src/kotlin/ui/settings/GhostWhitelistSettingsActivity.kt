package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.GhostHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Chats exempt from Ghost Mode — tap a row to remove it, "Add" to whitelist a new one. */
class GhostWhitelistSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuGhostWhitelist)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val dialogs = GhostHelper.getWhitelistedDialogs(currentAccount)
        if (dialogs.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuGhostWhitelist)))
            dialogs.forEachIndexed { index, dialogId ->
                items.add(UItem.asButton(DIALOG_BASE + index, dialogName(dialogId)))
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostWhitelistInfo)))
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostWhitelistEmpty)))
        }
        items.add(UItem.asButton(BUTTON_ADD, LocaleController.getString(R.string.InuGhostWhitelistAdd)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_ADD -> DialogPicker.pick(this) { dialogId ->
                if (!GhostHelper.isDialogWhitelisted(dialogId)) GhostHelper.toggleDialogWhitelist(dialogId)
                listView?.adapter?.update(true)
            }

            item.id >= DIALOG_BASE -> {
                val dialogs = GhostHelper.getWhitelistedDialogs(currentAccount)
                val index = item.id - DIALOG_BASE
                val dialogId = dialogs.getOrNull(index) ?: return
                GhostHelper.toggleDialogWhitelist(dialogId)
                listView?.adapter?.update(true)
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuGhostWhitelistRemoved)).show()
            }
        }
    }

    private fun dialogName(dialogId: Long): String {
        val controller = MessagesController.getInstance(currentAccount)
        val user = controller.getUser(dialogId)
        if (user != null) return UserObject.getUserName(user)
        val chat = controller.getChat(-dialogId)
        return chat?.title ?: "ID $dialogId"
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val DIALOG_BASE = 25000
    }
}
