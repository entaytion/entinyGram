package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.FiltersListBottomSheet
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet
import org.telegram.ui.Components.UndoView
import org.telegram.ui.FilterCreateActivity

object ChatFolderHelper {
    @JvmStatic
    fun canShow(chat: ChatActivity): Boolean {
        if (!InuConfig.ADD_TO_FOLDER_IN_CHAT.value) return false
        if (chat.isTopic || chat.parentActivity == null) return false
        val did = chat.dialogId
        return did != 0L && !DialogObject.isEncryptedDialog(did)
    }

    @JvmStatic
    fun show(chat: ChatActivity) {
        val activity = chat.parentActivity ?: return
        val account = chat.currentAccount
        val selected = arrayListOf(chat.dialogId)
        val sheet = FiltersListBottomSheet(chat, selected)
        sheet.setDelegate { filter, checked ->
            val dialogs = FiltersListBottomSheet.getDialogsCount(chat, filter, selected, true, false)
            if (filter == null) {
                chat.presentFragment(FilterCreateActivity(null, dialogs))
                return@setDelegate
            }
            val controller = chat.messagesController
            val unlimited = InuConfig.UNLIMITED_FOLDER_CHATS.value
            if (!checked && !unlimited) {
                val total = filter.alwaysShow.size + dialogs.size
                if (total > controller.dialogFiltersChatsLimitDefault && !chat.userConfig.isPremium || total > controller.dialogFiltersChatsLimitPremium) {
                    chat.showDialog(LimitReachedBottomSheet(chat, activity, LimitReachedBottomSheet.TYPE_CHATS_IN_FOLDER, account, null))
                    return@setDelegate
                }
            }
            if (checked) {
                for (did in selected) {
                    filter.neverShow.add(did)
                    filter.alwaysShow.remove(did)
                    FolderMembershipHelper.removeLocal(account, filter.id, did)
                }
            } else {
                if (dialogs.isEmpty()) return@setDelegate
                filter.neverShow.removeAll(dialogs.toSet())
                val slots = if (unlimited) maxOf(0, FolderMembershipHelper.serverLimit(account) - filter.alwaysShow.size) else dialogs.size
                dialogs.forEachIndexed { index, did ->
                    if (index < slots) filter.alwaysShow.add(did) else FolderMembershipHelper.addLocal(account, filter.id, did)
                }
            }
            FilterCreateActivity.saveFilterToServer(filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color, filter.alwaysShow, filter.neverShow, filter.pinnedDialogs, false, false, true, true, false, chat, null)
            val action = if (checked) UndoView.ACTION_REMOVED_FROM_FOLDER else UndoView.ACTION_ADDED_TO_FOLDER
            chat.undoView?.showWithAction(chat.dialogId, action, 1, filter, null, null)
        }
        chat.showDialog(sheet)
    }
}
