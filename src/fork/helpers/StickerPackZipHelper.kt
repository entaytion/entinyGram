package desu.inugram.helpers

import android.app.Activity
import android.widget.FrameLayout
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory

object StickerPackZipHelper {
    const val MENU_ID = 101

    @JvmStatic
    fun download(
        activity: Activity?,
        accountId: Int,
        container: FrameLayout,
        resourcesProvider: Theme.ResourcesProvider?,
        stickerSet: TLRPC.TL_messages_stickerSet?,
    ) {
        val set = stickerSet ?: return
        val documents = set.documents
        if (activity == null || documents.isNullOrEmpty()) return
        if (!StickerDownloadHelper.ensureStoragePermission(activity)) return

        var cancelled = false
        val progress = AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER)
        progress.setCanCancel(true)
        progress.setOnCancelListener { cancelled = true }
        progress.showDelayed(150)

        val files = ArrayList<Pair<TLRPC.Document, File>>(documents.size)
        fun fail() {
            progress.dismiss()
            BulletinFactory.of(container, resourcesProvider)
                .createErrorBulletin(LocaleController.getString(R.string.InuStickerPackZipFailed))
                .show()
        }

        fun next(index: Int) {
            if (cancelled) return
            if (index >= documents.size) {
                pack(activity, accountId, container, resourcesProvider, set, files, progress)
                return
            }
            val document = documents[index]
            StickerDownloadHelper.withDownloadedFile(accountId, document, set, { if (!cancelled) fail() }) { file ->
                files.add(document to file)
                next(index + 1)
            }
        }
        next(0)
    }

    private fun pack(
        activity: Activity,
        accountId: Int,
        container: FrameLayout,
        resourcesProvider: Theme.ResourcesProvider?,
        set: TLRPC.TL_messages_stickerSet,
        files: List<Pair<TLRPC.Document, File>>,
        progress: AlertDialog,
    ) {
        val slug = set.set?.short_name?.takeIf { it.isNotEmpty() } ?: "stickers_${set.set?.id ?: 0}"
        val emojiByDocument = HashMap<Long, String>()
        set.packs?.forEach { pack ->
            pack.documents.forEach { id -> emojiByDocument[id] = (emojiByDocument[id] ?: "") + pack.emoticon }
        }
        Utilities.globalQueue.postRunnable {
            val zip = File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "$slug.zip")
            val ok = runCatching {
                val width = files.size.toString().length
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    val info = StringBuilder().append(set.set?.title ?: slug).append('\n')
                    files.forEachIndexed { i, (document, file) ->
                        val name = (i + 1).toString().padStart(width, '0') + "." + extension(document)
                        out.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                        info.append(name).append(' ').append(emojiByDocument[document.id] ?: "").append('\n')
                    }
                    out.putNextEntry(ZipEntry("info.txt"))
                    out.write(info.toString().toByteArray())
                    out.closeEntry()
                }
            }.isSuccess
            AndroidUtilities.runOnUIThread {
                progress.dismiss()
                if (!ok) {
                    zip.delete()
                    BulletinFactory.of(container, resourcesProvider)
                        .createErrorBulletin(LocaleController.getString(R.string.InuStickerPackZipFailed))
                        .show()
                    return@runOnUIThread
                }
                MediaController.saveFile(zip.absolutePath, activity, 2, "$slug.zip", "application/zip", {
                    zip.delete()
                    BulletinFactory.of(container, resourcesProvider)
                        .createDownloadBulletin(BulletinFactory.FileType.UNKNOWN, 1, resourcesProvider)
                        .show()
                }, false)
            }
        }
    }

    private fun extension(document: TLRPC.Document): String = when (document.mime_type) {
        "application/x-tgsticker" -> "tgs"
        "video/webm" -> "webm"
        "image/webp" -> "webp"
        else -> FileLoader.getDocumentFileName(document).substringAfterLast('.', "").ifEmpty { "dat" }
    }
}
