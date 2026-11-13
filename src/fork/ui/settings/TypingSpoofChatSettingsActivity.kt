package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.TypingSpoofHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TypingSpoofChatSettingsActivity(private val dialogId: Long) : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTypingSpoof)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val controller = MessagesController.getInstance(currentAccount)
        val obj: TLObject? = if (dialogId >= 0) controller.getUser(dialogId) else controller.getChat(-dialogId)
        val name = when (obj) {
            is TLRPC.User -> UserObject.getUserName(obj)
            is TLRPC.Chat -> obj.title ?: "ID $dialogId"
            else -> "ID $dialogId"
        }
        val active = TypingSpoofHelper.isActive(dialogId)
        val action = TypingSpoofHelper.activeAction(dialogId)
        val current = TypingSpoofHelper.modes.firstOrNull { it.action == action }?.labelRes ?: R.string.InuTypingSpoofOff
        items.add(TypingSpoofChatCell.Factory.of(HEADER, obj, name, LocaleController.getString(current), active, plain = true))
        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypingSpoofMode)))
        items.add(UItem.asRadio(OFF, LocaleController.getString(R.string.InuTypingSpoofOff)).setChecked(!active))
        for (mode in TypingSpoofHelper.modes) {
            items.add(UItem.asRadio(MODE_BASE + mode.action, LocaleController.getString(mode.labelRes)).setChecked(active && action == mode.action))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTypingSpoofModeInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == OFF -> TypingSpoofHelper.stop(dialogId)
            item.id in MODE_BASE until MODE_BASE + 100 ->TypingSpoofHelper.start(currentAccount, dialogId, 0L, item.id - MODE_BASE)
        }
    }

    companion object {
        private val HEADER = InuUtils.generateId()
        private val OFF = InuUtils.generateId()
        private const val MODE_BASE = 28000
    }
}
