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

internal object ExportHtml {
    private const val PREVIEW_MAX_SIDE = 640

    internal fun build(
        session: ExportSession,
        controller: MessagesController,
        title: String,
        items: List<ExportItem>,
    ): String {
        val byMessage = HashMap<Int, ExportItem>()
        for (item in items) byMessage[item.message.id] = item
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>").append(esc(title)).append("</title>")
        sb.append("<style>")
            .append(":root{color-scheme:light dark}")
            .append("body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f1f3f5;color:#17242d}")
            .append(".chat{max-width:760px;margin:0 auto;padding:16px 12px 48px}")
            .append("h1{font-size:20px;margin:8px 4px 2px}.sub{color:#6a7786;font-size:13px;margin:0 4px 16px}")
            .append(".msg{background:#fff;border-radius:12px;padding:8px 12px;margin:6px 0;box-shadow:0 1px 1px rgba(0,0,0,.06)}")
            .append(".msg.out{background:#e3fee0}")
            .append(".info{display:flex;gap:8px;align-items:baseline;font-size:12px;margin-bottom:2px}")
            .append(".from{font-weight:600;color:#d94b4b}.date{color:#8a97a3;margin-left:auto}")
            .append(".fwd,.reply{font-size:12px;color:#6a7786;margin:2px 0}")
            .append(".text{white-space:normal;word-wrap:break-word;font-size:15px;line-height:1.35}")
            .append(".service{text-align:center;color:#8a97a3;font-size:13px;margin:10px 0;font-style:italic}")
            .append(".media img,.media video{max-width:100%;max-height:420px;border-radius:8px;display:block;margin:6px 0}")
            .append("audio{width:100%}.media img.sticker{max-width:180px;max-height:180px}")
            .append("a.file{display:inline-block;font-size:14px;color:#3a6df0;text-decoration:none;margin:4px 0}")
            .append(".missing{font-size:13px;color:#b04a4a}")
            .append("a[href^=\"#m\"]{color:#3a6df0;text-decoration:none}")
            .append("@media (prefers-color-scheme:dark){body{background:#0f1419;color:#e9eef2}")
            .append(".msg{background:#1c2733;box-shadow:none}.msg.out{background:#2b4d3a}")
            .append(".sub,.fwd,.reply{color:#8a97a3}}")
            .append("</style></head><body><div class=\"chat\">")
        sb.append("<h1>").append(esc(title)).append("</h1>")
        val exported = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        sb.append("<div class=\"sub\">")
            .append(session.collected.size).append(" messages · ")
            .append(esc(LocaleController.getString(R.string.InuChatExportExported)) ?: "exported")
            .append(" ").append(esc(exported)).append(" · entinyGram</div>")
        val timeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        for (m in session.collected.sortedBy { it.date }) {
            if (m.action != null || m is TLRPC.TL_messageService) {
                sb.append("<div class=\"service\">").append(esc(serviceLabel(controller, m))).append("</div>")
                continue
            }
            sb.append("<div class=\"msg").append(if (m.out) " out" else "").append("\" id=\"m")
                .append(m.id).append("\">")
            sb.append("<div class=\"info\">")
            m.from_id?.let { sb.append("<span class=\"from\">").append(esc(ExportPeers.displayName(controller, it))).append("</span>") }
            sb.append("<span class=\"date\">").append(timeFmt.format(Date(m.date * 1000L))).append("</span></div>")
            m.fwd_from?.let { f ->
                val name = f.from_name?.takeIf { it.isNotEmpty() }
                    ?: f.from_id?.let { ExportPeers.displayName(controller, it) }
                    ?: f.post_author
                if (!name.isNullOrEmpty()) {
                    sb.append("<div class=\"fwd\">").append(esc(name)).append("</div>")
                }
            }
            m.reply_to?.reply_to_msg_id?.takeIf { it != 0 }?.let {
                sb.append("<div class=\"reply\"><a href=\"#m").append(it).append("\">&8617;</a></div>")
            }
            if (!m.message.isNullOrEmpty()) {
                sb.append("<div class=\"text\">").append(esc(m.message).replace("\n", "<br>")).append("</div>")
            }
            byMessage[m.id]?.let { sb.append(mediaHtml(it)) }
            sb.append("</div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun mediaHtml(item: ExportItem): String {
        val label = kindLabel(item.kind)
        if (item.overLimit) {
            return "<div class=\"media\"><span class=\"missing\">$label · over size limit</span></div>"
        }
        if (item.file == null) {
            val why = if (item.failed) "download failed" else "not found"
            return "<div class=\"media\"><span class=\"missing\">$label · $why</span></div>"
        }
        val href = item.entryName.split('/').joinToString("/") { esc(it) }
        // entiny: viewers opened via content:// can't resolve sibling files, so images carry an inline fallback
        val fallback = if (item.kind == ExportMediaKind.PHOTO || item.kind == ExportMediaKind.STICKER) inlinePreview(item.file) else null
        val onError = fallback?.let { " data-fb=\"$it\" onerror=\"if(this.dataset.fb){this.src=this.dataset.fb;this.dataset.fb=''}\"" } ?: ""
        if (item.kind == ExportMediaKind.STICKER && fallback != null) {
            return "<div class=\"media\"><img class=\"sticker\" loading=\"lazy\" src=\"$href\" alt=\"$label\"$onError></div>"
        }
        return when (item.kind) {
            ExportMediaKind.PHOTO ->
                "<div class=\"media\"><a href=\"$href\"><img loading=\"lazy\" src=\"$href\" alt=\"$label\"$onError></a></div>"
            ExportMediaKind.VIDEO, ExportMediaKind.ROUND ->
                "<div class=\"media\"><video controls preload=\"none\" src=\"$href\"></video></div>"
            ExportMediaKind.VOICE ->
                "<div class=\"media\"><audio controls preload=\"none\" src=\"$href\"></audio></div>"
            else ->
                "<div class=\"media\"><a class=\"file\" href=\"$href\">$label · ${item.entryName.substringAfterLast('/')}</a></div>"
        }
    }

    private fun inlinePreview(file: File?): String? {
        if (file == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_MAX_SIDE) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val out = ByteArrayOutputStream()
            val alpha = bitmap.hasAlpha()
            bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 70, out)
            bitmap.recycle()
            "data:image/${if (alpha) "png" else "jpeg"};base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            FileLog.e("ChatExportHelper: preview ${file.name}: ${e.message}")
            null
        }
    }

    private fun kindLabel(kind: ExportMediaKind): String = when (kind) {
        ExportMediaKind.PHOTO -> "photo"
        ExportMediaKind.VIDEO -> "video"
        ExportMediaKind.VOICE -> "voice"
        ExportMediaKind.ROUND -> "video message"
        ExportMediaKind.STICKER -> "sticker"
        ExportMediaKind.GIF -> "gif"
        ExportMediaKind.DOCUMENT -> "file"
    }

    private fun serviceLabel(controller: MessagesController, m: TLRPC.Message): String {
        val action = m.action?.javaClass?.simpleName?.removePrefix("TL_") ?: return ""
        return action.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(Locale.getDefault())
    }

    private fun esc(s: String?): String = s
        ?.replace("&", "&amp;")
        ?.replace("<", "&lt;")
        ?.replace(">", "&gt;")
        ?.replace("\"", "&quot;")
        ?: ""
}
