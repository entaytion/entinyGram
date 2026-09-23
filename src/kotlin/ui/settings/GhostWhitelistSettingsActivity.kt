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

// entiny: shared by the whitelist and the per-chat ghost list, they only differ in storage and wording
class GhostWhitelistSettingsActivity @JvmOverloads constructor(
    private val targets: Boolean = false,
) : SettingsPageActivity() {

    override fun getTitle(): CharSequence =
        LocaleController.getString(if (targets) R.string.InuGhostTargets else R.string.InuGhostWhitelist)

    private fun dialogs(): List<Long> =
        if (targets) GhostHelper.getTargetedDialogs() else GhostHelper.getWhitelistedDialogs(currentAccount)

    private fun setListed(dialogId: Long, listed: Boolean) {
        if (targets) {
            GhostHelper.setDialogTargeted(dialogId, listed)
        } else if (GhostHelper.isDialogWhitelisted(dialogId) != listed) {
            GhostHelper.toggleDialogWhitelist(dialogId)
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val dialogs = dialogs()
        if (dialogs.isNotEmpty()) {
            items.add(UItem.asHeader(getTitle().toString()))
            dialogs.forEachIndexed { index, dialogId ->
                items.add(UItem.asButton(DIALOG_BASE + index, dialogName(dialogId)))
            }
            items.add(UItem.asShadow(LocaleController.getString(if (targets) R.string.InuGhostTargetsInfo else R.string.InuGhostWhitelistInfo)))
        } else {
            items.add(UItem.asShadow(LocaleController.getString(if (targets) R.string.InuGhostTargetsEmpty else R.string.InuGhostWhitelistEmpty)))
        }
        items.add(UItem.asButton(BUTTON_ADD, LocaleController.getString(if (targets) R.string.InuGhostTargetsAdd else R.string.InuGhostWhitelistAdd)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_ADD -> DialogPicker.pick(this) { dialogId ->
                setListed(dialogId, true)
                listView?.adapter?.update(true)
            }

            item.id >= DIALOG_BASE -> {
                val dialogId = dialogs().getOrNull(item.id - DIALOG_BASE) ?: return
                setListed(dialogId, false)
                listView?.adapter?.update(true)
                val removed = if (targets) R.string.InuGhostTargetsRemoved else R.string.InuGhostWhitelistRemoved
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(removed)).show()
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
