package desu.inugram.helpers.security

import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_stories
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Ghost mode ("invisible mode"), modeled after AyuGram's ghost mode.
 *
 * Hooks into [ConnectionsManager.sendRequestInternal] — the single choke point for every
 * outgoing MTProto request — and silently drops or rewrites the requests that would leak
 * activity to the other side:
 *  - `messages.*read*` → no read receipts (blue checkmarks)
 *  - `messages.readMessageContents` → no voice / round played indicator
 *  - `stories.readStories` / `stories.incrementStoryViews` → no story views
 *  - `messages.setTyping` / `messages.setEncryptedTyping` → no typing / recording / upload states
 *  - `account.updateStatus` → rewritten to offline (hide online), or followed by an offline
 *    packet shortly after going online (offline-after-online)
 *
 * Supports per-dialog whitelisting so specific conversations can bypass ghost mode.
 */
object GhostHelper {

    private val manualReadRequests: MutableSet<TLObject> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmStatic
    fun isGhostActive(): Boolean = InuConfig.GHOST_MODE.value ||
        InuConfig.GHOST_HIDE_READ.value ||
        InuConfig.GHOST_HIDE_VOICE_READ.value ||
        InuConfig.GHOST_HIDE_STORY_READ.value ||
        InuConfig.GHOST_HIDE_ONLINE.value ||
        InuConfig.GHOST_HIDE_TYPING.value ||
        InuConfig.GHOST_OFFLINE_AFTER_ONLINE.value

    @JvmStatic
    fun isUnreaderActive(): Boolean = InuConfig.GHOST_HIDE_READ.value

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
    fun isGhostActiveForDialog(dialogId: Long): Boolean {
        if (isDialogWhitelisted(dialogId)) return false
        return isGhostActive()
    }

    /**
     * Choke-point filter for outgoing MTProto requests.
     *
     * @return `true` when the request was consumed by ghost mode and stock must not send it.
     */
    @JvmStatic
    fun processSendRequest(
        request: TLObject,
        account: Int,
        onComplete: RequestDelegate?,
        onCompleteTimestamp: RequestDelegateTimestamp?,
    ): Boolean {
        if (manualReadRequests.remove(request)) {
            return false
        }
        if (!isGhostActive()) return false

        return when (request) {
            is TLRPC.TL_messages_setTyping -> {
                val dialogId = request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                if (dialogId != 0L && isDialogWhitelisted(dialogId)) return false
                if (InuConfig.GHOST_HIDE_TYPING.value) {
                    if (onComplete != null) onComplete.run(null, null)
                    else onCompleteTimestamp?.run(null, null, 0L)
                    true
                } else {
                    false
                }
            }
            is TLRPC.TL_messages_setEncryptedTyping -> {
                val dialogId = request.peer?.chat_id?.toLong() ?: 0L
                if (dialogId != 0L && isDialogWhitelisted(dialogId)) return false
                if (InuConfig.GHOST_HIDE_TYPING.value) {
                    if (onComplete != null) onComplete.run(null, null)
                    else onCompleteTimestamp?.run(null, null, 0L)
                    true
                } else {
                    false
                }
            }
            is TLRPC.TL_messages_readHistory,
            is TLRPC.TL_messages_readEncryptedHistory,
            is TLRPC.TL_messages_readDiscussion,
            is TLRPC.TL_messages_readSavedHistory,
            is TLRPC.TL_messages_markDialogUnread -> {
                val dialogId = when (request) {
                    is TLRPC.TL_messages_readHistory -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                    is TLRPC.TL_messages_readDiscussion -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                    else -> 0L
                }
                if (dialogId != 0L && isDialogWhitelisted(dialogId)) return false
                InuConfig.GHOST_HIDE_READ.value
            }
            is TLRPC.TL_channels_readHistory -> {
                val channelId = request.channel?.channel_id?.toLong() ?: 0L
                val dialogId = if (channelId != 0L) -channelId else 0L
                if (dialogId != 0L && isDialogWhitelisted(dialogId)) return false
                InuConfig.GHOST_HIDE_READ.value
            }
            is TLRPC.TL_messages_readMessageContents,
            is TLRPC.TL_channels_readMessageContents -> {
                InuConfig.GHOST_HIDE_VOICE_READ.value || InuConfig.GHOST_HIDE_READ.value
            }
            is TL_stories.TL_stories_readStories,
            is TL_stories.TL_stories_incrementStoryViews -> InuConfig.GHOST_HIDE_STORY_READ.value
            is TL_account.updateStatus -> {
                if (InuConfig.GHOST_HIDE_ONLINE.value) {
                    request.offline = true
                } else if (InuConfig.GHOST_OFFLINE_AFTER_ONLINE.value && !request.offline) {
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
                if (InuConfig.GHOST_READ_ON_SEND.value && InuConfig.GHOST_HIDE_READ.value) {
                    val dialogId = when (request) {
                        is TLRPC.TL_messages_sendMessage -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_sendMedia -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_sendMultiMedia -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_sendInlineBotResult -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_sendReaction -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_forwardMessages -> request.to_peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_sendVote -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        is TLRPC.TL_messages_sendQuickReplyMessages -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
                        else -> 0L
                    }
                    if (dialogId != 0L && !isDialogWhitelisted(dialogId)) {
                        AndroidUtilities.runOnUIThread {
                            markDialogAsRead(account, dialogId)
                        }
                    }
                }
                false
            }
            else -> false
        }
    }

    /**
     * Manually sends read request to Telegram servers bypassing Ghost Mode filter.
     */
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

        manualReadRequests.add(req)
        try {
            ConnectionsManager.getInstance(account).sendRequest(req) { _, error ->
                manualReadRequests.remove(req)
                if (error == null) {
                    AndroidUtilities.runOnUIThread {
                        controller.markDialogAsRead(dialogId, effectiveMaxId, 0, 0, false, 0, 0, true, 0)
                    }
                }
            }
        } catch (_: Exception) {
            manualReadRequests.remove(req)
        }
    }

    /**
     * Re-asserts the desired presence right after a settings change:
     *  - ghost on + hide-online → send offline immediately (drop the stale "online")
     *  - ghost off → send online to restore stock presence
     */
    @JvmStatic
    fun syncPresence(account: Int) {
        if (isGhostActive()) {
            if (InuConfig.GHOST_HIDE_ONLINE.value) sendStatus(account, offline = true)
        } else {
            sendStatus(account, offline = false)
        }
    }

    /**
     * Sends `account.updateStatus(offline = ...)`. Safe to call even while ghost mode is on:
     * the request re-enters [processSendRequest] and is handled idempotently.
     */
    @JvmStatic
    fun sendStatus(account: Int, offline: Boolean) {
        val req = TL_account.updateStatus()
        req.offline = offline
        try {
            ConnectionsManager.getInstance(account).sendRequest(req) { _, _ -> }
        } catch (_: Exception) {
        }
    }

    private fun scheduleOffline(account: Int) {
        Utilities.stageQueue.postRunnable({
            if (isGhostActive() && !InuConfig.GHOST_HIDE_ONLINE.value && InuConfig.GHOST_OFFLINE_AFTER_ONLINE.value) {
                sendStatus(account, offline = true)
            }
        }, 4000L)
    }
}
