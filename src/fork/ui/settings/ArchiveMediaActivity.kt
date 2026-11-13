package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import desu.inugram.helpers.archive.ArchiveCache
import desu.inugram.helpers.archive.ArchiveMediaHelper
import desu.inugram.helpers.archive.ArchiveStore
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CacheControlActivity
import org.telegram.ui.CachedMediaLayout
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.NumberTextView
import org.telegram.ui.Storage.CacheModel

class ArchiveMediaActivity : BaseFragment() {
    private var mediaLayout: CachedMediaLayout? = null
    private var model: CacheModel? = null
    private var selectedCount: NumberTextView? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(LocaleController.getString(R.string.InuCacheMediaCategory))
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> if (actionBar.isActionModeShowed) clearSelection() else finishFragment()
                    MENU_DELETE -> confirmDelete()
                }
            }
        })
        val actionMode = actionBar.createActionMode()
        selectedCount = NumberTextView(context).apply {
            setTextSize(18)
            setTypeface(AndroidUtilities.bold())
            setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon))
        }
        actionMode.addView(selectedCount, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0))
        actionMode.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54f), LocaleController.getString(R.string.Delete))

        val layout = object : CachedMediaLayout(context, this@ArchiveMediaActivity, null) {
            override fun showActionMode(show: Boolean) {
                if (show) actionBar.showActionMode() else actionBar.hideActionMode()
            }

            override fun actionModeIsVisible(): Boolean = actionBar.isActionModeShowed
        }
        layout.setDelegate(object : CachedMediaLayout.Delegate {
            override fun onItemSelected(entities: CacheControlActivity.DialogFileEntities?, fileInfo: CacheModel.FileInfo?, longPress: Boolean) {
                val current = model ?: return
                fileInfo ?: return
                current.toggleSelect(fileInfo)
                layout.updateVisibleRows()
                updateActionMode()
            }

            override fun clear() = confirmDelete()

            override fun clearSelection() = this@ArchiveMediaActivity.clearSelection()
        })
        mediaLayout = layout
        fragmentView = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        }
        load()
        return fragmentView
    }

    private fun load() {
        val dir = ArchiveMediaHelper.getSavedMediaDir(currentAccount)
        Utilities.globalQueue.postRunnable {
            val result = CacheModel(true)
            dir.listFiles()
                ?.filter { it.isFile && it.name != ".nomedia" && it.length() > 0 }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    val type = typeOf(file)
                    result.add(type, CacheModel.FileInfo(file).also {
                        it.type = type
                        it.size = file.length()
                        it.dialogId = file.name.substringBefore('_').toLongOrNull() ?: 0L
                    })
                }
            AndroidUtilities.runOnUIThread {
                if (isFinished) return@runOnUIThread
                model = result
                mediaLayout?.setCacheModel(result)
            }
        }
    }

    private fun updateActionMode() {
        val count = model?.getSelectedFiles() ?: 0
        if (count > 0) {
            selectedCount?.setNumber(count, actionBar.isActionModeShowed)
            if (!actionBar.isActionModeShowed) actionBar.showActionMode()
        } else {
            actionBar.hideActionMode()
        }
    }

    private fun clearSelection() {
        val current = model ?: return
        current.clearSelection()
        mediaLayout?.updateVisibleRows()
        actionBar.hideActionMode()
    }

    private fun confirmDelete() {
        val current = model ?: return
        if (current.getSelectedFiles() == 0) return
        val builder = AlertDialog.Builder(parentActivity ?: return)
            .setTitle(LocaleController.getString(R.string.ClearSelectedCache))
            .setMessage(LocaleController.getString(R.string.InuArchiveMediaDeleteSelected))
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ -> delete(current, current.selectedFiles.toList()) }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        val dialog = builder.create()
        showDialog(dialog)
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE) as? android.widget.TextView)?.setTextColor(Theme.getColor(Theme.key_text_RedBold))
    }

    private fun delete(current: CacheModel, files: List<CacheModel.FileInfo>) {
        val account = currentAccount
        val storage = MessagesStorage.getInstance(account)
        storage.storageQueue.postRunnable {
            storage.database?.let { db -> ArchiveStore.detachMediaPaths(db, files.map { it.file.absolutePath }) }
            files.forEach { it.file.delete() }
            ArchiveCache.invalidateMediaCache(account)
            AndroidUtilities.runOnUIThread {
                files.forEach(current::onFileDeleted)
                current.clearSelection()
                actionBar.hideActionMode()
                mediaLayout?.update()
            }
        }
    }

    companion object {
        private const val MENU_DELETE = 1

        private val PHOTO = setOf("jpg", "jpeg", "png", "webp", "heic", "bmp")
        private val VIDEO = setOf("mp4", "mov", "mkv", "webm", "3gp", "avi")
        private val VOICE = setOf("ogg", "oga", "opus")
        private val MUSIC = setOf("mp3", "m4a", "flac", "wav", "aac")

        private fun typeOf(file: File): Int = when (file.extension.lowercase()) {
            in PHOTO -> CacheControlActivity.TYPE_PHOTOS
            in VIDEO -> CacheControlActivity.TYPE_VIDEOS
            in VOICE -> CacheControlActivity.TYPE_VOICE
            in MUSIC -> CacheControlActivity.TYPE_MUSIC
            else -> CacheControlActivity.TYPE_DOCUMENTS
        }
    }
}
