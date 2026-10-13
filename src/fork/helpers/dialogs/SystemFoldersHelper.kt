package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig

// entiny: virtual folders live only in memory + InuConfig state; ids >= LOCAL_FILTER_ID_BASE keep them out of every server order request
object SystemFoldersHelper {

    const val SYSTEM_FILTER_ID_BASE = 200000
    private const val EMPTY_MARK = "-"

    enum class Type(val flags: Int, val titleRes: Int) {
        ADMINISTRATOR(MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS, R.string.InuSystemFolderAdministrator),
        BOTS(MessagesController.DIALOG_FILTER_FLAG_BOTS, R.string.InuSystemFolderBots),
        UNREAD(MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS or MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ, R.string.InuSystemFolderUnread),
        GROUPS(MessagesController.DIALOG_FILTER_FLAG_GROUPS, R.string.InuSystemFolderGroups),
        CHANNELS(MessagesController.DIALOG_FILTER_FLAG_CHANNELS, R.string.InuSystemFolderChannels);

        val id: Int get() = SYSTEM_FILTER_ID_BASE + ordinal
    }

    class Entry(val type: Type, var enabled: Boolean, var pos: Int)

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

    fun entries(): MutableList<Entry> {
        val raw = InuConfig.SYSTEM_FOLDERS_STATE.value
        if (raw.isEmpty()) return Type.entries.map { Entry(it, false, -1) }.toMutableList()
        if (raw == EMPTY_MARK) return mutableListOf()
        val result = ArrayList<Entry>()
        for (part in raw.split(',')) {
            val fields = part.split(':')
            val type = Type.entries.firstOrNull { it.name == fields.getOrNull(0) } ?: continue
            if (result.any { it.type == type }) continue
            result.add(Entry(type, fields.getOrNull(1) == "1", fields.getOrNull(2)?.toIntOrNull() ?: -1))
        }
        return result
    }

    private fun save(list: List<Entry>) {
        InuConfig.SYSTEM_FOLDERS_STATE.value =
            if (list.isEmpty()) EMPTY_MARK else list.joinToString(",") { "${it.type.name}:${if (it.enabled) 1 else 0}:${it.pos}" }
    }

    fun missingTypes(): List<Type> {
        val present = entries().map { it.type }
        return Type.entries.filter { it !in present }
    }

    fun setEnabled(type: Type, enabled: Boolean) {
        val list = entries()
        val entry = list.firstOrNull { it.type == type } ?: return
        entry.enabled = enabled
        save(list)
        syncAll()
    }

    fun delete(type: Type) {
        val list = entries()
        if (!list.removeAll { it.type == type }) return
        save(list)
        syncAll()
    }

    fun addRecommended() {
        val list = entries()
        for (type in missingTypes()) list.add(Entry(type, false, -1))
        list.sortBy { it.type.ordinal }
        save(list)
        syncAll()
    }

    @JvmStatic
    fun onRemovedByUser(filterId: Int) {
        val type = Type.entries.firstOrNull { it.id == filterId } ?: return
        delete(type)
    }

    @JvmStatic
    fun captureOrder(account: Int) {
        val filters = MessagesController.getInstance(account).dialogFilters
        val list = entries()
        var changed = false
        for (entry in list) {
            val index = filters.indexOfFirst { it.id == entry.type.id }
            if (index >= 0 && entry.pos != index) {
                entry.pos = index
                changed = true
            }
        }
        if (changed) save(list)
    }

    @JvmStatic
    fun syncAll() {
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(i).isClientActivated) sync(i)
        }
    }

    @JvmStatic
    fun sync(account: Int) {
        val controller = MessagesController.getInstance(account)
        if (!controller.dialogFiltersLoaded) return
        val list = entries()
        var changed = false
        for (type in Type.entries) {
            val existing = controller.dialogFiltersById.get(type.id) ?: continue
            if (list.any { it.type == type && it.enabled }) continue
            controller.dialogFilters.remove(existing)
            controller.dialogFiltersById.remove(type.id)
            changed = true
        }
        for (entry in list.filter { it.enabled }.sortedBy { if (it.pos < 0) Int.MAX_VALUE else it.pos }) {
            if (controller.dialogFiltersById.get(entry.type.id) != null) continue
            val filter = MessagesController.DialogFilter()
            filter.id = entry.type.id
            filter.name = LocaleController.getString(entry.type.titleRes)
            filter.flags = entry.type.flags
            val filters = controller.dialogFilters
            filter.order = (filters.maxOfOrNull { it.order } ?: 0) + 1
            val minIndex = if (filters.firstOrNull()?.isDefault == true) 1 else 0
            val index = if (entry.pos < 0) filters.size else entry.pos.coerceIn(minIndex, filters.size)
            filters.add(index, filter)
            controller.dialogFiltersById.put(filter.id, filter)
            changed = true
        }
        if (changed) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
        }
    }
}
