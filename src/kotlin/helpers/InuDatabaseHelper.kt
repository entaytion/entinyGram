package desu.inugram.helpers

import android.util.Log
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
            // These columns are used by dialog-scoped loads and TTL pruning. Without
            // dedicated indexes both operations degrade to full table scans as the
            // deleted-message cache grows.
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

        Log.d("InuDatabaseHelper", "migrating finished, new version = $version")
    }

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

    /** filterId -> set of locally-overlaid dialog ids */
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

    /** most recent entries first */
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

    /** Row count + a rough byte estimate (fixed per-row overhead + status string length). */
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

    /** Per-user breakdown (dialogId field repurposed as userId) — most logged first. */
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

    /** Clears presence logs for [userIds] (or every logged user if null). */
    fun clearPresenceLogs(db: SQLiteDatabase, userIds: Collection<Long>? = null) {
        if (userIds == null) {
            db.executeFast("DELETE FROM inu_presence_logs").stepThis().dispose()
        } else if (userIds.isNotEmpty()) {
            db.executeFast("DELETE FROM inu_presence_logs WHERE user_id IN (${userIds.joinToString(",")})").stepThis().dispose()
        }
    }

    /** Removes presence log entries older than [cutoffUnixSec]. */
    fun prunePresenceLogs(db: SQLiteDatabase, cutoffUnixSec: Long) {
        val query = db.executeFast("DELETE FROM inu_presence_logs WHERE timestamp < ?")
        query.bindLong(1, cutoffUnixSec)
        query.step()
        query.dispose()
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

    /** scope -> (dialogId -> pin_order), ordered ascending by pin_order within each scope */
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

    fun saveDeletedMessage(db: SQLiteDatabase, dialogId: Long, msgId: Int, fromId: Long, text: String, date: Int, mediaPath: String? = null) {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_deleted_messages(dialog_id, msg_id, from_id, text, date, media_path) VALUES(?, ?, ?, ?, ?, ?)");
        query.bindLong(1, dialogId)
        query.bindInteger(2, msgId)
        query.bindLong(3, fromId)
        query.bindString(4, text)
        query.bindInteger(5, if (date > 0) date else (System.currentTimeMillis() / 1000L).toInt())
        if (mediaPath != null) query.bindString(6, mediaPath) else query.bindNull(6)
        query.step()
        query.dispose()
    }

    fun forEachDeletedMessageInfo(db: SQLiteDatabase, consumer: (dialogId: Long, messageId: Int, date: Long) -> Unit) {
        val cursor = db.queryFinalized("SELECT dialog_id, msg_id, date FROM inu_deleted_messages")
        try {
            while (cursor.next()) {
                consumer(cursor.longValue(0), cursor.intValue(1), cursor.longValue(2))
            }
        } finally {
            cursor.dispose()
        }
    }

    fun loadDeletedMessageIds(db: SQLiteDatabase): Map<Long, HashSet<Int>> {
        val idsMap = HashMap<Long, HashSet<Int>>()
        forEachDeletedMessageInfo(db) { dialogId, msgId, _ ->
            idsMap.getOrPut(dialogId) { HashSet() }.add(msgId)
        }
        return idsMap
    }

    fun savePreservedMessage(db: SQLiteDatabase, dialogId: Long, msgId: Int) {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_preserved_messages(dialog_id, msg_id) VALUES(?, ?)")
        query.bindLong(1, dialogId)
        query.bindInteger(2, msgId)
        query.step()
        query.dispose()
    }

    fun loadPreservedMessageIds(db: SQLiteDatabase): Map<Long, HashSet<Int>> {
        val map = HashMap<Long, HashSet<Int>>()
        val cursor = db.queryFinalized("SELECT dialog_id, msg_id FROM inu_preserved_messages")
        try {
            while (cursor.next()) {
                map.getOrPut(cursor.longValue(0)) { HashSet() }.add(cursor.intValue(1))
            }
        } finally {
            cursor.dispose()
        }
        return map
    }

    data class MessageSearchResult(val dialogId: Long, val msgId: Int, val text: String, val date: Int, val isEdit: Boolean)

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

    fun getDeletedMediaPaths(db: SQLiteDatabase, dialogIds: Collection<Long>? = null): List<String> {
        val list = ArrayList<String>()
        val where = if (dialogIds == null) "WHERE media_path IS NOT NULL" else "WHERE media_path IS NOT NULL AND dialog_id IN (${dialogIds.joinToString(",")})"
        val cursor = db.queryFinalized("SELECT media_path FROM inu_deleted_messages $where")
        try {
            while (cursor.next()) {
                val path = cursor.stringValue(0)
                if (!path.isNullOrBlank()) list.add(path)
            }
        } finally {
            cursor.dispose()
        }
        return list
    }

    fun saveEditHistory(db: SQLiteDatabase, dialogId: Long, msgId: Int, text: String, date: Int, mediaPath: String? = null) {
        val trimmed = text.trim()
        val check = db.queryFinalized("SELECT text, media_path FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? ORDER BY date DESC LIMIT 1", dialogId, msgId)
        try {
            if (check.next()) {
                val lastText = check.stringValue(0)?.trim()
                val lastMedia = if (check.isNull(1)) null else check.stringValue(1)
                if (lastText == trimmed && lastMedia == mediaPath) {
                    return
                }
            }
        } finally {
            check.dispose()
        }

        if (trimmed.isNotEmpty()) {
            val exists = db.queryFinalized("SELECT 1 FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? AND text = ? LIMIT 1", dialogId, msgId, trimmed)
            try {
                if (exists.next()) {
                    return
                }
            } finally {
                exists.dispose()
            }
        }

        val query = db.executeFast("INSERT INTO inu_edit_history(dialog_id, msg_id, text, date, media_path) VALUES(?, ?, ?, ?, ?)");
        query.bindLong(1, dialogId)
        query.bindInteger(2, msgId)
        query.bindString(3, text)
        query.bindInteger(4, date)
        if (mediaPath != null) query.bindString(5, mediaPath) else query.bindNull(5)
        query.step()
        query.dispose()
    }

    fun loadEditHistory(db: SQLiteDatabase, dialogId: Long, msgId: Int): List<Triple<Long, String, String?>> {
        val list = ArrayList<Triple<Long, String, String?>>()
        val cursor = db.queryFinalized("SELECT date, text, media_path FROM inu_edit_history WHERE dialog_id = ? AND msg_id = ? ORDER BY date ASC", dialogId, msgId)
        try {
            while (cursor.next()) {
                val date = cursor.longValue(0)
                val text = cursor.stringValue(1)
                val mediaPath = if (cursor.isNull(2)) null else cursor.stringValue(2)
                list.add(Triple(date, text, mediaPath))
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
        val mediaPaths = getMediaPaths(db, "inu_deleted_messages", "date < ?", cutoffUnixSec)
        db.executeFast("DELETE FROM inu_deleted_messages WHERE date > 0 AND date < ?")
            .also {
                it.bindLong(1, cutoffUnixSec)
                it.step()
                it.dispose()
            }
        deleteUnreferencedMediaFiles(db, mediaPaths)
        // SQLite doesn't give us rows-affected easily via this API, that's fine
        return 0
    }

    /**
     * Removes edit history entries older than [cutoffUnixSec] from the DB.
     */
    fun pruneEditHistory(db: SQLiteDatabase, cutoffUnixSec: Long) {
        val mediaPaths = getMediaPaths(db, "inu_edit_history", "date < ?", cutoffUnixSec)
        db.executeFast("DELETE FROM inu_edit_history WHERE date > 0 AND date < ?")
            .also {
                it.bindLong(1, cutoffUnixSec)
                it.step()
                it.dispose()
            }
        deleteUnreferencedMediaFiles(db, mediaPaths)
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

        val mediaByDialog = getMediaSizeByDialog(db)
        return map.map { (id, pair) ->
            DialogCacheStat(id, pair.first, pair.second + (mediaByDialog[id] ?: 0L))
        }.sortedByDescending { it.estimatedSize }
    }

    /**
     * Actual on-disk size of media files referenced by [inu_deleted_messages]/[inu_edit_history],
     * grouped by dialog. The text-length estimate in [getDeletedMessagesStats] alone badly
     * undercounts dialogs with saved photos/videos — this fills that gap for the cache-management UI.
     */
    private fun getMediaSizeByDialog(db: SQLiteDatabase): Map<Long, Long> {
        val pathsByDialog = HashMap<Long, MutableSet<String>>()
        for (table in arrayOf("inu_deleted_messages", "inu_edit_history")) {
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
        }
        return pathsByDialog.mapValues { (_, paths) ->
            paths.sumOf { path -> try { java.io.File(path).length() } catch (_: Throwable) { 0L } }
        }
    }

    fun clearDeletedMessages(db: SQLiteDatabase, dialogIds: Collection<Long>? = null) {
        val mediaPaths = try { getMediaPathsForTables(db, dialogIds) } catch (_: Throwable) { emptyList() }

        if (dialogIds == null) {
            db.executeFast("DELETE FROM inu_deleted_messages").stepThis().dispose()
            db.executeFast("DELETE FROM inu_edit_history").stepThis().dispose()
            db.executeFast("DELETE FROM inu_deleted_reactions").stepThis().dispose()
        } else if (dialogIds.isNotEmpty()) {
            val idsStr = dialogIds.joinToString(",")
            db.executeFast("DELETE FROM inu_deleted_messages WHERE dialog_id IN ($idsStr)").stepThis().dispose()
            db.executeFast("DELETE FROM inu_edit_history WHERE dialog_id IN ($idsStr)").stepThis().dispose()
            db.executeFast("DELETE FROM inu_deleted_reactions WHERE dialog_id IN ($idsStr)").stepThis().dispose()
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
        val cursor = db.queryFinalized("SELECT media_path FROM $table WHERE media_path IS NOT NULL AND date > 0 AND $condition", value)
        try {
            while (cursor.next()) {
                cursor.stringValue(0)?.takeIf { it.isNotBlank() }?.let(paths::add)
            }
        } finally {
            cursor.dispose()
        }
        return paths
    }

    private fun deleteUnreferencedMediaFiles(db: SQLiteDatabase, paths: Collection<String>) {
        for (path in paths.distinct()) {
            val cursor = db.queryFinalized(
                "SELECT 1 FROM inu_deleted_messages WHERE media_path = ? UNION ALL SELECT 1 FROM inu_edit_history WHERE media_path = ? LIMIT 1",
                path,
                path,
            )
            try {
                if (cursor.next()) continue
                val file = java.io.File(path)
                if (file.exists()) file.delete()
            } catch (_: Throwable) { }
            finally {
                cursor.dispose()
            }
        }
    }

    /**
     * Returns the saved-deleted message ids grouped by dialog, optionally restricted to [dialogIds].
     * Used by the clear-cache flow to know which real messages must also be dropped from the chat.
     */
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

    /**
     * Physically removes saved-deleted messages from the chat dialogs.
     * The stock delete path (MessagesStorage.markMessagesAsDeleted) skips these rows while
     * SAVE_DELETED_MESSAGES is on, so clearing the cache must delete them directly.
     */
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
