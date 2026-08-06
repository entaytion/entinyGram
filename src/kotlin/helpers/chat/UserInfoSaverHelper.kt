package desu.inugram.helpers.chat

import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC

/**
 * Saves the "precious" user info fields that the server stops returning after
 * a while (phone country, registration month, name/photo change dates) and
 * merges them back into [TLRPC.PeerSettings] so profile info rows keep working.
 *
 * Native port of the MaterialGram "user_info_saver" plugin:
 * - persistence keyed by dialog (user) id, stored as JSON in the `inu_kv` table
 * - on save: keeps the old precious values when the server sends empty ones
 * - on read: fills missing precious fields in place (no flag bits touched, so
 *   the object is never persisted back to stock storage)
 */
object UserInfoSaverHelper {
    private data class StoredInfo(
        val phoneCountry: String?,
        val registrationMonth: String?,
        val nameChangeDate: Int,
        val photoChangeDate: Int,
    ) {
        fun isEmpty(): Boolean = phoneCountry.isNullOrEmpty() &&
            registrationMonth.isNullOrEmpty() &&
            nameChangeDate == 0 && photoChangeDate == 0

        fun toJson(): JSONObject = JSONObject().apply {
            if (!phoneCountry.isNullOrEmpty()) put("pc", phoneCountry)
            if (!registrationMonth.isNullOrEmpty()) put("rm", registrationMonth)
            if (nameChangeDate != 0) put("nd", nameChangeDate)
            if (photoChangeDate != 0) put("pd", photoChangeDate)
        }

        companion object {
            fun fromJson(json: JSONObject?): StoredInfo? {
                if (json == null) return null
                return StoredInfo(
                    phoneCountry = json.optString("pc").takeIf { it.isNotEmpty() },
                    registrationMonth = json.optString("rm").takeIf { it.isNotEmpty() },
                    nameChangeDate = json.optInt("nd"),
                    photoChangeDate = json.optInt("pd"),
                )
            }
        }
    }

    // account -> (dialogId -> stored info); UI thread only, mirrors SavedMessagesHelper
    private val cache = LongSparseArray<LongSparseArray<StoredInfo>>()
    private val loadedAccounts = HashSet<Int>()

    private fun kvKey(account: Int, dialogId: Long): String = "user_info:$account:$dialogId"

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.SAVE_USER_INFO.value

    @JvmStatic
    fun ensureAccountLoaded(account: Int) {
        if (loadedAccounts.contains(account)) return
        val storage = MessagesStorage.getInstance(account) ?: return
        val db = storage.database
        if (db != null) {
            loadFromDb(account, db)
        } else {
            storage.storageQueue.postRunnable {
                val asyncDb = storage.database ?: return@postRunnable
                loadFromDb(account, asyncDb)
            }
        }
    }

    private fun loadFromDb(account: Int, db: org.telegram.SQLite.SQLiteDatabase) {
        if (loadedAccounts.contains(account)) return
        val rows = InuDatabaseHelper.readKvByPrefix(db, "user_info:$account:")
        val dialogArray = LongSparseArray<StoredInfo>()
        for ((key, value) in rows) {
            val dialogId = key.removePrefix("user_info:$account:").toLongOrNull() ?: continue
            val info = StoredInfo.fromJson(runCatching { JSONObject(value) }.getOrNull()) ?: continue
            if (!info.isEmpty()) {
                dialogArray.put(dialogId, info)
            }
        }
        AndroidUtilities.runOnUIThread {
            if (loadedAccounts.contains(account)) return@runOnUIThread
            cache.put(account.toLong(), dialogArray)
            loadedAccounts.add(account)
            // trigger a re-read of peer settings so merged values show up in already-open UIs
            for (i in 0 until dialogArray.size()) {
                NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.peerSettingsDidLoad, dialogArray.keyAt(i))
            }
        }
    }

    private fun getFromCache(account: Int, dialogId: Long): StoredInfo? =
        cache.get(account.toLong())?.get(dialogId)

    private fun putToCache(account: Int, dialogId: Long, info: StoredInfo) {
        var dialogArray = cache.get(account.toLong())
        if (dialogArray == null) {
            dialogArray = LongSparseArray()
            cache.put(account.toLong(), dialogArray)
        }
        dialogArray.put(dialogId, info)
    }

    /**
     * Called from MessagesController.savePeerSettings after stock persistence.
     * All call sites are on the UI thread; the DB write goes to the storage queue.
     */
    @JvmStatic
    fun onSavePeerSettings(account: Int, dialogId: Long, settings: TLRPC.PeerSettings?) {
        if (!isEnabled() || settings == null) return
        val new = StoredInfo(
            phoneCountry = settings.phone_country?.takeIf { it.isNotEmpty() },
            registrationMonth = settings.registration_month?.takeIf { it.isNotEmpty() },
            nameChangeDate = settings.name_change_date,
            photoChangeDate = settings.photo_change_date,
        )
        if (new.isEmpty()) return

        // merge: keep old precious values when the server no longer sends them
        val prev = getFromCache(account, dialogId)
        val merged = StoredInfo(
            phoneCountry = new.phoneCountry ?: prev?.phoneCountry,
            registrationMonth = new.registrationMonth ?: prev?.registrationMonth,
            nameChangeDate = if (new.nameChangeDate != 0) new.nameChangeDate else prev?.nameChangeDate ?: 0,
            photoChangeDate = if (new.photoChangeDate != 0) new.photoChangeDate else prev?.photoChangeDate ?: 0,
        )
        if (merged == prev) return

        putToCache(account, dialogId, merged)
        val json = merged.toJson().toString()
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.writeKv(db, kvKey(account, dialogId), json)
        }
    }

    /**
     * Called from MessagesController.getPeerSettings (UI thread hot path).
     * Never does a DB read here; fills missing precious fields in place from cache.
     */
    @JvmStatic
    fun onGetPeerSettings(account: Int, dialogId: Long, current: TLRPC.PeerSettings?): TLRPC.PeerSettings? {
        if (!isEnabled() || current == null) return current
        ensureAccountLoaded(account)
        val info = getFromCache(account, dialogId) ?: return current
        if (current.phone_country.isNullOrEmpty() && !info.phoneCountry.isNullOrEmpty()) {
            current.phone_country = info.phoneCountry
        }
        if (current.registration_month.isNullOrEmpty() && !info.registrationMonth.isNullOrEmpty()) {
            current.registration_month = info.registrationMonth
        }
        if (current.name_change_date == 0 && info.nameChangeDate != 0) {
            current.name_change_date = info.nameChangeDate
        }
        if (current.photo_change_date == 0 && info.photoChangeDate != 0) {
            current.photo_change_date = info.photoChangeDate
        }
        return current
    }
}
