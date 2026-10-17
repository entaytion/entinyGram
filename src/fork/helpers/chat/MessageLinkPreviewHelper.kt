package desu.inugram.helpers.chat

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.TopicsController
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_forum
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.LaunchActivity

object MessageLinkPreviewHelper {
    private const val DISMISS_DELAY = 150L
    private const val PROGRESS_DELAY = 300L

    private class Target(
        val username: String?,
        val channelId: Long,
        val messageId: Int,
    )

    private class Progress(context: Context) {
        private val dialog = AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER)
        private var cancelled = false

        init {
            dialog.setOnCancelListener { cancelled = true }
            dialog.showDelayed(PROGRESS_DELAY)
        }

        fun finish(): Boolean {
            dialog.dismiss()
            return !cancelled
        }
    }

    @JvmStatic
    fun addMenuItem(fragment: ChatActivity, options: ItemOptions, url: String) {
        val target = parseLink(url) ?: return
        options.add(R.drawable.msg_message, LocaleController.getString(R.string.InuPreviewMessage)) {
            AndroidUtilities.runOnUIThread({ openPreview(fragment, target) }, DISMISS_DELAY)
        }
    }

    private fun openPreview(fragment: ChatActivity, target: Target) {
        val context = fragment.context ?: return
        val controller = fragment.messagesController

        if (target.username != null) {
            val dialogId = when (val peer = controller.getUserOrChat(target.username)) {
                is TLRPC.User -> peer.id
                is TLRPC.Chat -> -peer.id
                else -> 0L
            }
            if (dialogId != 0L) {
                present(fragment, target, dialogId)
                return
            }
            val progress = Progress(context)
            controller.userNameResolver.resolve(target.username) { resolved ->
                if (!progress.finish()) return@resolve
                if (resolved == null || resolved == 0L || resolved == Long.MAX_VALUE) {
                    showNotFound(fragment)
                } else {
                    present(fragment, target, resolved)
                }
            }
            return
        }

        if (controller.getChat(target.channelId) != null) {
            present(fragment, target, -target.channelId)
            return
        }
        loadChannel(fragment, target, Progress(context))
    }

    private fun loadChannel(fragment: ChatActivity, target: Target, progress: Progress) {
        val req = TLRPC.TL_channels_getChannels()
        req.id.add(TLRPC.TL_inputChannel().apply { channel_id = target.channelId })
        fragment.connectionsManager.sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                if (!progress.finish()) return@runOnUIThread
                val chats = (response as? TLRPC.TL_messages_chats)?.chats
                if (chats.isNullOrEmpty()) {
                    showNotFound(fragment)
                    return@runOnUIThread
                }
                fragment.messagesController.putChats(chats, false)
                present(fragment, target, -target.channelId)
            }
        }
    }

    private fun present(fragment: ChatActivity, target: Target, dialogId: Long) {
        val context = fragment.context ?: return
        val controller = fragment.messagesController

        val args = Bundle()
        if (dialogId < 0) {
            args.putLong("chat_id", -dialogId)
        } else {
            args.putLong("user_id", dialogId)
        }
        args.putInt("message_id", target.messageId.coerceAtLeast(1))
        if (!controller.checkCanOpenChat(args, fragment)) return

        val chat = if (dialogId < 0) controller.getChat(-dialogId) else null
        if (chat != null && chat.forum) {
            loadForumMessage(fragment, target, chat, Progress(context))
            return
        }
        showPreview(fragment, ChatActivity(args))
    }

    private fun loadForumMessage(fragment: ChatActivity, target: Target, chat: TLRPC.Chat, progress: Progress) {
        val req = TLRPC.TL_channels_getMessages()
        req.channel = MessagesController.getInputChannel(chat)
        req.id.add(target.messageId)
        fragment.connectionsManager.sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                val message = (response as? TLRPC.messages_Messages)?.messages?.find { it.id == target.messageId }
                if (message == null || message is TLRPC.TL_messageEmpty) {
                    if (progress.finish()) showNotFound(fragment)
                    return@runOnUIThread
                }
                val topicId = MessageObject.getTopicId(fragment.currentAccount, message, true)
                val topic = fragment.messagesController.topicsController.findTopic(chat.id, topicId)
                if (topic != null) {
                    if (progress.finish()) presentTopic(fragment, target, chat, topic)
                } else {
                    loadForumTopic(fragment, target, chat, topicId, progress)
                }
            }
        }
    }

    private fun loadForumTopic(fragment: ChatActivity, target: Target, chat: TLRPC.Chat, topicId: Long, progress: Progress) {
        val req = TL_forum.TL_messages_getForumTopicsByID()
        req.peer = MessagesController.getInputPeer(chat)
        req.topics.add(topicId.toInt())
        fragment.connectionsManager.sendRequest(req) { response, _ ->
            AndroidUtilities.runOnUIThread {
                if (!progress.finish()) return@runOnUIThread
                val controller = fragment.messagesController
                if (response is TLRPC.TL_messages_forumTopics) {
                    val messages = LongSparseArray<TLRPC.Message>()
                    response.messages.forEach { messages.put(it.id.toLong(), it) }
                    controller.putUsers(response.users, false)
                    controller.putChats(response.chats, false)
                    controller.topicsController.processTopics(chat.id, response.topics, messages, false, TopicsController.LOAD_TYPE_LOAD_UNKNOWN, -1)
                }
                val topic = controller.topicsController.findTopic(chat.id, topicId)
                if (topic == null) {
                    showNotFound(fragment)
                } else {
                    presentTopic(fragment, target, chat, topic)
                }
            }
        }
    }

    private fun presentTopic(fragment: ChatActivity, target: Target, chat: TLRPC.Chat, topic: TLRPC.TL_forumTopic) {
        val messageId = if (target.messageId == topic.id) 0 else target.messageId
        val preview = ForumUtilities.getChatActivityForTopic(fragment, chat.id, topic, messageId, Bundle()) ?: return
        showPreview(fragment, preview)
    }

    private fun showPreview(fragment: ChatActivity, preview: ChatActivity) {
        val context = fragment.context ?: return
        if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            fragment.presentFragmentAsPreview(preview)
        } else {
            fragment.presentFragmentAsPreviewWithMenu(preview, buildPreviewMenu(fragment, context))
            preview.allowExpandPreviewByClick = !InuConfig.INTERACTIVE_CHAT_PREVIEW.value
        }
        fragment.checkShowBlur(true)
    }

    private fun buildPreviewMenu(fragment: ChatActivity, context: Context): ActionBarPopupWindow.ActionBarPopupWindowLayout {
        val menu = ActionBarPopupWindow.ActionBarPopupWindowLayout(
            context,
            R.drawable.popup_fixed_alert4,
            fragment.resourceProvider,
            ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_SHOWN_FROM_BOTTOM,
        )
        menu.setBackgroundColor(fragment.getThemedColor(Theme.key_actionBarDefaultSubmenuBackground))

        val open = ActionBarMenuSubItem(context, false, false)
        open.setTextAndIcon(LocaleController.getString(R.string.Open), R.drawable.msg_openin)
        open.minimumWidth = 160
        open.setOnClickListener { fragment.getParentLayout()?.expandPreviewFragment() }
        menu.addView(open)

        return menu
    }

    private fun showNotFound(fragment: ChatActivity) {
        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.LinkNotFound)).show()
    }

    private fun parseLink(url: String): Target? {
        if (url.startsWith("#") || url.startsWith("$") || url.startsWith("mailto:") || url.startsWith("video?")) return null

        val uri = Uri.parse(if (url.contains("://")) url else "https://$url")
        if (uri.scheme == "tg") return parseInternalLink(uri)

        val host = AndroidUtilities.getHostAuthority(uri)?.lowercase() ?: return null
        val segments = ArrayList(uri.pathSegments)
        val prefix = LaunchActivity.PREFIX_T_ME_PATTERN.matcher(host)
        if (prefix.find()) {
            segments.add(0, prefix.group(1))
        } else if (host != "t.me" && host != "telegram.me" && host != "telegram.dog") {
            return null
        }

        if (uri.getQueryParameter("comment") != null || uri.getQueryParameter("thread") != null) return null
        if (segments.firstOrNull() == "s") segments.removeAt(0)

        if (segments.firstOrNull() == "c") {
            if (segments.size != 3) return null
            val channelId = segments[1].toLongOrNull() ?: return null
            val messageId = segments[2].toIntOrNull() ?: return null
            if (channelId <= 0 || messageId <= 0) return null
            return Target(null, channelId, messageId)
        }

        if (segments.size != 2) return null
        val messageId = segments[1].toIntOrNull() ?: return null
        if (messageId <= 0) return null
        return Target(segments[0], 0, messageId)
    }

    private fun parseInternalLink(uri: Uri): Target? {
        if (uri.getQueryParameter("comment") != null || uri.getQueryParameter("thread") != null) return null
        val messageId = uri.getQueryParameter("post")?.toIntOrNull() ?: return null
        if (messageId <= 0) return null
        return when (uri.host?.lowercase()) {
            "resolve" -> Target(uri.getQueryParameter("domain") ?: return null, 0, messageId)
            "privatepost" -> {
                val channelId = uri.getQueryParameter("channel")?.toLongOrNull() ?: return null
                if (channelId <= 0) return null
                Target(null, channelId, messageId)
            }
            else -> null
        }
    }
}
