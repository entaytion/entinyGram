package desu.inugram.helpers

import com.google.android.exoplayer2.util.Log
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.MessagesStorage

object InuDatabaseHelper {
    @JvmStatic
    fun migrate(messagesStorage: MessagesStorage) {
        val db = messagesStorage.database;

        db.executeFast("CREATE TABLE IF NOT EXISTS inu_kv(key TEXT PRIMARY KEY, value TEXT)")
            .stepThis().dispose();
        var version = readKv(db, "version")?.toInt() ?: 0;
        Log.d("InuDatabaseHelper", "migrating from version $version")

        if (version == 0) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_folder_meta(filter_id INTEGER PRIMARY KEY, emoticon TEXT)")
                .stepThis().dispose();
            writeKv(db, "version", "1")
            version = 1
        }

        if (version == 1) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_deleted_messages(dialog_id INTEGER, msg_id INTEGER, from_id INTEGER, text TEXT, date INTEGER, PRIMARY KEY(dialog_id, msg_id))")
                .stepThis().dispose();
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_edit_history(dialog_id INTEGER, msg_id INTEGER, text TEXT, date INTEGER)")
                .stepThis().dispose();
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_edit_history ON inu_edit_history(dialog_id, msg_id)")
                .stepThis().dispose();
            writeKv(db, "version", "2")
            version = 2
        }

        if (version == 2) {
            // no schema changes; just bump to enable TTL prune support
            writeKv(db, "version", "3")
            version = 3
        }

        Log.d("InuDatabaseHelper", "migrating finished, new version = $version")
    }

    fun saveDeletedMessage(db: SQLiteDatabase, dialogId: Long, msgId: Int, fromId: Long, text: String, date: Int) {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_deleted_messages VALUES(?, ?, ?, ?, ?)");
        query.bindLong(1, dialogId)
        query.bindInteger(2, msgId)
        query.bindLong(3, fromId)
        query.bindString(4, text)
        query.bindInteger(5, date)
        query.step()
        query.dispose()
    }

    fun loadDeletedMessageIds(db: SQLiteDatabase): Map<Long, HashSet<Int>> {
        val map = HashMap<Long, HashSet<Int>>()
        val cursor = db.queryFinalized("SELECT dialog_id, msg_id FROM inu_deleted_messages")
        try {
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                val msgId = cursor.intValue(1)
                map.getOrPut(dialogId) { HashSet() }.add(msgId)
            }
        } finally {
            cursor.dispose()
        }
        return map
    }

    fun saveEditHistory(db: SQLiteDatabase, dialogId: Long, msgId: Int, text: String, date: Int) {
        val query = db.executeFast("INSERT INTO inu_edit_history VALUES(?, ?, ?, ?)");
        query.bindLong(1, dialogId)
        query.bindInteger(2, msgId)
        query.bindString(3, text)
        query.bindInteger(4, date)
        query.step()
        query.dispose()
    }

    fun loadEditHistory(db: SQLiteDatabase, dialogId: Long, msgId: Int): List<Pair<Long, String>> {
        val list = ArrayList<Pair<Long, String>>()
        val cursor = db.queryFinalized("SELECT date, text FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? ORDER BY date ASC", dialogId, msgId)
        try {
            while (cursor.next()) {
                val date = cursor.longValue(0)
                val text = cursor.stringValue(1)
                list.add(Pair(date, text))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    /**
     * Removes deleted messages older than [cutoffUnixSec] from the DB.
     * Returns the number of rows deleted.
     */
    fun pruneDeletedMessages(db: SQLiteDatabase, cutoffUnixSec: Long): Int {
        db.executeFast("DELETE FROM inu_deleted_messages WHERE date > 0 AND date < ?")
            .also {
                it.bindLong(1, cutoffUnixSec)
                it.step()
                it.dispose()
            }
        // SQLite doesn't give us rows-affected easily via this API, that's fine
        return 0
    }

    /**
     * Removes edit history entries older than [cutoffUnixSec] from the DB.
     */
    fun pruneEditHistory(db: SQLiteDatabase, cutoffUnixSec: Long) {
        db.executeFast("DELETE FROM inu_edit_history WHERE date > 0 AND date < ?")
            .also {
                it.bindLong(1, cutoffUnixSec)
                it.step()
                it.dispose()
            }
    }

    data class DialogCacheStat(
        val dialogId: Long,
        val count: Int,
        val estimatedSize: Long
    )

    fun getDeletedMessagesStats(db: SQLiteDatabase): List<DialogCacheStat> {
        val map = HashMap<Long, Pair<Int, Long>>()
        var cursor = db.queryFinalized("SELECT dialog_id, COUNT(*), SUM(LENGTH(text)) FROM inu_deleted_messages GROUP BY dialog_id")
        try {
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                val count = cursor.intValue(1)
                val textLen = cursor.longValue(2)
                val estSize = (count * 250L) + textLen
                map[dialogId] = Pair(count, estSize)
            }
        } finally {
            cursor.dispose()
        }

        cursor = db.queryFinalized("SELECT dialog_id, COUNT(*), SUM(LENGTH(text)) FROM inu_edit_history GROUP BY dialog_id")
        try {
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                val count = cursor.intValue(1)
                val textLen = cursor.longValue(2)
                val estSize = (count * 150L) + textLen
                val existing = map[dialogId]
                if (existing != null) {
                    map[dialogId] = Pair(existing.first + count, existing.second + estSize)
                } else {
                    map[dialogId] = Pair(count, estSize)
                }
            }
        } finally {
            cursor.dispose()
        }

        return map.map { (id, pair) -> DialogCacheStat(id, pair.first, pair.second) }.sortedByDescending { it.estimatedSize }
    }

    fun clearDeletedMessages(db: SQLiteDatabase, dialogIds: Collection<Long>? = null) {
        if (dialogIds == null) {
            db.executeFast("DELETE FROM inu_deleted_messages").stepThis().dispose()
            db.executeFast("DELETE FROM inu_edit_history").stepThis().dispose()
        } else if (dialogIds.isNotEmpty()) {
            val idsStr = dialogIds.joinToString(",")
            db.executeFast("DELETE FROM inu_deleted_messages WHERE dialog_id IN ($idsStr)").stepThis().dispose()
            db.executeFast("DELETE FROM inu_edit_history WHERE dialog_id IN ($idsStr)").stepThis().dispose()
        }
    }

    fun readKv(db: SQLiteDatabase, key: String): String? {
        val cursor = db.queryFinalized("select value from inu_kv where key = ?", key);
        try {
            if (!cursor.next()) {
                return null;
            }
            return cursor.stringValue(0);
        } finally {
            cursor.dispose();
        }
    }

    fun writeKv(db: SQLiteDatabase, key: String, value: String): Unit {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_kv VALUES(?, ?)");
        query.bindString(1, key)
        query.bindString(2, value)
        query.step()
        query.dispose()
    }
}