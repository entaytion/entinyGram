package desu.inugram.helpers.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.FileProvider
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.export.ChatExportSheet
import desu.inugram.helpers.chat.export.ExportFiles
import desu.inugram.helpers.chat.export.ExportHtml
import desu.inugram.helpers.chat.export.ExportItem
import desu.inugram.helpers.chat.export.ExportJson
import desu.inugram.helpers.chat.export.ExportMediaKind
import desu.inugram.helpers.chat.export.ExportOptions
import desu.inugram.helpers.chat.export.ExportPeers
import desu.inugram.helpers.chat.export.ExportResultUi
import desu.inugram.helpers.chat.export.ExportSession
import desu.inugram.helpers.archive.ArchiveStore
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBar
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ChatExportHelper {

    const val FILENAME_SUFFIX = ".entiny-chat.json"
    private const val PAGE_SIZE = 100
    private const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val STALL_TIMEOUT_MS = 30_000L
    private const val STALL_CHECK_INTERVAL_MS = 5_000L
    private val OBSERVED_FILE_EVENTS = intArrayOf(
        NotificationCenter.fileLoaded,
        NotificationCenter.fileLoadFailed,
        NotificationCenter.fileLoadProgressChanged,
    )

    private val active = HashMap<Long, ExportSession>()

    @JvmStatic
    fun start(fragment: BaseFragment, currentAccount: Int, dialogId: Long) {
        if (active.containsKey(dialogId)) return
        val controller = MessagesController.getInstance(currentAccount)
        if (controller.getInputPeer(dialogId) == null) {
            BulletinFactory.of(fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuChatExportFailed)
            ).show()
            return
        }
        val context = fragment.parentActivity ?: return
        val title = ExportPeers.dialogTitle(controller, dialogId)
        ChatExportSheet(context, title) { options -> beginExport(fragment, currentAccount, dialogId, options) }.show()
    }

    private fun beginExport(
        fragment: BaseFragment,
        currentAccount: Int,
        dialogId: Long,
        options: ExportOptions,
    ) {
        val session = ExportSession(fragment, currentAccount, dialogId)
        session.options = options
        active[dialogId] = session
        val context = fragment.parentActivity
        if (context != null) {
            session.dialog = AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuChatExport))
                .setMessage(LocaleController.formatString(R.string.InuChatExportCollected, 0))
                .setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ ->
                    session.aborted = true
                    cleanupObservers(session)
                    active.remove(dialogId)
                }
                .create()
                .apply {
                    setCancelable(false)
                    show()
                }
        }
        fetchPage(session)
    }

    private fun fetchPage(session: ExportSession) {
        if (session.aborted || session.finished) return
        val controller = MessagesController.getInstance(session.account)
        val peer = controller.getInputPeer(session.dialogId)
        if (peer == null) {
            finishWithError(session, null)
            return
        }
        val req = TLRPC.TL_messages_getHistory()
        req.peer = peer
        req.offset_id = session.offsetId
        req.limit = PAGE_SIZE
        ConnectionsManager.getInstance(session.account).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (session.aborted || session.finished) return@runOnUIThread
                if (error != null || response !is TLRPC.messages_Messages) {
                    FileLog.e("ChatExportHelper: getHistory failed for ${session.dialogId}: ${error?.text}")
                    if (session.collected.isEmpty()) {
                        finishWithError(session, error?.text)
                    } else if (session.options.html) {
                        startMediaPhase(session)
                    } else {
                        writeAndShareJson(session)
                    }
                    return@runOnUIThread
                }
                controller.putUsers(response.users, false)
                controller.putChats(response.chats, false)
                var minId = Int.MAX_VALUE
                for (message in response.messages) {
                    if (message.id < minId) minId = message.id
                    if (message is TLRPC.TL_messageEmpty) continue
                    if (session.seenIds.add(message.id)) session.collected.add(message)
                }
                session.dialog?.setMessage(
                    LocaleController.formatString(R.string.InuChatExportCollected, session.collected.size)
                )
                if (response.messages.isEmpty() || response.messages.size < PAGE_SIZE || minId == session.offsetId) {
                    if (session.options.html) {
                        startMediaPhase(session)
                    } else {
                        writeAndShareJson(session)
                    }
                } else {
                    session.offsetId = minId
                    AndroidUtilities.runOnUIThread({ fetchPage(session) }, 250)
                }
            }
        }
    }

    private fun finishWithError(session: ExportSession, error: String?) {
        session.finished = true
        active.remove(session.dialogId)
        session.dialog?.dismiss()
        val msg = error?.takeIf { it.isNotEmpty() }
            ?: LocaleController.getString(R.string.InuChatExportFailed)
        BulletinFactory.of(session.fragment).createErrorBulletin(msg).show()
    }

    // ------------------------------------------------------------------ media

    private fun startMediaPhase(session: ExportSession) {
        val controller = MessagesController.getInstance(session.account)
        Utilities.globalQueue.postRunnable {
            val items = buildItems(session, controller)
            attachLocalPaths(session, items)
            AndroidUtilities.runOnUIThread {
                if (session.aborted) return@runOnUIThread
                session.items = items
                if (items.isEmpty()) {
                    finishMediaPhase(session)
                    return@runOnUIThread
                }
                val center = NotificationCenter.getInstance(session.account)
                val observer = object : NotificationCenter.NotificationCenterDelegate {
                    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                        if (account != session.account || session.finished) return
                        session.lastActivityAt = SystemClock.elapsedRealtime()
                        when (id) {
                            NotificationCenter.fileLoadProgressChanged -> return
                            // entiny: a failed load never reaches disk, so mark it here or it blocks the export forever
                            NotificationCenter.fileLoadFailed -> {
                                val name = args.getOrNull(0) as? String
                                items.forEach { if (it.requested && !it.done && it.attachName == name) it.failed = true }
                            }
                        }
                        resolveExistingFiles(session)
                        updateMediaProgress(session)
                        pump(session)
                    }
                }
                session.observer = observer
                for (event in OBSERVED_FILE_EVENTS) center.addObserver(observer, event)
                resolveExistingFiles(session)
                session.lastActivityAt = SystemClock.elapsedRealtime()
                scheduleStallCheck(session)
                updateMediaProgress(session)
                pump(session)
            }
        }
    }

    // entiny: some loads end silently (no loaded/failed event); if nothing moves for a while, skip what's stuck
    private fun scheduleStallCheck(session: ExportSession) {
        val check = Runnable {
            if (session.finished || session.aborted) return@Runnable
            resolveExistingFiles(session)
            if (SystemClock.elapsedRealtime() - session.lastActivityAt > STALL_TIMEOUT_MS) {
                session.items?.forEach { if (it.requested && !it.done) it.failed = true }
                session.lastActivityAt = SystemClock.elapsedRealtime()
            }
            updateMediaProgress(session)
            pump(session)
            scheduleStallCheck(session)
        }
        session.timeoutRunnable = check
        AndroidUtilities.runOnUIThread(check, STALL_CHECK_INTERVAL_MS)
    }

    private fun buildItems(session: ExportSession, controller: MessagesController): ArrayList<ExportItem> {
        val usedNames = HashSet<String>()
        val items = ArrayList<ExportItem>()
        var index = 0
        for (m in session.collected.sortedBy { it.date }) {
            index++
            val kind = kindOf(m) ?: continue
            if (!session.options.enabled(kind)) continue
            val size = mediaSizeBytes(m, kind)
            val item = ExportItem(m, kind, entryName(kind, index, m, usedNames))
            item.attachName = m.media?.let { media ->
                if (media.photo != null) largestPhotoSize(media.photo)?.let { FileLoader.getAttachFileName(it) }
                else media.document?.let { FileLoader.getAttachFileName(it) }
            }
            if (size > session.options.sizeLimitMb * 1024L * 1024L) {
                item.overLimit = true
            }
            items.add(item)
        }
        return items
    }

    // entiny: own sent media never lands in the download dirs, its original path lives only in the local db row
    private fun attachLocalPaths(session: ExportSession, items: List<ExportItem>) {
        val own = items.filter { it.message.out && it.message.id > 0 }
        if (own.isEmpty()) return
        val storage = MessagesStorage.getInstance(session.account)
        val latch = CountDownLatch(1)
        storage.storageQueue.postRunnable {
            try {
                val db = storage.database ?: return@postRunnable
                for (chunk in own.chunked(500)) {
                    val found = ArchiveStore.loadMessagesForArchive(db, session.dialogId, chunk.map { it.message.id })
                    for (item in chunk) item.localPath = found[item.message.id]?.attachPath?.takeIf { it.isNotEmpty() }
                }
            } catch (e: Throwable) {
                android.util.Log.d("ChatExport", "local attach paths failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
    }

    private fun kindOf(m: TLRPC.Message): ExportMediaKind? {
        val media = m.media ?: return null
        if (media is TLRPC.TL_messageMediaPhoto || media.photo != null) return ExportMediaKind.PHOTO
        val doc = media.document ?: return null
        if (media.voice) return ExportMediaKind.VOICE
        if (media.video) return if (media.round) ExportMediaKind.ROUND else ExportMediaKind.VIDEO
        if (media is TLRPC.TL_messageMediaDocument) {
            for (attr in doc.attributes) {
                if (attr is TLRPC.TL_documentAttributeSticker) return ExportMediaKind.STICKER
                if (attr is TLRPC.TL_documentAttributeAnimated) return ExportMediaKind.GIF
            }
            return ExportMediaKind.DOCUMENT
        }
        return null
    }

    private fun mediaSizeBytes(m: TLRPC.Message, kind: ExportMediaKind): Long {
        val media = m.media ?: return 0L
        return when (kind) {
            ExportMediaKind.PHOTO -> largestPhotoSize(media.photo)?.size?.toLong() ?: 0L
            else -> media.document?.size ?: 0L
        }
    }

    private fun largestPhotoSize(photo: TLRPC.Photo?): TLRPC.PhotoSize? =
        photo?.sizes?.lastOrNull()

    private fun entryName(kind: ExportMediaKind, index: Int, m: TLRPC.Message, used: HashSet<String>): String {
        val stamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(m.date * 1000L))
        val media = m.media
        val base = when (kind) {
            ExportMediaKind.PHOTO -> "photos/photo_${index}@$stamp.jpg"
            ExportMediaKind.VIDEO -> "videos/video_${index}@$stamp.${docExt(media?.document, "mp4")}"
            ExportMediaKind.VOICE -> "voice/voice_${index}@$stamp.ogg"
            ExportMediaKind.ROUND -> "video_messages/round_${index}@$stamp.mp4"
            ExportMediaKind.STICKER -> "stickers/sticker_${index}@$stamp.${docExt(media?.document, "webp")}"
            ExportMediaKind.GIF -> "gifs/gif_${index}@$stamp.${docExt(media?.document, "mp4")}"
            ExportMediaKind.DOCUMENT -> "documents/" + safeFileName(FileLoader.getDocumentFileName(media?.document))
        }
        var name = base
        var n = 2
        while (!used.add(name)) {
            name = base.substringBeforeLast('.') + "($n)." + base.substringAfterLast('.')
            n++
        }
        return name
    }

    private fun docExt(document: TLRPC.Document?, fallback: String): String {
        val fromName = FileLoader.getDocumentFileName(document).substringAfterLast('.', "")
        if (fromName.isNotEmpty() && fromName.length <= 5) return fromName.lowercase(Locale.US)
        return when (document?.mime_type) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "image/webp" -> "webp"
            "application/x-tgsticker" -> "tgs"
            "audio/ogg" -> "ogg"
            "image/gif" -> "gif"
            else -> fallback
        }
    }

    private fun safeFileName(name: String?): String {
        val cleaned = (name ?: "file")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "")
            .trim()
            .ifEmpty { "file" }
        return cleaned.take(120)
    }

    private fun resolveExistingFiles(session: ExportSession) {
        val loader = FileLoader.getInstance(session.account)
        for (item in session.items ?: return) {
            if (item.done) continue
            val media = item.message.media ?: continue
            val local = item.localPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
            if (local != null) {
                item.file = local
                continue
            }
            val file = if (media.photo != null) {
                val sizes = media.photo.sizes
                var found: File? = null
                for (i in sizes.indices.reversed()) {
                    found = listOf(false, true)
                        .mapNotNull { loader.getPathToAttach(sizes[i], it) }
                        .firstOrNull { it.exists() && it.length() > 0 }
                    if (found != null) break
                }
                found
            } else {
                val doc = media.document ?: continue
                listOf(false, true)
                    .mapNotNull { loader.getPathToAttach(doc, it) }
                    .firstOrNull { it.exists() && it.length() > 0 }
            }
            if (file != null) item.file = file
        }
    }

    private fun pump(session: ExportSession) {
        if (session.finished) return
        val items = session.items ?: return
        val loader = FileLoader.getInstance(session.account)
        var outstanding = 0
        for (i in items.indices) {
            val item = items[i]
            if (item.requested) {
                if (!item.done) outstanding++
                continue
            }
            if (item.done) continue
            if (outstanding >= MAX_CONCURRENT_DOWNLOADS) return
            item.requested = true
            outstanding++
            triggerDownload(session, loader, item)
        }
        if (outstanding == 0) finishMediaPhase(session)
    }

    private fun triggerDownload(session: ExportSession, loader: FileLoader, item: ExportItem) {
        val media = item.message.media ?: run { item.failed = true; return }
        try {
            if (media.photo != null) {
                val photo = media.photo
                val size = largestPhotoSize(photo) ?: run { item.failed = true; return }
                loader.loadFile(
                    ImageLocation.getForPhoto(size, photo), photo, "jpg",
                    FileLoader.PRIORITY_NORMAL, 1
                )
            } else {
                val doc = media.document ?: run { item.failed = true; return }
                loader.loadFile(doc, media, FileLoader.PRIORITY_NORMAL, 1)
            }
        } catch (e: Exception) {
            item.failed = true
        }
    }

    private fun updateMediaProgress(session: ExportSession) {
        val items = session.items ?: return
        val done = items.count { it.done }
        session.dialog?.setMessage(
            LocaleController.formatString(R.string.InuChatExportMediaProgress, done, items.size)
        )
    }

    private fun cleanupObservers(session: ExportSession) {
        session.observer?.let {
            val center = NotificationCenter.getInstance(session.account)
            for (event in OBSERVED_FILE_EVENTS) center.removeObserver(it, event)
        }
        session.observer = null
        session.timeoutRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        session.timeoutRunnable = null
    }

    private fun finishMediaPhase(session: ExportSession) {
        if (session.finished) return
        session.finished = true
        cleanupObservers(session)
        session.dialog?.setMessage(LocaleController.getString(R.string.InuChatExportPacking))
        val controller = MessagesController.getInstance(session.account)
        val title = ExportPeers.dialogTitle(controller, session.dialogId)
        val items = session.items ?: ArrayList()
        session.skipped = items.count { it.file == null }
        // entiny: write a plain folder like Telegram Desktop instead of zipping in cache and copying again
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val folder = ExportFiles.uniqueFile(ExportFiles.exportRoot(), "${ExportFiles.safeTitle(title)}-$date")
            val html = File(folder, "messages.html")
            val err = try {
                if (!folder.mkdirs()) throw Exception("cannot create $folder")
                for (item in items) {
                    val src = item.file ?: continue
                    try {
                        val dst = File(folder, item.entryName)
                        dst.parentFile?.mkdirs()
                        FileInputStream(src).use { input -> FileOutputStream(dst).use { input.copyTo(it, 64 * 1024) } }
                    } catch (e: Exception) {
                        FileLog.e("ChatExportHelper: copy ${item.entryName}: ${e.message}")
                        item.file = null
                        item.failed = true
                    }
                }
                html.writeText(ExportHtml.build(session, controller, title, items), Charsets.UTF_8)
                MediaScannerConnection.scanFile(ApplicationLoader.applicationContext, arrayOf(html.absolutePath), arrayOf("text/html"), null)
                null
            } catch (e: Exception) {
                FileLog.e("ChatExportHelper: save folder failed: ${e.message}")
                e.message ?: e.javaClass.simpleName
            }
            AndroidUtilities.runOnUIThread {
                active.remove(session.dialogId)
                session.dialog?.dismiss()
                if (err != null) {
                    BulletinFactory.of(session.fragment).createErrorBulletin(err).show()
                    return@runOnUIThread
                }
                ExportResultUi.showSaved(session, folder, html, "text/html")
            }
        }
    }

    // ------------------------------------------------------------------- html

    // ------------------------------------------------------------------- json

    private fun writeAndShareJson(session: ExportSession) {
        if (session.finished) return
        session.finished = true
        val controller = MessagesController.getInstance(session.account)
        val title = ExportPeers.dialogTitle(controller, session.dialogId)
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val root = ExportFiles.exportRoot()
            val file = ExportFiles.uniqueFile(root, "${ExportFiles.safeTitle(title)}-$date$FILENAME_SUFFIX")
            val err = try {
                if (!root.exists() && !root.mkdirs()) throw Exception("cannot create $root")
                file.writeText(ExportJson.build(session, controller, title), Charsets.UTF_8)
                MediaScannerConnection.scanFile(ApplicationLoader.applicationContext, arrayOf(file.absolutePath), arrayOf("application/json"), null)
                null
            } catch (e: Exception) {
                FileLog.e("ChatExportHelper: save json failed: ${e.message}")
                e.message ?: e.javaClass.simpleName
            }
            AndroidUtilities.runOnUIThread {
                active.remove(session.dialogId)
                session.dialog?.dismiss()
                if (err != null) {
                    BulletinFactory.of(session.fragment).createErrorBulletin(err).show()
                    return@runOnUIThread
                }
                ExportResultUi.showSaved(session, root, file, "application/json")
            }
        }
    }

}