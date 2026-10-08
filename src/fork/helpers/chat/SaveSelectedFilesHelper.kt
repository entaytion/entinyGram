package desu.inugram.helpers.chat

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.SparseArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

object SaveSelectedFilesHelper {
    private const val DOWNLOAD_TIMEOUT_MS = 15 * 60 * 1000L

    @JvmStatic
    fun createItem(context: Context, selectorColor: Int, iconColor: Int, onClick: Runnable): ActionBarMenuItem {
        val item = ActionBarMenuItem(context, null, selectorColor, iconColor, false)
        item.setIcon(R.drawable.msg_download)
        item.contentDescription = LocaleController.getString(R.string.SaveToDownloads)
        item.isDuplicateParentStateEnabled = false
        item.setOnClickListener { onClick.run() }
        return item
    }

    @JvmStatic
    fun save(fragment: BaseFragment, files: Array<SparseArray<MessageObject>>) {
        val messages = ArrayList<MessageObject>()
        for (array in files) for (i in 0 until array.size()) messages.add(array.valueAt(i))
        save(fragment, messages)
    }

    @JvmStatic
    fun save(fragment: BaseFragment, messages: Collection<MessageObject>) {
        val appContext = (fragment.parentActivity ?: return).applicationContext
        val list = ArrayList(messages)
        Thread {
            list.forEach { requestLoad(it) }
            var saved = 0
            for (message in list) {
                try {
                    if (saveOne(message, appContext)) saved++
                } catch (e: Throwable) {
                    Log.d("SaveSelectedFiles", "failed to save", e)
                }
            }
            AndroidUtilities.runOnUIThread {
                val factory = BulletinFactory.of(fragment)
                if (saved > 0) {
                    factory.createDownloadBulletin(BulletinFactory.FileType.UNKNOWN, saved, fragment.resourceProvider).show()
                } else {
                    factory.createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
                }
            }
        }.start()
    }

    private fun requestLoad(message: MessageObject) {
        val owner = message.messageOwner ?: return
        val loader = FileLoader.getInstance(message.currentAccount)
        val file = loader.getPathToMessage(owner)
        if (file != null && file.exists()) return
        val document = message.document
        if (document != null) {
            loader.loadFile(document, message, FileLoader.PRIORITY_NORMAL, 0)
            return
        }
        val photo = owner.media?.photo ?: return
        val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize()) ?: return
        loader.loadFile(ImageLocation.getForPhoto(size, photo), message, "jpg", FileLoader.PRIORITY_NORMAL, 0)
    }

    private fun saveOne(message: MessageObject, context: Context): Boolean {
        val owner = message.messageOwner ?: return false
        val document = message.document
        if (document == null && owner.media?.photo == null) return false
        val loader = FileLoader.getInstance(message.currentAccount)
        var file = loader.getPathToMessage(owner)
        if (file == null || !file.exists()) {
            val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                file = loader.getPathToMessage(owner)
                if (file != null && file.exists()) break
            }
            if (file == null || !file.exists()) return false
        }
        val type = if (document == null) 0 else if (message.isVideo) 1 else 2
        val name = (document?.let { FileLoader.getDocumentFileName(it) }).takeUnless { it.isNullOrEmpty() } ?: file.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaController.saveFileInternal(type, file, name) != null
        }
        AndroidUtilities.runOnUIThread { MediaController.saveFile(file.absolutePath, context, type, name, document?.mime_type) }
        return true
    }
}
