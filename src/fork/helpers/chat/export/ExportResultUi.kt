package desu.inugram.helpers.chat.export

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import androidx.core.content.FileProvider
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
import java.io.File

internal object ExportResultUi {
    // entiny: "View" opens the main file, "Show folder" jumps to it in the file manager
    internal fun showSaved(session: ExportSession, folder: File, mainFile: File, mime: String) {
        val context = session.fragment.parentActivity ?: return
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val shown = if (mainFile.parentFile == folder && mime == "text/html") folder else mainFile
        val path = downloads.name + "/" + shown.absolutePath.removePrefix(downloads.absolutePath).trimStart('/')
        AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuChatExport))
            .setMessage(savedMessage(session, path))
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

    private fun openFolder(session: ExportSession, folder: File) {
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

    private fun openWithChooser(session: ExportSession, file: File, mime: String) {
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

    private fun savedMessage(session: ExportSession, path: String): String {
        val saved = LocaleController.formatString(R.string.InuChatExportSaved, path)
        if (session.skipped <= 0) return saved
        return saved + "\n" + LocaleController.formatString(R.string.InuChatExportSkipped, session.skipped)
    }
}
