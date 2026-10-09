package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R

// entiny: virtual system folders are recomputed on every sync and never persisted, so they are not saved to the db or pushed to server
object SystemFoldersHelper {

    const val SYSTEM_FILTER_ID_BASE = 200000

    enum class Type(val flags: Int, val titleRes: Int) {
        ADMINISTRATOR(MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS, R.string.InuSystemFolderAdministrator),
        BOTS(MessagesController.DIALOG_FILTER_FLAG_BOTS, R.string.InuSystemFolderBots),
        UNREAD(MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ, R.string.InuSystemFolderUnread),
        GROUPS(MessagesController.DIALOG_FILTER_FLAG_GROUPS, R.string.InuSystemFolderGroups),
        CHANNELS(MessagesController.DIALOG_FILTER_FLAG_CHANNELS, R.string.InuSystemFolderChannels);

        val id: Int get() = SYSTEM_FILTER_ID_BASE + ordinal
    }

    @JvmStatic
    fun isSystem(filterId: Int): Boolean = filterId >= SYSTEM_FILTER_ID_BASE && filterId < SYSTEM_FILTER_ID_BASE + Type.entries.size

    @JvmStatic
    fun isAdministratorFolder(filterId: Int): Boolean = filterId == Type.ADMINISTRATOR.id

    @JvmStatic
    fun matchesAdministrator(account: Int, dialogId: Long): Boolean {
        if (dialogId >= 0) return false
        val chat = MessagesController.getInstance(account).getChat(-dialogId) ?: return false
        return chat.creator || ChatObject.hasAdminRights(chat)
    }

    @JvmStatic
    fun sync(account: Int) {
        val controller = MessagesController.getInstance(account)
        val enabled = InuConfig.SYSTEM_FOLDERS.value
        for (type in Type.entries) {
            val existing = controller.dialogFiltersById.get(type.id)
            if (enabled && existing == null) {
                val filter = MessagesController.DialogFilter()
                filter.id = type.id
                filter.name = LocaleController.getString(type.titleRes)
                filter.flags = type.flags
                controller.addFilter(filter, false)
            } else if (!enabled && existing != null) {
                controller.dialogFilters.remove(existing)
                controller.dialogFiltersById.remove(type.id)
            }
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
    }
}
