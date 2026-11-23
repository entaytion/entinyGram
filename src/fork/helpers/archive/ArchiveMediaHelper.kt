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
    private fun getSavedMediaDir(account: Int): File {
        val userId = UserConfig.getInstance(account).clientUserId
        synchronized(savedDirs) {
            savedDirs[userId]?.takeIf(File::isDirectory)?.let { return it }
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
    private const val FETCH_CHECK_MS = 60_000L
    private const val FETCH_MAX_MS = 30 * 60_000L
    private const val FETCH_HARD_MS = 6 * 60 * 60_000L
    private const val MAX_FETCHES = 8

    // entiny: file copies run here so storageQueue only sees short database writes
    private val mediaQueue by lazy { org.telegram.messenger.DispatchQueue("inuArchiveMedia") }
    private val pendingCopies = HashSet<String>()
    private val fetching = HashSet<String>()
    private val waitingFetches = ArrayDeque<Runnable>()
    private var activeFetches = 0
    private val migratedAccounts = HashSet<Int>()

    // entiny: media saved by older builds (app-private folder, old public folder) moves into the current folder once
    internal fun scheduleMediaMigration(account: Int) {
        synchronized(migratedAccounts) { if (!migratedAccounts.add(account)) return }
        mediaQueue.postRunnable({
            dropOldMirror()
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
                storage.database?.let { DeletedMessagesHelper.resumePendingMedia(account, it) }
                ArchiveQuota.schedule(account)
                mediaQueue.postRunnable { migrateSavedMedia(account, paths) }
            }
        }, STARTUP_DELAY_MS)
    }

    private fun dropOldMirror() {
        val index = File(ApplicationLoader.applicationContext.filesDir, "entinyGram/mirror_index.tsv")
        if (!index.exists()) return
        for (dir in setOf("entinyGram", InuConfig.DOWNLOAD_DIRECTORY.value)) File(downloads, "$dir/Ghosts").deleteRecursively()
        index.delete()
    }

    private fun migrateSavedMedia(account: Int, paths: List<String>) {
        val storage = MessagesStorage.getInstance(account)
        val legacy = (listOf(privateRoot()) + setOf("entinyGram", InuConfig.DOWNLOAD_DIRECTORY.value).map { File(downloads, "$it/media") })
            .mapNotNull { runCatching { it.canonicalFile.path + File.separator }.getOrNull() }
        val targetRoot by lazy { getSavedMediaDir(account).canonicalFile }
        var moved = 0
        for (path in paths) {
            try {
                val source = File(path).canonicalFile
                if (!source.isFile || source.length() <= 0) continue
                if (legacy.none { source.path.startsWith(it) }) continue
                if (source.path.startsWith(targetRoot.path + File.separator)) continue
                val copiedPath = runMediaCopy(account, PendingMediaCopy(source, source.name)) ?: continue
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

    internal class PendingMediaCopy(val source: File, val fileName: String) {
        val size = source.length()
        private var pinned: java.io.InputStream? = null

        // entiny: stock deletes replaced media right after it stores an edit, an open stream keeps the old bytes readable
        fun pin() = apply { pinned = runCatching { source.inputStream() }.getOrNull() }

        fun open(): java.io.InputStream = pinned ?: source.inputStream()

        fun release() {
            runCatching { pinned?.close() }
        }
    }

    internal fun isUsable(file: File, message: TLRPC.Message?): Boolean {
        if (!file.isFile || file.length() <= 0 || file.name.endsWith(".enc")) return false
        val expected = MessageObject.getMedia(message)?.document?.size ?: 0L
        return expected <= 0 || file.length() == expected
    }

    private val THUMB_TYPES = setOf("s", "m", "i", "j")

    // entiny: same pick as stock getPathToMessage, so the archive downloads and looks for one size
    internal fun targetPhotoSize(photo: TLRPC.Photo?): TLRPC.PhotoSize? =
        FileLoader.getClosestPhotoSizeWithSize(photo?.sizes, AndroidUtilities.getPhotoSize(true), false, null, true)

    private fun isFullPhoto(size: TLRPC.PhotoSize?): Boolean =
        size != null && size !is TLRPC.TL_photoSizeEmpty && size !is TLRPC.TL_photoStrippedSize &&
            size !is TLRPC.TL_photoCachedSize && size !is TLRPC.TL_photoPathSize && size.type !in THUMB_TYPES

    // entiny: a full photo of another resolution is fine, thumbnails never
    private fun fullPhotoSizes(photo: TLRPC.Photo?): List<TLRPC.PhotoSize> {
        val full = photo?.sizes.orEmpty().filter(::isFullPhoto).sortedByDescending { maxOf(it.w, it.h) }
        return (listOfNotNull(targetPhotoSize(photo)?.takeIf(::isFullPhoto)) + full).distinct()
    }

    internal fun planMediaCopy(account: Int, message: TLRPC.Message?, kind: ArchiveScope.Kind, maxBytes: Long = ArchiveScope.mediaLimitBytes(false), loaded: File? = null): PendingMediaCopy? {
        if (message?.media == null || message.media is TLRPC.TL_messageMediaWebPage || !InuConfig.ARCHIVE_MEDIA_ENABLED.value) return null
        if (!ArchiveScope.allows(account, MessageObject.getDialogId(message), message, kind)) return null
        return try {
            val fileLoader = FileLoader.getInstance(account) ?: return null
            val media = MessageObject.getMedia(message)
            val photoSizes = if (media?.document == null) fullPhotoSizes(media?.photo) else emptyList()
            val candidates = sequenceOf(
                loaded,
                message.attachPath?.takeIf { it.isNotBlank() }?.let { File(it) },
            ) + (if (media?.document != null) sequenceOf(
                fileLoader.getPathToMessage(message),
                fileLoader.getPathToMessage(message, true, true),
            ) else emptySequence()) + photoSizes.asSequence().flatMap {
                sequenceOf(fileLoader.getPathToAttach(it, null, false, true), fileLoader.getPathToAttach(it, null, true, true))
            }
            val source = candidates.filterNotNull().firstOrNull { isUsable(it, message) } ?: return null
            // entiny: the size limit is for copies; a deleted message's file kept in a telegram media folder costs no extra space
            val copies = kind == ArchiveScope.Kind.EDITED || !isTelegramMediaFile(source)
            PendingMediaCopy(source, originalName(message, source)).takeIf { !copies || it.size <= maxBytes }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "planMediaCopy error", e)
            null
        }
    }

    private val TELEGRAM_MEDIA_DIRS = intArrayOf(
        FileLoader.MEDIA_DIR_IMAGE, FileLoader.MEDIA_DIR_AUDIO, FileLoader.MEDIA_DIR_VIDEO, FileLoader.MEDIA_DIR_DOCUMENT,
        FileLoader.MEDIA_DIR_FILES, FileLoader.MEDIA_DIR_IMAGE_PUBLIC, FileLoader.MEDIA_DIR_VIDEO_PUBLIC,
    )

    private fun canonical(file: File?): String? = file?.let { runCatching { it.canonicalPath }.getOrNull() }

    // entiny: telegram's own media folders are app files the system leaves alone, unlike the cache folder
    internal fun isTelegramMediaFile(file: File): Boolean {
        val path = canonical(file) ?: return false
        val cache = canonical(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE))
        return TELEGRAM_MEDIA_DIRS.any { type ->
            val dir = canonical(FileLoader.getDirectory(type)) ?: return@any false
            dir != cache && path.startsWith(dir + File.separator)
        }
    }

    private fun isArchiveCopy(file: File): Boolean {
        val path = canonical(file) ?: return false
        return archiveRoots().any { root -> canonical(root)?.let { path.startsWith(it + File.separator) } == true }
    }

    // entiny: a deleted message's file in a telegram media folder is referenced and kept by KeptMedia; only cache-folder files are copied
    internal fun keepOrCopy(account: Int, key: String, plan: PendingMediaCopy, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        if (!isTelegramMediaFile(plan.source)) {
            copyThenStore(account, key, plan, store)
            return
        }
        plan.release()
        val path = plan.source.absolutePath
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            try {
                storage.database?.let { store(it, path) }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive reference store failed", e)
            }
        }
    }

    // entiny: the attachments screen and stats read the archive rows, so referenced telegram files show next to old copies
    internal fun savedMediaFiles(account: Int, db: org.telegram.SQLite.SQLiteDatabase): List<File> {
        val files = LinkedHashMap<String, File>()
        try {
            val cursor = db.queryFinalized(
                "SELECT media_path FROM inu_deleted_messages WHERE media_path IS NOT NULL AND media_path != ''" +
                    " UNION SELECT media_path FROM inu_edit_history WHERE media_path IS NOT NULL AND media_path != ''"
            )
            try {
                while (cursor.next()) cursor.stringValue(0)?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }?.let { files[it.absolutePath] = it }
            } finally {
                cursor.dispose()
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "saved media query failed", e)
        }
        val userId = UserConfig.getInstance(account).clientUserId
        for (root in archiveRoots()) {
            File(root, "account_$userId").listFiles()?.forEach {
                if (it.isFile && it.name != ".nomedia" && !it.name.endsWith(".partial") && it.length() > 0) {
                    files.putIfAbsent(it.absolutePath, it)
                }
            }
        }
        return files.values.toList()
    }

    internal fun isDeletableSaved(file: File): Boolean = isArchiveCopy(file) || isTelegramMediaFile(file)

    internal fun deleteSavedMediaFile(file: File) {
        if (isDeletableSaved(file)) file.delete()
    }

    internal fun copyThenStore(account: Int, key: String, plan: PendingMediaCopy, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        synchronized(pendingCopies) {
            if (!pendingCopies.add(key)) {
                plan.release()
                return
            }
        }
        mediaQueue.postRunnable {
            val copied = runMediaCopy(account, plan)
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
    // entiny: false means the media will never be fetched, so a pending task can be dropped
    private class FetchTarget(val media: TLRPC.MessageMedia, val document: TLRPC.Document?, val photoSize: TLRPC.PhotoSize?, val name: String, val limit: Long)

    // entiny: called on the storage queue, the chat lookup of a cold process only works there
    private fun fetchTarget(account: Int, message: TLRPC.Message): FetchTarget? {
        if (!InuConfig.ARCHIVE_MEDIA_ENABLED.value || message.media == null || message.media is TLRPC.TL_messageMediaWebPage) return null
        if (!UserConfig.getInstance(account).isClientActivated) return null
        val dialogId = MessageObject.getDialogId(message)
        if (DialogObject.isEncryptedDialog(dialogId) || !ArchiveScope.allows(account, dialogId, message)) return null
        if (dialogId < 0 && ArchiveScope.chat(account, -dialogId).let { it == null || (ChatObject.isPublic(it) && !InuConfig.ARCHIVE_MEDIA_PUBLIC.value) }) return null
        val media = MessageObject.getMedia(message) ?: return null
        if (media.ttl_seconds != 0) return null
        val document = media.document
        val photoSize = if (document == null) targetPhotoSize(media.photo)?.takeIf(::isFullPhoto) else null
        val size = document?.size ?: photoSize?.size?.toLong() ?: return null
        val limit = ArchiveScope.mediaLimitBytes(true)
        val name = FileLoader.getAttachFileName(document ?: photoSize)
        if (size <= 0 || size > limit || name.isNullOrEmpty()) return null
        return FetchTarget(media, document, photoSize, name, limit)
    }

    // entiny: a queued fetch is checked again when its turn comes; onStart runs only when the download really begins
    internal fun fetchThenStore(
        account: Int, key: String, message: TLRPC.Message, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean,
        wanted: ((org.telegram.SQLite.SQLiteDatabase) -> Boolean)? = null, onStart: ((org.telegram.SQLite.SQLiteDatabase) -> Unit)? = null,
    ): Boolean {
        if (fetchTarget(account, message) == null) return false
        val start = Runnable {
            val storage = MessagesStorage.getInstance(account)
            storage.storageQueue.postRunnable {
                val db = storage.database
                val target = if (db != null && (wanted?.invoke(db) != false)) fetchTarget(account, message) else null
                if (target == null) {
                    releaseFetch(key)
                } else {
                    onStart?.invoke(db!!)
                    startFetch(account, key, message, target, store)
                }
            }
        }
        synchronized(fetching) {
            if (!fetching.add(key)) return true
            if (activeFetches >= MAX_FETCHES) {
                waitingFetches.addLast(start)
                return true
            }
            activeFetches++
        }
        start.run()
        return true
    }

    private fun releaseFetch(key: String) {
        val next = synchronized(fetching) {
            fetching.remove(key)
            activeFetches--
            waitingFetches.removeFirstOrNull()?.also { activeFetches++ }
        }
        next?.run()
    }

    // entiny: past FETCH_MAX_MS the slot goes to the queue but the listener stays while the loader still works, so a late file is still caught
    private fun startFetch(account: Int, key: String, message: TLRPC.Message, target: FetchTarget, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) = AndroidUtilities.runOnUIThread {
        val center = NotificationCenter.getInstance(account)
        val loader = FileLoader.getInstance(account)
        val began = android.os.SystemClock.elapsedRealtime()
        var done = false
        var slotHeld = true
        val freeSlot = {
            if (slotHeld) {
                slotHeld = false
                releaseFetch(key)
            }
        }
        lateinit var observer: NotificationCenter.NotificationCenterDelegate
        lateinit var check: Runnable
        val finish = { file: File? ->
            if (!done) {
                done = true
                AndroidUtilities.cancelRunOnUIThread(check)
                center.removeObserver(observer, NotificationCenter.fileLoaded)
                center.removeObserver(observer, NotificationCenter.fileLoadFailed)
                if (file != null) {
                    val storage = MessagesStorage.getInstance(account)
                    storage.storageQueue.postRunnable {
                        storage.database?.let { KeptMedia.recordPath(account, it, MessageObject.getDialogId(message), message.id, file) }
                    }
                }
                mediaQueue.postRunnable {
                    try {
                        planMediaCopy(account, message, ArchiveScope.Kind.DELETED, target.limit, file)?.let { keepOrCopy(account, key, it, store) }
                    } finally {
                        AndroidUtilities.runOnUIThread { freeSlot() }
                    }
                }
            }
        }
        observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, currentAccount: Int, vararg args: Any?) {
                if (args.getOrNull(0) != target.name) return
                finish(if (id == NotificationCenter.fileLoaded) args.getOrNull(1) as? File else null)
            }
        }
        check = Runnable {
            if (done) return@Runnable
            val elapsed = android.os.SystemClock.elapsedRealtime() - began
            if (!loader.isLoadingFile(target.name) || elapsed >= FETCH_HARD_MS) {
                finish(null)
                return@Runnable
            }
            if (elapsed >= FETCH_MAX_MS) freeSlot()
            AndroidUtilities.runOnUIThread(check, FETCH_CHECK_MS)
        }
        center.addObserver(observer, NotificationCenter.fileLoaded)
        center.addObserver(observer, NotificationCenter.fileLoadFailed)
        AndroidUtilities.runOnUIThread(check, FETCH_CHECK_MS)
        if (target.document != null) loader.loadFile(target.document, message, FileLoader.PRIORITY_LOW, 0)
        else loader.loadFile(ImageLocation.getForPhoto(target.photoSize, target.media.photo), message, "jpg", FileLoader.PRIORITY_LOW, 0)
    }

    // entiny: a row gone means the archive was cleared meanwhile; a live copy wins, a dead path is replaced
    internal fun updateMediaPath(db: org.telegram.SQLite.SQLiteDatabase, table: String, where: String, path: String): Boolean {
        val cursor = db.queryFinalized("SELECT media_path FROM $table WHERE $where")
        val current = try {
            if (!cursor.next()) return false
            cursor.stringValue(0)
        } finally {
            cursor.dispose()
        }
        if (!current.isNullOrEmpty() && current != path && File(current).let { it.isFile && it.length() > 0 }) return false
        val query = db.executeFast("UPDATE $table SET media_path = ? WHERE $where")
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

    private fun runMediaCopy(account: Int, plan: PendingMediaCopy): String? {
        var temporary: File? = null
        return try {
            plan.open().use { input ->
                val target = reserveTarget(File(getSavedMediaDir(account), plan.fileName))
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
        getArchivedMediaPath(account, message)?.let { restoreToStockPath(it, stock) } ?: stock

    private fun getArchivedMediaPath(account: Int, message: TLRPC.Message): File? {
        if (!DeletedMessagesHelper.isSaveDeletedEnabled()) return null
        ArchiveCache.ensureAccountLoaded(account)
        val path = synchronized(ArchiveCache.cacheLock) {
            ArchiveCache.deletedMessageMediaPaths.get(account.toLong())?.get(MessageObject.getDialogId(message))?.get(message.id.toLong())
        } ?: return null
        return File(path).takeIf { isUsable(it, message) }
    }
}
