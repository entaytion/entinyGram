package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import java.io.File
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

// entiny: files of archived deleted messages stay in the telegram folders; the stock cleaners ask here before deleting
object KeptMedia {
    private class Snapshot(val names: Set<String>, val paths: Set<String>)

    private val lock = Any()
    @Volatile
    private var snapshots = arrayOfNulls<Snapshot>(UserConfig.MAX_ACCOUNT_COUNT)
    private val loading = HashSet<Int>()

    private fun enabled() =
        InuConfig.ARCHIVE_KEPT_MEDIA_PRESENT.value || (DeletedMessagesHelper.isSaveDeletedEnabled() && InuConfig.ARCHIVE_MEDIA_ENABLED.value)

    // entiny: an account whose index is not loaded yet protects everything, a cleaner run then simply does nothing
    @JvmStatic
    fun isProtected(file: File?): Boolean {
        if (file == null || !enabled()) return false
        if (!isReady()) {
            prepare()
            return true
        }
        return matchesSnapshot(file)
    }

    private fun matchesSnapshot(file: File): Boolean {
        val name = file.name
        val path = file.absolutePath
        for (snapshot in snapshots) {
            if (snapshot != null && (name in snapshot.names || path in snapshot.paths)) return true
        }
        return false
    }

    @JvmStatic
    fun isKnownProtected(file: File?): Boolean = file != null && enabled() && matchesSnapshot(file)

    @JvmStatic
    fun hasProtectedFiles(): Boolean =
        enabled() && snapshots.any { it != null && (it.names.isNotEmpty() || it.paths.isNotEmpty()) }

    @JvmStatic
    fun runWhenPrepared(action: Runnable) {
        fun loadNext(start: Int) {
            if (enabled()) {
                for (account in start until UserConfig.MAX_ACCOUNT_COUNT) {
                    if (snapshots[account] != null || !UserConfig.getInstance(account).isClientActivated) continue
                    val storage = MessagesStorage.getInstance(account)
                    storage.storageQueue.postRunnable {
                        storage.database?.let { ensureLoaded(account, it) }
                        loadNext(account + 1)
                    }
                    return
                }
            }
            action.run()
        }
        loadNext(0)
    }

    // entiny: the size limit counts only files the cleaner may delete
    @JvmStatic
    fun protectedBytes(dirs: android.util.SparseArray<File>): Long {
        if (!enabled()) return 0L
        var total = 0L
        fun walk(dir: File) {
            for (entry in dir.listFiles() ?: return) {
                if (entry.isDirectory) walk(entry) else if (isProtected(entry)) total += entry.length()
            }
        }
        for (i in 0 until dirs.size()) dirs.valueAt(i)?.let(::walk)
        return total
    }

