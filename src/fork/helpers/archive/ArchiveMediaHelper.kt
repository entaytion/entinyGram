package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object ArchiveMediaHelper {
    private const val TAG = "ArchiveMediaHelper"

    private const val MEDIA_DIR_NAME = "entinyGram/media"
    private const val NOMEDIA = ".nomedia"

    @JvmStatic
    fun isArchivePublic(): Boolean = InuConfig.ARCHIVE_MEDIA_PUBLIC.value

    // entiny: archive copy is always app-private so media_path stays valid; gallery export is a separate duplicate
    fun getSavedMediaDir(account: Int): File {
        val dir = File(ApplicationLoader.applicationContext.filesDir, "$MEDIA_DIR_NAME/account_${UserConfig.getInstance(account).clientUserId}")
        if (!dir.exists()) {
            try {
                dir.mkdirs()
                File(dir, NOMEDIA).createNewFile()
            } catch (e: Throwable) { }
        }
        return dir
    }

    private val EXPORTABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "mp4", "mov", "mkv", "3gp")
    private val ARCHIVE_NAME_REGEX = Regex("^(-?\\d+)_(\\d+)_[0-9a-fA-F-]{36}_(.*)$")
    private const val MIRROR_DIR_NAME = "Ghosts"
    private var noMediaChecked = false

    private data class MirrorRecord(val uri: String, val dir: String, val kind: String = "")

    // entiny: archive file name -> where its mirror copy lives, persisted so the copies can be moved when the download folder changes
    private val mirrorIndex: HashMap<String, MirrorRecord> by lazy {
        val map = HashMap<String, MirrorRecord>()
        try {
            val file = mirrorIndexFile()
            if (file.isFile) file.forEachLine {
                val parts = it.split('\t')
                if (parts.size >= 3) map[parts[0]] = MirrorRecord(parts[1], parts[2], parts.getOrElse(3) { "" })
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "mirror index read failed", e)
        }
        map
    }

    private fun mirrorIndexFile() = File(ApplicationLoader.applicationContext.filesDir, "entinyGram/mirror_index.tsv").also { it.parentFile?.mkdirs() }

    private fun rewriteMirrorIndex() {
        mirrorIndexFile().writeText(mirrorIndex.entries.joinToString("") { "${it.key}\t${it.value.uri}\t${it.value.dir}\t${it.value.kind}\n" })
    }

    private fun mirrorRelativePath(dir: String) = "${android.os.Environment.DIRECTORY_DOWNLOADS}/$dir/$MIRROR_DIR_NAME/"

    // entiny: mirrors an archived photo/video into Download/<download dir>/Ghosts once
    @JvmStatic
    @JvmOverloads
    fun exportToGallery(path: String?, kind: String = "") {
        if (path.isNullOrEmpty() || !isArchivePublic() || kind == "edit") return
        try {
            val source = File(path)
            if (!source.isFile || source.length() <= 0L) return
            if (source.extension.lowercase() !in EXPORTABLE_EXTENSIONS) return
            synchronized(mirrorIndex) { if (source.name in mirrorIndex) return }
            val dir = InuConfig.DOWNLOAD_DIRECTORY.value
            val uri = mirrorToDownloads(source, mirrorName(source, kind), dir) ?: return
            synchronized(mirrorIndex) {
                mirrorIndex[source.name] = MirrorRecord(uri.toString(), dir, kind)
                mirrorIndexFile().appendText("${source.name}\t${uri}\t${dir}\t${kind}\n")
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "archive mirror failed", e)
        }
    }

    private fun mirrorToDownloads(source: File, name: String, dir: String): android.net.Uri? {
        val resolver = ApplicationLoader.applicationContext.contentResolver
        val relative = mirrorRelativePath(dir)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeOf(source))
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        ensureNoMedia(dir)
        return uri
    }

    // entiny: a .nomedia keeps the mirror out of the gallery; the file API is tried first, MediaStore is the fallback
    private fun ensureNoMedia(dir: String) {
        if (noMediaChecked) return
        noMediaChecked = true
        try {
            val folder = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "$dir/$MIRROR_DIR_NAME")
            val marker = File(folder, ".nomedia")
            if (marker.exists()) return
            if (folder.isDirectory && runCatching { marker.createNewFile() }.getOrDefault(false)) return
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, ".nomedia")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, mirrorRelativePath(dir))
            }
            ApplicationLoader.applicationContext.contentResolver.insert(
                android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            )
        } catch (e: Throwable) {
            android.util.Log.d(TAG, ".nomedia creation failed", e)
        }
    }

    // entiny: the download folder changed, move the existing mirror copies instead of leaving them behind or exporting again
    @JvmStatic
    fun moveMirror(newDir: String) {
        mediaQueue.postRunnable {
            try {
                val resolver = ApplicationLoader.applicationContext.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, mirrorRelativePath(newDir))
                }
                var moved = 0
                synchronized(mirrorIndex) {
                    for ((name, record) in mirrorIndex.entries.toList()) {
                        if (record.dir == newDir) continue
                        val updated = try {
                            resolver.update(android.net.Uri.parse(record.uri), values, null, null) > 0
                        } catch (e: Throwable) {
                            false
                        }
                        if (updated) {
                            mirrorIndex[name] = record.copy(dir = newDir)
                            moved++
                        }
                    }
                    if (moved > 0) rewriteMirrorIndex()
                }
                if (moved > 0) {
                    noMediaChecked = false
                    ensureNoMedia(newDir)
                }
                android.util.Log.d(TAG, "mirror moved: $moved files to $newDir")
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "mirror move failed", e)
            }
        }
    }

    // entiny: edit revisions used to be mirrored too, drop those copies once; rows saved before the kind column are told apart by file name
    private fun removeEditMirrors() {
        if (!isArchivePublic()) return
        mediaQueue.postRunnable {
            try {
                val resolver = ApplicationLoader.applicationContext.contentResolver
                var removed = 0
                var changed = false
                synchronized(mirrorIndex) {
                    for ((name, record) in mirrorIndex.entries.toList()) {
                        if (record.kind.isNotEmpty()) continue
                        val uri = android.net.Uri.parse(record.uri)
                        val displayName = try {
                            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                        } catch (e: Throwable) {
                            continue
                        }
                        changed = true
                        when {
                            displayName == null -> mirrorIndex.remove(name)
                            displayName.substringBeforeLast('.').endsWith("_edit") -> {
                                try { resolver.delete(uri, null, null) } catch (e: Throwable) { }
                                mirrorIndex.remove(name)
                                removed++
                            }
                            else -> mirrorIndex[name] = record.copy(kind = "deleted")
                        }
                    }
                    if (changed) rewriteMirrorIndex()
                }
                android.util.Log.d(TAG, "edit mirror removed: $removed files")
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "edit mirror removal failed", e)
            }
        }
    }

    // entiny: the mirror was switched off, the archive itself stays in the app so the copies in Download can go
    @JvmStatic
    fun removeMirror() {
        mediaQueue.postRunnable {
            try {
                val resolver = ApplicationLoader.applicationContext.contentResolver
                var removed = 0
                synchronized(mirrorIndex) {
                    for ((name, record) in mirrorIndex.entries.toList()) {
                        try {
                            resolver.delete(android.net.Uri.parse(record.uri), null, null)
                            mirrorIndex.remove(name)
                            removed++
                        } catch (e: Throwable) {
                            android.util.Log.d(TAG, "mirror copy removal failed", e)
                        }
                    }
                    rewriteMirrorIndex()
                }
                android.util.Log.d(TAG, "mirror removed: $removed files")
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "mirror removal failed", e)
            }
        }
    }

    private fun mimeOf(file: File): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    // entiny: "<date>_<chat>_<message id>_<deleted|edit>.<ext>" instead of the stock cache name
    private fun mirrorName(source: File, kind: String): String {
        val match = ARCHIVE_NAME_REGEX.matchEntire(source.name)
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date(source.lastModified()))
        val parts = ArrayList<String>()
        parts.add(stamp)
        if (match != null) {
            chatTitle(match.groupValues[1].toLongOrNull())?.let { parts.add(it) }
            parts.add(match.groupValues[2])
        } else {
            parts.add(source.nameWithoutExtension.take(40))
        }
        if (kind.isNotEmpty()) parts.add(kind)
        return parts.joinToString("_") + "." + source.extension.lowercase()
    }

    private fun chatTitle(dialogId: Long?): String? {
        if (dialogId == null || dialogId == 0L) return null
        return try {
            val controller = MessagesController.getInstance(UserConfig.selectedAccount)
            val title = if (dialogId > 0) {
                org.telegram.messenger.UserObject.getUserName(controller.getUser(dialogId))
            } else {
                controller.getChat(-dialogId)?.title
            }
            title?.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), " ")?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: dialogId.toString()
        } catch (e: Throwable) {
            dialogId.toString()
        }
    }

    // entiny: exports archived photos and videos once, in small batches; safe to call repeatedly
    @JvmStatic
    fun exportExistingArchive(account: Int) {
        if (!isArchivePublic()) return
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            val rows = ArrayList<Pair<String, String>>()
            try {
                val db = storage.database
                if (db != null) {
                    for ((table, kind) in listOf("inu_deleted_messages" to "deleted")) {
                        val cursor = db.queryFinalized("SELECT media_path FROM $table WHERE media_path IS NOT NULL AND media_path != ''")
                        try {
                            while (cursor.next()) cursor.stringValue(0)?.let { rows.add(it to kind) }
                        } finally {
                            cursor.dispose()
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive export query failed", e)
            }
            mediaQueue.postRunnable {
                var done = 0
                for ((path, kind) in rows) {
                    exportToGallery(path, kind)
                    if (++done % 25 == 0) Thread.sleep(200)
                }
            }
        }
    }

    private const val ARCHIVE_QUOTA_BYTES = 3L * 1024 * 1024 * 1024
    private val archiveUsage = HashMap<Int, Long>()

    private fun archiveUsage(account: Int): Long = synchronized(archiveUsage) {
        archiveUsage.getOrPut(account) { getSavedMediaDir(account).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L }
    }

    private fun addArchiveUsage(account: Int, delta: Long) = synchronized(archiveUsage) {
        archiveUsage[account] = (archiveUsage[account] ?: 0L) + delta
    }

    internal fun resetArchiveUsage(account: Int) = synchronized(archiveUsage) { archiveUsage.remove(account) }

    private const val AUTO_COPY_MAX_BYTES = 50L * 1024 * 1024
    internal const val STARTUP_DELAY_MS = 20_000L

    // entiny: file copies run here so storageQueue only sees short database writes
    private val mediaQueue by lazy { org.telegram.messenger.DispatchQueue("inuArchiveMedia") }
    private val pendingCopies = HashSet<String>()
    private val migratedAccounts = HashSet<Int>()

    // entiny: legacy import of any media that still lives in the old public download folder
    internal fun scheduleMediaMigration(account: Int) {
        synchronized(migratedAccounts) { if (!migratedAccounts.add(account)) return }
        removeEditMirrors()
        mediaQueue.postRunnable({
            val targetRoot = getSavedMediaDir(account).canonicalFile
            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                val paths = ArrayList<String>()
                try {
                    val db = storage.database
                    if (db != null) {
                        val cursor = db.queryFinalized(
                            "SELECT media_path FROM inu_deleted_messages WHERE media_path IS NOT NULL AND media_path != ''" +
                                " UNION SELECT media_path FROM inu_edit_history WHERE media_path IS NOT NULL AND media_path != ''"
                        )
                        try {
                            while (cursor.next()) cursor.stringValue(0)?.let(paths::add)
                        } finally {
                            cursor.dispose()
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.d(TAG, "archive media migration query failed", e)
                }
                mediaQueue.postRunnable { migrateSavedMedia(account, targetRoot, paths) }
            }
        }, STARTUP_DELAY_MS)
    }

    private fun migrateSavedMedia(account: Int, targetRoot: File, paths: List<String>) {
        val started = android.os.SystemClock.elapsedRealtime()
        val storage = MessagesStorage.getInstance(account)
        var moved = 0
        for (path in paths) {
            try {
                val source = File(path).canonicalFile
                if (!source.isFile || source.length() <= 0) continue
                if (source.path.startsWith(targetRoot.path + File.separator)) continue
                val copiedPath = runMediaCopy(PendingMediaCopy(source, File(targetRoot, "${java.util.UUID.randomUUID()}_${source.name}"))) ?: continue
                moved++
                storage.storageQueue.postRunnable {
                    try {
                        storage.database?.let { InuDatabaseHelper.replaceSavedMediaPath(it, path, copiedPath) }
                        deleteLegacySource(source)
                    } catch (e: Throwable) {
                        File(copiedPath).delete()
                        android.util.Log.d(TAG, "archive media migration update failed", e)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive media migration failed", e)
            }
        }
        android.util.Log.d(TAG, "archive media migration: $moved of ${paths.size} files in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }

    // entiny: the archive used to live in the public Download folder, remove that copy after it was moved into the private archive
    private fun deleteLegacySource(source: File) {
        try {
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            for (dir in setOf("entinyGram", InuConfig.DOWNLOAD_DIRECTORY.value)) {
                val legacy = File(downloads, "$dir/media").canonicalFile
                if (source.path.startsWith(legacy.path + File.separator)) {
                    source.delete()
                    return
                }
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "legacy archive copy cleanup failed", e)
        }
    }

    internal data class PendingMediaCopy(val source: File, val target: File, val expectedSize: Long = 0L)

    // entiny: a copy made while the message is being deleted or edited follows the attachment settings
    internal fun planNewMediaCopy(account: Int, message: TLRPC.Message?): PendingMediaCopy? {
        if (message == null || !desu.inugram.InuConfig.ARCHIVE_MEDIA_ENABLED.value) return null
        if (!ArchiveScope.allows(account, MessageObject.getDialogId(message), message, ArchiveScope.Kind.MEDIA)) return null
        val limit = ArchiveScope.mediaLimitBytes()
        return planMediaCopy(account, message, if (limit > 0) limit else AUTO_COPY_MAX_BYTES)
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
            if (maxBytes > 0 && archiveUsage(account) + path.length() > ARCHIVE_QUOTA_BYTES) return null
            PendingMediaCopy(path, File(getSavedMediaDir(account), "${MessageObject.getDialogId(message)}_${message.id}_${java.util.UUID.randomUUID()}_${path.name}"), expectedSize)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "planMediaCopy error", e)
            null
        }
    }

    internal fun copyThenStore(account: Int, key: String, plan: PendingMediaCopy, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        synchronized(pendingCopies) { if (!pendingCopies.add(key)) return }
        mediaQueue.postRunnable {
            val started = android.os.SystemClock.elapsedRealtime()
            val copied = runMediaCopy(plan)
            android.util.Log.d(TAG, "archive copy ${plan.source.length()} bytes in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            if (copied == null) {
                synchronized(pendingCopies) { pendingCopies.remove(key) }
                return@postRunnable
            }
            val copiedSize = File(copied).length()
            addArchiveUsage(account, copiedSize)
            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                val kept = try {
                    storage.database?.let { store(it, copied) } == true
                } catch (e: Throwable) {
                    android.util.Log.d(TAG, "archive copy store failed", e)
                    false
                }
                if (!kept) {
                    File(copied).delete()
                    addArchiveUsage(account, -copiedSize)
                } else {
                    mediaQueue.postRunnable { exportToGallery(copied, if (key.startsWith("e:")) "edit" else "deleted") }
                }
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


    private fun runMediaCopy(plan: PendingMediaCopy?): String? {
        if (plan == null) return null
        val temporary = File(plan.target.parentFile, "${plan.target.name}.partial")
        try {
            check(plan.expectedSize <= 0 || plan.source.length() == plan.expectedSize)
            if (plan.target.isFile && plan.target.length() == plan.source.length() && plan.target.length() > 0) {
                return plan.target.absolutePath
            }
            plan.source.copyTo(temporary, overwrite = true)
            check(temporary.length() == plan.source.length() && temporary.length() > 0)
            check(plan.expectedSize <= 0 || temporary.length() == plan.expectedSize)
            check(temporary.renameTo(plan.target))
            return plan.target.absolutePath
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "copyMediaFile error", e)
            return null
        } finally {
            temporary.delete()
        }
    }

    fun downloadArchiveMedia(account: Int, message: TLRPC.Message, rowId: Long, deleted: Boolean, callback: (String?) -> Unit): () -> Unit {
        val loader = FileLoader.getInstance(account)
        val media = MessageObject.getMedia(message)
        val document = media?.document
        val photo = media?.photo
        val size = photo?.sizes?.let { FileLoader.getClosestPhotoSizeWithSize(it, AndroidUtilities.getPhotoSize()) }
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
        lateinit var observer: NotificationCenter.NotificationCenterDelegate
        lateinit var timeout: Runnable
        fun detach() {
            center.removeObserver(observer, NotificationCenter.fileLoaded)
            center.removeObserver(observer, NotificationCenter.fileLoadFailed)
            AndroidUtilities.cancelRunOnUIThread(timeout)
        }
        fun finish(success: Boolean) {
            if (!active) return
            detach()
            if (!success) {
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
                        exportToGallery(copied, if (deleted) "deleted" else "edit")
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
        AndroidUtilities.runOnUIThread(timeout, 120_000)
        if (planMediaCopy(account, message, 0L) != null) {
            finish(true)
        } else if (document != null) {
            loader.loadFile(document, message, FileLoader.PRIORITY_NORMAL, 1)
        } else {
            loader.loadFile(location, message, "jpg", FileLoader.PRIORITY_NORMAL, 1)
        }
        return { active = false; detach() }
    }

    // entiny: image loaders resolve documents by the stock cache path, so put the archived copy back there
    private fun restoreToStockPath(archived: File, stock: File): File {
        if (stock.path.isEmpty() || archived.length() > RESTORE_MAX_BYTES) return archived
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
