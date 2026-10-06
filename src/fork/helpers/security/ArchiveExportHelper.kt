package desu.inugram.helpers.security

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ArchiveExportHelper {

    fun export(fragment: BaseFragment) {
        val filesDir = fragment.parentActivity?.filesDir ?: return
        val storage = MessagesStorage.getInstance(fragment.currentAccount)
        storage.storageQueue.postRunnable {
            val db = storage.database
            var file: File? = null
            var count = 0
            if (db != null) {
                runCatching { file = write(filesDir, db).also { count = it.second }.first }
            }
            AndroidUtilities.runOnUIThread { finish(fragment, file, count) }
        }
    }

    private fun write(filesDir: File, db: SQLiteDatabase): Pair<File, Int> {
        val dir = File(filesDir, "cache/inu_share_once")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "entinygram-archive-$stamp.json")
        val count = target.bufferedWriter().use { InuDatabaseHelper.writeArchiveJson(db, it) }
        return target to count
    }

    private fun finish(fragment: BaseFragment, file: File?, count: Int) {
        val activity = fragment.parentActivity ?: return
        if (file == null || count == 0) {
            file?.delete()
            val text = LocaleController.getString(if (file == null) R.string.ErrorOccurred else R.string.InuExportArchiveEmpty)
            BulletinFactory.of(fragment).createErrorBulletin(text).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, LocaleController.getString(R.string.ShareFile)))
        }.onFailure {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
        }
    }
}
