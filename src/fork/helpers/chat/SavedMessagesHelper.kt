package desu.inugram.helpers.chat

import android.app.Activity
import android.util.SparseArray
import android.view.Gravity
import android.widget.FrameLayout
import androidx.collection.LongSparseArray
import androidx.core.content.res.ResourcesCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper

object SavedMessagesHelper {
    private const val TAG = "SavedMessagesHelper"

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

    private const val MEDIA_GALLERY_TYPE_IMAGE = 0
    private const val MEDIA_GALLERY_TYPE_VIDEO = 1
    private const val MEDIA_GALLERY_TYPE_FILE = 2
    private const val MEDIA_GALLERY_TYPE_AUDIO = 3

    // entiny: mirrors a saved archive file into the gallery via the stock save-to-gallery path
    @JvmStatic
    fun exportToGallery(path: String?) {
        if (path.isNullOrEmpty() || !isArchivePublic()) return
        try {
            val source = File(path)
            if (!source.isFile || source.length() <= 0L) return
            val extension = FileLoader.getFileExtension(source)?.lowercase()
            val mime = if (extension != null) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) else null
            val type = when {
                mime?.startsWith("image") == true -> MEDIA_GALLERY_TYPE_IMAGE
                mime?.startsWith("video") == true -> MEDIA_GALLERY_TYPE_VIDEO
                mime?.startsWith("audio") == true -> MEDIA_GALLERY_TYPE_AUDIO
                else -> MEDIA_GALLERY_TYPE_FILE
            }
            MediaController.saveFileInternal(type, source, galleryName(source))
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "archive gallery export failed", e)
        }
    }

    private fun galleryName(source: File): String {
        // entiny: strip the "<dialog>_<msg>_<uuid>_" prefix so gallery names stay readable
        val name = source.name
        val parts = name.split('_')
        return if (parts.size > 3) parts.drop(3).joinToString("_") else name
    }

    private const val AUTO_COPY_MAX_BYTES = 50L * 1024 * 1024
    private const val STARTUP_DELAY_MS = 20_000L

    // entiny: file copies run here so storageQueue only sees short database writes
    private val mediaQueue by lazy { org.telegram.messenger.DispatchQueue("inuArchiveMedia") }
    private val pendingCopies = HashSet<String>()
    private val migratedAccounts = HashSet<Int>()

    // entiny: legacy import of any media that still lives in the old public download folder
    private fun scheduleMediaMigration(account: Int) {
        synchronized(migratedAccounts) { if (!migratedAccounts.add(account)) return }
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
                val copiedPath = runMediaCopy(PendingMediaCopy(source, File(targetRoot, "${java.util.UUID.randomUUID()}_${source.name}"), export = false)) ?: continue
                moved++
                storage.storageQueue.postRunnable {
                    try {
                        storage.database?.let { InuDatabaseHelper.replaceSavedMediaPath(it, path, copiedPath) }
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

    private data class PendingMediaCopy(val source: File, val target: File, val expectedSize: Long = 0L, val export: Boolean = true)

    private fun planMediaCopy(account: Int, message: TLRPC.Message?, maxBytes: Long = AUTO_COPY_MAX_BYTES): PendingMediaCopy? {
        if (message?.media == null) return null
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
            PendingMediaCopy(path, File(getSavedMediaDir(account), "${MessageObject.getDialogId(message)}_${message.id}_${java.util.UUID.randomUUID()}_${path.name}"), expectedSize)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "planMediaCopy error", e)
            null
        }
    }

    private fun copyThenStore(account: Int, key: String, plan: PendingMediaCopy, store: (org.telegram.SQLite.SQLiteDatabase, String) -> Boolean) {
        synchronized(pendingCopies) { if (!pendingCopies.add(key)) return }
        mediaQueue.postRunnable {
            val started = android.os.SystemClock.elapsedRealtime()
            val copied = runMediaCopy(plan)
            android.util.Log.d(TAG, "archive copy ${plan.source.length()} bytes in ${android.os.SystemClock.elapsedRealtime() - started} ms")
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

    private fun updateMediaPath(db: org.telegram.SQLite.SQLiteDatabase, table: String, where: String, path: String): Boolean {
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

    private fun latestEditRowId(db: org.telegram.SQLite.SQLiteDatabase, dialogId: Long, msgId: Int, date: Int): Long {
        val cursor = db.queryFinalized("SELECT MAX(rowid) FROM inu_edit_history WHERE dialog_id = $dialogId AND msg_id = $msgId AND date = $date")
        return try { if (cursor.next()) cursor.longValue(0) else 0L } finally { cursor.dispose() }
    }

    private fun rememberDeletedMediaPath(account: Int, dialogId: Long, msgId: Int, path: String) {
        synchronized(cacheLock) {
            val paths = deletedMessageMediaPaths.get(account.toLong()) ?: LongSparseArray<LongSparseArray<String>>().also { deletedMessageMediaPaths.put(account.toLong(), it) }
            val dialogPaths = paths.get(dialogId) ?: LongSparseArray<String>().also { paths.put(dialogId, it) }
            dialogPaths.put(msgId.toLong(), path)
        }
    }

    private fun runMediaCopy(plan: PendingMediaCopy?): String? {
        if (plan == null) return null
        val temporary = File(plan.target.parentFile, "${plan.target.name}.partial")
        try {
            check(plan.expectedSize <= 0 || plan.source.length() == plan.expectedSize)
            if (plan.target.isFile && plan.target.length() == plan.source.length() && plan.target.length() > 0) {
                if (plan.export) exportToGallery(plan.target.absolutePath)
                return plan.target.absolutePath
            }
            plan.source.copyTo(temporary, overwrite = true)
            check(temporary.length() == plan.source.length() && temporary.length() > 0)
            check(plan.expectedSize <= 0 || temporary.length() == plan.expectedSize)
            check(temporary.renameTo(plan.target))
            if (plan.export) exportToGallery(plan.target.absolutePath)
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
                        if (deleted) rememberDeletedMediaPath(account, dialogId, message.id, copied)
                        else synchronized(cacheLock) { editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(message.id.toLong()) }
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

    private val deletedMessageIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()
    private val deletedMessageDates = LongSparseArray<LongSparseArray<LongSparseArray<Long>>>()
    // entiny: keep in-memory media paths for synchronous fallback in FileLoader without SQLite queries
    private val deletedMessageMediaPaths = LongSparseArray<LongSparseArray<LongSparseArray<String>>>()
    private val loadedAccounts = HashSet<Int>()
    private val loadingAccounts = HashSet<Int>()
    private val cacheLock = Any()
    private val cacheGeneration = HashMap<Int, Long>()

    private val editHistoryCache = LongSparseArray<LongSparseArray<LongSparseArray<ArrayList<EditEntry>>>>()

    // entiny: presence index avoids UI-thread SQLite queries during message layout
    private val editHistoryIds = LongSparseArray<LongSparseArray<HashSet<Int>>>()

    // entiny: synchronous record of permanent deletions so ChatActivity drops bubbles immediately
    private val purgingMessages = HashSet<Triple<Int, Long, Int>>()

    // entiny: bypasses global toggle when user explicitly checks keep-local in delete dialog
    private val pendingKeepLocal = HashSet<Triple<Int, Long, Int>>()

    // entiny: user-initiated deletes must never be archived; timestamped because several readers poll it
    private val pendingPermanentDelete = HashMap<Triple<Int, Long, Int>, Long>()
    private const val PERMANENT_PENDING_TTL_MS = 120_000L

    // entiny: history preview objects avoid stamping history rows with current message's deleted state
    private val historyPreviewObjects: MutableSet<MessageObject> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<MessageObject, Boolean>())

    private const val SHADOW_CACHE_MAX_PER_ACCOUNT = 500

    private class BoundedLinkedHashMap<K, V>(private val maxSize: Int) :
        LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }

    private data class ShadowEntry(
        val text: String,
        val hadMedia: Boolean,
        val date: Int,
        val entities: ArrayList<TLRPC.MessageEntity>? = null,
        val media: TLRPC.MessageMedia? = null,
        val editDate: Int = 0,
    )

    private val shadowMessageCache = LongSparseArray<LinkedHashMap<Pair<Long, Int>, ShadowEntry>>()

    data class EditEntry(
        val timestamp: Long,
        val text: String,
        val mediaPath: String? = null,
        val entities: ArrayList<TLRPC.MessageEntity>? = null,
        val media: TLRPC.MessageMedia? = null,
        val originalMessageId: Int = 0,
        val fromId: Long = 0L,
        val message: TLRPC.Message? = null,
        val rowId: Long = 0L,
    )

    @JvmStatic
    fun isSaveDeletedEnabled(): Boolean = InuConfig.SAVE_DELETED_MESSAGES.value

    @JvmStatic
    fun isSaveEditedEnabled(): Boolean = InuConfig.SAVE_EDITED_MESSAGES.value

    @JvmStatic
    fun shouldSaveForDialog(account: Int, dialogId: Long): Boolean {
        if (!isSaveDeletedEnabled()) return false
        val controller = MessagesController.getInstance(account) ?: return true
        if (org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) {
            return InuConfig.SAVE_SELF_DESTRUCT_TEXT.value || InuConfig.SAVE_DELETED_SECRET.value
        }
        if (org.telegram.messenger.DialogObject.isUserDialog(dialogId)) {
            val user = controller.getUser(dialogId)
            if (user != null && user.bot) {
                return InuConfig.SAVE_DELETED_BOTS.value
            }
            return InuConfig.SAVE_DELETED_PRIVATE.value
        } else if (org.telegram.messenger.DialogObject.isChatDialog(dialogId)) {
            val chat = controller.getChat(-dialogId)
            if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat) && !chat.megagroup) {
                return InuConfig.SAVE_DELETED_CHANNELS.value
            }
            return InuConfig.SAVE_DELETED_GROUPS.value
        }
        return true
    }

    private const val LOAD_PAGE_ROWS = 2000
    private const val LOAD_RETRY_MS = 60_000L
    private val loadFailedAt = HashMap<Int, Long>()

    private class ArchiveIndex(val generation: Long) {
        val deleted = LongSparseArray<HashSet<Int>>()
        val dates = LongSparseArray<LongSparseArray<Long>>()
        val media = LongSparseArray<LongSparseArray<String>>()
        val edits = LongSparseArray<HashSet<Int>>()
        var lastDeletedRow = 0L
        var lastEditRow = 0L
        var deletedDone = false
        var rows = 0
        var busyMs = 0L
    }

    @JvmStatic
    fun ensureAccountLoaded(account: Int) {
        val generation = synchronized(cacheLock) {
            if (loadedAccounts.contains(account) || loadingAccounts.contains(account)) return
            val failed = loadFailedAt[account]
            if (failed != null && android.os.SystemClock.elapsedRealtime() - failed < LOAD_RETRY_MS) return
            loadingAccounts.add(account)
            cacheGeneration[account] ?: 0L
        }
        val storage = MessagesStorage.getInstance(account)
        if (storage == null) {
            synchronized(cacheLock) { loadingAccounts.remove(account) }
            return
        }
        val index = ArchiveIndex(generation)
        storage.storageQueue.postRunnable { loadIndexPage(account, storage, index) }
    }

    // entiny: one page per runnable so dialog and message loads can run between pages
    private fun loadIndexPage(account: Int, storage: MessagesStorage, index: ArchiveIndex) {
        if (synchronized(cacheLock) { (cacheGeneration[account] ?: 0L) != index.generation }) return
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val db = storage.database ?: throw IllegalStateException("database closed")
            val finished: Boolean
            if (!index.deletedDone) {
                val count = InuDatabaseHelper.readDeletedIndexPage(db, index.lastDeletedRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId, date, mediaPath ->
                    index.lastDeletedRow = rowId
                    (index.deleted.get(dialogId) ?: HashSet<Int>().also { index.deleted.put(dialogId, it) }).add(msgId)
                    if (date > 0) (index.dates.get(dialogId) ?: LongSparseArray<Long>().also { index.dates.put(dialogId, it) }).put(msgId.toLong(), date)
                    if (mediaPath != null) (index.media.get(dialogId) ?: LongSparseArray<String>().also { index.media.put(dialogId, it) }).put(msgId.toLong(), mediaPath)
                }
                index.rows += count
                if (count < LOAD_PAGE_ROWS) index.deletedDone = true
                finished = false
            } else {
                val count = InuDatabaseHelper.readEditIndexPage(db, index.lastEditRow, LOAD_PAGE_ROWS) { rowId, dialogId, msgId ->
                    index.lastEditRow = rowId
                    (index.edits.get(dialogId) ?: HashSet<Int>().also { index.edits.put(dialogId, it) }).add(msgId)
                }
                index.rows += count
                finished = count < LOAD_PAGE_ROWS
            }
            index.busyMs += android.os.SystemClock.elapsedRealtime() - started
            if (finished) finishIndex(account, index) else storage.storageQueue.postRunnable { loadIndexPage(account, storage, index) }
        } catch (e: Throwable) {
            synchronized(cacheLock) {
                loadingAccounts.remove(account)
                loadFailedAt[account] = android.os.SystemClock.elapsedRealtime()
            }
            android.util.Log.e(TAG, "load archive failed", e)
        }
    }

    private fun finishIndex(account: Int, index: ArchiveIndex) {
        val generation = index.generation
        val deletedArray = index.deleted
        val dateArray = index.dates
        val editArray = index.edits
        val mediaPathArray = index.media
        android.util.Log.d(TAG, "archive index: ${index.rows} rows, ${index.busyMs} ms on storage queue")
        scheduleMediaMigration(account)
        org.telegram.messenger.AndroidUtilities.runOnUIThread {
            synchronized(cacheLock) {
                if ((cacheGeneration[account] ?: 0L) != generation) return@runOnUIThread
                var existingEdits = editHistoryIds.get(account.toLong())
                if (existingEdits == null) {
                    editHistoryIds.put(account.toLong(), editArray)
                } else {
                    for (i in 0 until editArray.size()) {
                        val k = editArray.keyAt(i)
                        val v = editArray.valueAt(i)
                        val target = existingEdits.get(k)
                        if (target == null) existingEdits.put(k, v) else target.addAll(v)
                    }
                }
                var existingDialogs = deletedMessageIds.get(account.toLong())
                if (existingDialogs == null) {
                    deletedMessageIds.put(account.toLong(), deletedArray)
                } else {
                    for (i in 0 until deletedArray.size()) {
                        val k = deletedArray.keyAt(i)
                        val v = deletedArray.valueAt(i)
                        val targetSet = existingDialogs.get(k)
                        if (targetSet == null) {
                            existingDialogs.put(k, v)
                        } else {
                            targetSet.addAll(v)
                        }
                    }
                }

                var existingDates = deletedMessageDates.get(account.toLong())
                if (existingDates == null) {
                    deletedMessageDates.put(account.toLong(), dateArray)
                } else {
                    for (i in 0 until dateArray.size()) {
                        val k = dateArray.keyAt(i)
                        val v = dateArray.valueAt(i)
                        val targetDates = existingDates.get(k)
                        if (targetDates == null) {
                            existingDates.put(k, v)
                        } else {
                            for (j in 0 until v.size()) {
                                targetDates.put(v.keyAt(j), v.valueAt(j))
                            }
                        }
                    }
                }

                var existingMediaPaths = deletedMessageMediaPaths.get(account.toLong())
                if (existingMediaPaths == null) {
                    deletedMessageMediaPaths.put(account.toLong(), mediaPathArray)
                } else {
                    for (i in 0 until mediaPathArray.size()) {
                        val k = mediaPathArray.keyAt(i)
                        val v = mediaPathArray.valueAt(i)
                        val targetPaths = existingMediaPaths.get(k)
                        if (targetPaths == null) {
                            existingMediaPaths.put(k, v)
                        } else {
                            for (j in 0 until v.size()) {
                                targetPaths.put(v.keyAt(j), v.valueAt(j))
                            }
                        }
                    }
                }
                loadedAccounts.add(account)
                loadingAccounts.remove(account)
                loadFailedAt.remove(account)
            }
        }
    }

    @JvmStatic
    fun schedulePrune(account: Int) {
        if (InuConfig.DELETED_MESSAGES_TTL.value == 0) return
        MessagesStorage.getInstance(account)?.storageQueue?.postRunnable({ pruneIfNeeded(account) }, STARTUP_DELAY_MS)
    }

    @JvmStatic
    fun pruneIfNeeded(account: Int) {
        val ttlDays = InuConfig.DELETED_MESSAGES_TTL.value
        if (ttlDays == 0) return
        val cutoff = System.currentTimeMillis() / 1000L - ttlDays * 86400L
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val started = android.os.SystemClock.elapsedRealtime()
            val result = try {
                val pruned = InuDatabaseHelper.pruneDeletedMessages(db, cutoff)
                for ((dialogId, mids) in pruned) {
                    storage.updateDialogsWithDeletedMessages(dialogId, getChannelId(account, dialogId), ArrayList(mids), null)
                }
                pruned to InuDatabaseHelper.pruneEditHistory(db, cutoff)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "archive prune failed", e)
                null
            } ?: return@postRunnable
            val (expired, editsPruned) = result
            android.util.Log.d(TAG, "archive prune: ${expired.values.sumOf { it.size }} deleted, $editsPruned edits in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            if (expired.isEmpty() && editsPruned == 0) return@postRunnable
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
                    loadingAccounts.remove(account)
                    deletedMessageIds.remove(account.toLong())
                    deletedMessageMediaPaths.remove(account.toLong())
                    deletedMessageDates.remove(account.toLong())
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                    shadowMessageCache.remove(account.toLong())
                    loadedAccounts.remove(account)
                }
                val controller = MessagesController.getInstance(account)
                for ((dialogId, mids) in expired) {
                    val idSet = mids.toHashSet()
                    controller.dialogMessage.get(dialogId)?.removeAll { it != null && it.id in idSet }
                    for (mid in mids) {
                        if (controller.dialogMessagesByIds.get(mid)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(mid)
                    }
                    org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                        org.telegram.messenger.NotificationCenter.messagesDeleted,
                        ArrayList(mids), getChannelId(account, dialogId), false, false, false, 0
                    )
                }
                ensureAccountLoaded(account)
            }
        }
    }

    private fun getChannelId(account: Int, dialogId: Long): Long {
        if (!org.telegram.messenger.DialogObject.isChatDialog(dialogId)) return 0L
        val controller = org.telegram.messenger.MessagesController.getInstance(account) ?: return 0L
        val chat = controller.getChat(-dialogId)
        return if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat)) chat.id else 0L
    }

    @JvmStatic
    fun clearEditHistory(account: Int, onDone: Runnable? = null) {
        clearEditHistory(account, null, onDone)
    }

    @JvmStatic
    fun clearEditHistory(account: Int, dialogIds: Collection<Long>?, onDone: Runnable?) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            try {
                storage.database?.let { InuDatabaseHelper.clearEditHistory(it, dialogIds) }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "clear edit history failed", e)
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
                    loadedAccounts.remove(account)
                    loadingAccounts.remove(account)
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                }
                ensureAccountLoaded(account)
                onDone?.run()
            }
        }
    }

    @JvmStatic
    fun clearCache(account: Int, dialogIds: Collection<Long>? = null, deletedOnly: Boolean = false, onDone: Runnable? = null) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database
            val pairs = if (db != null) InuDatabaseHelper.getDeletedMessageIds(db, dialogIds) else emptyMap()
            if (db != null) {
                InuDatabaseHelper.clearDeletedMessages(db, dialogIds, !deletedOnly)
                for ((dialogId, mids) in pairs) {
                    InuDatabaseHelper.deleteSavedMessages(db, dialogId, mids)
                    val channelId = getChannelId(account, dialogId)
                    storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(mids), null)
                }
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (pairs.isNotEmpty()) {
                    val controller = org.telegram.messenger.MessagesController.getInstance(account)
                    for ((dialogId, mids) in pairs) {
                        for (mid in mids) {
                            if (controller.dialogMessagesByIds.get(mid)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(mid)
                        }
                        val list = controller.dialogMessage.get(dialogId)
                        if (list != null) {
                            val midSet = mids.toHashSet()
                            list.removeAll { it?.id != null && it.id in midSet }
                        }
                        val channelId = getChannelId(account, dialogId)
                        org.telegram.messenger.NotificationCenter.getInstance(account)
                            .postNotificationName(
                                org.telegram.messenger.NotificationCenter.messagesDeleted,
                                java.util.ArrayList(mids), channelId, false, false, false, 0
                            )
                    }
                }
                synchronized(cacheLock) {
                    cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
                    loadingAccounts.remove(account)
                    deletedMessageIds.remove(account.toLong())
                    deletedMessageMediaPaths.remove(account.toLong())
                    deletedMessageDates.remove(account.toLong())
                    editHistoryCache.remove(account.toLong())
                    editHistoryIds.remove(account.toLong())
                    shadowMessageCache.remove(account.toLong())
                    loadedAccounts.remove(account)
                }
                onDone?.run()
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun deletePermanently(account: Int, dialogId: Long, msgIds: List<Int>, onDone: Runnable? = null) {
        dropGhostsFromChat(account, dialogId, msgIds, true, onDone)
    }

    // entiny: purgeArchive=false hides the ghost bubble but keeps the saved copy (archive row, media, edit history)
    private fun dropGhostsFromChat(account: Int, dialogId: Long, msgIds: List<Int>, purgeArchive: Boolean, onDone: Runnable? = null) {
        if (msgIds.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        // entiny: drop from in-memory set before broadcasting deletion so ghost bubbles don't survive
        if (purgeArchive) {
            synchronized(cacheLock) {
                val ids = deletedMessageIds.get(account.toLong())?.get(dialogId)
                val dates = deletedMessageDates.get(account.toLong())?.get(dialogId)
                for (msgId in msgIds) {
                    ids?.remove(msgId)
                    dates?.remove(msgId.toLong())
                }
            }
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            if (purgeArchive) {
                InuDatabaseHelper.deleteDeletedMessageEntries(db, dialogId, msgIds)
                InuDatabaseHelper.deleteEditHistory(db, dialogId, msgIds)
            }
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, msgIds)
            val channelId = getChannelId(account, dialogId)
            storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(msgIds), null)
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (purgeArchive) {
                    synchronized(cacheLock) {
                        val ids = deletedMessageIds.get(account.toLong())?.get(dialogId)
                        val dates = deletedMessageDates.get(account.toLong())?.get(dialogId)
                        val edits = editHistoryCache.get(account.toLong())?.get(dialogId)
                        for (msgId in msgIds) {
                            ids?.remove(msgId)
                            dates?.remove(msgId.toLong())
                            edits?.remove(msgId.toLong())
                            editHistoryIds.get(account.toLong())?.get(dialogId)?.remove(msgId)
                            deletedMessageMediaPaths.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong())
                        }
                    }
                }
                val controller = MessagesController.getInstance(account)
                val idSet = msgIds.toHashSet()
                for (msgId in msgIds) {
                    if (controller.dialogMessagesByIds.get(msgId)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(msgId)
                }
                val list = controller.dialogMessage.get(dialogId)
                if (list != null) {
                    list.removeAll { it?.id != null && it.id in idSet }
                }
                org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(
                        org.telegram.messenger.NotificationCenter.messagesDeleted,
                        ArrayList(msgIds), channelId, false, false, false, 0
                    )
                synchronized(cacheLock) {
                    for (msgId in msgIds) purgingMessages.remove(Triple(account, dialogId, msgId))
                }
                onDone?.run()
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun extractPreserved(
        account: Int,
        dialogId: Long,
        msgIds: MutableList<Int>?,
        permanent: Boolean,
        fragment: BaseFragment? = null,
    ): Boolean {
        if (msgIds.isNullOrEmpty() || !isSaveDeletedEnabled()) return false
        val preserved = msgIds.filter { isMessageDeleted(account, dialogId, it) }
        if (preserved.isEmpty()) {
            val stale = findStaleArchiveEntries(account, msgIds)
            if (stale.isEmpty()) return false
            if (permanent) {
                for ((did, mids) in stale) deletePermanently(account, did, mids, null)
                showPurgedBulletin(fragment)
            }
            return true
        }
        msgIds.removeAll(preserved.toHashSet())
        synchronized(cacheLock) {
                for (id in preserved) purgingMessages.add(Triple(account, dialogId, id))
        }
        if (permanent) {
            deletePermanently(account, dialogId, preserved) { showPurgedBulletin(fragment) }
        } else {
            dropGhostsFromChat(account, dialogId, preserved, false, null)
        }
        return true
    }

    // entiny: archive rows stored under a wrong dialog key (storage-guess / saved-subfolder mismatch) stay purgeable
    private fun findStaleArchiveEntries(account: Int, msgIds: List<Int>): Map<Long, List<Int>> {
        val stale = HashMap<Long, ArrayList<Int>>()
        synchronized(cacheLock) {
            val dialogs = deletedMessageIds.get(account.toLong()) ?: return@synchronized
            for (mid in msgIds) {
                for (i in 0 until dialogs.size()) {
                    if (dialogs.valueAt(i)?.contains(mid) == true) {
                        stale.getOrPut(dialogs.keyAt(i)) { ArrayList() }.add(mid)
                    }
                }
            }
        }
        return stale
    }

    private fun showPurgedBulletin(fragment: BaseFragment?) {
        val activity = fragment?.parentActivity ?: return
        val drawable = ResourcesCompat.getDrawable(activity.resources, R.drawable.inu_tabler_trash_x, null)?.mutate() ?: return
        BulletinFactory.of(fragment).createSimpleBulletin(
            drawable,
            LocaleController.getString(R.string.InuDeletePermanentlyDone),
        ).show()
    }

    @JvmStatic
    fun hasPreservedSelection(
        account: Int,
        selectedMessage: MessageObject?,
        selectedMessages: Array<SparseArray<MessageObject>?>?,
        selectedGroup: MessageObject.GroupedMessages?,
    ): Boolean {
        if (!isSaveDeletedEnabled()) return false
        val targets = ArrayList<Pair<Long, Int>>()
        if (selectedMessage != null) {
            if (selectedGroup != null) {
                for (msg in selectedGroup.messages) targets.add(msg.dialogId to msg.id)
            } else {
                targets.add(selectedMessage.dialogId to selectedMessage.id)
            }
        } else if (selectedMessages != null) {
            for (sparse in selectedMessages) {
                if (sparse == null) continue
                for (i in 0 until sparse.size()) {
                    val msg = sparse.valueAt(i) ?: continue
                    targets.add(msg.dialogId to msg.id)
                }
            }
        }
        return targets.any { isMessageDeleted(account, it.first, it.second) }
    }

    @JvmStatic
    fun decorateDeleteAlert(
        builder: AlertDialog.Builder,
        box: FrameLayout?,
        activity: Activity,
        resourcesProvider: Theme.ResourcesProvider?,
        account: Int,
        selectedMessage: MessageObject?,
        selectedMessages: Array<SparseArray<MessageObject>?>?,
        selectedGroup: MessageObject.GroupedMessages?,
        state: BooleanArray,
    ) {
        if (!hasPreservedSelection(account, selectedMessage, selectedMessages, selectedGroup)) return
        var container = box
        if (container == null) {
            container = FrameLayout(activity)
            builder.setView(container)
            builder.setCustomViewOffset(9)
        }
        val cell = CheckBoxCell(activity, 1, resourcesProvider)
        cell.background = Theme.getSelectorDrawable(false)
        cell.setText(LocaleController.getString(R.string.InuDeletePermanently), "", false, false)
        cell.setPadding(
            AndroidUtilities.dp(if (LocaleController.isRTL) 16f else 8f), 0,
            AndroidUtilities.dp(if (LocaleController.isRTL) 8f else 16f), 0,
        )
        container.addView(
            cell,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT.toFloat(), 48f,
                Gravity.TOP or Gravity.LEFT,
                0f, 48f * container.childCount, 0f, 0f,
            ),
        )
        cell.setOnClickListener { v ->
            state[0] = !state[0]
            (v as CheckBoxCell).setChecked(state[0], true)
        }
        // entiny: manual delete of a ghost defaults to a full purge, matching the old menu item
        state[0] = true
        cell.setChecked(true, false)
    }

    @JvmStatic
    fun requestKeepLocal(account: Int, dialogId: Long, msgIds: Collection<Int>) {
        if (msgIds.isEmpty()) return
        synchronized(cacheLock) {
            for (id in msgIds) pendingKeepLocal.add(Triple(account, dialogId, id))
        }
    }

    @JvmStatic
    fun hasPendingKeepLocal(account: Int, dialogId: Long, msgIds: Collection<Int>): Boolean {
        if (msgIds.isEmpty()) return false
        synchronized(cacheLock) {
            return msgIds.any { Triple(account, dialogId, it) in pendingKeepLocal }
        }
    }

    @JvmStatic
    fun consumeKeepLocalRequest(account: Int, dialogId: Long, msgId: Int): Boolean {
        synchronized(cacheLock) {
            return pendingKeepLocal.remove(Triple(account, dialogId, msgId))
        }
    }

    @JvmStatic
    fun requestPermanentDelete(account: Int, dialogId: Long, msgIds: Collection<Int>) {
        if (msgIds.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            for (id in msgIds) pendingPermanentDelete[Triple(account, dialogId, id)] = now
        }
    }

    @JvmStatic
    fun isPermanentDeleteRequested(account: Int, dialogId: Long, msgId: Int): Boolean {
        val key = Triple(account, dialogId, msgId)
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val ts = pendingPermanentDelete[key] ?: return false
            if (now - ts > PERMANENT_PENDING_TTL_MS) {
                pendingPermanentDelete.remove(key)
                return false
            }
            return true
        }
    }

    // entiny: mids are account-unique; cancel-upload keys may be recorded under a different dialog than the archive hook
    @JvmStatic
    fun isPermanentDeleteRequestedForMid(account: Int, msgId: Int): Boolean {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val iterator = pendingPermanentDelete.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > PERMANENT_PENDING_TTL_MS) {
                    iterator.remove()
                } else if (msgId < 0 && entry.key.first == account && entry.key.third == msgId) {
                    return true
                }
            }
            return false
        }
    }

    // entiny: with save-deleted on, stock skips messages_v2 cleanup for every row; delete the ones that
    // were not archived (own delete, off-category, permanent request) so their bubbles don't resurrect on reload
    @JvmStatic
    fun deleteNonPreservedFromStorage(
        account: Int,
        db: org.telegram.SQLite.SQLiteDatabase,
        dialogId: Long,
        mids: List<Int>?,
    ) {
        if (mids.isNullOrEmpty()) return
        val doomed = mids.filter {
            !isMessageDeleted(account, dialogId, it) || isPermanentDeleteRequested(account, dialogId, it) || isPermanentDeleteRequestedForMid(account, it)
        }
        if (doomed.isNotEmpty()) {
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, doomed)
        }
    }

    internal fun archiveText(text: String?, message: TLRPC.Message?): String {
        if (!text.isNullOrBlank()) return text
        val media = message?.media ?: return ""
        val res = when (media) {
            is TLRPC.TL_messageMediaPoll -> return "📊 " + (media.poll?.question?.text ?: LocaleController.getString(R.string.Poll))
            is TLRPC.TL_messageMediaPhoto -> R.string.AttachPhoto
            is TLRPC.TL_messageMediaGeo, is TLRPC.TL_messageMediaVenue -> R.string.AttachLocation
            is TLRPC.TL_messageMediaGeoLive -> R.string.AttachLiveLocation
            is TLRPC.TL_messageMediaContact -> R.string.AttachContact
            is TLRPC.TL_messageMediaDocument -> {
                val doc = media.document
                when {
                    doc == null -> R.string.AttachDocument
                    org.telegram.messenger.MessageObject.isStickerDocument(doc) -> R.string.AttachSticker
                    org.telegram.messenger.MessageObject.isRoundVideoDocument(doc) -> R.string.AttachRound
                    org.telegram.messenger.MessageObject.isVoiceDocument(doc) -> R.string.AttachAudio
                    org.telegram.messenger.MessageObject.isGifDocument(doc) -> R.string.AttachGif
                    org.telegram.messenger.MessageObject.isVideoDocument(doc) -> R.string.AttachVideo
                    org.telegram.messenger.MessageObject.isMusicDocument(doc) -> R.string.AttachMusic
                    else -> R.string.AttachDocument
                }
            }
            else -> return ""
        }
        return LocaleController.getString(res)
    }

    // entiny: a deletion is only worth archiving when we actually hold something to preserve
    private fun findShadowDialog(account: Int, msgId: Int): Long? = synchronized(cacheLock) {
        shadowMessageCache.get(account.toLong())?.keys?.lastOrNull { it.second == msgId && it.first > 0 }?.first
    }

    private fun hasPreservableData(text: String?, message: TLRPC.Message?): Boolean {
        if (!text.isNullOrBlank()) return true
        val media = message?.media ?: return false
        return media !is TLRPC.TL_messageMediaEmpty
    }

    // entiny: full copy keeps entities, media, reply and sender for the archive screen
    private fun snapshotMessage(account: Int, dialogId: Long, msgId: Int, fromId: Long, message: TLRPC.Message?): TLRPC.Message? {
        val copy = message?.let { InuUtils.cloneTLObject(it, TLRPC.Message::TLdeserialize) }?.also {
            it.attachPath = message.attachPath
            it.dialog_id = dialogId
        }
        if (copy != null) return copy
        val shadow = synchronized(cacheLock) { shadowMessageCache.get(account.toLong())?.get(dialogId to msgId) } ?: return null
        return TLRPC.TL_message().apply {
            id = msgId
            this.dialog_id = dialogId
            date = shadow.date
            this.message = shadow.text
            if (fromId != 0L) from_id = MessagesController.getInstance(account).getPeer(fromId)
            peer_id = MessagesController.getInstance(account).getPeer(dialogId)
            out = fromId != 0L && fromId == UserConfig.getInstance(account).clientUserId
            if (!shadow.entities.isNullOrEmpty()) {
                entities = ArrayList(shadow.entities)
                flags = flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
            }
            if (shadow.media != null) {
                media = InuDatabaseHelper.cloneMedia(shadow.media)
                if (media != null) flags = flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun markMessageDeleted(account: Int, dialogIdIn: Long, msgIdIn: Int, fromIdIn: Long, textIn: String?, date: Int, messageIn: TLRPC.Message? = null, forceSave: Boolean = false) {
        var dialogId = dialogIdIn
        // entiny: dialogMessagesByIds is keyed by mid only, so a channel delete can hand over another chat's message
        val foreign = messageIn != null && dialogIdIn != 0L && MessageObject.getDialogId(messageIn) != dialogIdIn
        val message = if (foreign) null else messageIn
        var text = if (foreign) null else textIn
        var fromId = if (foreign) 0L else fromIdIn
        val msgId = msgIdIn
        if (fromId == 0L && message != null && !message.out && message.from_id == null && org.telegram.messenger.DialogObject.isUserDialog(dialogIdIn)) {
            fromId = dialogIdIn
        }
        // entiny: private deletes arrive without a dialog id, recover it from the shadow cache
        if (dialogId == 0L && message == null && msgId > 0) {
            findShadowDialog(account, msgId)?.let { dialogId = it }
        }
        if (text.isNullOrEmpty() && message == null && dialogId != 0L) {
            text = synchronized(cacheLock) { shadowMessageCache.get(account.toLong())?.get(dialogId to msgId)?.text }
        }
        // entiny: reject dialog id 0 to prevent marking matching IDs in unrelated chats as deleted
        if (dialogId == 0L) return
        // entiny: unsent or cancelled uploads carry local ids and must never reach the archive
        // entiny: secret chat messages carry negative ids, only the send state tells an unsent one apart
        if ((msgId <= 0 && !org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) || (message != null && message.send_state != 0)) return
        // entiny: a message already archived (timer path) keeps getting its real text from the storage delete even when the chat type is off
        val alreadyRecorded = isMessageDeleted(account, dialogId, msgId)
        if (!forceSave && !alreadyRecorded && !shouldSaveForDialog(account, dialogId)) return
        if (!forceSave && !InuConfig.SAVE_DELETED_OWN.value && (fromId == UserConfig.getInstance(account).clientUserId || message?.out == true)) return
        if (!forceSave && isPermanentDeleteRequested(account, dialogId, msgId)) return
        if (!forceSave && isPermanentDeleteRequestedForMid(account, msgId)) return
        // entiny: prevent empty text from subsequent delete reports overwriting preserved text
        if (alreadyRecorded && text.isNullOrEmpty() && message?.media == null) return
        // entiny: deletions for messages never loaded locally carry no data; recording them
        // created phantom ghosts that littered chats with deleted-media placeholders
        if (!forceSave && !alreadyRecorded && !hasPreservableData(text, message)) return
        ensureAccountLoaded(account)
        val deletionTime = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000L
        val snapshot = snapshotMessage(account, dialogId, msgId, fromId, message)
        val mediaCopy = planMediaCopy(account, snapshot ?: message)
        synchronized(cacheLock) {
            var dialogs = deletedMessageIds.get(account.toLong())
            if (dialogs == null) {
                dialogs = LongSparseArray()
                deletedMessageIds.put(account.toLong(), dialogs)
            }
            var set = dialogs.get(dialogId)
            if (set == null) {
                set = HashSet()
                dialogs.put(dialogId, set)
            }
            set.add(msgId)

            var dateAcc = deletedMessageDates.get(account.toLong())
            if (dateAcc == null) {
                dateAcc = LongSparseArray()
                deletedMessageDates.put(account.toLong(), dateAcc)
            }
            var dateDialog = dateAcc.get(dialogId)
            if (dateDialog == null) {
                dateDialog = LongSparseArray()
                dateAcc.put(dialogId, dateDialog)
            }
            if (dateDialog.get(msgId.toLong()) == null) dateDialog.put(msgId.toLong(), deletionTime)
        }

        val storage = MessagesStorage.getInstance(account) ?: return
        val persist = Runnable {
            val db = storage.database ?: return@Runnable
            val savedPath = InuDatabaseHelper.deletedMessageMediaPath(db, dialogId, msgId)?.takeIf { File(it).isFile }
            InuDatabaseHelper.saveDeletedMessage(db, dialogId, msgId, fromId, archiveText(text, message ?: snapshot), deletionTime.toInt(), savedPath, snapshot)
            if (savedPath != null) {
                rememberDeletedMediaPath(account, dialogId, msgId, savedPath)
            } else if (mediaCopy != null) {
                copyThenStore(account, "d:$account:$dialogId:$msgId", mediaCopy) { copyDb, copied ->
                    val stored = updateMediaPath(copyDb, "inu_deleted_messages", "dialog_id = $dialogId AND msg_id = $msgId", copied)
                    if (stored) rememberDeletedMediaPath(account, dialogId, msgId, copied)
                    stored
                }
            }
        }
        if (Thread.currentThread() === storage.storageQueue) persist.run() else storage.storageQueue.postRunnable(persist)
    }

    @JvmStatic
    fun isPreservedOrWillBe(account: Int, msg: MessageObject?): Boolean {
        if (msg == null) return false
        val dialogId = msg.getDialogId()
        val msgId = msg.id
        synchronized(cacheLock) {
            if (purgingMessages.contains(Triple(account, dialogId, msgId))) return false
        }
        if (isPermanentDeleteRequested(account, dialogId, msgId)) return false
        if (isMessageDeleted(account, dialogId, msgId)) return true
        // entiny: nothing to archive means nothing to preserve — drop the bubble instead of
        // leaving an in-memory ghost that survives until cache clear
        if (!hasPreservableData(msg.messageOwner?.message, msg.messageOwner)) return false
        if (!shouldSaveForDialog(account, dialogId)) return false
        if (!InuConfig.SAVE_DELETED_OWN.value && msg.isOutOwner) return false
        return true
    }

    @JvmStatic
    fun replyIdsToClear(account: Int, messagesByDialogs: LongSparseArray<ArrayList<Int>>?, mids: List<Int>?, ids: String): String {
        if (mids.isNullOrEmpty() || messagesByDialogs == null || !isSaveDeletedEnabled()) return ids
        val kept = HashSet<Int>()
        for (i in 0 until messagesByDialogs.size()) {
            val dialogId = messagesByDialogs.keyAt(i)
            messagesByDialogs.valueAt(i)?.forEach {
                if (isMessageDeleted(account, dialogId, it) && !isPermanentDeleteRequested(account, dialogId, it)) kept.add(it)
            }
        }
        return if (kept.isEmpty()) ids else mids.filter { it !in kept }.joinToString(",")
    }

    @JvmStatic
    fun dropStaleDeletedReply(account: Int, message: TLRPC.Message?) {
        if (message == null || message.replyMessage !is TLRPC.TL_messageEmpty || !isSaveDeletedEnabled()) return
        val replyId = message.reply_to?.reply_to_msg_id ?: return
        if (replyId == 0) return
        val db = MessagesStorage.getInstance(account).database ?: return
        try {
            if (InuDatabaseHelper.isDeletedMessageArchived(db, MessageObject.getReplyToDialogId(message), replyId)) message.replyMessage = null
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "stale reply check failed", e)
        }
    }

    @JvmStatic
    fun fillArchivedReplies(
        account: Int,
        db: org.telegram.SQLite.SQLiteDatabase?,
        dialogId: Long,
        ids: List<Int>?,
        owners: SparseArray<ArrayList<TLRPC.Message>>?,
        usersToLoad: ArrayList<Long>?,
        chatsToLoad: ArrayList<Long>?,
    ) {
        if (!isSaveDeletedEnabled() || db == null || ids.isNullOrEmpty() || owners == null) return
        val missing = ids.filter { id -> owners.get(id)?.any { it.replyMessage == null } == true }
        if (missing.isEmpty()) return
        try {
            for ((id, message) in InuDatabaseHelper.archivedMessages(db, dialogId, missing)) {
                message.id = id
                message.dialog_id = dialogId
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
                owners.get(id)?.forEach { if (it.replyMessage == null) it.replyMessage = message }
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "archived reply lookup failed", e)
        }
    }

    // entiny: record deletions with dialog ids resolved by MessagesStorage from messages_v2
    @JvmStatic
    fun markMessagesDeletedFromStorage(
        account: Int,
        messagesByDialogs: LongSparseArray<ArrayList<Int>>?,
        resolved: List<TLRPC.Message>?,
    ) {
        if (!isSaveDeletedEnabled() || messagesByDialogs == null) return
        val byId = HashMap<Pair<Long, Int>, TLRPC.Message>()
        resolved?.forEach { m ->
            val dialogId = MessageObject.getDialogId(m)
            m.dialog_id = dialogId
            byId[dialogId to m.id] = m
        }
        var total = 0
        for (i in 0 until messagesByDialogs.size()) total += messagesByDialogs.valueAt(i)?.size ?: 0
        val db = if (total > 1) MessagesStorage.getInstance(account).database else null
        val started = android.os.SystemClock.elapsedRealtime()
        // entiny: one savepoint instead of an autocommit per archived row; nests safely inside stock transactions
        db?.executeFast("SAVEPOINT inu_archive_batch")?.stepThis()?.dispose()
        try {
            for (i in 0 until messagesByDialogs.size()) {
                val dialogId = messagesByDialogs.keyAt(i)
                val mids = messagesByDialogs.valueAt(i) ?: continue
                for (mid in mids) {
                    val message = byId[dialogId to mid]
                    val fromId = message?.from_id?.let { org.telegram.messenger.DialogObject.getPeerDialogId(it) } ?: 0L
                    markMessageDeleted(account, dialogId, mid, fromId, message?.message ?: "", 0, message)
                }
            }
        } finally {
            db?.executeFast("RELEASE SAVEPOINT inu_archive_batch")?.stepThis()?.dispose()
        }
        if (total > 50) android.util.Log.d(TAG, "archived $total deletions in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }

    @JvmStatic
    fun isMessageDeleted(account: Int, dialogId: Long, msgId: Int): Boolean {
        // entiny: timer and secret-chat archives keep their mark with the general toggle off
        if (!isSaveDeletedEnabled() && !InuConfig.SAVE_TIMED_MESSAGES.value && !InuConfig.SAVE_SELF_DESTRUCT_TEXT.value) return false
        ensureAccountLoaded(account)
        return synchronized(cacheLock) {
            val accMap = deletedMessageIds.get(account.toLong()) ?: return@synchronized false
            accMap.get(dialogId)?.contains(msgId) == true
        }
    }

    @JvmStatic
    fun isMessageDeleted(dialogId: Long, msgId: Int): Boolean {
        return isMessageDeleted(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun getDeletedDate(account: Int, dialogId: Long, msgId: Int): Long {
        ensureAccountLoaded(account)
        return synchronized(cacheLock) {
            val accMap = deletedMessageDates.get(account.toLong()) ?: return@synchronized 0L
            accMap.get(dialogId)?.get(msgId.toLong()) ?: 0L
        }
    }

    @JvmStatic
    fun getDeletedDate(dialogId: Long, msgId: Int): Long {
        return getDeletedDate(UserConfig.selectedAccount, dialogId, msgId)
    }

    // entiny: image loaders resolve documents by the stock cache path, so put the archived copy back there
    @JvmStatic
    fun restoreToStockPath(archived: File, stock: File): File {
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
    fun getArchivedMediaPath(account: Int, dialogId: Long, msgId: Int): File? {
        if (!isSaveDeletedEnabled()) return null
        ensureAccountLoaded(account)
        val path = synchronized(cacheLock) {
            deletedMessageMediaPaths.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())
        } ?: return null
        val file = File(path)
        return if (file.isFile && file.length() > 0) file else null
    }

    @JvmStatic
    fun showDeletionTimeBulletin(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, dialogId: Long, msgId: Int) {
        if (context == null) return
        val date = getDeletedDate(dialogId, msgId)
        val timeStr = if (date > 0) {
            LocaleController.formatDateAudio(date, true)
        } else {
            null
        }
        val text = if (timeStr != null) {
            LocaleController.formatString(R.string.InuDeletedAt, timeStr)
        } else {
            LocaleController.getString(R.string.InuSaveDeletedMessages)
        }
        val bulletinFactory = if (activity != null) org.telegram.ui.Components.BulletinFactory.of(activity) else org.telegram.ui.Components.BulletinFactory.global()
        bulletinFactory?.createSimpleBulletin(
            R.drawable.msg_delete,
            text
        )?.show()
    }

    @JvmStatic
    fun recordEditHistory(account: Int, dialogId: Long, msgId: Int, oldText: String, date: Int, message: TLRPC.Message? = null) {
        recordEditHistory(account, dialogId, msgId, oldText, date, message, message?.entities, message?.media)
    }

    private fun recordEditHistory(
        account: Int,
        dialogId: Long,
        msgId: Int,
        oldText: String,
        date: Int,
        message: TLRPC.Message?,
        entities: ArrayList<TLRPC.MessageEntity>?,
        media: TLRPC.MessageMedia?,
    ) {
        if (!isSaveEditedEnabled()) return
        val mediaCopy = planMediaCopy(account, message)
        if (oldText.isEmpty() && (media == null || media is TLRPC.TL_messageMediaEmpty)) return
        val now = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000
        val keptEntities = if (entities.isNullOrEmpty()) null else ArrayList(entities)
        val keptMedia = InuDatabaseHelper.cloneMedia(media)
        
        synchronized(cacheLock) {
            var accMap = editHistoryCache.get(account.toLong())
            if (accMap == null) {
                accMap = LongSparseArray()
                editHistoryCache.put(account.toLong(), accMap)
            }
            var dialogMap = accMap.get(dialogId)
            if (dialogMap == null) {
                dialogMap = LongSparseArray()
                accMap.put(dialogId, dialogMap)
            }
            var list = dialogMap.get(msgId.toLong())
            if (list == null) {
                list = ArrayList()
                dialogMap.put(msgId.toLong(), list)
            }
            val last = list.lastOrNull()
            if (last != null && last.timestamp == now && last.text == oldText && sameContent(last.entities, last.media, keptEntities, keptMedia)) return
            list.add(EditEntry(now, oldText, null, keptEntities, keptMedia))

            var idAcc = editHistoryIds.get(account.toLong())
            if (idAcc == null) {
                idAcc = LongSparseArray()
                editHistoryIds.put(account.toLong(), idAcc)
            }
            var idSet = idAcc.get(dialogId)
            if (idSet == null) {
                idSet = HashSet()
                idAcc.put(dialogId, idSet)
            }
            idSet.add(msgId)
        }

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            try {
                val last = InuDatabaseHelper.lastEditHistory(db, dialogId, msgId)
                if (last != null && last.text == oldText && sameContent(last.entities, last.media, keptEntities, keptMedia)) {
                    synchronized(cacheLock) {
                        editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong())
                    }
                    return@postRunnable
                }
                InuDatabaseHelper.saveEditHistory(db, dialogId, msgId, oldText, now.toInt(), null, keptEntities, keptMedia)
                synchronized(cacheLock) {
                    editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong())
                }
                if (mediaCopy != null) {
                    val rowId = latestEditRowId(db, dialogId, msgId, now.toInt())
                    if (rowId > 0) copyThenStore(account, "e:$account:$rowId", mediaCopy) { copyDb, copied ->
                        val stored = updateMediaPath(copyDb, "inu_edit_history", "rowid = $rowId", copied)
                        if (stored) synchronized(cacheLock) { editHistoryCache.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong()) }
                        stored
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "edit history save failed", e)
            }
        }
    }

    @JvmStatic
    fun recordEditHistory(account: Int, dialogId: Long, msgId: Int, oldText: String, date: Int) {
        recordEditHistory(account, dialogId, msgId, oldText, date, null)
    }

    @JvmStatic
    fun rememberMessage(account: Int, dialogId: Long, msgId: Int, text: String?, hasMedia: Boolean, date: Int, entities: ArrayList<TLRPC.MessageEntity>?, media: TLRPC.MessageMedia?) {
        rememberShadow(account, dialogId, msgId, text, hasMedia, date, entities, media, 0)
    }

    private fun rememberShadow(account: Int, dialogId: Long, msgId: Int, text: String?, hasMedia: Boolean, date: Int, entities: ArrayList<TLRPC.MessageEntity>?, media: TLRPC.MessageMedia?, editDate: Int) {
        if (!isSaveEditedEnabled() && !isSaveDeletedEnabled()) return
        val entry = ShadowEntry(
            text ?: "",
            hasMedia,
            date,
            if (entities.isNullOrEmpty()) null else ArrayList(entities),
            InuDatabaseHelper.cloneMedia(media),
            editDate,
        )
        synchronized(cacheLock) {
            val map = shadowMessageCache.get(account.toLong())
                ?: BoundedLinkedHashMap<Pair<Long, Int>, ShadowEntry>(SHADOW_CACHE_MAX_PER_ACCOUNT)
                    .also { shadowMessageCache.put(account.toLong(), it) }
            map[dialogId to msgId] = entry
        }
    }

    @JvmStatic
    fun recordEditHistoryFromShadow(account: Int, dialogId: Long, msgId: Int, newMessage: TLRPC.Message) {
        if (!isSaveEditedEnabled()) return
        val old = synchronized(cacheLock) {
            shadowMessageCache.get(account.toLong())?.get(dialogId to msgId)
        } ?: return
        val textChanged = old.text != (newMessage.message ?: "")
        val mediaChanged = !sameContent(old.entities, old.media, newMessage.entities, newMessage.media)
        if (textChanged || mediaChanged) {
            recordEditHistory(account, dialogId, msgId, old.text, old.editDate.takeIf { it > 0 } ?: old.date, null, old.entities, old.media)
            rememberShadow(account, dialogId, msgId, newMessage.message, newMessage.media != null, old.date, newMessage.entities, newMessage.media, newMessage.edit_date)
        }
    }

    @JvmStatic
    fun recordOwnEdit(account: Int, obj: MessageObject?, newText: String?, newEntities: ArrayList<TLRPC.MessageEntity>?) {
        val old = obj?.messageOwner ?: return
        if (!isSaveEditedEnabled() || old.message.isNullOrEmpty()) return
        val textChanged = old.message != newText
        val formatChanged = newEntities != null && !sameContent(old.entities, null, newEntities, null)
        if (!textChanged && !formatChanged) return
        recordEditHistory(account, obj.dialogId, obj.id, old.message, if (old.edit_date != 0) old.edit_date else old.date, old)
    }

    @JvmStatic
    fun recordEditHistoryIfChanged(account: Int, dialogId: Long, msgId: Int, oldMessage: TLRPC.Message?, newMessage: TLRPC.Message) {
        if (oldMessage == null || !isSaveEditedEnabled()) return
        val textChanged = oldMessage.message != newMessage.message
        // entiny: compare the actual attachment, "has media" is set on every ordinary update
        val contentChanged = !sameContent(oldMessage.entities, oldMessage.media, newMessage.entities, newMessage.media)
        if (textChanged || contentChanged) {
            recordEditHistory(account, dialogId, msgId, oldMessage.message ?: "", oldMessage.edit_date.takeIf { it > 0 } ?: oldMessage.date, oldMessage)
        }
    }

    private fun serialized(value: org.telegram.tgnet.TLObject?): ByteArray? {
        if (value == null) return null
        val buffer = org.telegram.tgnet.NativeByteBuffer(value.objectSize)
        return try {
            value.serializeToStream(buffer)
            buffer.position(0)
            buffer.readData(value.objectSize, true)
        } finally {
            buffer.reuse()
        }
    }

    internal fun sameContent(oldEntities: ArrayList<TLRPC.MessageEntity>?, oldMedia: TLRPC.MessageMedia?, newEntities: ArrayList<TLRPC.MessageEntity>?, newMedia: TLRPC.MessageMedia?): Boolean {
        if (!serialized(oldMedia).contentEquals(serialized(newMedia))) return false
        val old = oldEntities.orEmpty()
        val new = newEntities.orEmpty()
        return old.size == new.size && old.indices.all { serialized(old[it]).contentEquals(serialized(new[it])) }
    }

    fun invalidateMediaCache(account: Int) {
        synchronized(cacheLock) {
            deletedMessageMediaPaths.remove(account.toLong())
            editHistoryCache.remove(account.toLong())
            cacheGeneration[account] = (cacheGeneration[account] ?: 0L) + 1L
            loadedAccounts.remove(account)
            loadingAccounts.remove(account)
        }
    }

    @JvmStatic
    fun handleMessageEdited(account: Int, newMessage: TLRPC.Message, cached: MessageObject?) {
        if (!isSaveEditedEnabled()) return
        val old = cached?.takeIf { it.dialogId == newMessage.dialog_id }?.messageOwner
        if (old != null) {
            recordEditHistoryIfChanged(account, newMessage.dialog_id, newMessage.id, old, newMessage)
            val shadow = synchronized(cacheLock) { shadowMessageCache.get(account.toLong())?.get(newMessage.dialog_id to newMessage.id) }
            if (shadow != null) rememberShadow(account, newMessage.dialog_id, newMessage.id, newMessage.message, newMessage.media != null, shadow.date, newMessage.entities, newMessage.media, newMessage.edit_date)
        } else {
            // entiny: dialogMessagesByIds holds only the dialog preview, so fall back to the shadow cache
            recordEditHistoryFromShadow(account, newMessage.dialog_id, newMessage.id, newMessage)
        }
    }

    @JvmStatic
    fun recordEditHistory(dialogId: Long, msgId: Int, oldText: String) {
        recordEditHistory(UserConfig.selectedAccount, dialogId, msgId, oldText, 0, null)
    }

    @JvmStatic
    fun getEditHistoryAsync(account: Int, dialogId: Long, msgId: Int, onLoaded: (List<EditEntry>) -> Unit) {
        val storage = MessagesStorage.getInstance(account)
        if (storage == null) {
            onLoaded(emptyList())
            return
        }
        storage.storageQueue.postRunnable {
            val db = storage.database
            val loaded: List<EditEntry> = if (db == null) {
                emptyList()
            } else {
                InuDatabaseHelper.loadEditHistory(db, dialogId, msgId)
                    .map { EditEntry(it.date, it.text, it.mediaPath, it.entities, it.media, rowId = it.rowId) }
            }
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                synchronized(cacheLock) {
                    var accMap = editHistoryCache.get(account.toLong())
                    if (accMap == null) {
                        accMap = LongSparseArray()
                        editHistoryCache.put(account.toLong(), accMap)
                    }
                    var dialogMap = accMap.get(dialogId)
                    if (dialogMap == null) {
                        dialogMap = LongSparseArray()
                        accMap.put(dialogId, dialogMap)
                    }
                    if (dialogMap.get(msgId.toLong()) == null) {
                        dialogMap.put(msgId.toLong(), ArrayList(loaded))
                    }
                }
                onLoaded(loaded)
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun deleteEditHistoryEntry(account: Int, dialogId: Long, msgId: Int, rowId: Long, onDone: Runnable? = null) {
        if (rowId <= 0L) return
        synchronized(cacheLock) {
            val list = editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())
            list?.removeAll { it.rowId == rowId }
            if (list != null && list.isEmpty()) {
                editHistoryIds.get(account.toLong())?.get(dialogId)?.remove(msgId)
            }
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.deleteEditHistoryRevision(db, dialogId, msgId, rowId)
            org.telegram.messenger.AndroidUtilities.runOnUIThread { onDone?.run() }
        }
    }

    @JvmStatic
    fun hasEditHistory(account: Int, dialogId: Long, msgId: Int): Boolean {
        if (!isSaveEditedEnabled()) return false
        ensureAccountLoaded(account)
        return synchronized(cacheLock) {
            if (editHistoryCache.get(account.toLong())?.get(dialogId)?.get(msgId.toLong())?.isNotEmpty() == true) {
                return@synchronized true
            }
            editHistoryIds.get(account.toLong())?.get(dialogId)?.contains(msgId) == true
        }
    }

    private val historyTimeLabels = java.util.WeakHashMap<MessageObject, CharSequence>()

    @JvmStatic
    fun setHistoryTimeLabel(msg: MessageObject, label: CharSequence) {
        synchronized(historyTimeLabels) { historyTimeLabels[msg] = label }
    }

    @JvmStatic
    fun historyTimeLabel(msg: MessageObject?): CharSequence? {
        if (msg == null) return null
        return synchronized(historyTimeLabels) { historyTimeLabels[msg] }
    }

    @JvmStatic
    fun markAsHistoryPreview(msg: MessageObject) {
        synchronized(historyPreviewObjects) { historyPreviewObjects.add(msg) }
    }

    @JvmStatic
    fun isHistoryPreview(msg: MessageObject?): Boolean {
        if (msg == null) return false
        return synchronized(historyPreviewObjects) { historyPreviewObjects.contains(msg) }
    }

    @JvmStatic
    fun hasEditHistory(dialogId: Long, msgId: Int): Boolean {
        return hasEditHistory(UserConfig.selectedAccount, dialogId, msgId)
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, dialogId: Long, msgId: Int) {
        val account = activity?.currentAccount ?: UserConfig.selectedAccount
        val controller = MessagesController.getInstance(account)
        var msgObj: MessageObject? = controller.dialogMessagesByIds.get(msgId)?.takeIf { it.dialogId == dialogId }
        if (msgObj == null) {
            val dummyMsg = TLRPC.TL_message().apply {
                id = msgId
                dialog_id = dialogId
                // entiny: require peer so MessageObject layout generation does not bail out without text
                peer_id = controller.getPeer(dialogId)
                date = (System.currentTimeMillis() / 1000L).toInt()
            }
            msgObj = MessageObject(account, dummyMsg, false, true)
        }
        showEditHistoryDialog(context, activity, msgObj)
    }

    @JvmStatic
    fun showEditHistoryDialog(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, msgObj: MessageObject) {
        if (context == null || !isSaveEditedEnabled()) return
        val dialogId = msgObj.getDialogId()
        val msgId = msgObj.id
        val account = msgObj.currentAccount
        val isEdited = (msgObj.messageOwner != null && (msgObj.messageOwner.flags and TLRPC.MESSAGE_FLAG_EDITED) != 0) || (msgObj.messageOwner?.edit_date ?: 0) != 0
        if (!hasEditHistory(account, dialogId, msgId) && !isEdited) {
            val bulletinFactory = if (activity != null) org.telegram.ui.Components.BulletinFactory.of(activity) else org.telegram.ui.Components.BulletinFactory.global()
            bulletinFactory?.createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.InuNoEditHistory)
            )?.show()
            return
        }

        val frag = desu.inugram.ui.AyuMessageHistoryActivity(msgObj)
        if (activity != null) {
            activity.presentFragment(frag)
        } else if (context is org.telegram.ui.LaunchActivity) {
            context.actionBarLayout?.presentFragment(frag)
        }
    }
}
