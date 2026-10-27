package desu.inugram.helpers

import android.util.Log
import desu.inugram.helpers.archive.ArchiveStore
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC

object InuDatabaseHelper {
    private const val TAG = "InuDatabaseHelper"
    private const val START_MIGRATION_BELOW = 2

    private class Migration(val to: Int, val atomic: Boolean = false, val run: (SQLiteDatabase) -> Unit)

    private val MIGRATIONS = listOf(
        Migration(1) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_folder_meta(filter_id INTEGER PRIMARY KEY, emoticon TEXT)")
                .stepThis().dispose();
        },
        Migration(2) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_deleted_messages(dialog_id INTEGER, msg_id INTEGER, from_id INTEGER, text TEXT, date INTEGER, PRIMARY KEY(dialog_id, msg_id))")
                .stepThis().dispose();
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_edit_history(dialog_id INTEGER, msg_id INTEGER, text TEXT, date INTEGER)")
                .stepThis().dispose();
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_edit_history ON inu_edit_history(dialog_id, msg_id)")
                .stepThis().dispose();
        },
        Migration(3) {},
        Migration(4) { db ->
            try {
                db.executeFast("ALTER TABLE inu_deleted_messages ADD COLUMN media_path TEXT").stepThis().dispose()
            } catch (e: Throwable) { }
            try {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN media_path TEXT").stepThis().dispose()
            } catch (e: Throwable) { }
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_deleted_reactions(dialog_id INTEGER, msg_id INTEGER, emoji TEXT, count INTEGER, custom_id INTEGER, PRIMARY KEY(dialog_id, msg_id, emoji))")
                .stepThis().dispose()
        },
        Migration(5) {},
        Migration(6) { db ->
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_deleted_messages_dialog ON inu_deleted_messages(dialog_id)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_deleted_messages_date ON inu_deleted_messages(date)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_edit_history_date ON inu_edit_history(date)")
                .stepThis().dispose()
        },
        Migration(7) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_local_pins(scope INTEGER NOT NULL, dialog_id INTEGER NOT NULL, pin_order INTEGER NOT NULL, PRIMARY KEY(scope, dialog_id))")
                .stepThis().dispose()
        },
        Migration(8) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_presence_watch(user_id INTEGER PRIMARY KEY)")
                .stepThis().dispose()
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_presence_logs(user_id INTEGER NOT NULL, status_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_presence_logs_user ON inu_presence_logs(user_id)")
                .stepThis().dispose()
        },
        Migration(9) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_local_folder_chats(filter_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, PRIMARY KEY(filter_id, dialog_id))")
                .stepThis().dispose()
        },
        Migration(10) { db ->
            try {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN entities BLOB").stepThis().dispose()
            } catch (e: Throwable) { }
            try {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN media BLOB").stepThis().dispose()
            } catch (e: Throwable) { }
        },
        Migration(11) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_recent_dialogs(dialog_id INTEGER PRIMARY KEY, opened_at INTEGER NOT NULL)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_recent_dialogs_opened ON inu_recent_dialogs(opened_at)")
                .stepThis().dispose()
        },
        Migration(12) { db ->
            db.executeFast("DELETE FROM inu_deleted_messages WHERE (text IS NULL OR text = '') AND (media_path IS NULL OR media_path = '')")
                .stepThis().dispose()
        },
        Migration(13) {},
        Migration(14) { db ->
            try {
                db.executeFast("ALTER TABLE inu_deleted_messages ADD COLUMN message BLOB").stepThis().dispose()
            } catch (e: Throwable) { }
        },
        Migration(15) { db ->
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_presence_logs_ts ON inu_presence_logs(timestamp)")
                .stepThis().dispose()
        },
        Migration(16) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_read_times(dialog_id INTEGER NOT NULL, max_id INTEGER NOT NULL, read_at INTEGER NOT NULL, PRIMARY KEY(dialog_id, max_id))")
                .stepThis().dispose()
        },
        Migration(17) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_last_seen(user_id INTEGER PRIMARY KEY, seen_at INTEGER NOT NULL)")
                .stepThis().dispose()
        },
        Migration(18) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_contact_changes(user_id INTEGER NOT NULL, field TEXT NOT NULL, old_value TEXT, new_value TEXT, date INTEGER NOT NULL)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_contact_changes_user ON inu_contact_changes(user_id)")
                .stepThis().dispose()
        },
        Migration(19) { db ->
            val cursor = db.queryFinalized("PRAGMA table_info(inu_edit_history)")
            val hasArchivedAt = try {
                var found = false
                while (cursor.next()) {
                    if (cursor.stringValue(1) == "archived_at") found = true
                }
                found
            } finally {
                cursor.dispose()
            }
            if (!hasArchivedAt) {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN archived_at INTEGER").stepThis().dispose()
            }
            db.executeFast("UPDATE inu_edit_history SET archived_at = date WHERE archived_at IS NULL").stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_edit_history_archived_at ON inu_edit_history(archived_at)").stepThis().dispose()
        },
        Migration(20, atomic = true) { db ->
            db.executeFast("CREATE TABLE inu_edit_history_v20(id INTEGER PRIMARY KEY AUTOINCREMENT, dialog_id INTEGER, msg_id INTEGER, text TEXT, date INTEGER, media_path TEXT, entities BLOB, media BLOB, archived_at INTEGER)").stepThis().dispose()
            db.executeFast("INSERT INTO inu_edit_history_v20(id, dialog_id, msg_id, text, date, media_path, entities, media, archived_at) SELECT rowid, dialog_id, msg_id, text, date, media_path, entities, media, archived_at FROM inu_edit_history").stepThis().dispose()
            db.executeFast("DROP TABLE inu_edit_history").stepThis().dispose()
            db.executeFast("ALTER TABLE inu_edit_history_v20 RENAME TO inu_edit_history").stepThis().dispose()
            db.executeFast("CREATE INDEX idx_inu_edit_history ON inu_edit_history(dialog_id, msg_id)").stepThis().dispose()
            db.executeFast("CREATE INDEX idx_inu_edit_history_date ON inu_edit_history(date)").stepThis().dispose()
            db.executeFast("CREATE INDEX idx_inu_edit_history_archived_at ON inu_edit_history(archived_at)").stepThis().dispose()
        },
        Migration(21) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_hidden_dialogs(dialog_id INTEGER PRIMARY KEY)")
                .stepThis().dispose()
        },
        Migration(22) { db ->
            for (table in listOf("messages_v2", "messages_holes", "media_v4", "media_holes_v2", "media_counts_v2")) {
                db.executeFast("DELETE FROM $table WHERE uid NOT IN (SELECT did FROM dialogs WHERE folder_id >= 0)")
                    .stepThis().dispose()
            }
        },
        Migration(23) { db ->
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_bookmarks(dialog_id INTEGER NOT NULL, msg_id INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(dialog_id, msg_id))")
                .stepThis().dispose()
        },
        Migration(24, atomic = true) { db ->
            ArchiveStore.cleanEditHistory(db)
            db.executeFast("CREATE UNIQUE INDEX IF NOT EXISTS idx_inu_edit_history_revision ON inu_edit_history(dialog_id, msg_id, date)").stepThis().dispose()
        },
        Migration(25) { db ->
            try {
                db.executeFast("ALTER TABLE inu_deleted_messages ADD COLUMN deleted_at INTEGER").stepThis().dispose()
            } catch (e: Throwable) { }
            db.executeFast("UPDATE inu_deleted_messages SET deleted_at = date WHERE deleted_at IS NULL").stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_deleted_messages_deleted_at ON inu_deleted_messages(deleted_at)").stepThis().dispose()
            db.executeFast("DROP TABLE IF EXISTS inu_preserved_messages").stepThis().dispose()
        },
    )

    @JvmStatic
    fun migrate(messagesStorage: MessagesStorage) {
        val db = messagesStorage.database

        db.executeFast("CREATE TABLE IF NOT EXISTS inu_kv(key TEXT PRIMARY KEY, value TEXT)").stepThis().dispose()
        var version = readKv(db, "version")?.toInt() ?: 0
        Log.d(TAG, "migrating from version $version")
        if (version < START_MIGRATION_BELOW) messagesStorage.inu_startDatabaseMigration()

        for (migration in MIGRATIONS) {
            if (migration.to <= version) continue
            if (!apply(db, migration)) break
            version = migration.to
        }

        db.executeFast("DROP TABLE IF EXISTS inu_feed_cache").stepThis().dispose()

        Log.d(TAG, "migrating finished, new version = $version")
    }

    private fun apply(db: SQLiteDatabase, migration: Migration): Boolean {
        val started = android.os.SystemClock.elapsedRealtime()
        if (!migration.atomic) {
            migration.run(db)
            writeKv(db, "version", migration.to.toString())
            return true
        }
        val savepoint = "inu_migration_${migration.to}"
        db.executeFast("SAVEPOINT $savepoint").stepThis().dispose()
        try {
            migration.run(db)
            writeKv(db, "version", migration.to.toString())
            db.executeFast("RELEASE SAVEPOINT $savepoint").stepThis().dispose()
            Log.d(TAG, "migration ${migration.to} took ${android.os.SystemClock.elapsedRealtime() - started} ms")
            return true
        } catch (e: Throwable) {
            try {
                db.executeFast("ROLLBACK TO SAVEPOINT $savepoint").stepThis().dispose()
                db.executeFast("RELEASE SAVEPOINT $savepoint").stepThis().dispose()
            } catch (rollbackError: Throwable) {
                e.addSuppressed(rollbackError)
            }
            Log.e(TAG, "migration ${migration.to} failed", e)
            return false
        }
    }

    fun loadBookmarks(db: SQLiteDatabase): List<Pair<Long, Int>> {
        val list = ArrayList<Pair<Long, Int>>()
        val cursor = db.queryFinalized("SELECT dialog_id, msg_id FROM inu_bookmarks ORDER BY added_at, msg_id")
        try {
            while (cursor.next()) list.add(cursor.longValue(0) to cursor.intValue(1))
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun addBookmarks(db: SQLiteDatabase, dialogId: Long, msgIds: List<Int>) {
        val now = (System.currentTimeMillis() / 1000).toInt()
        for (id in msgIds) {
            val query = db.executeFast("INSERT OR IGNORE INTO inu_bookmarks(dialog_id, msg_id, added_at) VALUES(?, ?, ?)")
            query.bindLong(1, dialogId)
            query.bindInteger(2, id)
            query.bindInteger(3, now)
            query.step()
            query.dispose()
        }
    }

    fun removeBookmarks(db: SQLiteDatabase, dialogId: Long, msgIds: List<Int>) {
        db.executeFast("DELETE FROM inu_bookmarks WHERE dialog_id = $dialogId AND msg_id IN (${msgIds.joinToString(",")})").stepThis().dispose()
    }

    fun clearBookmarks(db: SQLiteDatabase) {
        db.executeFast("DELETE FROM inu_bookmarks").stepThis().dispose()
    }

    fun saveReadTime(db: SQLiteDatabase, dialogId: Long, maxId: Int, readAt: Int) {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_read_times(dialog_id, max_id, read_at) VALUES(?, ?, ?)")
        query.bindLong(1, dialogId)
        query.bindInteger(2, maxId)
        query.bindInteger(3, readAt)
        query.step()
        query.dispose()
    }

    fun deleteReadTime(db: SQLiteDatabase, dialogId: Long, maxId: Int) {
        val query = db.executeFast("DELETE FROM inu_read_times WHERE dialog_id = ? AND max_id = ?")
        query.bindLong(1, dialogId)
        query.bindInteger(2, maxId)
        query.step()
        query.dispose()
    }

    fun loadReadTimesForDialog(db: SQLiteDatabase, dialogId: Long): List<Pair<Int, Int>> {
        val list = ArrayList<Pair<Int, Int>>()
        val cursor = db.queryFinalized("SELECT max_id, read_at FROM inu_read_times WHERE dialog_id = ? ORDER BY max_id", dialogId)
        try {
            while (cursor.next()) list.add(cursor.intValue(0) to cursor.intValue(1))
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun saveLastSeen(db: SQLiteDatabase, userId: Long, seenAt: Int) {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_last_seen(user_id, seen_at) VALUES(?, ?)")
        query.bindLong(1, userId)
        query.bindInteger(2, seenAt)
        query.step()
        query.dispose()
    }

    fun loadLastSeen(db: SQLiteDatabase): List<Pair<Long, Int>> {
        val list = ArrayList<Pair<Long, Int>>()
        val cursor = db.queryFinalized("SELECT user_id, seen_at FROM inu_last_seen")
        try {
            while (cursor.next()) {
                list.add(Pair(cursor.longValue(0), cursor.intValue(1)))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun saveContactChange(db: SQLiteDatabase, userId: Long, field: String, oldValue: String?, newValue: String?, date: Int) {
        val query = db.executeFast("INSERT INTO inu_contact_changes(user_id, field, old_value, new_value, date) VALUES(?, ?, ?, ?, ?)")
        query.bindLong(1, userId)
        query.bindString(2, field)
        if (oldValue == null) query.bindNull(3) else query.bindString(3, oldValue)
        if (newValue == null) query.bindNull(4) else query.bindString(4, newValue)
        query.bindInteger(5, date)
        query.step()
        query.dispose()
    }

    fun loadContactChanges(db: SQLiteDatabase, userId: Long, limit: Int = 200): List<ContactChangeRow> {
        val list = ArrayList<ContactChangeRow>()
        val cursor = db.queryFinalized(
            "SELECT field, old_value, new_value, date FROM inu_contact_changes WHERE user_id = ? ORDER BY date DESC LIMIT ?",
            userId, limit,
        )
        try {
            while (cursor.next()) {
                list.add(ContactChangeRow(cursor.stringValue(0), cursor.stringValue(1), cursor.stringValue(2), cursor.intValue(3)))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    data class ContactChangeRow(val field: String, val oldValue: String?, val newValue: String?, val date: Int)

    fun saveLocalFolderChat(db: SQLiteDatabase, filterId: Int, dialogId: Long) {
        val query = db.executeFast("INSERT OR IGNORE INTO inu_local_folder_chats(filter_id, dialog_id) VALUES(?, ?)")
        query.bindInteger(1, filterId)
        query.bindLong(2, dialogId)
        query.step()
        query.dispose()
    }

    fun removeLocalFolderChat(db: SQLiteDatabase, filterId: Int, dialogId: Long) {
        val query = db.executeFast("DELETE FROM inu_local_folder_chats WHERE filter_id = ? AND dialog_id = ?")
        query.bindInteger(1, filterId)
        query.bindLong(2, dialogId)
        query.step()
        query.dispose()
    }

    fun loadLocalFolderChats(db: SQLiteDatabase): Map<Int, Set<Long>> {
        val map = HashMap<Int, HashSet<Long>>()
        val cursor = db.queryFinalized("SELECT filter_id, dialog_id FROM inu_local_folder_chats")
        try {
            while (cursor.next()) {
                map.getOrPut(cursor.intValue(0)) { HashSet() }.add(cursor.longValue(1))
            }
        } finally {
            cursor.dispose()
        }
        return map
    }

    fun saveWatch(db: SQLiteDatabase, userId: Long) {
        val query = db.executeFast("INSERT OR IGNORE INTO inu_presence_watch(user_id) VALUES(?)")
        query.bindLong(1, userId)
        query.step()
        query.dispose()
    }

    fun removeWatch(db: SQLiteDatabase, userId: Long) {
        val query = db.executeFast("DELETE FROM inu_presence_watch WHERE user_id = ?")
        query.bindLong(1, userId)
        query.step()
        query.dispose()
    }

    fun loadWatchedUsers(db: SQLiteDatabase): Set<Long> {
        val set = HashSet<Long>()
        val cursor = db.queryFinalized("SELECT user_id FROM inu_presence_watch")
        try {
            while (cursor.next()) set.add(cursor.longValue(0))
        } finally {
            cursor.dispose()
        }
        return set
    }

    fun appendPresenceLog(db: SQLiteDatabase, userId: Long, statusType: String, timestamp: Int) {
        val query = db.executeFast("INSERT INTO inu_presence_logs(user_id, status_type, timestamp) VALUES(?, ?, ?)")
        query.bindLong(1, userId)
        query.bindString(2, statusType)
        query.bindInteger(3, timestamp)
        query.step()
        query.dispose()
    }

    fun loadPresenceLogs(db: SQLiteDatabase, userId: Long, limit: Int = 200): List<Triple<Long, String, Int>> {
        val list = ArrayList<Triple<Long, String, Int>>()
        val cursor = db.queryFinalized("SELECT user_id, status_type, timestamp FROM inu_presence_logs WHERE user_id = ? ORDER BY timestamp DESC LIMIT ?", userId, limit)
        try {
            while (cursor.next()) {
                list.add(Triple(cursor.longValue(0), cursor.stringValue(1), cursor.intValue(2)))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun getPresenceLogsStats(db: SQLiteDatabase): DialogCacheStat {
        val cursor = db.queryFinalized("SELECT COUNT(*), SUM(LENGTH(status_type)) FROM inu_presence_logs")
        try {
            if (cursor.next()) {
                val count = cursor.intValue(0)
                val textLen = cursor.longValue(1)
                return DialogCacheStat(0L, count, count * 24L + textLen)
            }
        } finally {
            cursor.dispose()
        }
        return DialogCacheStat(0L, 0, 0L)
    }

    fun getPresenceLogsStatsByUser(db: SQLiteDatabase): List<DialogCacheStat> {
        val list = ArrayList<DialogCacheStat>()
        val cursor = db.queryFinalized("SELECT user_id, COUNT(*), SUM(LENGTH(status_type)) FROM inu_presence_logs GROUP BY user_id")
        try {
            while (cursor.next()) {
                val userId = cursor.longValue(0)
                val count = cursor.intValue(1)
                val textLen = cursor.longValue(2)
                list.add(DialogCacheStat(userId, count, count * 24L + textLen))
            }
        } finally {
            cursor.dispose()
        }
        return list.sortedByDescending { it.estimatedSize }
    }

    fun clearPresenceLogs(db: SQLiteDatabase, userIds: Collection<Long>? = null) {
        if (userIds == null) {
            db.executeFast("DELETE FROM inu_presence_logs").stepThis().dispose()
        } else if (userIds.isNotEmpty()) {
            db.executeFast("DELETE FROM inu_presence_logs WHERE user_id IN (${userIds.joinToString(",")})").stepThis().dispose()
        }
    }

    fun prunePresenceLogs(db: SQLiteDatabase, cutoffUnixSec: Long) {
        val query = db.executeFast("DELETE FROM inu_presence_logs WHERE timestamp < ?")
        query.bindLong(1, cutoffUnixSec)
        query.step()
        query.dispose()
    }

    fun getTableStat(db: SQLiteDatabase, table: String, textColumn: String?, rowBytes: Long): DialogCacheStat {
        val sum = if (textColumn != null) "SUM(LENGTH($textColumn))" else "0"
        val cursor = db.queryFinalized("SELECT COUNT(*), $sum FROM $table")
        try {
            if (cursor.next()) {
                val count = cursor.intValue(0)
                return DialogCacheStat(0L, count, count * rowBytes + cursor.longValue(1))
            }
        } finally {
            cursor.dispose()
        }
        return DialogCacheStat(0L, 0, 0L)
    }

    fun saveLocalPin(db: SQLiteDatabase, scope: Int, dialogId: Long, order: Int) {
        val query = db.executeFast("REPLACE INTO inu_local_pins(scope, dialog_id, pin_order) VALUES(?, ?, ?)")
        query.bindInteger(1, scope)
        query.bindLong(2, dialogId)
        query.bindInteger(3, order)
        query.step()
        query.dispose()
    }

    fun removeLocalPin(db: SQLiteDatabase, scope: Int, dialogId: Long) {
        val query = db.executeFast("DELETE FROM inu_local_pins WHERE scope = ? AND dialog_id = ?")
        query.bindInteger(1, scope)
        query.bindLong(2, dialogId)
        query.step()
        query.dispose()
    }

    fun saveHiddenDialog(db: SQLiteDatabase, dialogId: Long) {
        val query = db.executeFast("REPLACE INTO inu_hidden_dialogs(dialog_id) VALUES(?)")
        query.bindLong(1, dialogId)
        query.step()
        query.dispose()
    }

    fun removeHiddenDialog(db: SQLiteDatabase, dialogId: Long) {
        val query = db.executeFast("DELETE FROM inu_hidden_dialogs WHERE dialog_id = ?")
        query.bindLong(1, dialogId)
        query.step()
        query.dispose()
    }

    fun loadHiddenDialogs(db: SQLiteDatabase): Set<Long> {
        val set = HashSet<Long>()
        val cursor = db.queryFinalized("SELECT dialog_id FROM inu_hidden_dialogs")
        try {
            while (cursor.next()) set.add(cursor.longValue(0))
        } finally {
            cursor.dispose()
        }
        return set
    }

    fun loadLocalPins(db: SQLiteDatabase): Map<Int, LinkedHashMap<Long, Int>> {
        val map = HashMap<Int, LinkedHashMap<Long, Int>>()
        val cursor = db.queryFinalized("SELECT scope, dialog_id, pin_order FROM inu_local_pins ORDER BY pin_order ASC")
        try {
            while (cursor.next()) {
                map.getOrPut(cursor.intValue(0)) { LinkedHashMap() }[cursor.longValue(1)] = cursor.intValue(2)
            }
        } finally {
            cursor.dispose()
        }
        return map
    }

    fun saveRecentDialog(db: SQLiteDatabase, dialogId: Long, openedAt: Long) {
        val query = db.executeFast("REPLACE INTO inu_recent_dialogs(dialog_id, opened_at) VALUES(?, ?)")
        query.bindLong(1, dialogId)
        query.bindLong(2, openedAt)
        query.step()
        query.dispose()
    }

    fun trimRecentDialogs(db: SQLiteDatabase, keep: Int) {
        val query = db.executeFast(
            "DELETE FROM inu_recent_dialogs WHERE dialog_id NOT IN " +
                "(SELECT dialog_id FROM inu_recent_dialogs ORDER BY opened_at DESC LIMIT ?)"
        )
        query.bindInteger(1, keep)
        query.step()
        query.dispose()
    }

    fun deleteRecentDialog(db: SQLiteDatabase, dialogId: Long) {
        val query = db.executeFast("DELETE FROM inu_recent_dialogs WHERE dialog_id = ?")
        query.bindLong(1, dialogId)
        query.step()
        query.dispose()
    }

    fun clearRecentDialogs(db: SQLiteDatabase) {
        db.executeFast("DELETE FROM inu_recent_dialogs").stepThis().dispose()
    }

    fun loadRecentDialogs(db: SQLiteDatabase): List<Long> {
        val list = ArrayList<Long>()
        val cursor = db.queryFinalized("SELECT dialog_id FROM inu_recent_dialogs ORDER BY opened_at DESC")
        try {
            while (cursor.next()) {
                list.add(cursor.longValue(0))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    data class DialogCacheStat(
        val dialogId: Long,
        val count: Int,
        val estimatedSize: Long,
        val mediaSize: Long = 0L,
        val lastDate: Long = 0L,
        val preview: String = "",
        val latestFromId: Long = 0L,
    )

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

    fun readKvByPrefix(db: SQLiteDatabase, prefix: String): Map<String, String> {
        val map = HashMap<String, String>()
        val cursor = db.queryFinalized("select key, value from inu_kv where key like ?", "$prefix%")
        try {
            while (cursor.next()) {
                map[cursor.stringValue(0)] = cursor.stringValue(1)
            }
        } finally {
            cursor.dispose()
        }
        return map
    }

    fun writeKv(db: SQLiteDatabase, key: String, value: String): Unit {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_kv(key, value) VALUES(?, ?)");
        query.bindString(1, key)
        query.bindString(2, value)
        query.step()
        query.dispose()
    }
}
