package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.ContactsController
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC

object ArchiveScope {
    const val MODE_ALL = 0
    const val MODE_ONLY = 1

    const val FLAG_SECRET = 1 shl 20
    const val FLAG_BOT_IN_GROUP = 1 shl 21

    enum class Kind { DELETED, EDITED, MEDIA }

    class Entry(val flags: Int, val ids: List<Long>) {
        val isEmpty: Boolean get() = flags == 0 && ids.isEmpty()
        val count: Int get() = Integer.bitCount(flags) + ids.size
    }

    private class Config(val mode: Int, val never: Entry, val only: Entry)

    private val EMPTY = Entry(0, emptyList())
    private val DEFAULT = Config(MODE_ALL, EMPTY, EMPTY)

    private val cachedRaw = arrayOfNulls<String>(Kind.values().size)
    private val cachedConfig = Array(Kind.values().size) { DEFAULT }

    private val MEDIA_LIMITS_MB = intArrayOf(1, 5, 16, 64, 256, 1024, 0)

    private fun item(kind: Kind) = when (kind) {
        Kind.DELETED -> InuConfig.ARCHIVE_SCOPE_DELETED
        Kind.EDITED -> InuConfig.ARCHIVE_SCOPE_EDITED
        Kind.MEDIA -> InuConfig.ARCHIVE_SCOPE_MEDIA
    }

    // entiny: must run at startup, a lazy migration reads the legacy defaults as exclusions once the master toggle is on
    @JvmStatic
    @Synchronized
    fun init() = migrateIfNeeded()

    @Synchronized
    private fun config(kind: Kind): Config {
        migrateIfNeeded()
        val raw = item(kind).value
        val slot = kind.ordinal
        if (cachedRaw[slot] != raw) {
            cachedConfig[slot] = parse(raw)
            cachedRaw[slot] = raw
        }
        return cachedConfig[slot]
    }

    private fun store(kind: Kind, config: Config) {
        item(kind).value = JSONObject()
            .put("mode", config.mode)
            .put("never", entryJson(config.never))
            .put("only", entryJson(config.only))
            .toString()
    }

    fun mode(kind: Kind): Int = config(kind).mode

    fun setMode(kind: Kind, mode: Int) {
        val current = config(kind)
        store(kind, Config(mode, current.never, current.only))
    }

    fun entry(kind: Kind, never: Boolean): Entry = config(kind).let { if (never) it.never else it.only }

    fun save(kind: Kind, never: Boolean, entry: Entry) {
        val current = config(kind)
        store(kind, if (never) Config(current.mode, entry, current.only) else Config(current.mode, current.never, entry))
    }

    @JvmStatic
    @JvmOverloads
    fun allows(account: Int, dialogId: Long, message: TLRPC.Message? = null, kind: Kind = Kind.DELETED): Boolean {
        val config = config(kind)
        return if (config.mode == MODE_ONLY) matches(config.only, account, dialogId, message)
        else !matches(config.never, account, dialogId, message)
    }

    fun summary(kind: Kind): Pair<Int, Int> {
        val config = config(kind)
        return if (config.mode == MODE_ONLY) MODE_ONLY to config.only.count else MODE_ALL to config.never.count
    }