    @JvmStatic
    fun isReady(): Boolean {
        val current = snapshots
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (current[account] == null && UserConfig.getInstance(account).isClientActivated) return false
        }
        return true
    }

    // entiny: true when every signed-in account has its index; otherwise loading starts and the caller skips this run
    @JvmStatic
    fun prepare(): Boolean {
        if (!enabled() || isReady()) return true
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (snapshots[account] != null || !UserConfig.getInstance(account).isClientActivated) continue
            val claimed = synchronized(lock) { loading.add(account) }
            if (!claimed) continue
            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                try {
                    storage.database?.let { reload(account, it) }
                } finally {
                    synchronized(lock) { loading.remove(account) }
                }
            }
        }
        return false
    }

    @JvmStatic
    fun reload(account: Int, db: SQLiteDatabase) {
        val names = HashSet<String>()
        val paths = HashSet<String>()
        try {
            val cursor = db.queryFinalized("SELECT name, path FROM inu_kept_media")
            try {
                while (cursor.next()) {
                    cursor.stringValue(0)?.takeIf { it.isNotEmpty() }?.let(names::add)
                    if (!cursor.isNull(1)) cursor.stringValue(1)?.takeIf { it.isNotEmpty() }?.let(paths::add)
                }
            } finally {
                cursor.dispose()
            }
        } catch (e: Throwable) {
            android.util.Log.d("KeptMedia", "kept media index load failed", e)
        }
        publish(account, Snapshot(names, paths))
    }

    private fun publish(account: Int, snapshot: Snapshot) = synchronized(lock) {
        val next = snapshots.copyOf()
        next[account] = snapshot
        snapshots = next
    }

    private fun ensureLoaded(account: Int, db: SQLiteDatabase) {
        if (snapshots[account] == null) reload(account, db)
    }

    // entiny: runs on the storage queue before stock deletes anything; the index changes first, the rows follow in the same queue run
    @JvmStatic
    fun protect(account: Int, db: SQLiteDatabase, dialogId: Long, msgId: Int, message: TLRPC.Message) {
        val entries = entriesOf(account, message)
        if (entries.isEmpty()) return
        ensureLoaded(account, db)
        val current = snapshots[account] ?: return
        if (!InuConfig.ARCHIVE_KEPT_MEDIA_PRESENT.value) InuConfig.ARCHIVE_KEPT_MEDIA_PRESENT.value = true
        publish(account, Snapshot(current.names + entries.map { it.first }, current.paths + entries.mapNotNull { it.second }))
        val state = db.executeFast("INSERT OR REPLACE INTO inu_kept_media(dialog_id, msg_id, name, path) VALUES(?, ?, ?, ?)")
        try {
            for ((name, path) in entries) {
                state.requery()
                state.bindLong(1, dialogId)
                state.bindInteger(2, msgId)
                state.bindString(3, name)
                if (path != null) state.bindString(4, path) else state.bindNull(4)
                state.step()
            }
        } finally {
            state.dispose()
        }
    }

    // entiny: the rows are already gone from inu_kept_media here; the index is rebuilt so a shared file stays covered by its other rows
    @JvmStatic
    fun release(account: Int, db: SQLiteDatabase) {
        if (snapshots[account] != null) reload(account, db)
        val current = snapshots
        val empty = (0 until UserConfig.MAX_ACCOUNT_COUNT).all { a ->
            val snapshot = current[a]
            if (snapshot == null) !UserConfig.getInstance(a).isClientActivated else snapshot.names.isEmpty() && snapshot.paths.isEmpty()
        }
        if (empty && InuConfig.ARCHIVE_KEPT_MEDIA_PRESENT.value) InuConfig.ARCHIVE_KEPT_MEDIA_PRESENT.value = false
    }

    @JvmStatic
    fun recordPath(account: Int, db: SQLiteDatabase, dialogId: Long, msgId: Int, file: File) {
        val path = file.absolutePath
        val state = db.executeFast("UPDATE inu_kept_media SET path = ? WHERE dialog_id = ? AND msg_id = ? AND name = ?")
        try {
            state.bindString(1, path)
            state.bindLong(2, dialogId)
            state.bindInteger(3, msgId)
            state.bindString(4, file.name)
            state.step()
        } finally {
            state.dispose()
        }
        val current = snapshots[account] ?: return
        if (path !in current.paths) publish(account, Snapshot(current.names, current.paths + path))
    }

    private fun entriesOf(account: Int, message: TLRPC.Message): List<Pair<String, String?>> {
        val media = MessageObject.getMedia(message) ?: return emptyList()
        if (media is TLRPC.TL_messageMediaEmpty || media is TLRPC.TL_messageMediaWebPage) return emptyList()
        val objects = ArrayList<TLObject>()
        media.photo?.sizes?.filterTo(objects) {
            it !is TLRPC.TL_photoSizeEmpty && it !is TLRPC.TL_photoStrippedSize && it !is TLRPC.TL_photoCachedSize && it !is TLRPC.TL_photoPathSize
        }
        media.document?.let { document ->
            objects.add(document)
            document.thumbs?.filterTo(objects) { it !is TLRPC.TL_photoStrippedSize && it !is TLRPC.TL_photoCachedSize && it !is TLRPC.TL_photoPathSize }
        }
        val loader = FileLoader.getInstance(account)
        val result = LinkedHashMap<String, String?>()
        for (obj in objects) {
            val name = FileLoader.getAttachFileName(obj)
            if (name.isNullOrEmpty() || '/' in name) continue
            val path = try {
                loader.getPathToAttach(obj, null, false, true)?.takeIf { it.isFile }?.absolutePath
                    ?: loader.getPathToAttach(obj, null, true, true)?.takeIf { it.isFile }?.absolutePath
            } catch (e: Throwable) {
                null
            }
            result[name] = path
            if (obj is TLRPC.Document) result["q_$name"] = path?.let { File(File(it).parentFile, "q_${File(it).name}").absolutePath }
        }
        return result.entries.map { it.key to it.value }
    }
}
