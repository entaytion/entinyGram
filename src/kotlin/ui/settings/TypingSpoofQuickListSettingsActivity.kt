package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.TypingSpoofHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Quick access to the typing-spoof action picker without first opening the target chat. */
class TypingSpoofQuickListSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTypingSpoof)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val active = TypingSpoofHelper.getActiveDialogIds()
        if (active.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypingSpoofActive)))
            active.forEachIndexed { index, dialogId ->
                items.add(UItem.asButton(ACTIVE_BASE + index, dialogName(dialogId)))
            }
            items.add(UItem.asShadow(null))
        }
        items.add(UItem.asButton(BUTTON_PICK, LocaleController.getString(R.string.InuTypingSpoofPickChat)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTypingSpoofQuickListInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_PICK -> DialogPicker.pick(this) { dialogId ->
                TypingSpoofHelper.showPicker(this, currentAccount, dialogId, 0L)
            }

            item.id >= ACTIVE_BASE -> {
                val active = TypingSpoofHelper.getActiveDialogIds()
                val dialogId = active.getOrNull(item.id - ACTIVE_BASE) ?: return
                TypingSpoofHelper.showPicker(this, currentAccount, dialogId, 0L)
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
        private val BUTTON_PICK = InuUtils.generateId()
        private const val ACTIVE_BASE = 27000
    }
}
