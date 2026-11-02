package desu.inugram.helpers.chat.export

import desu.inugram.InuConfig
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import java.io.File

internal enum class ExportMediaKind { PHOTO, VIDEO, VOICE, ROUND, STICKER, GIF, DOCUMENT }

internal class ExportOptions {
    var html = InuConfig.CHAT_EXPORT_HTML.value
    var photo = hasBit(ExportMediaKind.PHOTO)
    var video = hasBit(ExportMediaKind.VIDEO)
    var voice = hasBit(ExportMediaKind.VOICE)
    var round = hasBit(ExportMediaKind.ROUND)
    var sticker = hasBit(ExportMediaKind.STICKER)
    var gif = hasBit(ExportMediaKind.GIF)
    var document = hasBit(ExportMediaKind.DOCUMENT)
    var sizeLimitMb = InuConfig.CHAT_EXPORT_SIZE_LIMIT.value

    fun saveAsDefault() {
        InuConfig.CHAT_EXPORT_HTML.value = html
        InuConfig.CHAT_EXPORT_MEDIA.value = ExportMediaKind.entries
            .filter { enabled(it) }
            .fold(0) { acc, kind -> acc or (1 shl kind.ordinal) }
        InuConfig.CHAT_EXPORT_SIZE_LIMIT.value = sizeLimitMb
    }

    private fun hasBit(kind: ExportMediaKind): Boolean =
        InuConfig.CHAT_EXPORT_MEDIA.value and (1 shl kind.ordinal) != 0

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
