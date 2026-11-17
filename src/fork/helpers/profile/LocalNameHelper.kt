package desu.inugram.helpers.profile

import desu.inugram.helpers.InuPrefs
import android.content.Intent
import android.util.Log
import desu.inugram.InuConfig
import desu.inugram.ui.profile.LocalNameEditor
import org.json.JSONObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LocalNameHelper {
    private const val PREFS = "inu_local_names"
    private const val TAG = "LocalNameHelper"
    internal const val F_FIRST = "first_name"
    internal const val F_LAST = "last_name"
    internal const val F_TITLE = "title"
    internal const val F_USERNAME = "username"
    internal const val F_ABOUT = "about"
    internal const val F_VERIFIED = "verified"
    internal const val F_PREMIUM = "premium"
    internal const val F_EMOJI = "emoji_status"

    private val USER_FIELDS = listOf(F_FIRST, F_LAST, F_USERNAME, F_ABOUT, F_VERIFIED, F_PREMIUM, F_EMOJI)
    private val CHAT_FIELDS = listOf(F_TITLE, F_USERNAME, F_ABOUT, F_VERIFIED, F_EMOJI)

    class Entry(
        val over: MutableMap<String, String> = HashMap(),
        val orig: MutableMap<String, String> = HashMap(),
        var avatar: String? = null,
    ) {
        fun isEmpty() = over.isEmpty() && avatar == null
    }

    private val maps = arrayOfNulls<ConcurrentHashMap<Long, Entry>>(UserConfig.MAX_ACCOUNT_COUNT)

    private fun prefs() = InuPrefs.of(PREFS)

    private fun avatarDir() = File(ApplicationLoader.applicationContext.filesDir, "inu_local_avatars").apply { mkdirs() }

    private fun map(account: Int): ConcurrentHashMap<Long, Entry> {
        maps[account]?.let { return it }
        synchronized(this) {
            maps[account]?.let { return it }
            val loaded = ConcurrentHashMap<Long, Entry>()
            try {
                val root = JSONObject(prefs().getString("a$account", null) ?: "{}")
                for (key in root.keys()) {
                    val node = root.getJSONObject(key)
                    val entry = Entry()
                    node.optJSONObject("o")?.let { o -> for (f in o.keys()) entry.over[f] = o.getString(f) }
                    node.optJSONObject("w")?.let { w -> for (f in w.keys()) entry.orig[f] = w.getString(f) }
                    entry.avatar = node.optString("av").takeIf { it.isNotEmpty() }
                    if (!entry.isEmpty()) loaded[key.toLong()] = entry
                }
            } catch (e: Throwable) {
                Log.d(TAG, "load failed", e)
            }
            maps[account] = loaded
            return loaded
        }
    }

    private fun persist(account: Int) {
        val root = JSONObject()
        for ((id, entry) in map(account)) {
            val node = JSONObject().put("o", JSONObject(entry.over as Map<*, *>)).put("w", JSONObject(entry.orig as Map<*, *>))
            entry.avatar?.let { node.put("av", it) }
            root.put(id.toString(), node)
        }
        prefs().edit().putString("a$account", root.toString()).apply()
    }

    internal fun entryOf(account: Int, dialogId: Long): Entry? = map(account)[dialogId]

    internal fun fieldsFor(dialogId: Long) = if (dialogId > 0) USER_FIELDS else CHAT_FIELDS

    internal fun getField(user: TLRPC.User, f: String): String? = when (f) {
        F_FIRST -> user.first_name
        F_LAST -> user.last_name
        F_VERIFIED -> if (user.verified) "1" else "0"
        F_PREMIUM -> if (user.premium) "1" else "0"
        F_EMOJI -> (UserObject.getEmojiStatusDocumentId(user) ?: 0L).toString()
        else -> user.username
    }

    private fun setField(user: TLRPC.User, f: String, v: String?) {
        when (f) {
            F_FIRST -> user.first_name = v
            F_LAST -> user.last_name = v
            F_USERNAME -> user.username = v
            F_VERIFIED -> user.verified = v == "1"
            F_PREMIUM -> user.premium = v == "1"
            F_EMOJI -> user.emoji_status = statusFor(v)
        }
    }

    private fun statusFor(v: String?): TLRPC.EmojiStatus? {
        val id = v?.toLongOrNull() ?: 0L
        if (id > 0L) return TLRPC.TL_emojiStatus().also { it.document_id = id }
        return if (v == null) null else TLRPC.TL_emojiStatusEmpty()
    }

    internal fun getField(chat: TLRPC.Chat, f: String): String? = when (f) {
        F_TITLE -> chat.title
        F_VERIFIED -> if (chat.verified) "1" else "0"
        F_EMOJI -> DialogObject.getEmojiStatusDocumentId(chat.emoji_status).toString()
        else -> chat.username
    }

    private fun setField(chat: TLRPC.Chat, f: String, v: String?) {
        when (f) {
            F_TITLE -> chat.title = v
            F_USERNAME -> chat.username = v
            F_VERIFIED -> chat.verified = v == "1"
            F_EMOJI -> chat.emoji_status = statusFor(v)
        }
    }

    private inline fun applyEntry(account: Int, entry: Entry, only: String?, get: (String) -> String?, set: (String, String?) -> Unit) {
        var dirty = false
        for ((f, v) in entry.over) {
            if (only != null && f != only) continue
            if (only == null && f == F_ABOUT) continue
            val cur = get(f).orEmpty()
            if (cur == v) continue
            entry.orig[f] = cur
            set(f, v)
            dirty = true
        }
        if (dirty) persist(account)
    }

    @JvmStatic
    fun applyTo(user: TLRPC.User?, account: Int) {
        if (user == null || !InuConfig.LOCAL_NAMES.value) return
        val entry = map(account)[user.id] ?: return
        applyEntry(account, entry, null, { getField(user, it) }, { f, v -> setField(user, f, v) })
    }

    @JvmStatic
    fun applyTo(chat: TLRPC.Chat?, account: Int) {
        if (chat == null || !InuConfig.LOCAL_NAMES.value) return
        val entry = map(account)[-chat.id] ?: return
        applyEntry(account, entry, null, { getField(chat, it) }, { f, v -> setField(chat, f, v) })
    }

    @JvmStatic
    fun applyTo(info: TLRPC.UserFull?, account: Int) {
        if (info == null || !InuConfig.LOCAL_NAMES.value) return
        val entry = map(account)[info.id] ?: return
        applyEntry(account, entry, F_ABOUT, { info.about }, { _, v -> info.about = v })
    }

    @JvmStatic
    fun applyTo(info: TLRPC.ChatFull?, account: Int) {
        if (info == null || !InuConfig.LOCAL_NAMES.value) return
        val entry = map(account)[-info.id] ?: return
        applyEntry(account, entry, F_ABOUT, { info.about }, { _, v -> info.about = v })
    }

    @JvmStatic
    fun hasAvatar(account: Int, dialogId: Long): Boolean {
        if (!InuConfig.LOCAL_NAMES.value) return false
        val name = map(account)[dialogId]?.avatar ?: return false
        return File(avatarDir(), name).exists()
    }

    @JvmStatic
    fun avatarLocation(account: Int, dialogId: Long, type: Int): ImageLocation? {
        if (type == ImageLocation.TYPE_STRIPPED || type == ImageLocation.TYPE_VIDEO_BIG || type == ImageLocation.TYPE_VIDEO_SMALL) return null
        val name = map(account)[dialogId]?.avatar ?: return null
        return ImageLocation.getForPath(File(avatarDir(), name).absolutePath)
    }

    fun entries(account: Int): List<Long> =
        map(account).keys.sortedBy { displayName(account, it).lowercase() }

    fun count(account: Int) = map(account).size

    private fun controller(account: Int) = MessagesController.getInstance(account)

    fun displayName(account: Int, dialogId: Long): String {
        val entry = map(account)[dialogId]
        if (dialogId > 0) {
            val first = entry?.over?.get(F_FIRST) ?: entry?.orig?.get(F_FIRST)
            val last = entry?.over?.get(F_LAST) ?: entry?.orig?.get(F_LAST)
            val joined = listOfNotNull(first, last).filter { it.isNotEmpty() }.joinToString(" ")
            if (joined.isNotEmpty()) return joined
            controller(account).getUser(dialogId)?.let { return UserObject.getUserName(it) }
            entry?.over?.get(F_USERNAME)?.let { return "@$it" }
            return "ID $dialogId"
        }
        entry?.over?.get(F_TITLE)?.let { return it }
        controller(account).getChat(-dialogId)?.title?.let { return it }
        entry?.over?.get(F_USERNAME)?.let { return "@$it" }
        return "ID $dialogId"
    }

    fun originalName(account: Int, dialogId: Long): String {
        val entry = map(account)[dialogId]
        if (dialogId > 0) {
            val user = controller(account).getUser(dialogId)
            fun orig(f: String) = entry?.orig?.get(f) ?: user?.let { getField(it, f) }.orEmpty()
            return listOf(orig(F_FIRST), orig(F_LAST)).filter { it.isNotEmpty() }.joinToString(" ")
        }
        val chat = controller(account).getChat(-dialogId)
        return entry?.orig?.get(F_TITLE) ?: chat?.title.orEmpty()
    }

    fun summary(account: Int, dialogId: Long): String {
        val original = originalName(account, dialogId)
        return if (original.isEmpty()) "ID $dialogId" else LocaleController.formatString(R.string.InuLocalNameWas, original)
    }

    internal fun cached(account: Int, dialogId: Long): TLObject? =
        if (dialogId > 0) controller(account).getUser(dialogId) else controller(account).getChat(-dialogId)

    internal fun cachedFull(account: Int, dialogId: Long): TLObject? =
        if (dialogId > 0) controller(account).getUserFull(dialogId) else controller(account).getChatFull(-dialogId)

    private fun originalField(account: Int, dialogId: Long, entry: Entry?, f: String): String {
        entry?.orig?.get(f)?.let { return it }
        return when (val obj = cached(account, dialogId)) {
            is TLRPC.User -> getField(obj, f)
            is TLRPC.Chat -> getField(obj, f)
            else -> null
        }.orEmpty()
    }

    private fun originalAbout(account: Int, dialogId: Long, entry: Entry?): String {
        entry?.orig?.get(F_ABOUT)?.let { return it }
        return when (val full = cachedFull(account, dialogId)) {
            is TLRPC.UserFull -> full.about
            is TLRPC.ChatFull -> full.about
            else -> null
        }.orEmpty()
    }

    private fun applyToCached(account: Int, dialogId: Long) {
        when (val obj = cached(account, dialogId)) {
            is TLRPC.User -> applyTo(obj, account)
            is TLRPC.Chat -> applyTo(obj, account)
        }
        when (val full = cachedFull(account, dialogId)) {
            is TLRPC.UserFull -> applyTo(full, account)
            is TLRPC.ChatFull -> applyTo(full, account)
        }
    }

    private fun restoreCached(account: Int, dialogId: Long, entry: Entry, fields: Collection<String>) {
        val obj = cached(account, dialogId)
        if (obj != null && fields.any { it != F_ABOUT && entry.orig.containsKey(it) }) {
            for (f in fields) {
                if (f == F_ABOUT) continue
                val value = (entry.orig[f] ?: continue).ifEmpty { null }
                when (obj) {
                    is TLRPC.User -> setField(obj, f, value)
                    is TLRPC.Chat -> setField(obj, f, value)
                }
            }
            val storage = MessagesStorage.getInstance(account)
            when (obj) {
                is TLRPC.User -> storage.putUsersAndChats(arrayListOf(obj), null, true, true)
                is TLRPC.Chat -> storage.putUsersAndChats(null, arrayListOf(obj), true, true)
            }
        }
        if (F_ABOUT in fields && entry.orig.containsKey(F_ABOUT)) {
            val about = entry.orig[F_ABOUT].orEmpty()
            when (val full = cachedFull(account, dialogId)) {
                is TLRPC.UserFull -> {
                    full.about = about
                    MessagesStorage.getInstance(account).updateUserInfo(full, true)
                }
                is TLRPC.ChatFull -> {
                    full.about = about
                    MessagesStorage.getInstance(account).updateChatInfo(full, true)
                }
            }
        }
    }

    fun save(account: Int, dialogId: Long, values: Map<String, String>) {
        val fields = fieldsFor(dialogId)
        val existing = map(account)[dialogId]
        val clearsAbout = values[F_ABOUT]?.isBlank() == true && F_ABOUT in fields && originalAbout(account, dialogId, existing).isNotEmpty()
        val clean = values.filterKeys { it in fields }
            .mapValues { it.value.trim().let { v -> if (it.key == F_USERNAME) v.removePrefix("@") else v } }
            .filter { it.value.isNotEmpty() || (it.key == F_ABOUT && clearsAbout) }
            .filter { it.value != (if (it.key == F_ABOUT) originalAbout(account, dialogId, existing) else originalField(account, dialogId, existing, it.key)) || (it.key == F_ABOUT && clearsAbout) }
            .toMutableMap()
        val emoji = clean[F_EMOJI]
        if (dialogId > 0 && emoji != null && emoji != "0" && originalField(account, dialogId, existing, F_PREMIUM) == "0") clean[F_PREMIUM] = "1"
        if (clean.isEmpty() && existing?.avatar == null) {
            remove(account, dialogId)
            return
        }
        val entry = existing ?: Entry().also { map(account)[dialogId] = it }
        val dropped = entry.over.keys.filter { it !in clean }
        restoreCached(account, dialogId, entry, dropped)
        dropped.forEach { entry.over.remove(it); entry.orig.remove(it) }
        entry.over.putAll(clean)
        applyToCached(account, dialogId)
        persist(account)
        refreshUi(account, dialogId)
    }

    fun saveMerged(account: Int, dialogId: Long, extra: Map<String, String>) {
        val base = HashMap<String, String>(map(account)[dialogId]?.over ?: emptyMap())
        base.putAll(extra)
        save(account, dialogId, base)
    }

    fun remove(account: Int, dialogId: Long) {
        val entry = map(account).remove(dialogId) ?: return
        restoreCached(account, dialogId, entry, entry.orig.keys)
        entry.avatar?.let { File(avatarDir(), it).delete() }
        persist(account)
        refreshUi(account, dialogId)
    }

    fun clear(account: Int) {
        for (id in map(account).keys.toList()) {
            val entry = map(account).remove(id) ?: continue
            restoreCached(account, id, entry, entry.orig.keys)
            entry.avatar?.let { File(avatarDir(), it).delete() }
        }
        persist(account)
        refreshUi(account, 0L)
    }

    internal fun setAvatar(account: Int, dialogId: Long, source: File) {
        val entry = map(account).getOrPut(dialogId) { Entry() }
        val name = "${account}_${dialogId}_${System.currentTimeMillis()}.jpg"
        source.copyTo(File(avatarDir(), name), overwrite = true)
        entry.avatar?.let { File(avatarDir(), it).delete() }
        entry.avatar = name
        persist(account)
        refreshUi(account, dialogId)
    }

    internal fun removeAvatar(account: Int, dialogId: Long) {
        val entry = map(account)[dialogId] ?: return
        entry.avatar?.let { File(avatarDir(), it).delete() }
        entry.avatar = null
        if (entry.isEmpty()) map(account).remove(dialogId)
        persist(account)
        refreshUi(account, dialogId)
    }

    @JvmStatic
    fun onToggle() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(account).isClientActivated) continue
            for ((id, entry) in map(account)) {
                if (InuConfig.LOCAL_NAMES.value) applyToCached(account, id) else restoreCached(account, id, entry, entry.orig.keys)
            }
            if (!InuConfig.LOCAL_NAMES.value) map(account).values.forEach { it.orig.clear() }
            persist(account)
            refreshUi(account, 0L)
        }
    }

    private fun refreshUi(account: Int, dialogId: Long) {
        AndroidUtilities.runOnUIThread {
            val center = NotificationCenter.getInstance(account)
            center.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL)
            center.postNotificationName(NotificationCenter.dialogsNeedReload)
            center.postNotificationName(NotificationCenter.contactsDidLoad)
            if (dialogId > 0) {
                (cachedFull(account, dialogId) as? TLRPC.UserFull)?.let { center.postNotificationName(NotificationCenter.userInfoDidLoad, dialogId, it) }
            } else if (dialogId < 0) {
                (cachedFull(account, dialogId) as? TLRPC.ChatFull)?.let { center.postNotificationName(NotificationCenter.chatInfoDidLoad, it, 0, false, true) }
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
        }
    }

    fun exportJson(account: Int): String {
        val root = JSONObject()
        for ((id, entry) in map(account)) {
            if (entry.over.isNotEmpty()) root.put(id.toString(), JSONObject(entry.over as Map<*, *>))
        }
        return JSONObject().put("local_names", root).toString()
    }

    fun importJson(account: Int, text: String): Int {
        val root = JSONObject(text).getJSONObject("local_names")
        var imported = 0
        for (key in root.keys()) {
            val dialogId = key.toLongOrNull() ?: continue
            if (dialogId == 0L) continue
            val node = root.optJSONObject(key) ?: continue
            val values = HashMap<String, String>()
            for (f in fieldsFor(dialogId)) node.optString(f).takeIf { it.isNotBlank() }?.let { values[f] = it }
            if (values.isEmpty()) continue
            save(account, dialogId, values)
            imported++
        }
        return imported
    }

    fun showEditor(fragment: BaseFragment, account: Int, dialogId: Long, onChanged: Runnable? = null) =
        LocalNameEditor.show(fragment, account, dialogId, onChanged)

    fun shareExport(fragment: BaseFragment, account: Int) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, exportJson(account))
        }
        fragment.parentActivity?.startActivity(Intent.createChooser(send, LocaleController.getString(R.string.InuLocalNamesExport)))
    }
}
