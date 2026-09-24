package desu.inugram.helpers.diag

import desu.inugram.InuConfig
import desu.inugram.helpers.push.UnifiedPushHelper
import org.telegram.messenger.ContactsController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier

// entiny: turns TL objects, notification args and app state into short readable text for diag logs
object DiagDump {

    private const val MAX_DEPTH = 2
    private const val MAX_LIST = 12
    private const val MAX_STRING = 160

    // never written: credentials, keys, auth payloads
    private val SECRET_FIELDS = setOf("secret", "password", "new_password", "current_password", "srp_id", "srp_B", "A", "M1",
        "phone_code", "phone_code_hash", "code", "bytes", "key", "auth_key", "nonce", "server_nonce", "encrypted_data", "g_a", "g_b")
    private val SECRET_CLASS = Regex("(?i)(^|_)(auth|password|secure|takeout)")

    // region TL objects

    fun tl(obj: Any?): String = if (obj is TLObject) render(obj, 0) else value(obj)

    fun value(obj: Any?): String = when (obj) {
        null -> "null"
        is TLObject -> render(obj, MAX_DEPTH - 1)
        is CharSequence -> quote(obj.toString())
        is Number, is Boolean -> obj.toString()
        is Collection<*> -> list(obj.toList(), MAX_DEPTH - 1)
        is Array<*> -> list(obj.toList(), MAX_DEPTH - 1)
        else -> obj.javaClass.simpleName
    }

    private fun render(obj: TLObject, depth: Int): String {
        val name = obj.javaClass.simpleName
        if (SECRET_CLASS.containsMatchIn(name)) return "$name{…}"
        if (depth >= MAX_DEPTH) return name
        val parts = ArrayList<String>()
        for (field in fieldsOf(obj.javaClass)) {
            val v = try {
                field.get(obj)
            } catch (_: Exception) {
                continue
            }
            if (v == null || isDefault(v)) continue
            val shown = when {
                field.name in SECRET_FIELDS -> "•••"
                v is ByteArray -> "bytes[${v.size}]"
                v is TLObject -> render(v, depth + 1)
                v is Collection<*> -> if (v.isEmpty()) continue else list(v.toList(), depth + 1)
                v is CharSequence -> quote(v.toString())
                v is Number || v is Boolean -> v.toString()
                else -> continue
            }
            parts.add("${field.name}=$shown")
        }
        return if (parts.isEmpty()) name else "$name{${parts.joinToString(", ")}}"
    }

    private fun list(items: List<Any?>, depth: Int): String {
        val shown = items.take(MAX_LIST).map { if (it is TLObject) render(it, depth + 1) else value(it) }
        val tail = if (items.size > MAX_LIST) ", …(${items.size})" else ""
        return "[${shown.joinToString(", ")}$tail]"
    }

    private fun isDefault(v: Any): Boolean = when (v) {
        is Boolean -> !v
        is Int -> v == 0
        is Long -> v == 0L
        is Double -> v == 0.0
        is CharSequence -> v.isEmpty()
        else -> false
    }

    private fun quote(s: String) = "\"" + (if (s.length > MAX_STRING) s.take(MAX_STRING) + "…" else s).replace("\n", "\\n") + "\""

    private val fieldCache = HashMap<Class<*>, List<Field>>()

    private fun fieldsOf(cls: Class<*>): List<Field> = synchronized(fieldCache) {
        fieldCache.getOrPut(cls) {
            val out = ArrayList<Field>()
            var c: Class<*>? = cls
            while (c != null && c != TLObject::class.java && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.name == "flags" || f.name.startsWith("flags")) continue
                    f.isAccessible = true
                    out.add(f)
                }
                c = c.superclass
            }
            out
        }
    }

    // endregion

    // region state dumps: "contacts", "user:<id>", "dialog:<id>", "push", "config:<GLOB>"

    fun state(what: String): String {
        val key = what.substringBefore(':').lowercase()
        val arg = what.substringAfter(':', "")
        val account = UserConfig.selectedAccount
        return when (key) {
            "contacts" -> contacts(account)
            "user" -> user(account, arg.toLong())
            "dialog" -> dialog(account, arg.toLong())
            "push" -> push()
            "config" -> config(arg.ifEmpty { "*" })
            else -> "unknown dump '$what' (use contacts, user:<id>, dialog:<id>, push, config:<glob>)"
        }
    }

    private fun contacts(account: Int): String {
        val cc = ContactsController.getInstance(account)
        val uc = UserConfig.getInstance(account)
        val watched = DiagLog.current()?.watch.orEmpty().filter { it > 0 }
        val present = watched.joinToString(",") { "$it=${cc.contactsDict.containsKey(it)}" }
        return "acc=$account loaded=${cc.contactsLoaded} count=${cc.contacts.size} dict=${cc.contactsDict.size} " +
            "phonebook=${cc.contactsBook.size} sync=${uc.syncContacts} suggest=${uc.suggestContacts}" +
            if (present.isNotEmpty()) " watched[$present]" else ""
    }

    private fun user(account: Int, id: Long): String {
        val user = MessagesController.getInstance(account).getUser(id) ?: return "id=$id not in memory"
        val inDict = ContactsController.getInstance(account).contactsDict.containsKey(id)
        return "id=$id contact=${user.contact} mutual=${user.mutual_contact} inContactsDict=$inDict " +
            "phoneVisible=${!user.phone.isNullOrEmpty()} deleted=${user.deleted} bot=${user.bot} premium=${user.premium}"
    }

    private fun dialog(account: Int, id: Long): String {
        val d = MessagesController.getInstance(account).dialogs_dict.get(id) ?: return "id=$id not loaded"
        val inboxMax = MessagesController.getInstance(account).dialogs_read_inbox_max[id]
        return "id=$id top=${d.top_message} unread=${d.unread_count} mark=${d.unread_mark} readInboxMax=${d.read_inbox_max_id}/$inboxMax " +
            "folder=${d.folder_id} pinned=${d.pinned}"
    }

    private fun push(): String {
        val up = UnifiedPushHelper.isEnabled()
        return "type=${SharedConfig.pushType} tokenSet=${SharedConfig.pushString.isNotEmpty()} status='${SharedConfig.pushStringStatus}' " +
            "unifiedPush=$up distributor=${if (up) UnifiedPushHelper.currentDistributor() else "-"}"
    }

    private fun config(glob: String): String {
        val regex = Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val items = InuConfig::class.java.declaredFields
            .filter { regex.matches(it.name) && InuConfig.Item::class.java.isAssignableFrom(it.type) }
            .mapNotNull { f ->
                f.isAccessible = true
                val item = f.get(InuConfig) as? InuConfig.Item<*> ?: return@mapNotNull null
                "${f.name}=${item.value}"
            }
        return if (items.isEmpty()) "no InuConfig items match '$glob'" else items.joinToString(" ")
    }

    // endregion
}
