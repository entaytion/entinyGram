package desu.inugram.helpers.network

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DownloadController
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme

object DownloadQueueHelper {
    private const val FINISHED_LIMIT = 15

    @JvmStatic
    fun largeQueueIndex(index: Int): Int = if (InuConfig.DOWNLOAD_QUEUE.value) 0 else index

    @JvmStatic
    fun limit(large: Boolean, stock: Int): Int = if (large && InuConfig.DOWNLOAD_QUEUE.value) 1 else stock

    @JvmStatic
    fun show(fragment: BaseFragment) {
        val context = fragment.parentActivity ?: return
        val account = fragment.currentAccount
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            list.removeAllViews()
            val controller = DownloadController.getInstance(account)
            val active = ArrayList(controller.downloadingFiles)
            val finished = ArrayList(controller.recentDownloadingFiles).take(FINISHED_LIMIT)
            if (active.isEmpty() && finished.isEmpty()) {
                list.addView(label(context, LocaleController.getString(R.string.InuDownloadsEmpty), 15f, Theme.key_windowBackgroundWhiteGrayText, false))
                return
            }
            if (active.isNotEmpty()) {
                list.addView(label(context, LocaleController.getString(R.string.InuDownloadsActive), 13f, Theme.key_windowBackgroundWhiteBlueHeader, true))
                active.forEachIndexed { i, message ->
                    val state = LocaleController.getString(if (i == 0 || !InuConfig.DOWNLOAD_QUEUE.value) R.string.InuDownloadsRunning else R.string.InuDownloadsWaiting)
                    list.addView(row(context, message, state, true) {
                        message.document?.let { FileLoader.getInstance(account).cancelLoadFile(it) }
                        AndroidUtilities.runOnUIThread({ fill() }, 300)
                    })
                }
            }
            if (finished.isNotEmpty()) {
                list.addView(label(context, LocaleController.getString(R.string.InuDownloadsFinished), 13f, Theme.key_windowBackgroundWhiteBlueHeader, true))
                finished.forEach { list.addView(row(context, it, null, false) {}) }
            }
        }
        fill()
        val sheet = BottomSheet.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuDownloadsManager))
            .setCustomView(ScrollView(context).apply {
                addView(list)
                setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
            })
            .create()
        fragment.showDialog(sheet)
    }

    private fun label(context: Context, text: String, size: Float, colorKey: Int, bold: Boolean) = TextView(context).apply {
        this.text = text
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.getColor(colorKey))
        setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
    }

    private fun row(context: Context, message: MessageObject, state: String?, cancellable: Boolean, onCancel: () -> Unit): View {
        val name = message.document?.let { FileLoader.getDocumentFileName(it) }.takeUnless { it.isNullOrEmpty() } ?: message.fileName.orEmpty()
        val size = message.document?.size?.takeIf { it > 0 }?.let { AndroidUtilities.formatFileSize(it.toLong()) }
        val details = listOfNotNull(state, size).joinToString(" · ")
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = name
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                })
                if (details.isNotEmpty()) addView(TextView(context).apply {
                    text = details
                    textSize = 13f
                    setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (cancellable) addView(TextView(context).apply {
                text = LocaleController.getString(R.string.Cancel)
                textSize = 14f
                setTextColor(Theme.getColor(Theme.key_text_RedBold))
                setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
                setOnClickListener { onCancel() }
            })
        }
    }
}
