package desu.inugram.helpers.chat

import desu.inugram.helpers.SharePicker
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ChatExportHelper {

    const val FILENAME_SUFFIX = ".entiny-chat.json"
    private const val PAGE_SIZE = 100

    private class Session(val fragment: BaseFragment, val account: Int, val dialogId: Long) {
        val collected = ArrayList<TLRPC.Message>()
        val seenIds = HashSet<Int>()
        var offsetId = 0
        var dialog: AlertDialog? = null
        var aborted = false
        var finished = false
    }

    private val active = HashMap<Long, Session>()

    @JvmStatic
    fun start(fragment: BaseFragment, currentAccount: Int, dialogId: Long) {
        if (active.containsKey(dialogId)) return
        val controller = MessagesController.getInstance(currentAccount)
        if (controller.getInputPeer(dialogId) == null) {
            BulletinFactory.of(fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuChatExportFailed)
            ).show()
            return
        }
        val session = Session(fragment, currentAccount, dialogId)
        active[dialogId] = session
        val context = fragment.parentActivity
        if (context != null) {
            session.dialog = AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuChatExport))
                .setMessage(LocaleController.formatString(R.string.InuChatExportCollected, 0))
                .setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ ->
                    session.aborted = true
                    active.remove(dialogId)
                }
                .create()
                .apply {
                    setCancelable(false)
                    show()
                }
        }
        fetchPage(session)
    }

    private fun fetchPage(session: Session) {
        if (session.aborted || session.finished) return
        val controller = MessagesController.getInstance(session.account)
        val peer = controller.getInputPeer(session.dialogId)
        if (peer == null) {
            finishWithError(session, null)
            return
        }
        val req = TLRPC.TL_messages_getHistory()
        req.peer = peer
        req.offset_id = session.offsetId
        req.limit = PAGE_SIZE
        ConnectionsManager.getInstance(session.account).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                if (session.aborted || session.finished) return@runOnUIThread
                if (error != null || response !is TLRPC.messages_Messages) {
                    FileLog.e("ChatExportHelper: getHistory failed for ${session.dialogId}: ${error?.text}")
                    if (session.collected.isEmpty()) {
                        finishWithError(session, error?.text)
                    } else {
                        writeAndShare(session)
                    }
                    return@runOnUIThread
                }
                controller.putUsers(response.users, false)
                controller.putChats(response.chats, false)
                var minId = Int.MAX_VALUE
                for (message in response.messages) {
                    if (message.id < minId) minId = message.id
                    if (message is TLRPC.TL_messageEmpty) continue
                    if (session.seenIds.add(message.id)) session.collected.add(message)
                }
                session.dialog?.setMessage(
                    LocaleController.formatString(R.string.InuChatExportCollected, session.collected.size)
                )
                if (response.messages.isEmpty() || response.messages.size < PAGE_SIZE || minId == session.offsetId) {
                    writeAndShare(session)
                } else {
                    session.offsetId = minId
                    AndroidUtilities.runOnUIThread({ fetchPage(session) }, 250)
                }
            }
        }
    }

    private fun finishWithError(session: Session, error: String?) {
        session.finished = true
        active.remove(session.dialogId)
        session.dialog?.dismiss()
        val msg = error?.takeIf { it.isNotEmpty() }
            ?: LocaleController.getString(R.string.InuChatExportFailed)
        BulletinFactory.of(session.fragment).createErrorBulletin(msg).show()
    }

    private fun writeAndShare(session: Session) {
        if (session.finished) return
        session.finished = true
        active.remove(session.dialogId)
        session.dialog?.dismiss()
        val controller = MessagesController.getInstance(session.account)
        val title = dialogTitle(controller, session.dialogId)
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeTitle = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { "chat" }
            val file = File(AndroidUtilities.getCacheDir(), "$safeTitle-$date$FILENAME_SUFFIX")
            val err = try {
                file.parentFile?.mkdirs()
                file.writeText(buildJson(session, controller, title), Charsets.UTF_8)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            AndroidUtilities.runOnUIThread {
                if (err != null) {
                    BulletinFactory.of(session.fragment).createErrorBulletin(err).show()
                    return@runOnUIThread
                }
                openSharePicker(session, file)
            }
        }
    }

    private fun buildJson(session: Session, controller: MessagesController, title: String): String {
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
            o.put("from_id", peerId(it))
            o.put("from_name", displayName(controller, it))
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
                o.put("forwarded_from_id", peerId(it))
                o.put("forwarded_from_name", displayName(controller, it))
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

    private fun peerId(peer: TLRPC.Peer): Long = when (peer) {
        is TLRPC.TL_peerUser -> peer.user_id
        is TLRPC.TL_peerChat -> peer.chat_id
        is TLRPC.TL_peerChannel -> peer.channel_id
        else -> 0
    }

    private fun displayName(controller: MessagesController, peer: TLRPC.Peer): String {
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

    private fun dialogTitle(controller: MessagesController, dialogId: Long): String {
        if (dialogId > 0) {
            val user = controller.getUser(dialogId)
            if (user != null) return UserObject.getUserName(user)
        } else {
            controller.getChat(-dialogId)?.title?.let { return it }
        }
        return dialogId.toString()
    }

    private fun openSharePicker(session: Session, file: File) {
        val activity = session.fragment.parentActivity as? LaunchActivity
        if (activity == null) {
            BulletinFactory.of(session.fragment).createSimpleBulletin(R.raw.chats_infotip, file.absolutePath).show()
            return
        }
        SharePicker.shareFile(activity, file, "application/json")
    }
}
