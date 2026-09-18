package desu.inugram.helpers.security

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class SuppressKind {
    READ,
    TYPING,
    ONLINE,
    VOICE_READ,
    STORY_READ,
}

object GhostHelper {

    private val temporarilyAllowedDialogs: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val offlineRunnables: ConcurrentHashMap<Int, Runnable> = ConcurrentHashMap()

    @JvmStatic
    fun isGhostActive(): Boolean = InuConfig.GHOST_MODE_ENABLED.value

    @JvmStatic
    fun setGhostMode(enabled: Boolean) {
        InuConfig.GHOST_MODE_ENABLED.value = enabled
        syncPresence(UserConfig.selectedAccount)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
    }

    @JvmStatic
    fun toggleGhostMode(): Boolean {
        val newState = !InuConfig.GHOST_MODE_ENABLED.value
        setGhostMode(newState)
        return newState
    }

    @JvmStatic
    fun applyChatTitleGhost(parentFragment: ChatActivity?, titleTextView: SimpleTextView?) {
        if (titleTextView == null) return
        val dialogId = parentFragment?.dialogId ?: 0L
        if (dialogId != 0L && isGhostActiveForDialog(dialogId)) {
            val ghost = ContextCompat.getDrawable(titleTextView.context, R.drawable.inu_ghost_filled)?.mutate()
            if (ghost != null) {
                ghost.setBounds(0, 0, AndroidUtilities.dp(15f), AndroidUtilities.dp(15f))
                val color = Theme.getColor(Theme.key_actionBarDefaultSubtitle, parentFragment?.resourceProvider)
                ghost.setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN))
            }
            titleTextView.setLeftDrawable(ghost)
        } else {
            titleTextView.setLeftDrawable(null)
        }
    }

    @JvmStatic
    fun isDialogWhitelisted(dialogId: Long): Boolean {
        return InuConfig.GHOST_WHITELIST_DIALOGS.value.contains(dialogId.toString())
    }

    @JvmStatic
    fun toggleDialogWhitelist(dialogId: Long): Boolean {
        val current = InuConfig.GHOST_WHITELIST_DIALOGS.value.toMutableSet()
        val key = dialogId.toString()
        val isNowWhitelisted = if (current.contains(key)) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        InuConfig.GHOST_WHITELIST_DIALOGS.value = current
        return isNowWhitelisted
    }

    @JvmStatic
    fun getWhitelistedDialogs(account: Int): List<Long> {
        val current = InuConfig.GHOST_WHITELIST_DIALOGS.value
        val controller = MessagesController.getInstance(account)
        val valid = HashSet<String>()
        val result = ArrayList<Long>()
        for (key in current) {
            val id = key.toLongOrNull() ?: continue
            if (controller?.dialogs_dict?.get(id) != null) {
                valid.add(key)
                result.add(id)
            }
        }
        if (valid.size != current.size) {
            InuConfig.GHOST_WHITELIST_DIALOGS.value = valid
        }
        return result
    }

    @JvmStatic
    fun isGhostActiveForDialog(dialogId: Long): Boolean {
        if (dialogId != 0L && isDialogWhitelisted(dialogId)) return false
        return isGhostActive()
    }

    @JvmStatic
    fun shouldSuppress(dialogId: Long, kind: SuppressKind): Boolean {
        if (!InuConfig.GHOST_MODE_ENABLED.value) {
            return false
        }
        if (dialogId != 0L && temporarilyAllowedDialogs.contains(dialogId)) {
            return false
        }
        if (dialogId != 0L && isDialogWhitelisted(dialogId)) {
            return false
        }
        val suppress = when (kind) {
            SuppressKind.READ -> InuConfig.GHOST_HIDE_READ.value
            SuppressKind.TYPING -> InuConfig.GHOST_HIDE_TYPING.value
            SuppressKind.ONLINE -> InuConfig.GHOST_PRESENCE_MODE.value == InuConfig.GhostPresenceModeItem.HIDDEN
            SuppressKind.VOICE_READ -> InuConfig.GHOST_HIDE_VOICE_READ.value || InuConfig.GHOST_HIDE_READ.value
            SuppressKind.STORY_READ -> InuConfig.GHOST_HIDE_STORY_READ.value
        }
        if (suppress) {
            val dump = "suppress dialogId=$dialogId kind=$kind read=${InuConfig.GHOST_HIDE_READ.value} " +
                "voiceRead=${InuConfig.GHOST_HIDE_VOICE_READ.value} storyRead=${InuConfig.GHOST_HIDE_STORY_READ.value} " +
                "typing=${InuConfig.GHOST_HIDE_TYPING.value} presence=${InuConfig.GHOST_PRESENCE_MODE.value}"
            android.util.Log.d("GhostMode", dump)
            org.telegram.messenger.FileLog.d("GhostMode: $dump")
        }
        return suppress
    }

    @JvmStatic
    fun shouldSuppressRead(dialogId: Long): Boolean = shouldSuppress(dialogId, SuppressKind.READ)

    @JvmStatic
    fun shouldSuppressLocalRead(dialogId: Long): Boolean {
        return !InuConfig.GHOST_MARK_READ_LOCALLY.value && shouldSuppress(dialogId, SuppressKind.READ)
    }

    @JvmStatic
    fun processSendRequest(
        request: TLObject,
        account: Int,
        onComplete: RequestDelegate?,
        onCompleteTimestamp: RequestDelegateTimestamp?,
    ): Boolean {
        val dialogId = extractDialogId(request)

        return when (request) {
            is TLRPC.TL_messages_setTyping,
            is TLRPC.TL_messages_setEncryptedTyping -> {
                if (shouldSuppress(dialogId, SuppressKind.TYPING)) {
                    if (onComplete != null) onComplete.run(null, null)
                    else onCompleteTimestamp?.run(null, null, 0L)
                    true
                } else {
                    false
                }
            }
            is TLRPC.TL_channels_readHistory,
            is TLRPC.TL_messages_readHistory,
            is TLRPC.TL_messages_readEncryptedHistory,
            is TLRPC.TL_messages_readDiscussion,
            is TLRPC.TL_messages_readSavedHistory,
            is TLRPC.TL_messages_markDialogUnread -> {
                shouldSuppress(dialogId, SuppressKind.READ)
            }
            is TLRPC.TL_messages_readMessageContents,
            is TLRPC.TL_channels_readMessageContents -> {
                shouldSuppress(dialogId, SuppressKind.VOICE_READ)
            }
            is TL_stories.TL_stories_readStories,
            is TL_stories.TL_stories_incrementStoryViews -> {
                shouldSuppress(dialogId, SuppressKind.STORY_READ)
            }
            is TL_account.updateStatus -> {
                if (shouldSuppress(0L, SuppressKind.ONLINE)) {
                    request.offline = true
                }
                if (autoOfflineEnabled() && !request.offline) {
                    scheduleOffline(account)
                }
                false
            }
            is TLRPC.TL_messages_sendMessage,
            is TLRPC.TL_messages_sendMedia,
            is TLRPC.TL_messages_sendMultiMedia,
            is TLRPC.TL_messages_sendInlineBotResult,
            is TLRPC.TL_messages_sendReaction,
            is TLRPC.TL_messages_forwardMessages,
            is TLRPC.TL_messages_sendVote,
            is TLRPC.TL_messages_sendQuickReplyMessages -> {
                if (dialogId != 0L && InuConfig.GHOST_READ_ON_SEND.value && shouldSuppress(dialogId, SuppressKind.READ)) {
                    AndroidUtilities.runOnUIThread {
                        markDialogAsRead(account, dialogId)
                    }
                }
                // entiny: server flips account online upon sending messages; re-assert offline after send round-trip
                if (autoOfflineEnabled()) {
                    scheduleOffline(account)
                }
                false
            }
            else -> false
        }
    }

    private fun extractDialogId(request: TLObject): Long {
        return when (request) {
            is TLRPC.TL_messages_setTyping -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_setEncryptedTyping -> request.peer?.chat_id?.toLong() ?: 0L
            is TLRPC.TL_messages_readHistory -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_readDiscussion -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_channels_readHistory -> {
                val channelId = request.channel?.channel_id ?: 0L
                if (channelId != 0L) -channelId else 0L
            }
            is TLRPC.TL_channels_readMessageContents -> {
                val channelId = request.channel?.channel_id ?: 0L
                if (channelId != 0L) -channelId else 0L
            }
            is TLRPC.TL_messages_sendMessage -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendMedia -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendMultiMedia -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendInlineBotResult -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendReaction -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_forwardMessages -> request.to_peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendVote -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendQuickReplyMessages -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TL_stories.TL_stories_readStories -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TL_stories.TL_stories_incrementStoryViews -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            else -> 0L
        }
    }

    @JvmStatic
    fun markDialogAsRead(account: Int, dialogId: Long, maxId: Int = 0) {
        val controller = MessagesController.getInstance(account) ?: return
        val effectiveMaxId = if (maxId > 0) maxId else {
            val dialog = controller.dialogs_dict.get(dialogId)
            dialog?.top_message ?: 0
        }

        val chat = if (DialogObject.isChatDialog(dialogId)) controller.getChat(-dialogId) else null
        val req: TLObject = if (chat != null && ChatObject.isChannel(chat)) {
            val inputChannel = controller.getInputChannel(-dialogId) ?: return
            TLRPC.TL_channels_readHistory().apply {
                channel = inputChannel
                max_id = effectiveMaxId
            }
        } else {
            val inputPeer = controller.getInputPeer(dialogId) ?: return
            TLRPC.TL_messages_readHistory().apply {
                peer = inputPeer
                max_id = effectiveMaxId
            }
        }

        temporarilyAllowedDialogs.add(dialogId)
        try {
            ConnectionsManager.getInstance(account).sendRequest(req) { _, error ->
                try {
                    if (error == null) {
                        AndroidUtilities.runOnUIThread {
                            controller.markDialogAsRead(dialogId, effectiveMaxId, 0, 0, false, 0, 0, true, 0)
                        }
                    }
                } finally {
                    temporarilyAllowedDialogs.remove(dialogId)
                }
            }
        } catch (_: Exception) {
            temporarilyAllowedDialogs.remove(dialogId)
        }
    }

    // entiny: do not touch MessagesController.ignoreSetOnline because ChatActivity uses it to gate local read marking
    @JvmStatic
    fun syncPresence(account: Int) {
        val hide = InuConfig.GHOST_MODE_ENABLED.value &&
            InuConfig.GHOST_PRESENCE_MODE.value != InuConfig.GhostPresenceModeItem.NORMAL
        sendStatus(account, offline = hide)
    }

    @JvmStatic
    fun sendStatus(account: Int, offline: Boolean) {
        val req = TL_account.updateStatus()
        req.offline = offline
        try {
            ConnectionsManager.getInstance(account).sendRequest(req) { _, _ -> }
        } catch (_: Exception) {
        }
    }

    private fun autoOfflineEnabled(): Boolean =
        InuConfig.GHOST_MODE_ENABLED.value && InuConfig.GHOST_AUTO_OFFLINE.value

    private fun scheduleOffline(account: Int) {
        offlineRunnables.remove(account)?.let { Utilities.stageQueue.cancelRunnable(it) }
        val runnable = Runnable {
            offlineRunnables.remove(account)
            if (autoOfflineEnabled()) {
                sendStatus(account, offline = true)
            }
        }
        offlineRunnables[account] = runnable
        Utilities.stageQueue.postRunnable(runnable, OFFLINE_REASSERT_DELAY_MS)
    }

    private const val OFFLINE_REASSERT_DELAY_MS = 2500L
}
