package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import java.io.File
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesStorage

// entiny: per account; the oldest attachments go first, the message rows and their text stay
object ArchiveQuota {
    private const val PAGE = 100
    private val OPTIONS_GB = intArrayOf(0, 1, 2, 5)

    fun options(): IntArray = OPTIONS_GB

    fun limitBytes(): Long = InuConfig.ARCHIVE_MEDIA_QUOTA_GB.value.toLong() * 1024L * 1024L * 1024L

    @JvmStatic
    fun schedule(account: Int) {
        if (limitBytes() <= 0) return
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable { storage.database?.let { enforce(account, it) } }
    }

    private class Row(val edit: Boolean, val key: Long, val dialogId: Long, val msgId: Int, val path: String)

    private fun enforce(account: Int, db: SQLiteDatabase) {
        val limit = limitBytes()
        if (limit <= 0) return
        val files = ArchiveMediaHelper.savedMediaFiles(account, db).filter(ArchiveMediaHelper::isDeletableSaved)
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        var freed = false
        var skipped = 0
        while (total > limit) {
            val rows = oldest(db, skipped)
            if (rows.isEmpty()) break
            for (row in rows) {
                val file = File(row.path)
                if (!file.isFile || !ArchiveMediaHelper.isDeletableSaved(file)) {
                    skipped++
                    continue
                }
                detach(db, row)
                freed = true
                if (!isReferenced(db, row.path)) {
                    val size = file.length()
                    ArchiveMediaHelper.deleteSavedMediaFile(file)
                    if (!file.exists()) total -= size
                }
                if (total <= limit) break
            }
        }
        if (!freed) return
        KeptMedia.release(account, db)
        AndroidUtilities.runOnUIThread { ArchiveCache.invalidateMediaCache(account) }
    }

    private fun oldest(db: SQLiteDatabase, skipped: Int): List<Row> {
        val rows = ArrayList<Row>()
        val cursor = db.queryFinalized(
            "SELECT 0, rowid, dialog_id, msg_id, media_path, deleted_at AS t FROM inu_deleted_messages WHERE media_path IS NOT NULL AND media_path != ''" +
                " UNION ALL SELECT 1, rowid, dialog_id, msg_id, media_path, date AS t FROM inu_edit_history WHERE media_path IS NOT NULL AND media_path != ''" +
                " ORDER BY t LIMIT $PAGE OFFSET $skipped"
        )
        try {
            while (cursor.next()) {
                rows.add(Row(cursor.intValue(0) == 1, cursor.longValue(1), cursor.longValue(2), cursor.intValue(3), cursor.stringValue(4)))
            }
        } finally {
            cursor.dispose()
        }
        return rows
    }

    // entiny: no task and an empty path mean removed on purpose, so a restart does not fetch it again
    private fun detach(db: SQLiteDatabase, row: Row) {
        if (row.edit) {
            db.executeFast("UPDATE inu_edit_history SET media_path = NULL WHERE rowid = ${row.key}").stepThis().dispose()
            return
        }
        db.executeFast("UPDATE inu_deleted_messages SET media_path = NULL WHERE rowid = ${row.key}").stepThis().dispose()
        ArchiveStore.deleteMediaTask(db, row.dialogId, row.msgId)
        db.executeFast("DELETE FROM inu_kept_media WHERE dialog_id = ${row.dialogId} AND msg_id = ${row.msgId}").stepThis().dispose()
    }

    private fun isReferenced(db: SQLiteDatabase, path: String): Boolean {
        val cursor = db.queryFinalized(
            "SELECT 1 FROM inu_deleted_messages WHERE media_path = ? UNION ALL SELECT 1 FROM inu_edit_history WHERE media_path = ? LIMIT 1",
            path,
            path,
        )
        return try { cursor.next() } finally { cursor.dispose() }
    }
}
