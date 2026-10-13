package desu.inugram.helpers.chat

import android.content.Context
import android.util.Base64
import desu.inugram.InuConfig
import desu.inugram.ui.showInputDialog
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import java.util.concurrent.ConcurrentHashMap

// entiny: inspired by Local Message Edit plugin (Pixuz)
object LocalEditHelper {
    private const val PREFS = "inu_local_edits"

    private class Entry(val original: String, val text: String, val entities: String?)

    private val prefs by lazy { ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val entries: ConcurrentHashMap<String, Entry> by lazy {
        val map = ConcurrentHashMap<String, Entry>()
        for ((key, value) in prefs.all) {
            val json = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            map[key] = Entry(json.optString("o"), json.optString("t"), json.optString("e").ifEmpty { null })
        }
        map
    }

    private fun key(account: Int, owner: TLRPC.Message) = "$account:${MessageObject.getDialogId(owner)}:${owner.id}"

    private fun key(message: MessageObject) = key(message.currentAccount, message.messageOwner)

    private fun persist(key: String, entry: Entry?) {
        prefs.edit().apply {
            if (entry == null) {
                remove(key)
            } else {
                putString(key, JSONObject().put("o", entry.original).put("t", entry.text).put("e", entry.entities ?: "").toString())
            }
        }.apply()
    }

    private fun serialize(list: List<TLRPC.MessageEntity>?): String? {
        if (list.isNullOrEmpty()) return null
        val data = SerializedData()
        data.writeInt32(list.size)
        for (entity in list) entity.serializeToStream(data)
        return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
    }

    private fun deserialize(value: String?): ArrayList<TLRPC.MessageEntity> {
        val out = ArrayList<TLRPC.MessageEntity>()
        if (value.isNullOrEmpty()) return out
        val data = SerializedData(Base64.decode(value, Base64.NO_WRAP))
        val count = data.readInt32(true)
        for (i in 0 until count) {
            TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(true), true)?.let { out.add(it) }
        }
        return out
    }

    @JvmStatic
    fun apply(message: MessageObject) {
        if (!InuConfig.LOCAL_MESSAGE_EDIT.value) return
        val owner = message.messageOwner ?: return
        if (owner.action != null) return
        val entry = entries[key(message)] ?: return
        if (owner.message == entry.text) return
        owner.message = entry.text
        owner.entities = ArrayList()
    }

    fun has(message: MessageObject): Boolean = entries.containsKey(key(message))

    fun canEdit(message: MessageObject): Boolean {
        if (!InuConfig.LOCAL_MESSAGE_EDIT.value) return false
        val owner = message.messageOwner ?: return false
        if (owner.action != null || owner.id <= 0) return false
        return !owner.message.isNullOrEmpty() || has(message)
    }

    fun count(): Int = entries.size

    fun clearAll() {
        entries.clear()
        prefs.edit().clear().apply()
    }

    fun edit(activity: ChatActivity, message: MessageObject) {
        val owner = message.messageOwner ?: return
        val key = key(message)
        val existing = entries[key]
        showInputDialog(
            activity,
            LocaleController.getString(R.string.InuLocalEditTitle),
            initialText = existing?.text ?: owner.message.orEmpty(),
            multiline = true,
        ) { text ->
            if (text.isEmpty()) return@showInputDialog false
            val original = existing?.original ?: owner.message.orEmpty()
            if (text == original) {
                if (existing != null) reset(activity, message)
                return@showInputDialog true
            }
            val entry = Entry(original, text, existing?.entities ?: serialize(owner.entities))
            entries[key] = entry
            persist(key, entry)
            owner.message = text
            owner.entities = ArrayList()
            refresh(activity, message)
            BulletinFactory.of(activity).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuLocalEditSaved)).show()
            true
        }
    }

    fun reset(activity: ChatActivity, message: MessageObject) {
        val owner = message.messageOwner ?: return
        val key = key(message)
        val entry = entries.remove(key) ?: return
        persist(key, null)
        owner.message = entry.original
        owner.entities = deserialize(entry.entities)
        refresh(activity, message)
    }

    private fun refresh(activity: ChatActivity, message: MessageObject) {
        val media = message.messageOwner.media
        val hasMedia = media != null && media !is TLRPC.TL_messageMediaEmpty && media !is TLRPC.TL_messageMediaWebPage
        if (hasMedia) {
            message.generateCaption()
            message.resetLayout()
        } else {
            message.applyNewText()
        }
        activity.chatAdapter?.updateRowWithMessageObject(message, false, false)
    }
}
