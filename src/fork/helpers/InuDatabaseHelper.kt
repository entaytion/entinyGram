package desu.inugram.helpers

import android.util.Log
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC

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
            writeKv(db, "version", "3")
            version = 3
        }

        if (version == 3) {
            try {
                db.executeFast("ALTER TABLE inu_deleted_messages ADD COLUMN media_path TEXT").stepThis().dispose()
            } catch (e: Throwable) { }
            try {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN media_path TEXT").stepThis().dispose()
            } catch (e: Throwable) { }
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_deleted_reactions(dialog_id INTEGER, msg_id INTEGER, emoji TEXT, count INTEGER, custom_id INTEGER, PRIMARY KEY(dialog_id, msg_id, emoji))")
                .stepThis().dispose()
            writeKv(db, "version", "4")
            version = 4
        }

        if (version == 4) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_preserved_messages(dialog_id INTEGER, msg_id INTEGER, PRIMARY KEY(dialog_id, msg_id))")
                .stepThis().dispose();
            writeKv(db, "version", "5")
            version = 5
        }

        if (version == 5) {
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_deleted_messages_dialog ON inu_deleted_messages(dialog_id)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_deleted_messages_date ON inu_deleted_messages(date)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_edit_history_date ON inu_edit_history(date)")
                .stepThis().dispose()
            writeKv(db, "version", "6")
            version = 6
        }

        if (version == 6) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_local_pins(scope INTEGER NOT NULL, dialog_id INTEGER NOT NULL, pin_order INTEGER NOT NULL, PRIMARY KEY(scope, dialog_id))")
                .stepThis().dispose()
            writeKv(db, "version", "7")
            version = 7
        }

        if (version == 7) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_presence_watch(user_id INTEGER PRIMARY KEY)")
                .stepThis().dispose()
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_presence_logs(user_id INTEGER NOT NULL, status_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_presence_logs_user ON inu_presence_logs(user_id)")
                .stepThis().dispose()
            writeKv(db, "version", "8")
            version = 8
        }

        if (version == 8) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_local_folder_chats(filter_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, PRIMARY KEY(filter_id, dialog_id))")
                .stepThis().dispose()
            writeKv(db, "version", "9")
            version = 9
        }

        if (version == 9) {
            try {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN entities BLOB").stepThis().dispose()
            } catch (e: Throwable) { }
            try {
                db.executeFast("ALTER TABLE inu_edit_history ADD COLUMN media BLOB").stepThis().dispose()
            } catch (e: Throwable) { }
            writeKv(db, "version", "10")
            version = 10
        }

        if (version == 10) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_recent_dialogs(dialog_id INTEGER PRIMARY KEY, opened_at INTEGER NOT NULL)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_recent_dialogs_opened ON inu_recent_dialogs(opened_at)")
                .stepThis().dispose()
            writeKv(db, "version", "11")
            version = 11
        }

        if (version == 11) {
            db.executeFast("DELETE FROM inu_deleted_messages WHERE (text IS NULL OR text = '') AND (media_path IS NULL OR media_path = '')")
                .stepThis().dispose()
            writeKv(db, "version", "12")
            version = 12
        }

        if (version == 12) {
            writeKv(db, "version", "13")
            version = 13
        }

        if (version == 13) {
            try {
                db.executeFast("ALTER TABLE inu_deleted_messages ADD COLUMN message BLOB").stepThis().dispose()
            } catch (e: Throwable) { }
            writeKv(db, "version", "14")
            version = 14
        }

        if (version == 14) {
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_presence_logs_ts ON inu_presence_logs(timestamp)")
                .stepThis().dispose()
            writeKv(db, "version", "15")
            version = 15
        }

        if (version == 15) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_read_times(dialog_id INTEGER NOT NULL, max_id INTEGER NOT NULL, read_at INTEGER NOT NULL, PRIMARY KEY(dialog_id, max_id))")
                .stepThis().dispose()
            writeKv(db, "version", "16")
            version = 16
        }

        if (version == 16) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_last_seen(user_id INTEGER PRIMARY KEY, seen_at INTEGER NOT NULL)")
                .stepThis().dispose()
            writeKv(db, "version", "17")
            version = 17
        }

        if (version == 17) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_contact_changes(user_id INTEGER NOT NULL, field TEXT NOT NULL, old_value TEXT, new_value TEXT, date INTEGER NOT NULL)")
                .stepThis().dispose()
            db.executeFast("CREATE INDEX IF NOT EXISTS idx_inu_contact_changes_user ON inu_contact_changes(user_id)")
                .stepThis().dispose()
            writeKv(db, "version", "18")
            version = 18
        }

        if (version == 18) {
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
            writeKv(db, "version", "19")
            version = 19
        }

        if (version == 19) {
            val started = android.os.SystemClock.elapsedRealtime()
            db.executeFast("SAVEPOINT inu_edit_history_v20").stepThis().dispose()
            try {
                db.executeFast("CREATE TABLE inu_edit_history_v20(id INTEGER PRIMARY KEY AUTOINCREMENT, dialog_id INTEGER, msg_id INTEGER, text TEXT, date INTEGER, media_path TEXT, entities BLOB, media BLOB, archived_at INTEGER)").stepThis().dispose()
                db.executeFast("INSERT INTO inu_edit_history_v20(id, dialog_id, msg_id, text, date, media_path, entities, media, archived_at) SELECT rowid, dialog_id, msg_id, text, date, media_path, entities, media, archived_at FROM inu_edit_history").stepThis().dispose()
                db.executeFast("DROP TABLE inu_edit_history").stepThis().dispose()
                db.executeFast("ALTER TABLE inu_edit_history_v20 RENAME TO inu_edit_history").stepThis().dispose()
                db.executeFast("CREATE INDEX idx_inu_edit_history ON inu_edit_history(dialog_id, msg_id)").stepThis().dispose()
                db.executeFast("CREATE INDEX idx_inu_edit_history_date ON inu_edit_history(date)").stepThis().dispose()
                db.executeFast("CREATE INDEX idx_inu_edit_history_archived_at ON inu_edit_history(archived_at)").stepThis().dispose()
                writeKv(db, "version", "20")
                db.executeFast("RELEASE SAVEPOINT inu_edit_history_v20").stepThis().dispose()
                version = 20
            } catch (e: Throwable) {
                try {
                    db.executeFast("ROLLBACK TO SAVEPOINT inu_edit_history_v20").stepThis().dispose()
                    db.executeFast("RELEASE SAVEPOINT inu_edit_history_v20").stepThis().dispose()
                } catch (rollbackError: Throwable) {
                    e.addSuppressed(rollbackError)
                }
                Log.e("InuDatabaseHelper", "edit history v20 rebuild failed", e)
            }
            Log.d("InuDatabaseHelper", "edit history v20 rebuild took ${android.os.SystemClock.elapsedRealtime() - started} ms")
        }

        if (version == 20) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_hidden_dialogs(dialog_id INTEGER PRIMARY KEY)")
                .stepThis().dispose()
            writeKv(db, "version", "21")
            version = 21
        }

        if (version == 21) {
            for (table in listOf("messages_v2", "messages_holes", "media_v4", "media_holes_v2", "media_counts_v2")) {
                db.executeFast("DELETE FROM $table WHERE uid NOT IN (SELECT did FROM dialogs WHERE folder_id >= 0)")
                    .stepThis().dispose()
            }
            writeKv(db, "version", "22")
            version = 22
        }

        if (version == 22) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_bookmarks(dialog_id INTEGER NOT NULL, msg_id INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY(dialog_id, msg_id))")
                .stepThis().dispose()
            writeKv(db, "version", "23")
            version = 23
        }

        // entiny: the feed no longer keeps its own cache
        db.executeFast("DROP TABLE IF EXISTS inu_feed_cache").stepThis().dispose()

        Log.d("InuDatabaseHelper", "migrating finished, new version = $version")
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

    fun loadReadTimes(db: SQLiteDatabase): List<Triple<Long, Int, Int>> {
        val list = ArrayList<Triple<Long, Int, Int>>()
        val cursor = db.queryFinalized("SELECT dialog_id, max_id, read_at FROM inu_read_times ORDER BY dialog_id, max_id")
        try {
            while (cursor.next()) {
                list.add(Triple(cursor.longValue(0), cursor.intValue(1), cursor.intValue(2)))
            }
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

    fun clearEditHistory(db: SQLiteDatabase, dialogIds: Collection<Long>? = null) {
        if (dialogIds != null && dialogIds.isEmpty()) return
        val where = if (dialogIds == null) "" else " WHERE dialog_id IN (${dialogIds.joinToString(",")})"
        val paths = ArrayList<String>()
        val cursor = db.queryFinalized("SELECT media_path FROM inu_edit_history$where")
        try {
            while (cursor.next()) cursor.stringValue(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
        } finally {
            cursor.dispose()
        }
        db.executeFast("DELETE FROM inu_edit_history$where").stepThis().dispose()
        deleteUnreferencedMediaFiles(db, paths)
    }

    fun clearDeletedReactions(db: SQLiteDatabase) {
        db.executeFast("DELETE FROM inu_deleted_reactions").stepThis().dispose()
    }

    fun detachSavedMedia(db: SQLiteDatabase) {
        db.executeFast("UPDATE inu_deleted_messages SET media_path = NULL WHERE media_path IS NOT NULL").stepThis().dispose()
        db.executeFast("UPDATE inu_edit_history SET media_path = NULL WHERE media_path IS NOT NULL").stepThis().dispose()
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

    fun saveDeletedMessage(db: SQLiteDatabase, dialogId: Long, msgId: Int, fromId: Long, text: String, date: Int, mediaPath: String? = null, message: TLRPC.Message? = null) {
        val row = "FROM inu_deleted_messages WHERE dialog_id = ? AND msg_id = ?"
        val query = db.executeFast(
            "INSERT OR REPLACE INTO inu_deleted_messages(dialog_id, msg_id, from_id, text, date, media_path, message) VALUES(?, ?, " +
                "CASE WHEN ? != 0 THEN ? ELSE COALESCE((SELECT from_id $row), 0) END, " +
                "COALESCE(NULLIF(?, ''), (SELECT text $row), ''), " +
                "COALESCE((SELECT date $row), ?), " +
                "COALESCE(?, (SELECT media_path $row)), " +
                "COALESCE(?, (SELECT message $row)))"
        )
        try {
            var index = 0
            fun key() {
                query.bindLong(++index, dialogId)
                query.bindInteger(++index, msgId)
            }
            key()
            query.bindLong(++index, fromId)
            query.bindLong(++index, fromId)
            key()
            query.bindString(++index, text)
            key()
            key()
            query.bindInteger(++index, if (date > 0) date else (System.currentTimeMillis() / 1000L).toInt())
            if (mediaPath != null) query.bindString(++index, mediaPath) else query.bindNull(++index)
            key()
            if (message != null) query.bindTlObject(++index, message) else query.bindNull(++index)
            key()
            query.step()
        } finally {
            query.dispose()
        }
    }

    fun deletedMessageMediaPath(db: SQLiteDatabase, dialogId: Long, msgId: Int): String? {
        val cursor = db.queryFinalized("SELECT media_path FROM inu_deleted_messages WHERE dialog_id = ? AND msg_id = ? AND media_path IS NOT NULL", dialogId, msgId)
        return try {
            if (cursor.next()) cursor.stringValue(0)?.takeIf { it.isNotBlank() } else null
        } finally {
            cursor.dispose()
        }
    }

    fun isDeletedMessageArchived(db: SQLiteDatabase, dialogId: Long, msgId: Int): Boolean {
        val cursor = db.queryFinalized("SELECT 1 FROM inu_deleted_messages WHERE dialog_id = ? AND msg_id = ?", dialogId, msgId)
        return try { cursor.next() } finally { cursor.dispose() }
    }

    fun archivedMessages(db: SQLiteDatabase, dialogId: Long, mids: Collection<Int>): Map<Int, TLRPC.Message> {
        val result = HashMap<Int, TLRPC.Message>()
        if (mids.isEmpty()) return result
        val cursor = db.queryFinalized("SELECT msg_id, message FROM inu_deleted_messages WHERE dialog_id = ? AND msg_id IN (${mids.joinToString(",")}) AND message IS NOT NULL", dialogId)
        try {
            while (cursor.next()) {
                readMessageBlob(cursor, 1)?.let { result[cursor.intValue(0)] = it }
            }
        } finally {
            cursor.dispose()
        }
        return result
    }

    fun loadMessagesForArchive(db: SQLiteDatabase, dialogId: Long, mids: Collection<Int>): Map<Int, TLRPC.Message> {
        val result = HashMap<Int, TLRPC.Message>()
        if (mids.isEmpty()) return result
        val cursor = db.queryFinalized("SELECT mid, data FROM messages_v2 WHERE uid = ? AND mid IN (${mids.joinToString(",")})", dialogId)
        try {
            while (cursor.next()) {
                val mid = cursor.intValue(0)
                readTl(cursor, 1) { data ->
                    TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)?.also { it.readAttachPath(data, 0) }
                }?.let {
                    it.id = mid
                    it.dialog_id = dialogId
                    result[mid] = it
                }
            }
        } finally {
            cursor.dispose()
        }
        val missing = mids.filter { it !in result }
        if (missing.isNotEmpty()) result.putAll(archivedMessages(db, dialogId, missing))
        return result
    }

    private fun readMessageBlob(cursor: org.telegram.SQLite.SQLiteCursor, column: Int): TLRPC.Message? =
        readTl(cursor, column) { data -> TLRPC.Message.TLdeserialize(data, data.readInt32(false), false) }

    // entiny: rows archived before the message blob existed still have their original in messages_v2
    private fun loadLiveMessage(db: SQLiteDatabase, dialogId: Long, msgId: Int): TLRPC.Message? {
        val cursor = db.queryFinalized("SELECT data FROM messages_v2 WHERE uid = ? AND mid = ?", dialogId, msgId)
        return try {
            if (cursor.next()) {
                readTl(cursor, 0) { data ->
                    TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)?.also { it.readAttachPath(data, 0) }
                }
            } else null
        } catch (e: Throwable) {
            null
        } finally {
            cursor.dispose()
        }
    }

    fun readDeletedIndexPage(db: SQLiteDatabase, afterRowId: Long, limit: Int, consumer: (rowId: Long, dialogId: Long, messageId: Int, date: Long, mediaPath: String?) -> Unit): Int {
        var count = 0
        val cursor = db.queryFinalized("SELECT rowid, dialog_id, msg_id, date, media_path FROM inu_deleted_messages WHERE rowid > ? ORDER BY rowid LIMIT ?", afterRowId, limit)
        try {
            while (cursor.next()) {
                count++
                consumer(cursor.longValue(0), cursor.longValue(1), cursor.intValue(2), cursor.longValue(3), if (cursor.isNull(4)) null else cursor.stringValue(4)?.takeIf { it.isNotEmpty() })
            }
        } finally {
            cursor.dispose()
        }
        return count
    }

    fun readEditIndexPage(db: SQLiteDatabase, afterRowId: Long, limit: Int, consumer: (rowId: Long, dialogId: Long, messageId: Int) -> Unit): Int {
        var count = 0
        val cursor = db.queryFinalized("SELECT rowid, dialog_id, msg_id FROM inu_edit_history WHERE rowid > ? ORDER BY rowid LIMIT ?", afterRowId, limit)
        try {
            while (cursor.next()) {
                count++
                consumer(cursor.longValue(0), cursor.longValue(1), cursor.intValue(2))
            }
        } finally {
            cursor.dispose()
        }
        return count
    }

    fun writeArchiveJson(db: SQLiteDatabase, out: java.io.Writer): Int {
        var count = 0
        fun q(s: String?): String = if (s == null) "null" else org.json.JSONObject.quote(s)
        out.write("{\n\"deleted\": [")
        var cursor = db.queryFinalized("SELECT dialog_id, msg_id, from_id, date, text, media_path FROM inu_deleted_messages ORDER BY date ASC")
        try {
            var first = true
            while (cursor.next()) {
                if (!first) out.write(",")
                first = false
                count++
                val media = if (cursor.isNull(5)) null else cursor.stringValue(5)
                out.write("\n{\"dialog_id\":${cursor.longValue(0)},\"msg_id\":${cursor.intValue(1)},\"from_id\":${cursor.longValue(2)},\"date\":${cursor.intValue(3)},\"text\":${q(cursor.stringValue(4))},\"media_path\":${q(media)}}")
            }
        } finally {
            cursor.dispose()
        }
        out.write("\n],\n\"edits\": [")
        cursor = db.queryFinalized("SELECT dialog_id, msg_id, date, text, media_path FROM inu_edit_history ORDER BY date ASC")
        try {
            var first = true
            while (cursor.next()) {
                if (!first) out.write(",")
                first = false
                count++
                val media = if (cursor.isNull(4)) null else cursor.stringValue(4)
                out.write("\n{\"dialog_id\":${cursor.longValue(0)},\"msg_id\":${cursor.intValue(1)},\"date\":${cursor.intValue(2)},\"text\":${q(cursor.stringValue(3))},\"media_path\":${q(media)}}")
            }
        } finally {
            cursor.dispose()
        }
        out.write("\n]\n}\n")
        return count
    }

    data class MessageSearchResult(val dialogId: Long, val msgId: Int, val text: String, val date: Int, val isEdit: Boolean, val mediaPath: String? = null, val message: TLRPC.Message? = null)

    data class DeletedMessage(val msgId: Int, val text: String, val date: Int, val mediaPath: String? = null, val fromId: Long = 0L, val message: TLRPC.Message? = null)

    fun deletedMessagesInDialog(db: SQLiteDatabase, dialogId: Long, limit: Int = 300, offset: Int = 0): List<DeletedMessage> {
        val list = ArrayList<DeletedMessage>()
        val cursor = db.queryFinalized(
            "SELECT msg_id, text, date, media_path, from_id, message FROM inu_deleted_messages WHERE dialog_id = ? ORDER BY date DESC, msg_id DESC LIMIT ? OFFSET ?",
            dialogId, limit, offset,
        )
        try {
            while (cursor.next()) {
                val msgId = cursor.intValue(0)
                val message = readMessageBlob(cursor, 5) ?: loadLiveMessage(db, dialogId, msgId)
                list.add(DeletedMessage(msgId, cursor.stringValue(1) ?: "", cursor.intValue(2), cursor.stringValue(3), cursor.longValue(4), message))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun deletedByAuthorInDialog(db: SQLiteDatabase, fromId: Long, dialogId: Long, limit: Int = 300, offset: Int = 0): List<MessageSearchResult> {
        val list = ArrayList<MessageSearchResult>()
        val cursor = db.queryFinalized(
            "SELECT dialog_id, msg_id, text, date, media_path, message FROM inu_deleted_messages WHERE from_id = ? AND dialog_id = ? ORDER BY date DESC, msg_id DESC LIMIT ? OFFSET ?",
            fromId, dialogId, limit, offset,
        )
        try {
            while (cursor.next()) {
                val msgId = cursor.intValue(1)
                val message = readMessageBlob(cursor, 5) ?: loadLiveMessage(db, dialogId, msgId)
                list.add(MessageSearchResult(cursor.longValue(0), msgId, cursor.stringValue(2) ?: "", cursor.intValue(3), isEdit = false, mediaPath = cursor.stringValue(4), message = message))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun searchDeletedMessages(db: SQLiteDatabase, query: String, limit: Int = 100): List<MessageSearchResult> {
        val list = ArrayList<MessageSearchResult>()
        val cursor = db.queryFinalized(
            "SELECT dialog_id, msg_id, text, date FROM inu_deleted_messages WHERE text LIKE ? ORDER BY date DESC LIMIT ?",
            "%$query%", limit,
        )
        try {
            while (cursor.next()) {
                list.add(MessageSearchResult(cursor.longValue(0), cursor.intValue(1), cursor.stringValue(2), cursor.intValue(3), isEdit = false))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun searchEditHistory(db: SQLiteDatabase, query: String, limit: Int = 100): List<MessageSearchResult> {
        val list = ArrayList<MessageSearchResult>()
        val cursor = db.queryFinalized(
            "SELECT dialog_id, msg_id, text, date FROM inu_edit_history WHERE text LIKE ? ORDER BY date DESC LIMIT ?",
            "%$query%", limit,
        )
        try {
            while (cursor.next()) {
                list.add(MessageSearchResult(cursor.longValue(0), cursor.intValue(1), cursor.stringValue(2), cursor.intValue(3), isEdit = true))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun saveEditHistory(
        db: SQLiteDatabase,
        dialogId: Long,
        msgId: Int,
        text: String,
        date: Int,
        mediaPath: String? = null,
        entities: ArrayList<TLRPC.MessageEntity>? = null,
        media: TLRPC.MessageMedia? = null,
    ) {
        val entitiesObject = if (entities.isNullOrEmpty()) {
            null
        } else {
            TLRPC.TL_textWithEntities().also { it.text = text; it.entities = entities }
        }
        val mediaObject = if (media is TLRPC.TL_messageMediaEmpty) null else media

        val query = db.executeFast("INSERT INTO inu_edit_history(dialog_id, msg_id, text, date, media_path, entities, media, archived_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)")
        try {
            query.bindLong(1, dialogId)
            query.bindInteger(2, msgId)
            query.bindString(3, text)
            query.bindInteger(4, date)
            if (mediaPath != null) query.bindString(5, mediaPath) else query.bindNull(5)
            if (entitiesObject != null) query.bindTlObject(6, entitiesObject) else query.bindNull(6)
            if (mediaObject != null) query.bindTlObject(7, mediaObject) else query.bindNull(7)
            query.bindLong(8, System.currentTimeMillis() / 1000L)
            query.step()
        } catch (e: Throwable) {
            Log.e("InuDatabaseHelper", "saveEditHistory failed", e)
        } finally {
            query.dispose()
        }
    }

    data class EditHistoryRow(
        val date: Long,
        val text: String,
        val mediaPath: String?,
        val entities: ArrayList<TLRPC.MessageEntity>?,
        val media: TLRPC.MessageMedia?,
        val rowId: Long = 0,
    )

    // entiny: deep-clone media via serialization so displaying cached revisions does not mutate live chat messages
    fun cloneMedia(media: TLRPC.MessageMedia?): TLRPC.MessageMedia? {
        if (media == null || media is TLRPC.TL_messageMediaEmpty) return null
        var buffer: NativeByteBuffer? = null
        return try {
            buffer = NativeByteBuffer(media.objectSize)
            media.serializeToStream(buffer)
            buffer.position(0)
            TLRPC.MessageMedia.TLdeserialize(buffer, buffer.readInt32(false), false)
        } catch (e: Throwable) {
            Log.e("InuDatabaseHelper", "cloneMedia failed", e)
            null
        } finally {
            buffer?.reuse()
        }
    }

    private fun <T> readTl(cursor: org.telegram.SQLite.SQLiteCursor, column: Int, reader: (NativeByteBuffer) -> T?): T? {
        if (cursor.isNull(column)) return null
        val data = try {
            cursor.byteBufferValue(column)
        } catch (e: Throwable) {
            null
        } ?: return null
        return try {
            reader(data)
        } catch (e: Throwable) {
            Log.e("InuDatabaseHelper", "readTl failed", e)
            null
        } finally {
            data.reuse()
        }
    }

    fun lastEditHistory(db: SQLiteDatabase, dialogId: Long, msgId: Int): EditHistoryRow? =
        readEditHistory(db, "SELECT date, text, media_path, entities, media, rowid FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? ORDER BY date DESC, rowid DESC LIMIT 1", dialogId, msgId).firstOrNull()

    fun loadEditHistory(db: SQLiteDatabase, dialogId: Long, msgId: Int): List<EditHistoryRow> =
        readEditHistory(db, "SELECT date, text, media_path, entities, media, rowid FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? ORDER BY date ASC, rowid ASC", dialogId, msgId)

    private fun readEditHistory(db: SQLiteDatabase, sql: String, dialogId: Long, msgId: Int): List<EditHistoryRow> {
        val list = ArrayList<EditHistoryRow>()
        val cursor = db.queryFinalized(sql, dialogId, msgId)
        try {
            while (cursor.next()) {
                val date = cursor.longValue(0)
                val text = cursor.stringValue(1) ?: ""
                val mediaPath = if (cursor.isNull(2)) null else cursor.stringValue(2)
                val entities = readTl(cursor, 3) { data ->
                    TLRPC.TL_textWithEntities.TLdeserialize(data, data.readInt32(false), false)?.entities
                }
                val media = readTl(cursor, 4) { data ->
                    TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false)
                }
                list.add(EditHistoryRow(date, text, mediaPath, entities, media, cursor.longValue(5)))
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun loadEditHistoryInDialog(db: SQLiteDatabase, dialogId: Long): List<Pair<Int, EditHistoryRow>> {
        val list = ArrayList<Pair<Int, EditHistoryRow>>()
        val cursor = db.queryFinalized(
            "SELECT date, text, media_path, entities, media, id, msg_id FROM inu_edit_history WHERE dialog_id = ? ORDER BY date ASC, id ASC",
            dialogId,
        )
        try {
            while (cursor.next()) {
                val entities = readTl(cursor, 3) { data ->
                    TLRPC.TL_textWithEntities.TLdeserialize(data, data.readInt32(false), false)?.entities
                }
                val media = readTl(cursor, 4) { data ->
                    TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false)
                }
                val row = EditHistoryRow(
                    cursor.longValue(0), cursor.stringValue(1) ?: "",
                    if (cursor.isNull(2)) null else cursor.stringValue(2),
                    entities, media, cursor.longValue(5),
                )
                list.add(cursor.intValue(6) to row)
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun deleteEditHistoryEntry(db: SQLiteDatabase, dialogId: Long, msgId: Int, date: Long) {
        val mediaPaths = getMediaPaths(db, "inu_edit_history", "dialog_id = $dialogId AND msg_id = $msgId AND date = ?", date)
        db.executeFast("DELETE FROM inu_edit_history WHERE dialog_id = $dialogId AND msg_id = $msgId AND date = $date").stepThis().dispose()
        deleteUnreferencedMediaFiles(db, mediaPaths)
    }

    fun deleteEditHistoryRevision(db: SQLiteDatabase, dialogId: Long, msgId: Int, rowId: Long) {
        val mediaPaths = getMediaPaths(db, "inu_edit_history", "dialog_id = $dialogId AND msg_id = $msgId AND id = ?", rowId)
        val query = db.executeFast("DELETE FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? AND id = ?")
        try {
            query.bindLong(1, dialogId)
            query.bindInteger(2, msgId)
            query.bindLong(3, rowId)
            query.step()
        } finally {
            query.dispose()
        }
        deleteUnreferencedMediaFiles(db, mediaPaths)
    }

    fun expiredDeletedMessageIds(db: SQLiteDatabase, cutoffUnixSec: Long): Map<Long, List<Int>> {
        val ids = HashMap<Long, MutableList<Int>>()
        val cursor = db.queryFinalized("SELECT dialog_id, msg_id FROM inu_deleted_messages WHERE date > 0 AND date < ?", cutoffUnixSec)
        try {
            while (cursor.next()) {
                ids.getOrPut(cursor.longValue(0)) { ArrayList() }.add(cursor.intValue(1))
            }
        } finally {
            cursor.dispose()
        }
        return ids
    }

    fun pruneDeletedMessages(db: SQLiteDatabase, cutoffUnixSec: Long): Map<Long, List<Int>> {
        val expiredIds = expiredDeletedMessageIds(db, cutoffUnixSec)
        if (expiredIds.isEmpty()) return expiredIds
        val mediaPaths = getMediaPaths(db, "inu_deleted_messages", "date > 0 AND date < ?", cutoffUnixSec)
        for ((dialogId, mids) in expiredIds) {
            deleteSavedMessages(db, dialogId, mids)
            db.executeFast("DELETE FROM inu_preserved_messages WHERE dialog_id = $dialogId AND msg_id IN (${mids.joinToString(",")})").stepThis().dispose()
        }
        db.executeFast("DELETE FROM inu_deleted_messages WHERE date > 0 AND date < ?")
            .also {
                it.bindLong(1, cutoffUnixSec)
                it.step()
                it.dispose()
            }
        deleteUnreferencedMediaFiles(db, mediaPaths)
        return expiredIds
    }

    fun pruneEditHistory(db: SQLiteDatabase, cutoffUnixSec: Long): Int {
        val countCursor = db.queryFinalized("SELECT COUNT(*) FROM inu_edit_history WHERE archived_at > 0 AND archived_at < ?", cutoffUnixSec)
        val count = try { if (countCursor.next()) countCursor.intValue(0) else 0 } finally { countCursor.dispose() }
        if (count == 0) return 0
        val mediaPaths = getMediaPaths(db, "inu_edit_history", "archived_at > 0 AND archived_at < ?", cutoffUnixSec)
        db.executeFast("DELETE FROM inu_edit_history WHERE archived_at > 0 AND archived_at < ?")
            .also {
                it.bindLong(1, cutoffUnixSec)
                it.step()
                it.dispose()
            }
        deleteUnreferencedMediaFiles(db, mediaPaths)
        return count
    }

    data class DialogCacheStat(
        val dialogId: Long,
        val count: Int,
        val estimatedSize: Long,
        val mediaSize: Long = 0L
    )

    fun getDeletedMessagesStats(db: SQLiteDatabase, edited: Boolean = false): List<DialogCacheStat> {
        val table = if (edited) "inu_edit_history" else "inu_deleted_messages"
        val rowSize = if (edited) 150L else 250L
        val map = HashMap<Long, Pair<Int, Long>>()
        val cursor = db.queryFinalized("SELECT dialog_id, COUNT(*), SUM(LENGTH(text)) FROM $table GROUP BY dialog_id")
        try {
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                val count = cursor.intValue(1)
                val textLen = cursor.longValue(2)
                val estSize = (count * rowSize) + textLen
                map[dialogId] = Pair(count, estSize)
            }
        } finally {
            cursor.dispose()
        }

        val mediaByDialog = getMediaSizeByDialog(db, table)
        return map.map { (id, pair) ->
            val mediaSize = mediaByDialog[id] ?: 0L
            DialogCacheStat(id, pair.first, pair.second + mediaSize, mediaSize)
        }.sortedByDescending { it.estimatedSize }
    }

    private fun getMediaSizeByDialog(db: SQLiteDatabase, table: String): Map<Long, Long> {
        val pathsByDialog = HashMap<Long, MutableSet<String>>()
        val cursor = db.queryFinalized("SELECT dialog_id, media_path FROM $table WHERE media_path IS NOT NULL")
        try {
            while (cursor.next()) {
                val dialogId = cursor.longValue(0)
                val path = cursor.stringValue(1)
                if (!path.isNullOrBlank()) pathsByDialog.getOrPut(dialogId) { HashSet() }.add(path)
            }
        } finally {
            cursor.dispose()
        }
        return pathsByDialog.mapValues { (_, paths) ->
            paths.sumOf { path -> try { java.io.File(path).length() } catch (_: Throwable) { 0L } }
        }
    }

    fun clearDeletedMessages(db: SQLiteDatabase, dialogIds: Collection<Long>? = null, includeHistory: Boolean = true) {
        val mediaPaths = try { getMediaPathsForTables(db, dialogIds) } catch (_: Throwable) { emptyList() }

        if (dialogIds == null) {
            db.executeFast("DELETE FROM inu_deleted_messages").stepThis().dispose()
            if (includeHistory) {
                db.executeFast("DELETE FROM inu_edit_history").stepThis().dispose()
                db.executeFast("DELETE FROM inu_deleted_reactions").stepThis().dispose()
            }
        } else if (dialogIds.isNotEmpty()) {
            val idsStr = dialogIds.joinToString(",")
            db.executeFast("DELETE FROM inu_deleted_messages WHERE dialog_id IN ($idsStr)").stepThis().dispose()
            if (includeHistory) {
                db.executeFast("DELETE FROM inu_edit_history WHERE dialog_id IN ($idsStr)").stepThis().dispose()
                db.executeFast("DELETE FROM inu_deleted_reactions WHERE dialog_id IN ($idsStr)").stepThis().dispose()
            }
        }
        deleteUnreferencedMediaFiles(db, mediaPaths)
    }

    private fun getMediaPathsForTables(db: SQLiteDatabase, dialogIds: Collection<Long>?): List<String> {
        val paths = ArrayList<String>()
        if (dialogIds != null && dialogIds.isEmpty()) return paths
        for (table in arrayOf("inu_deleted_messages", "inu_edit_history")) {
            val where = if (dialogIds == null) {
                "media_path IS NOT NULL"
            } else {
                "media_path IS NOT NULL AND dialog_id IN (${dialogIds.joinToString(",")})"
            }
            val cursor = db.queryFinalized("SELECT media_path FROM $table WHERE $where")
            try {
                while (cursor.next()) {
                    cursor.stringValue(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
                }
            } finally {
                cursor.dispose()
            }
        }
        return paths
    }

    private fun getMediaPaths(db: SQLiteDatabase, table: String, condition: String, value: Long): List<String> {
        val paths = ArrayList<String>()
        val cursor = db.queryFinalized("SELECT media_path FROM $table WHERE media_path IS NOT NULL AND $condition", value)
        try {
            while (cursor.next()) {
                cursor.stringValue(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
            }
        } finally {
            cursor.dispose()
        }
        return paths
    }

    fun replaceSavedMediaPath(db: SQLiteDatabase, oldPath: String, newPath: String) {
        db.executeFast("SAVEPOINT inu_media_move").stepThis().dispose()
        try {
            for (table in arrayOf("inu_deleted_messages", "inu_edit_history")) {
                val query = db.executeFast("UPDATE $table SET media_path = ? WHERE media_path = ?")
                try {
                    query.bindString(1, newPath)
                    query.bindString(2, oldPath)
                    query.step()
                } finally {
                    query.dispose()
                }
            }
            db.executeFast("RELEASE SAVEPOINT inu_media_move").stepThis().dispose()
        } catch (e: Throwable) {
            try {
                db.executeFast("ROLLBACK TO SAVEPOINT inu_media_move").stepThis().dispose()
                db.executeFast("RELEASE SAVEPOINT inu_media_move").stepThis().dispose()
            } catch (rollbackError: Throwable) {
                e.addSuppressed(rollbackError)
            }
            throw e
        }
    }

    private fun deleteUnreferencedMediaFiles(db: SQLiteDatabase, paths: Collection<String>) {
        val archiveRoot = try {
            java.io.File(org.telegram.messenger.ApplicationLoader.applicationContext.filesDir, "entinyGram/media").canonicalFile
        } catch (_: Throwable) {
            return
        }
        for (path in paths.distinct()) {
            val file = try { java.io.File(path).canonicalFile } catch (_: Throwable) { continue }
            val relativePath = file.path.removePrefix(archiveRoot.path + java.io.File.separator)
            if (relativePath == file.path) continue
            val accountDirectory = relativePath.substringBefore(java.io.File.separator)
            if (!accountDirectory.matches(Regex("account_[1-9][0-9]*"))) continue
            val accountRoot = java.io.File(archiveRoot, accountDirectory)
            if (!file.path.startsWith(accountRoot.path + java.io.File.separator) || !file.isFile) continue
            val cursor = db.queryFinalized(
                "SELECT 1 FROM inu_deleted_messages WHERE media_path = ? UNION ALL SELECT 1 FROM inu_edit_history WHERE media_path = ? LIMIT 1",
                path,
                path,
            )
            try {
                if (cursor.next()) continue
                file.delete()
            } catch (_: Throwable) { }
            finally {
                cursor.dispose()
            }
        }
    }

    fun getDeletedMessageIds(db: SQLiteDatabase, dialogIds: Collection<Long>? = null): Map<Long, List<Int>> {
        val map = HashMap<Long, MutableList<Int>>()
        val where = if (dialogIds == null) "" else "WHERE dialog_id IN (${dialogIds.joinToString(",")})"
        val cursor = db.queryFinalized("SELECT dialog_id, msg_id FROM inu_deleted_messages $where")
        try {
            while (cursor.next()) {
                map.getOrPut(cursor.longValue(0)) { ArrayList() }.add(cursor.intValue(1))
            }
        } finally {
            cursor.dispose()
        }
        return map
    }

    fun deleteDeletedMessageEntries(db: SQLiteDatabase, dialogId: Long, mids: List<Int>) {
        if (mids.isEmpty()) return
        val idsStr = mids.joinToString(",")
        val mediaPaths = try {
            val paths = ArrayList<String>()
            val cursor = db.queryFinalized("SELECT media_path FROM inu_deleted_messages WHERE dialog_id = $dialogId AND msg_id IN ($idsStr) AND media_path IS NOT NULL")
            try {
                while (cursor.next()) {
                    cursor.stringValue(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
                }
            } finally {
                cursor.dispose()
            }
            paths
        } catch (_: Throwable) {
            emptyList()
        }
        db.executeFast("DELETE FROM inu_deleted_messages WHERE dialog_id = $dialogId AND msg_id IN ($idsStr)").stepThis().dispose()
        deleteUnreferencedMediaFiles(db, mediaPaths)
    }

    fun deleteEditHistory(db: SQLiteDatabase, dialogId: Long, mids: List<Int>) {
        if (mids.isEmpty()) return
        val idsStr = mids.joinToString(",")
        val mediaPaths = getMediaPaths(db, "inu_edit_history", "dialog_id = ? AND msg_id IN ($idsStr)", dialogId)
        db.executeFast("DELETE FROM inu_edit_history WHERE dialog_id = $dialogId AND msg_id IN ($idsStr)").stepThis().dispose()
        deleteUnreferencedMediaFiles(db, mediaPaths)
    }

    // entiny: delete directly from messages_v2 because markMessagesAsDeleted skips rows while save-deleted is enabled
    fun deleteSavedMessages(db: SQLiteDatabase, dialogId: Long, mids: List<Int>) {
        if (mids.isEmpty()) return
        val idsStr = mids.joinToString(",")
        db.executeFast("DELETE FROM messages_v2 WHERE uid = $dialogId AND mid IN ($idsStr)").stepThis().dispose()
        db.executeFast("DELETE FROM messages_topics WHERE uid = $dialogId AND mid IN ($idsStr)").stepThis().dispose()
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
