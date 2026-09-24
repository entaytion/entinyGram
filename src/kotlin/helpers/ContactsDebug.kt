package desu.inugram.helpers

import desu.inugram.helpers.diag.DiagLog
import org.telegram.tgnet.TLRPC

// entiny: "contacts" diag zone -- every place the contact list changes, active only under a .entinylog profile
object ContactsDebug {
    private const val CAT = "contacts"

    @JvmStatic
    fun onLoaded(account: Int, from: Int, size: Int) {
        if (!DiagLog.on(CAT)) return
        val source = when (from) { 0 -> "server"; 1 -> "db"; 2 -> "imported"; else -> "from=$from" }
        DiagLog.log(CAT, "acc=$account loaded $size contacts from $source")
    }

    // the whole in-memory list is swapped here, so anything missing from the new one silently disappears
    @JvmStatic
    fun onReplaced(account: Int, old: Map<Long, TLRPC.TL_contact>, new: Map<Long, TLRPC.TL_contact>) {
        if (!DiagLog.on(CAT)) return
        val removed = old.keys - new.keys
        val added = new.keys - old.keys
        if (removed.isEmpty() && added.isEmpty()) return
        DiagLog.trace(CAT, "acc=$account list replaced ${old.size}->${new.size} removed=[${DiagLog.ids(removed)}] added=[${DiagLog.ids(added)}]")
    }

    @JvmStatic
    fun onUpdates(account: Int, ids: List<Long>) {
        if (!DiagLog.on(CAT)) return
        val removed = ids.filter { it < 0 }.map { -it }
        val added = ids.filter { it > 0 }
        DiagLog.log(CAT, "acc=$account updates added=[${DiagLog.ids(added)}] removed=[${DiagLog.ids(removed)}]")
    }

    @JvmStatic
    fun onDeleteRequest(account: Int, users: List<TLRPC.User>) {
        if (!DiagLog.on(CAT)) return
        DiagLog.trace(CAT, "acc=$account deleteContact [${DiagLog.ids(users.map { it.id })}]")
    }

    @JvmStatic
    fun onAddResponse(account: Int, userId: Long, users: List<TLRPC.User>) {
        if (!DiagLog.on(CAT)) return
        val self = users.firstOrNull { it.id == userId }
        DiagLog.log(CAT, "acc=$account addContact ok user=${DiagLog.ids(listOf(userId))} inResponse=${self != null} " +
            "contact=${self?.contact} mutual=${self?.mutual_contact} phone=${!self?.phone.isNullOrEmpty()}")
    }

    @JvmStatic
    fun onDbPut(size: Int, deleteAll: Boolean) {
        if (!DiagLog.on(CAT)) return
        DiagLog.trace(CAT, "db putContacts size=$size deleteAll=$deleteAll")
    }

    @JvmStatic
    fun onDbDelete(uids: List<Long>) {
        if (!DiagLog.on(CAT)) return
        DiagLog.trace(CAT, "db deleteContacts [${DiagLog.ids(uids)}]")
    }
}