    fun matches(entry: Entry, account: Int, dialogId: Long, message: TLRPC.Message?): Boolean {
        if (entry.isEmpty) return false
        if (dialogId in entry.ids) return true
        val flags = entry.flags
        val controller = MessagesController.getInstance(account)
        return when {
            DialogObject.isEncryptedDialog(dialogId) -> flags and FLAG_SECRET != 0
            dialogId > 0 -> {
                val user = controller.getUser(dialogId)
                when {
                    user?.bot == true -> flags and MessagesController.DIALOG_FILTER_FLAG_BOTS != 0
                    user != null && (user.self || user.contact || ContactsController.getInstance(account).isContact(dialogId)) ->
                        flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS != 0
                    else -> flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS != 0
                }
            }
            dialogId < 0 -> {
                val chat = controller.getChat(-dialogId)
                when {
                    chat != null && ChatObject.isChannel(chat) && !chat.megagroup ->
                        flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS != 0
                    flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS != 0 -> true
                    flags and FLAG_BOT_IN_GROUP != 0 -> isBotMessage(controller, message)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun isBotMessage(controller: MessagesController, message: TLRPC.Message?): Boolean {
        val id = message?.from_id?.user_id?.takeIf { it != 0L } ?: return false
        return controller.getUser(id)?.bot == true
    }

    fun mediaLimitOptions(): IntArray = MEDIA_LIMITS_MB

    // entiny: 0 means no limit; the cap follows the current connection like the stock auto-download does
    fun mediaLimitBytes(): Long {
        val mb = (if (ApplicationLoader.isConnectedToWiFi()) InuConfig.ARCHIVE_MEDIA_LIMIT_WIFI else InuConfig.ARCHIVE_MEDIA_LIMIT_MOBILE).value
        return if (mb <= 0) 0L else mb * 1024L * 1024L
    }

    private fun entryJson(entry: Entry): JSONObject = JSONObject().put("flags", entry.flags).put("ids", JSONArray(entry.ids))

    private fun parseEntry(json: JSONObject?): Entry {
        if (json == null) return EMPTY
        val ids = json.optJSONArray("ids")
        return Entry(json.optInt("flags"), List(ids?.length() ?: 0) { ids!!.getLong(it) })
    }

    private fun parse(raw: String): Config {
        if (raw.isEmpty()) return DEFAULT
        return try {
            val json = JSONObject(raw)
            Config(json.optInt("mode"), parseEntry(json.optJSONObject("never")), parseEntry(json.optJSONObject("only")))
        } catch (e: Throwable) {
            DEFAULT
        }
    }

    // entiny: the first exclusion list came from the six per-category toggles, then from one shared list; each archive now has its own
    private fun migrateIfNeeded() {
        if (InuConfig.ARCHIVE_SCOPE_KINDS_MIGRATED.value) return
        InuConfig.ARCHIVE_SCOPE_KINDS_MIGRATED.value = true
        var never = parseLegacyEntry(InuConfig.ARCHIVE_SCOPE_NEVER.value)
        if (!InuConfig.ARCHIVE_SCOPE_MIGRATED.value && InuConfig.SAVE_DELETED_MESSAGES.value) {
            var flags = never.flags
            if (!InuConfig.SAVE_DELETED_PRIVATE.value) {
                flags = flags or MessagesController.DIALOG_FILTER_FLAG_CONTACTS or MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
            }
            if (!InuConfig.SAVE_DELETED_GROUPS.value) flags = flags or MessagesController.DIALOG_FILTER_FLAG_GROUPS
            if (!InuConfig.SAVE_DELETED_CHANNELS.value) flags = flags or MessagesController.DIALOG_FILTER_FLAG_CHANNELS
            if (!InuConfig.SAVE_DELETED_BOTS.value) flags = flags or MessagesController.DIALOG_FILTER_FLAG_BOTS
            if (!InuConfig.SAVE_DELETED_SECRET.value) flags = flags or FLAG_SECRET
            never = Entry(flags, never.ids)
        }
        InuConfig.ARCHIVE_SCOPE_MIGRATED.value = true
        val legacy = Config(InuConfig.ARCHIVE_SCOPE_MODE.value, never, parseLegacyEntry(InuConfig.ARCHIVE_SCOPE_ONLY.value))
        if (legacy.never.isEmpty && legacy.only.isEmpty && legacy.mode == MODE_ALL) return
        for (kind in Kind.values()) store(kind, legacy)
    }

    private fun parseLegacyEntry(raw: String): Entry = try {
        if (raw.isEmpty()) EMPTY else parseEntry(JSONObject(raw))
    } catch (e: Throwable) {
        EMPTY
    }
}
