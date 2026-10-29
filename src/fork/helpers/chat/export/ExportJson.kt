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

internal object ExportJson {
    internal fun build(session: ExportSession, controller: MessagesController, title: String): String {
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
            o.put("from_id", ExportPeers.peerId(it))
            o.put("from_name", ExportPeers.displayName(controller, it))
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
                o.put("forwarded_from_id", ExportPeers.peerId(it))
                o.put("forwarded_from_name", ExportPeers.displayName(controller, it))
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
}
