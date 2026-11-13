package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.TypingSpoofHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLObject
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TypingSpoofQuickListSettingsActivity : SettingsPageActivity() {
    private var shown: List<Long> = emptyList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTypingSpoof)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineEntry(
                BUTTON_ADD,
                R.drawable.msg_contact_add,
                LocaleController.getString(R.string.InuTypingSpoofAddChat),
                LocaleController.getString(R.string.InuTypingSpoofAddChatInfo),
            )
        )
        items.add(UItem.asShadow(null))
        val chats = TypingSpoofHelper.savedChats(currentAccount)
            .sortedWith(compareBy({ !TypingSpoofHelper.isActive(it) }, { dialogName(it).lowercase() }))
        shown = chats
        if (chats.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuTypingSpoofEmpty)))
            return
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypingSpoofChats)))
        chats.forEachIndexed { index, dialogId ->
            items.add(
                TypingSpoofChatCell.Factory.of(
                    ROW_BASE + index,
                    dialogObject(dialogId),
                    dialogName(dialogId),
                    modeLabel(dialogId),
                    TypingSpoofHelper.isActive(dialogId),
                )
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTypingSpoofListInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == BUTTON_ADD -> DialogPicker.pick(this) { dialogId -> onPicked(dialogId) }

            item.id >= ROW_BASE -> {
                val dialogId = shown.getOrNull(item.id - ROW_BASE) ?: return
                presentFragment(TypingSpoofChatSettingsActivity(dialogId))
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (item.id < ROW_BASE) return false
        val dialogId = shown.getOrNull(item.id - ROW_BASE) ?: return false
        val opts = ItemOptions.makeOptions(this, view)
        opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.Remove), true) {
            TypingSpoofHelper.forgetChat(currentAccount, dialogId)
            listView?.adapter?.update(true)
        }
        opts.show()
        return true
    }

    private fun onPicked(dialogId: Long) {
        val selfId = UserConfig.getInstance(currentAccount).getClientUserId()
        if (dialogId == selfId || !TypingSpoofHelper.canSpoofTyping(currentAccount, dialogId)) {
            // entiny: wait for the picker close transition, a bulletin shown during it is dropped
            AndroidUtilities.runOnUIThread({
                BulletinFactory.of(this)
                    .createErrorBulletin(LocaleController.getString(R.string.InuTypingSpoofUnsupportedChannel))
                    .show()
            }, 300)
            return
        }
        TypingSpoofHelper.saveChat(currentAccount, dialogId)
        // entiny: presenting during the picker close transition is refused
        AndroidUtilities.runOnUIThread({ presentFragment(TypingSpoofChatSettingsActivity(dialogId)) }, 300)
    }

    private fun modeLabel(dialogId: Long): String {
        val action = TypingSpoofHelper.activeAction(dialogId)
        val res = TypingSpoofHelper.modes.firstOrNull { it.action == action }?.labelRes ?: R.string.InuTypingSpoofOff
        return LocaleController.getString(res)
    }

    private fun dialogObject(dialogId: Long): TLObject? {
        val controller = MessagesController.getInstance(currentAccount)
        return if (dialogId >= 0) controller.getUser(dialogId) else controller.getChat(-dialogId)
    }

    private fun dialogName(dialogId: Long): String = when (val obj = dialogObject(dialogId)) {
        is org.telegram.tgnet.TLRPC.User -> UserObject.getUserName(obj)
        is org.telegram.tgnet.TLRPC.Chat -> obj.title ?: "ID $dialogId"
        else -> "ID $dialogId"
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ROW_BASE = 27000
    }
}
