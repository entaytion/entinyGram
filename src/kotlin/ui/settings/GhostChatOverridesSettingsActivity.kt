package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.GhostHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class GhostChatOverridesSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuGhostOverrides)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val dialogs = GhostHelper.getOverriddenDialogs()
        if (dialogs.isNotEmpty()) {
            items.add(UItem.asHeader(getTitle().toString()))
            dialogs.forEachIndexed { index, dialogId ->
                items.add(UItem.asButton(DIALOG_BASE + index, dialogName(dialogId), GhostHelper.overrideSummary(dialogId)))
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostOverridesInfo)))
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostOverridesEmpty)))
        }
        items.add(UItem.asButton(BUTTON_ADD, LocaleController.getString(R.string.InuGhostOverridesAdd)))
    }

    private fun refresh() {
        listView?.adapter?.update(true)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_ADD -> DialogPicker.pick(this) { dialogId ->
                // entiny: wait for the picker to close before showing the dialog on this page
                AndroidUtilities.runOnUIThread({
                    GhostHelper.showChatOverridesDialog(this, currentAccount, dialogId) { refresh() }
                }, 300)
            }

            item.id >= DIALOG_BASE -> {
                val dialogId = GhostHelper.getOverriddenDialogs().getOrNull(item.id - DIALOG_BASE) ?: return
                val opts = ItemOptions.makeOptions(this, view)
                opts.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit)) {
                    GhostHelper.showChatOverridesDialog(this, currentAccount, dialogId) { refresh() }
                }
                opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
                    GhostHelper.removeChatOverride(dialogId)
                    refresh()
                    BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuGhostOverridesRemoved))
                        .show()
                }
                opts.show()
            }
        }
    }

    private fun dialogName(dialogId: Long): String {
        val controller = MessagesController.getInstance(currentAccount)
        if (dialogId > 0) controller.getUser(dialogId)?.let { return UserObject.getUserName(it) }
        if (dialogId < 0) controller.getChat(-dialogId)?.let { return it.title ?: "ID $dialogId" }
        return "ID $dialogId"
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val DIALOG_BASE = 25000
    }
}
