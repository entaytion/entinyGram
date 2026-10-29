package desu.inugram.helpers.chat.export

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
import desu.inugram.helpers.archive.ArchiveStore

internal enum class ExportMediaKind { PHOTO, VIDEO, VOICE, ROUND, STICKER, GIF, DOCUMENT }

internal class ExportOptions {
    var html = true
    var photo = true
    var video = true
    var voice = true
    var round = true
    var sticker = true
    var gif = true
    var document = true
    var sizeLimitMb = 100

    fun enabled(kind: ExportMediaKind): Boolean = when (kind) {
        ExportMediaKind.PHOTO -> photo
        ExportMediaKind.VIDEO -> video
        ExportMediaKind.VOICE -> voice
        ExportMediaKind.ROUND -> round
        ExportMediaKind.STICKER -> sticker
        ExportMediaKind.GIF -> gif
        ExportMediaKind.DOCUMENT -> document
    }
}

internal class ExportItem(val message: TLRPC.Message, val kind: ExportMediaKind, var entryName: String) {
    var file: File? = null
    var overLimit = false
    var failed = false
    var requested = false
    var attachName: String? = null
    var localPath: String? = null
    val done: Boolean get() = file != null || overLimit || failed
}

internal class ExportSession(val fragment: BaseFragment, val account: Int, val dialogId: Long) {
    val collected = ArrayList<TLRPC.Message>()
    val seenIds = HashSet<Int>()
    var offsetId = 0
    var dialog: AlertDialog? = null
    var aborted = false
    var finished = false
    var options = ExportOptions()
    var items: ArrayList<ExportItem>? = null
    var observer: NotificationCenter.NotificationCenterDelegate? = null
    var timeoutRunnable: Runnable? = null
    var lastActivityAt = 0L
    var skipped = 0
}
