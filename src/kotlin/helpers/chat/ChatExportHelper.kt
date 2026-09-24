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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import androidx.core.content.FileProvider
import desu.inugram.InuConfig
import desu.inugram.helpers.diag.DiagLog
import desu.inugram.helpers.SharePicker
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
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
import org.telegram.ui.LaunchActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ChatExportHelper {

    const val FILENAME_SUFFIX = ".entiny-chat.json"
    private const val PAGE_SIZE = 100
    private const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val PREVIEW_MAX_SIDE = 640
    private const val STALL_TIMEOUT_MS = 30_000L
    private const val STALL_CHECK_INTERVAL_MS = 5_000L
    private val OBSERVED_FILE_EVENTS = intArrayOf(
        NotificationCenter.fileLoaded,
        NotificationCenter.fileLoadFailed,
        NotificationCenter.fileLoadProgressChanged,
    )

    private enum class MediaKind { PHOTO, VIDEO, VOICE, ROUND, STICKER, GIF, DOCUMENT }

    private class Options {
        var html = true
        var photo = true
        var video = true
        var voice = true
        var round = true
        var sticker = true
        var gif = true
        var document = true
        var sizeLimitMb = 100

        fun enabled(kind: MediaKind): Boolean = when (kind) {
            MediaKind.PHOTO -> photo
            MediaKind.VIDEO -> video
            MediaKind.VOICE -> voice
            MediaKind.ROUND -> round
            MediaKind.STICKER -> sticker
            MediaKind.GIF -> gif
            MediaKind.DOCUMENT -> document
        }
    }

    private class Item(val message: TLRPC.Message, val kind: MediaKind, var entryName: String) {
        var file: File? = null
        var overLimit = false
        var failed = false
        var requested = false
        var attachName: String? = null
        val done: Boolean get() = file != null || overLimit || failed
    }

    private class Session(val fragment: BaseFragment, val account: Int, val dialogId: Long) {
        val collected = ArrayList<TLRPC.Message>()
        val seenIds = HashSet<Int>()
        var offsetId = 0
        var dialog: AlertDialog? = null
        var aborted = false
        var finished = false
        var options = Options()
        var items: ArrayList<Item>? = null
        var nextToRequest = 0
        var observer: NotificationCenter.NotificationCenterDelegate? = null
        var timeoutRunnable: Runnable? = null
        var lastActivityAt = 0L
    }

    private val active = HashMap<Long, Session>()

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
        showOptionsDialog(fragment, currentAccount, dialogId)
    }

    private fun showOptionsDialog(fragment: BaseFragment, currentAccount: Int, dialogId: Long) {
        val context = fragment.parentActivity ?: return
        val options = Options()
        val density = TypedValue.COMPLEX_UNIT_DIP
        val rows = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(8f))
        }

        // entiny: stock dialog checkbox rows instead of hand-sized CheckBox2 (createLinear takes dp, not px)
        fun addRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val cell = CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT)
            cell.background = Theme.getSelectorDrawable(false)
            cell.setText(label, "", initial, false)
            cell.setOnClickListener {
                val now = !cell.isChecked
                cell.setChecked(now, true)
                onChange(now)
            }
            rows.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        addRow(LocaleController.getString(R.string.InuChatExportHtml), options.html) { options.html = it }
        addRow(LocaleController.getString(R.string.InuChatExportPhotos), options.photo) { options.photo = it }
        addRow(LocaleController.getString(R.string.InuChatExportVideos), options.video) { options.video = it }
        addRow(LocaleController.getString(R.string.InuChatExportVoice), options.voice) { options.voice = it }
        addRow(LocaleController.getString(R.string.InuChatExportVideoMessages), options.round) { options.round = it }
        addRow(LocaleController.getString(R.string.InuChatExportStickers), options.sticker) { options.sticker = it }
        addRow(LocaleController.getString(R.string.InuChatExportGifs), options.gif) { options.gif = it }
        addRow(LocaleController.getString(R.string.InuChatExportFiles), options.document) { options.document = it }

        val limitLabel = android.widget.TextView(context).apply {
            setTextSize(density, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            setPadding(0, AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(2f))
        }
        fun formatLimit(mb: Int): String =
            LocaleController.formatString(R.string.InuChatExportSizeLimit, mb)
        val slider = LimitSlider(context, progressForMb(options.sizeLimitMb)) { p ->
            val mb = mbForProgress(p)
            options.sizeLimitMb = mb
            limitLabel.text = formatLimit(mb)
        }
        limitLabel.text = formatLimit(options.sizeLimitMb)
        rows.addView(limitLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 0f))
        rows.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 0f))

        val dialog = AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuChatExportOptions))
            .setView(rows)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.InuChatExportGo)) { _, _ ->
                beginExport(fragment, currentAccount, dialogId, options)
            }
            .create()
        dialog.show()
    }

    // log scale: progress 0..1 -> 1..1024 MB
    private fun mbForProgress(p: Float): Int =
        Math.round(Math.pow(2.0, (p.coerceIn(0f, 1f) * 10).toDouble())).toInt().coerceIn(1, 1024)

    private fun progressForMb(mb: Int): Float =
        (Math.log((mb.coerceIn(1, 1024)).toDouble()) / Math.log(2.0) / 10.0).toFloat()

    private class LimitSlider(
        context: Context,
        initialProgress: Float,
        private val onChanged: (Float) -> Unit,
    ) : View(context) {
        private val seekBar = SeekBar(this)

        init {
            seekBar.setColors(
                Theme.getColor(Theme.key_player_progressBackground),
                Theme.getColor(Theme.key_player_progressCachedBackground),
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progressBackground),
            )
            seekBar.setProgress(initialProgress)
            seekBar.setDelegate(object : SeekBar.SeekBarDelegate {
                override fun onSeekBarDrag(progress: Float) {
                    seekBar.setProgress(progress)
                    onChanged(progress)
                }

                override fun onSeekBarContinuousDrag(progress: Float) {
                    onChanged(progress)
                }
            })
            isClickable = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthSpec),
                AndroidUtilities.dp(32f),
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            seekBar.setSize(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            seekBar.draw(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val handled = seekBar.onTouch(event.actionMasked, event.x, event.y)
            if (handled) {
                invalidate()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return handled || super.onTouchEvent(event)
        }
    }

    private fun beginExport(
        fragment: BaseFragment,
        currentAccount: Int,
        dialogId: Long,
        options: Options,
    ) {
        val session = Session(fragment, currentAccount, dialogId)
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

    private fun fetchPage(session: Session) {
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
                    DiagLog.log("export", "getHistory failed dialog=${session.dialogId} offset=${session.offsetId} error=${error?.code} ${error?.text}")
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
                DiagLog.log("export", "page dialog=${session.dialogId} got=${response.messages.size} total=${session.collected.size} offset=${session.offsetId}")
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

    private fun finishWithError(session: Session, error: String?) {
        session.finished = true
        active.remove(session.dialogId)
        session.dialog?.dismiss()
        val msg = error?.takeIf { it.isNotEmpty() }
            ?: LocaleController.getString(R.string.InuChatExportFailed)
        BulletinFactory.of(session.fragment).createErrorBulletin(msg).show()
    }

    // ------------------------------------------------------------------ media

    private fun startMediaPhase(session: Session) {
        val controller = MessagesController.getInstance(session.account)
        Utilities.globalQueue.postRunnable {
            val items = buildItems(session, controller)
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
                                DiagLog.log("export", "file load failed name=$name")
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
    private fun scheduleStallCheck(session: Session) {
        val check = Runnable {
            if (session.finished || session.aborted) return@Runnable
            resolveExistingFiles(session)
            if (SystemClock.elapsedRealtime() - session.lastActivityAt > STALL_TIMEOUT_MS) {
                DiagLog.log("export", "stall: skipping ${session.items?.count { it.requested && !it.done }} stuck files")
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

    private fun buildItems(session: Session, controller: MessagesController): ArrayList<Item> {
        val usedNames = HashSet<String>()
        val items = ArrayList<Item>()
        var index = 0
        for (m in session.collected.sortedBy { it.date }) {
            index++
            val kind = kindOf(m) ?: continue
            if (!session.options.enabled(kind)) continue
            val size = mediaSizeBytes(m, kind)
            val item = Item(m, kind, entryName(kind, index, m, usedNames))
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

    private fun kindOf(m: TLRPC.Message): MediaKind? {
        val media = m.media ?: return null
        if (media is TLRPC.TL_messageMediaPhoto || media.photo != null) return MediaKind.PHOTO
        val doc = media.document ?: return null
        if (media.voice) return MediaKind.VOICE
        if (media.video) return if (media.round) MediaKind.ROUND else MediaKind.VIDEO
        if (media is TLRPC.TL_messageMediaDocument) {
            for (attr in doc.attributes) {
                if (attr is TLRPC.TL_documentAttributeSticker) return MediaKind.STICKER
                if (attr is TLRPC.TL_documentAttributeAnimated) return MediaKind.GIF
            }
            return MediaKind.DOCUMENT
        }
        return null
    }

    private fun mediaSizeBytes(m: TLRPC.Message, kind: MediaKind): Long {
        val media = m.media ?: return 0L
        return when (kind) {
            MediaKind.PHOTO -> largestPhotoSize(media.photo)?.size?.toLong() ?: 0L
            else -> media.document?.size ?: 0L
        }
    }

    private fun largestPhotoSize(photo: TLRPC.Photo?): TLRPC.PhotoSize? =
        photo?.sizes?.lastOrNull()

    private fun entryName(kind: MediaKind, index: Int, m: TLRPC.Message, used: HashSet<String>): String {
        val stamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(m.date * 1000L))
        val media = m.media
        val base = when (kind) {
            MediaKind.PHOTO -> "photos/photo_${index}@$stamp.jpg"
            MediaKind.VIDEO -> "videos/video_${index}@$stamp.${docExt(media?.document, "mp4")}"
            MediaKind.VOICE -> "voice/voice_${index}@$stamp.ogg"
            MediaKind.ROUND -> "video_messages/round_${index}@$stamp.mp4"
            MediaKind.STICKER -> "stickers/sticker_${index}@$stamp.${docExt(media?.document, "webp")}"
            MediaKind.GIF -> "gifs/gif_${index}@$stamp.${docExt(media?.document, "mp4")}"
            MediaKind.DOCUMENT -> "documents/" + safeFileName(FileLoader.getDocumentFileName(media?.document))
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

    private fun resolveExistingFiles(session: Session) {
        val loader = FileLoader.getInstance(session.account)
        for (item in session.items ?: return) {
            if (item.done) continue
            val media = item.message.media ?: continue
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

    private fun pump(session: Session) {
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

    private fun triggerDownload(session: Session, loader: FileLoader, item: Item) {
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

    private fun updateMediaProgress(session: Session) {
        val items = session.items ?: return
        val done = items.count { it.done }
        session.dialog?.setMessage(
            LocaleController.formatString(R.string.InuChatExportMediaProgress, done, items.size)
        )
    }

    private fun cleanupObservers(session: Session) {
        session.observer?.let {
            val center = NotificationCenter.getInstance(session.account)
            for (event in OBSERVED_FILE_EVENTS) center.removeObserver(it, event)
        }
        session.observer = null
        session.timeoutRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        session.timeoutRunnable = null
    }

    private fun finishMediaPhase(session: Session) {
        if (session.finished) return
        session.finished = true
        cleanupObservers(session)
        session.dialog?.setMessage(LocaleController.getString(R.string.InuChatExportPacking))
        val controller = MessagesController.getInstance(session.account)
        val title = dialogTitle(controller, session.dialogId)
        val items = session.items ?: ArrayList()
        // entiny: write a plain folder like Telegram Desktop instead of zipping in cache and copying again
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val folder = uniqueFile(exportRoot(), "${safeTitle(title)}-$date")
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
                html.writeText(buildHtml(session, controller, title, items), Charsets.UTF_8)
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
                DiagLog.log("export", "saved html folder=${folder.name} media=${items.count { it.file != null }} failed=${items.count { it.failed }} overLimit=${items.count { it.overLimit }}")
                showSavedDialog(session, folder, html, "text/html")
            }
        }
    }

    private fun exportRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${InuConfig.DOWNLOAD_DIRECTORY.value}/Chat Export")

    private fun safeTitle(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { "chat" }

    // name -> name(2) -> name(3)... keeping the extension, works for folders too
    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base($n)$ext")
            n++
        }
        return candidate
    }

    // ------------------------------------------------------------------- html

    private fun buildHtml(
        session: Session,
        controller: MessagesController,
        title: String,
        items: List<Item>,
    ): String {
        val byMessage = HashMap<Int, Item>()
        for (item in items) byMessage[item.message.id] = item
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>").append(esc(title)).append("</title>")
        sb.append("<style>")
            .append(":root{color-scheme:light dark}")
            .append("body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f1f3f5;color:#17242d}")
            .append(".chat{max-width:760px;margin:0 auto;padding:16px 12px 48px}")
            .append("h1{font-size:20px;margin:8px 4px 2px}.sub{color:#6a7786;font-size:13px;margin:0 4px 16px}")
            .append(".msg{background:#fff;border-radius:12px;padding:8px 12px;margin:6px 0;box-shadow:0 1px 1px rgba(0,0,0,.06)}")
            .append(".msg.out{background:#e3fee0}")
            .append(".info{display:flex;gap:8px;align-items:baseline;font-size:12px;margin-bottom:2px}")
            .append(".from{font-weight:600;color:#d94b4b}.date{color:#8a97a3;margin-left:auto}")
            .append(".fwd,.reply{font-size:12px;color:#6a7786;margin:2px 0}")
            .append(".text{white-space:normal;word-wrap:break-word;font-size:15px;line-height:1.35}")
            .append(".service{text-align:center;color:#8a97a3;font-size:13px;margin:10px 0;font-style:italic}")
            .append(".media img,.media video{max-width:100%;max-height:420px;border-radius:8px;display:block;margin:6px 0}")
            .append("audio{width:100%}.media img.sticker{max-width:180px;max-height:180px}")
            .append("a.file{display:inline-block;font-size:14px;color:#3a6df0;text-decoration:none;margin:4px 0}")
            .append(".missing{font-size:13px;color:#b04a4a}")
            .append("a[href^=\"#m\"]{color:#3a6df0;text-decoration:none}")
            .append("@media (prefers-color-scheme:dark){body{background:#0f1419;color:#e9eef2}")
            .append(".msg{background:#1c2733;box-shadow:none}.msg.out{background:#2b4d3a}")
            .append(".sub,.fwd,.reply{color:#8a97a3}}")
            .append("</style></head><body><div class=\"chat\">")
        sb.append("<h1>").append(esc(title)).append("</h1>")
        val exported = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        sb.append("<div class=\"sub\">")
            .append(session.collected.size).append(" messages · ")
            .append(esc(LocaleController.getString(R.string.InuChatExportExported)) ?: "exported")
            .append(" ").append(esc(exported)).append(" · entinyGram</div>")
        val timeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        for (m in session.collected.sortedBy { it.date }) {
            if (m.action != null || m is TLRPC.TL_messageService) {
                sb.append("<div class=\"service\">").append(esc(serviceLabel(controller, m))).append("</div>")
                continue
            }
            sb.append("<div class=\"msg").append(if (m.out) " out" else "").append("\" id=\"m")
                .append(m.id).append("\">")
            sb.append("<div class=\"info\">")
            m.from_id?.let { sb.append("<span class=\"from\">").append(esc(displayName(controller, it))).append("</span>") }
            sb.append("<span class=\"date\">").append(timeFmt.format(Date(m.date * 1000L))).append("</span></div>")
            m.fwd_from?.let { f ->
                val name = f.from_name?.takeIf { it.isNotEmpty() }
                    ?: f.from_id?.let { displayName(controller, it) }
                    ?: f.post_author
                if (!name.isNullOrEmpty()) {
                    sb.append("<div class=\"fwd\">").append(esc(name)).append("</div>")
                }
            }
            m.reply_to?.reply_to_msg_id?.takeIf { it != 0 }?.let {
                sb.append("<div class=\"reply\"><a href=\"#m").append(it).append("\">&8617;</a></div>")
            }
            if (!m.message.isNullOrEmpty()) {
                sb.append("<div class=\"text\">").append(esc(m.message).replace("\n", "<br>")).append("</div>")
            }
            byMessage[m.id]?.let { sb.append(mediaHtml(it)) }
            sb.append("</div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun mediaHtml(item: Item): String {
        val label = kindLabel(item.kind)
        if (item.overLimit) {
            return "<div class=\"media\"><span class=\"missing\">$label · over size limit</span></div>"
        }
        if (item.file == null) {
            return "<div class=\"media\"><span class=\"missing\">$label · unavailable</span></div>"
        }
        val href = item.entryName.split('/').joinToString("/") { esc(it) }
        // entiny: viewers opened via content:// can't resolve sibling files, so images carry an inline fallback
        val fallback = if (item.kind == MediaKind.PHOTO || item.kind == MediaKind.STICKER) inlinePreview(item.file) else null
        val onError = fallback?.let { " data-fb=\"$it\" onerror=\"if(this.dataset.fb){this.src=this.dataset.fb;this.dataset.fb=''}\"" } ?: ""
        if (item.kind == MediaKind.STICKER && fallback != null) {
            return "<div class=\"media\"><img class=\"sticker\" loading=\"lazy\" src=\"$href\" alt=\"$label\"$onError></div>"
        }
        return when (item.kind) {
            MediaKind.PHOTO ->
                "<div class=\"media\"><a href=\"$href\"><img loading=\"lazy\" src=\"$href\" alt=\"$label\"$onError></a></div>"
            MediaKind.VIDEO, MediaKind.ROUND ->
                "<div class=\"media\"><video controls preload=\"none\" src=\"$href\"></video></div>"
            MediaKind.VOICE ->
                "<div class=\"media\"><audio controls preload=\"none\" src=\"$href\"></audio></div>"
            else ->
                "<div class=\"media\"><a class=\"file\" href=\"$href\">$label · ${item.entryName.substringAfterLast('/')}</a></div>"
        }
    }

    private fun inlinePreview(file: File?): String? {
        if (file == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_MAX_SIDE) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val out = ByteArrayOutputStream()
            val alpha = bitmap.hasAlpha()
            bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 70, out)
            bitmap.recycle()
            "data:image/${if (alpha) "png" else "jpeg"};base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            FileLog.e("ChatExportHelper: preview ${file.name}: ${e.message}")
            null
        }
    }

    private fun kindLabel(kind: MediaKind): String = when (kind) {
        MediaKind.PHOTO -> "photo"
        MediaKind.VIDEO -> "video"
        MediaKind.VOICE -> "voice"
        MediaKind.ROUND -> "video message"
        MediaKind.STICKER -> "sticker"
        MediaKind.GIF -> "gif"
        MediaKind.DOCUMENT -> "file"
    }

    private fun serviceLabel(controller: MessagesController, m: TLRPC.Message): String {
        val action = m.action?.javaClass?.simpleName?.removePrefix("TL_") ?: return ""
        return action.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(Locale.getDefault())
    }

    private fun esc(s: String?): String = s
        ?.replace("&", "&amp;")
        ?.replace("<", "&lt;")
        ?.replace(">", "&gt;")
        ?.replace("\"", "&quot;")
        ?: ""

    // ------------------------------------------------------------------- json

    private fun writeAndShareJson(session: Session) {
        if (session.finished) return
        session.finished = true
        val controller = MessagesController.getInstance(session.account)
        val title = dialogTitle(controller, session.dialogId)
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val root = exportRoot()
            val file = uniqueFile(root, "${safeTitle(title)}-$date$FILENAME_SUFFIX")
            val err = try {
                if (!root.exists() && !root.mkdirs()) throw Exception("cannot create $root")
                file.writeText(buildJson(session, controller, title), Charsets.UTF_8)
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
                showSavedDialog(session, root, file, "application/json")
            }
        }
    }

    private fun buildJson(session: Session, controller: MessagesController, title: String): String {
        val root = JSONObject()
        root.put("entiny_export", 1)
        root.put("type", "chat_export")
        root.put("dialog_id", session.dialogId)
        root.put("title", title)
        root.put(
            "export_date",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
        )
        val arr = JSONArray()
        for (message in session.collected.sortedBy { it.date }) {
            arr.put(messageJson(controller, message))
        }
        root.put("messages", arr)
        return root.toString(2)
    }

    private fun messageJson(controller: MessagesController, m: TLRPC.Message): JSONObject {
        val o = JSONObject()
        o.put("id", m.id)
        o.put("date", m.date)
        m.from_id?.let {
            o.put("from_id", peerId(it))
            o.put("from_name", displayName(controller, it))
        }
        if (!m.message.isNullOrEmpty()) o.put("text", m.message)
        if (m.edit_date != 0) o.put("edit_date", m.edit_date)
        if (m.pinned) o.put("pinned", true)
        if (m.out) o.put("out", true)
        if (m.via_bot_id != 0L) o.put("via_bot_id", m.via_bot_id)
        if (m.views != 0) o.put("views", m.views)
        if (m.forwards != 0) o.put("forwards", m.forwards)
        m.reply_to?.let { o.put("reply_to_msg_id", it.reply_to_msg_id) }
        m.fwd_from?.let { f ->
            o.put("forwarded", true)
            f.from_id?.let {
                o.put("forwarded_from_id", peerId(it))
                o.put("forwarded_from_name", displayName(controller, it))
            }
            f.from_name?.takeIf { it.isNotEmpty() }?.let { o.put("forwarded_from_name", it) }
            f.post_author?.takeIf { it.isNotEmpty() }?.let { o.put("forwarded_post_author", it) }
        }
        mediaType(m.media)?.let { o.put("media", it) }
        m.action?.let { o.put("service_action", it.javaClass.simpleName.removePrefix("TL_")) }
        return o
    }

    private fun mediaType(media: TLRPC.MessageMedia?): String? = when (media) {
        null, is TLRPC.TL_messageMediaEmpty -> null
        is TLRPC.TL_messageMediaPhoto -> "photo"
        is TLRPC.TL_messageMediaDocument -> when {
            media.voice -> "voice"
            media.video -> if (media.round) "round" else "video"
            else -> "document"
        }
        is TLRPC.TL_messageMediaGeo -> "location"
        is TLRPC.TL_messageMediaVenue -> "location"
        is TLRPC.TL_messageMediaContact -> "contact"
        is TLRPC.TL_messageMediaPoll -> "poll"
        is TLRPC.TL_messageMediaDice -> "dice"
        is TLRPC.TL_messageMediaWebPage -> "webpage"
        is TLRPC.TL_messageMediaStory -> "story"
        is TLRPC.TL_messageMediaInvoice -> "invoice"
        else -> media.javaClass.simpleName.removePrefix("TL_messageMedia").lowercase(Locale.US)
    }

    private fun peerId(peer: TLRPC.Peer): Long = when (peer) {
        is TLRPC.TL_peerUser -> peer.user_id
        is TLRPC.TL_peerChat -> peer.chat_id
        is TLRPC.TL_peerChannel -> peer.channel_id
        else -> 0
    }

    private fun displayName(controller: MessagesController, peer: TLRPC.Peer): String {
        return when (peer) {
            is TLRPC.TL_peerUser -> {
                val user = controller.getUser(peer.user_id)
                if (user != null && !UserObject.isDeleted(user)) UserObject.getUserName(user)
                else LocaleController.getString(R.string.HiddenName)
            }
            is TLRPC.TL_peerChat, is TLRPC.TL_peerChannel -> {
                val id = peerId(peer)
                controller.getChat(id)?.title ?: "ID $id"
            }
            else -> "ID ${peerId(peer)}"
        }
    }

    private fun dialogTitle(controller: MessagesController, dialogId: Long): String {
        if (dialogId > 0) {
            val user = controller.getUser(dialogId)
            if (user != null) return UserObject.getUserName(user)
        } else {
            controller.getChat(-dialogId)?.title?.let { return it }
        }
        return dialogId.toString()
    }

    // entiny: "View" opens the main file, "Show folder" jumps to it in the file manager
    private fun showSavedDialog(session: Session, folder: File, mainFile: File, mime: String) {
        val context = session.fragment.parentActivity ?: return
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val shown = if (mainFile.parentFile == folder && mime == "text/html") folder else mainFile
        val path = downloads.name + "/" + shown.absolutePath.removePrefix(downloads.absolutePath).trimStart('/')
        AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuChatExport))
            .setMessage(LocaleController.formatString(R.string.InuChatExportSaved, path))
            .setPositiveButton(LocaleController.getString(R.string.InuChatExportView)) { _, _ ->
                openWithChooser(session, mainFile, mime)
            }
            .setNeutralButton(LocaleController.getString(R.string.InuChatExportShowFolder)) { _, _ ->
                openFolder(session, folder)
            }
            .setNegativeButton(LocaleController.getString(R.string.OK), null)
            .create()
            .show()
    }

    private fun openFolder(session: Session, folder: File) {
        val activity = session.fragment.parentActivity ?: return
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        val relative = folder.absolutePath.removePrefix(storageRoot).trimStart('/')
        // the stock Files app understands a documents-provider uri for a directory on primary storage
        val docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(folder), "resource/folder"),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        AndroidUtilities.addToClipboard(folder.absolutePath)
        BulletinFactory.of(session.fragment).createCopyBulletin(folder.absolutePath).show()
    }

    private fun openWithChooser(session: Session, file: File, mime: String) {
        val activity = session.fragment.parentActivity ?: return
        try {
            val uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.InuChatExportView)))
        } catch (e: Exception) {
            BulletinFactory.of(session.fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuChatExportFailed)
            ).show()
        }
    }
}
