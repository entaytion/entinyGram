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

internal object ExportPeers {
    internal fun peerId(peer: TLRPC.Peer): Long = when (peer) {
        is TLRPC.TL_peerUser -> peer.user_id
        is TLRPC.TL_peerChat -> peer.chat_id
        is TLRPC.TL_peerChannel -> peer.channel_id
        else -> 0
    }

    internal fun displayName(controller: MessagesController, peer: TLRPC.Peer): String {
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

    internal fun dialogTitle(controller: MessagesController, dialogId: Long): String {
        if (dialogId > 0) {
            val user = controller.getUser(dialogId)
            if (user != null) return UserObject.getUserName(user)
        } else {
            controller.getChat(-dialogId)?.title?.let { return it }
        }
        return dialogId.toString()
    }
}
