package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
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

    internal const val STARTUP_DELAY_MS = 20_000L
    private const val FETCH_TIMEOUT_MS = 3 * 60_000L
    private const val MAX_FETCHES = 8

    // entiny: file copies run here so storageQueue only sees short database writes
    private val mediaQueue by lazy { org.telegram.messenger.DispatchQueue("inuArchiveMedia") }
    private val pendingCopies = HashSet<String>()
    private val fetching = HashSet<String>()
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

    internal class PendingMediaCopy(val source: File, val target: File) {
        val size = source.length()
        private var pinned: java.io.InputStream? = null

        // entiny: stock deletes replaced media right after it stores an edit, an open stream keeps the old bytes readable
        fun pin() = apply { pinned = runCatching { source.inputStream() }.getOrNull() }

        fun open(): java.io.InputStream = pinned ?: source.inputStream()

        fun release() {
            runCatching { pinned?.close() }
        }
    }

    internal fun planMediaCopy(account: Int, message: TLRPC.Message?, kind: ArchiveScope.Kind, maxBytes: Long = ArchiveScope.mediaLimitBytes(false)): PendingMediaCopy? {
        if (message?.media == null || message.media is TLRPC.TL_messageMediaWebPage || !InuConfig.ARCHIVE_MEDIA_ENABLED.value) return null
        if (!ArchiveScope.allows(account, MessageObject.getDialogId(message), message, kind)) return null
        return try {
            val fileLoader = FileLoader.getInstance(account) ?: return null
            val media = MessageObject.getMedia(message)
            val expectedSize = media?.document?.size ?: 0L
            if (expectedSize > maxBytes) return null
            val photoSizes = media?.photo?.sizes.orEmpty().filter { it !is TLRPC.TL_photoSizeEmpty && it !is TLRPC.TL_photoStrippedSize }
            val candidates = sequenceOf(
                message.attachPath?.takeIf { it.isNotBlank() }?.let { File(it) },
                fileLoader.getPathToMessage(message),
                fileLoader.getPathToMessage(message, true, true),
            ) + photoSizes.asReversed().asSequence().map { fileLoader.getPathToAttach(it, null, false, true) }
            val source = candidates.filterNotNull().firstOrNull {
                it.isFile && it.length() > 0 && (expectedSize <= 0 || it.length() == expectedSize) && !it.name.endsWith(".enc")
            } ?: return null
            PendingMediaCopy(source, File(getSavedMediaDir(account), originalName(message, source))).takeIf { it.size <= maxBytes }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "planMediaCopy error", e)
            null
        }
    }

    internal fun copyThenStore(account: Int, key: String, plan: PendingMediaCopy, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        synchronized(pendingCopies) {
            if (!pendingCopies.add(key)) {
                plan.release()
                return
            }
        }
        mediaQueue.postRunnable {
            val copied = runMediaCopy(plan)
            if (copied == null) {
                synchronized(pendingCopies) { pendingCopies.remove(key) }
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

    // entiny: the only download the archive starts: media of a deleted message that is not on disk, size checked first, public chats only when allowed
    internal fun fetchThenStore(account: Int, key: String, message: TLRPC.Message, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        if (!InuConfig.ARCHIVE_MEDIA_ENABLED.value || message.media == null || message.media is TLRPC.TL_messageMediaWebPage) return
        val dialogId = MessageObject.getDialogId(message)
        if (DialogObject.isEncryptedDialog(dialogId) || !ArchiveScope.allows(account, dialogId, message)) return
        if (dialogId < 0 && ArchiveScope.chat(account, -dialogId).let { it == null || (ChatObject.isPublic(it) && !InuConfig.ARCHIVE_MEDIA_PUBLIC.value) }) return
        val media = MessageObject.getMedia(message) ?: return
        if (media.ttl_seconds != 0) return
        val document = media.document
        val photoSize = if (document == null) FileLoader.getClosestPhotoSizeWithSize(media.photo?.sizes, AndroidUtilities.getPhotoSize()) else null
        val size = document?.size ?: photoSize?.size?.toLong() ?: return
        val limit = ArchiveScope.mediaLimitBytes(true)
        val name = FileLoader.getAttachFileName(document ?: photoSize)
        if (size <= 0 || size > limit || name.isNullOrEmpty()) return
        synchronized(fetching) { if (fetching.size >= MAX_FETCHES || !fetching.add(key)) return }
        AndroidUtilities.runOnUIThread {
            val center = NotificationCenter.getInstance(account)
            lateinit var observer: NotificationCenter.NotificationCenterDelegate
            val stop = Runnable {
                center.removeObserver(observer, NotificationCenter.fileLoaded)
                center.removeObserver(observer, NotificationCenter.fileLoadFailed)
                synchronized(fetching) { fetching.remove(key) }
            }
            observer = object : NotificationCenter.NotificationCenterDelegate {
                override fun didReceivedNotification(id: Int, currentAccount: Int, vararg args: Any?) {
                    if (args.getOrNull(0) != name) return
                    AndroidUtilities.cancelRunOnUIThread(stop)
                    stop.run()
                    if (id != NotificationCenter.fileLoaded) return
                    mediaQueue.postRunnable { planMediaCopy(account, message, ArchiveScope.Kind.DELETED, limit)?.let { copyThenStore(account, key, it, store) } }
                }
            }
            center.addObserver(observer, NotificationCenter.fileLoaded)
            center.addObserver(observer, NotificationCenter.fileLoadFailed)
            AndroidUtilities.runOnUIThread(stop, FETCH_TIMEOUT_MS)
            val loader = FileLoader.getInstance(account)
            if (document != null) loader.loadFile(document, message, FileLoader.PRIORITY_LOW, 0)
            else loader.loadFile(ImageLocation.getForPhoto(photoSize, media.photo), message, "jpg", FileLoader.PRIORITY_LOW, 0)
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

    private fun runMediaCopy(plan: PendingMediaCopy): String? {
        var temporary: File? = null
        return try {
            plan.open().use { input ->
                val target = reserveTarget(plan.target)
                val partial = File(target.parentFile, "${target.name}.partial").also { temporary = it }
                partial.outputStream().use { input.copyTo(it) }
                check(plan.size > 0 && partial.length() == plan.size)
                check(partial.renameTo(target))
                target.absolutePath
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "copyMediaFile error", e)
            null
        } finally {
            temporary?.delete()
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
