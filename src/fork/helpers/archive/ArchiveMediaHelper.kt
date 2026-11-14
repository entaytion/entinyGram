package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object ArchiveMediaHelper {
    private const val TAG = "ArchiveMediaHelper"
    private const val FOLDER = "entinyGram/Saved Attachments"

    private val downloads: File
        get() = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

    private fun privateRoot() = File(ApplicationLoader.applicationContext.filesDir, "entinyGram/media")

    internal fun archiveRoots(): List<File> = listOf(File(downloads, FOLDER), privateRoot())

    private val savedDirs = HashMap<Long, File>()

    // entiny: one hidden folder in Download; the app-private one is only the fallback when shared storage is not writable
    fun getSavedMediaDir(account: Int): File {
        val userId = UserConfig.getInstance(account).clientUserId
        synchronized(savedDirs) {
            savedDirs[userId]?.let { return it }
            val dir = File(downloads, "$FOLDER/account_$userId").takeIf(::prepare) ?: File(privateRoot(), "account_$userId").also(::prepare)
            savedDirs[userId] = dir
            return dir
        }
    }

    private fun prepare(dir: File): Boolean = try {
        (dir.isDirectory || dir.mkdirs()) && File(dir.parentFile, ".nomedia").let { it.exists() || it.createNewFile() }
    } catch (e: Throwable) {
        false
    }

    private const val AUTO_COPY_MAX_BYTES = 50L * 1024 * 1024
    private const val AUTO_FETCH_TIMEOUT_MS = 30 * 60_000L
    internal const val STARTUP_DELAY_MS = 20_000L

    // entiny: file copies run here so storageQueue only sees short database writes
    private val mediaQueue by lazy { org.telegram.messenger.DispatchQueue("inuArchiveMedia") }
    private val pendingCopies = HashSet<String>()
    private val migratedAccounts = HashSet<Int>()

    // entiny: media saved by older builds (app-private folder, old public folder) moves into the current folder once
    internal fun scheduleMediaMigration(account: Int) {
        synchronized(migratedAccounts) { if (!migratedAccounts.add(account)) return }
        mediaQueue.postRunnable({
            dropOldMirror()
            val targetRoot = getSavedMediaDir(account).canonicalFile
            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                val paths = ArrayList<String>()
                try {
                    val cursor = storage.database.queryFinalized(
                        "SELECT media_path FROM inu_deleted_messages WHERE media_path IS NOT NULL AND media_path != ''" +
                            " UNION SELECT media_path FROM inu_edit_history WHERE media_path IS NOT NULL AND media_path != ''"
                    )
                    try {
                        while (cursor.next()) cursor.stringValue(0)?.let(paths::add)
                    } finally {
                        cursor.dispose()
                    }
                } catch (e: Throwable) {
                    android.util.Log.d(TAG, "archive media migration query failed", e)
                }
                mediaQueue.postRunnable { migrateSavedMedia(account, targetRoot, paths) }
            }
        }, STARTUP_DELAY_MS)
    }

    private fun dropOldMirror() {
        val index = File(ApplicationLoader.applicationContext.filesDir, "entinyGram/mirror_index.tsv")
        if (!index.exists()) return
        for (dir in setOf("entinyGram", InuConfig.DOWNLOAD_DIRECTORY.value)) File(downloads, "$dir/Ghosts").deleteRecursively()
        index.delete()
    }

    private fun migrateSavedMedia(account: Int, targetRoot: File, paths: List<String>) {
        val storage = MessagesStorage.getInstance(account)
        val legacy = (listOf(privateRoot()) + setOf("entinyGram", InuConfig.DOWNLOAD_DIRECTORY.value).map { File(downloads, "$it/media") })
            .mapNotNull { runCatching { it.canonicalFile.path + File.separator }.getOrNull() }
        var moved = 0
        for (path in paths) {
            try {
                val source = File(path).canonicalFile
                if (!source.isFile || source.length() <= 0) continue
                if (source.path.startsWith(targetRoot.path + File.separator)) continue
                val copiedPath = runMediaCopy(PendingMediaCopy(source, File(targetRoot, source.name))) ?: continue
                moved++
                storage.storageQueue.postRunnable {
                    try {
                        ArchiveStore.replaceSavedMediaPath(storage.database, path, copiedPath)
                        if (legacy.any { source.path.startsWith(it) }) source.delete()
                    } catch (e: Throwable) {
                        File(copiedPath).delete()
                        android.util.Log.d(TAG, "archive media migration update failed", e)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive media migration failed", e)
            }
        }
        if (moved > 0) storage.storageQueue.postRunnable {
            AndroidUtilities.runOnUIThread {
                ArchiveCache.invalidateMediaCache(account)
                ArchiveCache.ensureAccountLoaded(account)
            }
        }
    }

    internal data class PendingMediaCopy(val source: File, val target: File)

    // entiny: a copy made while the message is being deleted or edited follows the attachment settings
    internal fun planNewMediaCopy(account: Int, message: TLRPC.Message?): PendingMediaCopy? {
        if (message == null || !desu.inugram.InuConfig.ARCHIVE_MEDIA_ENABLED.value) return null
        if (!ArchiveScope.allows(account, MessageObject.getDialogId(message), message, ArchiveScope.Kind.MEDIA)) return null
        val limit = ArchiveScope.mediaLimitBytes()
        return planMediaCopy(account, message, if (limit > 0) limit else AUTO_COPY_MAX_BYTES)
    }

    // entiny: the file reference dies with the message, so media that was never opened is fetched right at deletion
    internal fun fetchNewMedia(account: Int, message: TLRPC.Message, rowId: Long = 0L, deleted: Boolean = true) {
        if (!InuConfig.ARCHIVE_MEDIA_ENABLED.value || MessageObject.getMedia(message).let { it?.document == null && it?.photo == null }) return
        if (!ArchiveScope.allows(account, MessageObject.getDialogId(message), message, ArchiveScope.Kind.MEDIA)) return
        val limit = ArchiveScope.mediaLimitBytes().takeIf { it > 0 } ?: AUTO_COPY_MAX_BYTES
        if ((MessageObject.getMedia(message)?.document?.size ?: 0L) > limit) return
        if (planMediaCopy(account, message, 0L) != null) return
        AndroidUtilities.runOnUIThread { downloadArchiveMedia(account, message, rowId, deleted, AUTO_FETCH_TIMEOUT_MS) {} }
    }

    internal fun planMediaCopy(account: Int, message: TLRPC.Message?, maxBytes: Long = AUTO_COPY_MAX_BYTES): PendingMediaCopy? {
        if (message?.media == null || message.media is TLRPC.TL_messageMediaWebPage) return null
        return try {
            val fileLoader = FileLoader.getInstance(account) ?: return null
            val expectedSize = MessageObject.getMedia(message)?.document?.size ?: 0L
            if (maxBytes > 0 && expectedSize > maxBytes) return null
            val path = sequenceOf(
                message.attachPath?.takeIf { it.isNotBlank() }?.let { File(it) },
                fileLoader.getPathToMessage(message),
                fileLoader.getPathToMessage(message, true, true),
            ).filterNotNull().firstOrNull { it.isFile && it.length() > 0 && (expectedSize <= 0 || it.length() == expectedSize) && !it.name.endsWith(".enc") } ?: return null
            if (maxBytes > 0 && path.length() > maxBytes) return null
            PendingMediaCopy(path, File(getSavedMediaDir(account), originalName(message, path)))
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "planMediaCopy error", e)
            null
        }
    }

    internal fun copyThenStore(account: Int, key: String, plan: PendingMediaCopy, onSourceLost: (() -> Unit)? = null, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        synchronized(pendingCopies) { if (!pendingCopies.add(key)) return }
        mediaQueue.postRunnable {
            val copied = runMediaCopy(plan)
            if (copied == null) {
                synchronized(pendingCopies) { pendingCopies.remove(key) }
                if (!plan.source.exists()) {
                    onSourceLost?.invoke()
                }
                return@postRunnable
            }
            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                val kept = try {
                    storage.database?.let { store(it, copied) } == true
                } catch (e: Throwable) {
                    android.util.Log.d(TAG, "archive copy store failed", e)
                    false
                }
                if (!kept) File(copied).delete()
                synchronized(pendingCopies) { pendingCopies.remove(key) }
            }
        }
    }

    internal fun updateMediaPath(db: org.telegram.SQLite.SQLiteDatabase, table: String, where: String, path: String): Boolean {
        val free = "($where) AND (media_path IS NULL OR media_path = '')"
        val cursor = db.queryFinalized("SELECT 1 FROM $table WHERE $free")
        val exists = try { cursor.next() } finally { cursor.dispose() }
        if (!exists) return false
        val query = db.executeFast("UPDATE $table SET media_path = ? WHERE $free")
        try {
            query.bindString(1, path)
            query.step()
        } finally {
            query.dispose()
        }
        return true
    }

    internal fun latestEditRowId(db: org.telegram.SQLite.SQLiteDatabase, dialogId: Long, msgId: Int, date: Int): Long {
        val cursor = db.queryFinalized("SELECT MAX(rowid) FROM inu_edit_history WHERE dialog_id = $dialogId AND msg_id = $msgId AND date = $date")
        return try { if (cursor.next()) cursor.longValue(0) else 0L } finally { cursor.dispose() }
    }


    private fun originalName(message: TLRPC.Message, source: File): String {
        val document = MessageObject.getMedia(message)?.document ?: return source.name
        return FileLoader.getDocumentFileName(document)
            ?.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: source.name
    }

    // entiny: two copies of the same name can run at once, so the free name is claimed by creating its .partial
    private fun reserveTarget(target: File): File = synchronized(savedDirs) {
        val base = target.nameWithoutExtension
        val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
        var candidate = target
        var index = 2
        while (candidate.exists() || !File(candidate.parentFile, "${candidate.name}.partial").createNewFile()) {
            candidate = File(target.parentFile, "$base ($index)$ext")
            index++
        }
        candidate
    }

    private fun runMediaCopy(plan: PendingMediaCopy?): String? {
        if (plan == null) return null
        val target = try {
            reserveTarget(plan.target)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "copyMediaFile reserve error", e)
            return null
        }
        val temporary = File(target.parentFile, "${target.name}.partial")
        return try {
            plan.source.copyTo(temporary, overwrite = true)
            check(temporary.length() > 0 && temporary.length() == plan.source.length())
            check(temporary.renameTo(target))
            target.absolutePath
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "copyMediaFile error", e)
            null
        } finally {
            temporary.delete()
        }
    }

    fun downloadArchiveMedia(account: Int, message: TLRPC.Message, rowId: Long, deleted: Boolean, timeoutMs: Long = 120_000, callback: (String?) -> Unit): () -> Unit {
        val loader = FileLoader.getInstance(account)
        val media = MessageObject.getMedia(message)
        val document = media?.document
        val photo = media?.photo
        val size = photo?.sizes?.let { FileLoader.getClosestPhotoSizeWithSize(it, AndroidUtilities.getPhotoSize(true), false, null, true) }
        val location = if (size != null && photo != null) ImageLocation.getForPhoto(size, photo) else null
        val name = when {
            document != null -> FileLoader.getAttachFileName(document)
            location != null -> FileLoader.getAttachFileName(size)
            else -> {
                callback(null)
                return {}
            }
        }
        val center = NotificationCenter.getInstance(account)
        var active = true
        var loading = false
        lateinit var observer: NotificationCenter.NotificationCenterDelegate
        lateinit var timeout: Runnable
        fun detach() {
            center.removeObserver(observer, NotificationCenter.fileLoaded)
            center.removeObserver(observer, NotificationCenter.fileLoadFailed)
            AndroidUtilities.cancelRunOnUIThread(timeout)
        }
        fun stopLoading() {
            if (!loading) return
            loading = false
            if (document != null) loader.cancelLoadFile(document) else loader.cancelLoadFile(size)
        }
        fun finish(success: Boolean) {
            if (!active) return
            detach()
            if (!success) {
                stopLoading()
                active = false
                callback(null)
                return
            }
            fun deliver(path: String?) {
                AndroidUtilities.runOnUIThread {
                    if (active) {
                        active = false
                        callback(path)
                    }
                }
            }
            val dialogId = MessageObject.getDialogId(message)
            val table = if (deleted) "inu_deleted_messages" else "inu_edit_history"
            val where = if (deleted) "dialog_id = $dialogId AND msg_id = ${message.id}" else "rowid = $rowId AND dialog_id = $dialogId AND msg_id = ${message.id}"
            val storage = MessagesStorage.getInstance(account)
            mediaQueue.postRunnable {
                val plan = planMediaCopy(account, message, 0L)
                if (plan == null) {
                    deliver(null)
                    return@postRunnable
                }
                val copied = runMediaCopy(plan)
                if (copied == null) {
                    deliver(plan.source.absolutePath)
                    return@postRunnable
                }
                storage.storageQueue.postRunnable {
                    var stored = false
                    try {
                        val query = storage.database.executeFast("UPDATE $table SET media_path = ? WHERE $where")
                        try {
                            query.bindString(1, copied)
                            query.step()
                        } finally {
                            query.dispose()
                        }
                        val cursor = storage.database.queryFinalized("SELECT 1 FROM $table WHERE $where AND media_path = ?", copied)
                        stored = try { cursor.next() } finally { cursor.dispose() }
                    } catch (e: Exception) {
                        android.util.Log.d(TAG, "archive download save failed", e)
                    }
                    if (stored) {
                        if (deleted) ArchiveCache.rememberDeletedMediaPath(account, dialogId, message.id, copied)
                        else synchronized(ArchiveCache.cacheLock) { ArchiveCache.editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(message.id.toLong()) }
                        deliver(copied)
                    } else {
                        File(copied).delete()
                        deliver(plan.source.absolutePath)
                    }
                }
            }
        }
        observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, currentAccount: Int, vararg args: Any?) {
                if (args.getOrNull(0) == name) finish(id == NotificationCenter.fileLoaded)
            }
        }
        timeout = Runnable { finish(false) }
        center.addObserver(observer, NotificationCenter.fileLoaded)
        center.addObserver(observer, NotificationCenter.fileLoadFailed)
        AndroidUtilities.runOnUIThread(timeout, timeoutMs)
        if (planMediaCopy(account, message, 0L) != null) {
            finish(true)
        } else {
            loading = true
            val parent = MessageObject(account, message, false, false)
            if (document != null) {
                loader.loadFile(document, parent, FileLoader.PRIORITY_NORMAL, 1)
            } else {
                loader.loadFile(location, parent, "jpg", FileLoader.PRIORITY_NORMAL, 1)
            }
        }
        return {
            if (active) {
                active = false
                detach()
                stopLoading()
            }
        }
    }

    // entiny: image loaders resolve documents by the stock cache path, so put the archived copy back there
    private fun restoreToStockPath(archived: File, stock: File): File {
        if (stock.path.isEmpty() || archived.length() > RESTORE_MAX_BYTES) return archived
        if (Thread.currentThread() === android.os.Looper.getMainLooper().thread) {
            mediaQueue.postRunnable { if (!stock.exists()) restoreToStockPath(archived, stock) }
            return archived
        }
        val temporary = File(stock.parentFile, "${stock.name}.${java.util.UUID.randomUUID()}.partial")
        return try {
            stock.parentFile?.mkdirs()
            archived.copyTo(temporary, overwrite = true)
            check(temporary.length() == archived.length() && temporary.length() > 0)
            check(temporary.renameTo(stock))
            stock
        } catch (e: Throwable) {
            archived
        } finally {
            temporary.delete()
        }
    }

    private const val RESTORE_MAX_BYTES = 8L * 1024 * 1024

    @JvmStatic
    fun restoreArchived(account: Int, message: TLRPC.Message, stock: File): File =
        getArchivedMediaPath(account, MessageObject.getDialogId(message), message.id)?.let { restoreToStockPath(it, stock) } ?: stock

    private fun getArchivedMediaPath(account: Int, dialogId: Long, msgId: Int): File? {
        if (!DeletedMessagesHelper.isSaveDeletedEnabled()) return null
        ArchiveCache.ensureAccountLoaded(account)
        val path = synchronized(ArchiveCache.cacheLock) {
            ArchiveCache.deletedMessageMediaPaths.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())
        } ?: return null
        val file = File(path)
        return if (file.isFile && file.length() > 0) file else null
    }
}
