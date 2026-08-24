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

/**
 * Ghost mode ("invisible mode"), modeled after AyuGram/NagramX's ghost mode.
 *
 * No stored master flag. Two computed views over the same sub-toggles:
 *  - [isGhostActive] — OR of the sub-toggles: "any suppression is on". Drives display-only
 *    indicators (chat-title ghost icon, Invisible status label) so partial setups still show up.
 *  - [isFullGhost] — AND over every non-locked component being in its ghost state. This is
 *    exactly AyuGram's `AyuConfig.isGhostModeActive()` / exteraless's locked-pair variant and
 *    is what the drawer/burger quick toggle flips via [setGhostMode], which skips components
 *    locked through [InuConfig.GHOST_LOCK_*].
 *
 * [shouldSuppress] reads each sub-toggle directly — no master gate — avoiding the
 * desync a stored master flag creates when multiple UI entry points
 * (drawer icon, settings page, presence picker) can all write to it independently.
 *
 * Unified single source of truth for both network packet filtering
 * ([ConnectionsManager.sendRequestInternal]) and local UI/DB suppression
 * ([MessagesController.markDialogAsRead]).
 */
object GhostHelper {

    private val temporarilyAllowedDialogs: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private var offlineRunnable: Runnable? = null

    /** True if any suppression behavior is currently enabled. Display-only — never gates [shouldSuppress]. */
    @JvmStatic
    fun isGhostActive(): Boolean =
        InuConfig.GHOST_HIDE_READ.value ||
            InuConfig.GHOST_HIDE_VOICE_READ.value ||
            InuConfig.GHOST_HIDE_STORY_READ.value ||
            InuConfig.GHOST_HIDE_TYPING.value ||
            InuConfig.GHOST_PRESENCE_MODE.value != InuConfig.GhostPresenceModeItem.NORMAL

    /**
     * True if every non-locked component is in its ghost state — the state represented
     * by the quick toggle (`AyuConfig.isGhostModeActive()` / NagramX locked pairs).
     * Locked components are skipped entirely, like exteraless's `ghostToggleItems`.
     */
    @JvmStatic
    fun isFullGhost(): Boolean {
        if (!InuConfig.GHOST_LOCK_HIDE_READ.value && !InuConfig.GHOST_HIDE_READ.value) return false
        if (!InuConfig.GHOST_LOCK_HIDE_VOICE_READ.value && !InuConfig.GHOST_HIDE_VOICE_READ.value) return false
        if (!InuConfig.GHOST_LOCK_HIDE_STORY_READ.value && !InuConfig.GHOST_HIDE_STORY_READ.value) return false
        if (!InuConfig.GHOST_LOCK_HIDE_TYPING.value && !InuConfig.GHOST_HIDE_TYPING.value) return false
        if (!InuConfig.GHOST_LOCK_PRESENCE.value &&
            InuConfig.GHOST_PRESENCE_MODE.value != InuConfig.GhostPresenceModeItem.HIDDEN
        ) return false
        return true
    }

    /** Mass-sets the unlocked sub-toggles together (mirrors AyuGram/NagramX `setGhostMode`; locked ones keep their state). */
    @JvmStatic
    fun setGhostMode(enabled: Boolean) {
        if (!InuConfig.GHOST_LOCK_HIDE_READ.value) InuConfig.GHOST_HIDE_READ.value = enabled
        if (!InuConfig.GHOST_LOCK_HIDE_VOICE_READ.value) InuConfig.GHOST_HIDE_VOICE_READ.value = enabled
        if (!InuConfig.GHOST_LOCK_HIDE_STORY_READ.value) InuConfig.GHOST_HIDE_STORY_READ.value = enabled
        if (!InuConfig.GHOST_LOCK_HIDE_TYPING.value) InuConfig.GHOST_HIDE_TYPING.value = enabled
        if (!InuConfig.GHOST_LOCK_PRESENCE.value) {
            InuConfig.GHOST_PRESENCE_MODE.value =
                if (enabled) InuConfig.GhostPresenceModeItem.HIDDEN else InuConfig.GhostPresenceModeItem.NORMAL
        }
        syncPresence(UserConfig.selectedAccount)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
    }

    @JvmStatic
    fun toggleGhostMode(): Boolean {
        val newState = !isFullGhost()
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

    /**
     * All whitelisted dialogs still known to this account, pruning stale ids (deleted dialogs)
     * lazily — avoids the whitelist growing unboundedly with dialogs that no longer exist.
     */
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

    /**
     * Single source of truth for checking if an action should be suppressed by Ghost Mode.
     * Reads each sub-toggle directly — no master-flag gate (see class doc).
     */
    @JvmStatic
    fun shouldSuppress(dialogId: Long, kind: SuppressKind): Boolean {
        if (dialogId != 0L && temporarilyAllowedDialogs.contains(dialogId)) {
            return false
        }
        if (dialogId != 0L && isDialogWhitelisted(dialogId)) {
            return false
        }
        return when (kind) {
            SuppressKind.READ -> InuConfig.GHOST_HIDE_READ.value
            SuppressKind.TYPING -> InuConfig.GHOST_HIDE_TYPING.value
            SuppressKind.ONLINE -> InuConfig.GHOST_PRESENCE_MODE.value == InuConfig.GhostPresenceModeItem.HIDDEN
            SuppressKind.VOICE_READ -> InuConfig.GHOST_HIDE_VOICE_READ.value || InuConfig.GHOST_HIDE_READ.value
            SuppressKind.STORY_READ -> InuConfig.GHOST_HIDE_STORY_READ.value
        }
    }

    @JvmStatic
    fun shouldSuppressRead(dialogId: Long): Boolean = shouldSuppress(dialogId, SuppressKind.READ)

    @JvmStatic
    fun shouldSuppressLocalRead(dialogId: Long): Boolean {
        if (!InuConfig.GHOST_MARK_READ_LOCALLY.value && shouldSuppress(dialogId, SuppressKind.READ)) {
            return true
        }
        return false
    }

    /**
     * Choke-point filter for outgoing MTProto requests in ConnectionsManager.sendRequestInternal.
     */
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
            is TLRPC.TL_messages_readHistory,
            is TLRPC.TL_channels_readHistory,
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
                } else if (InuConfig.GHOST_PRESENCE_MODE.value == InuConfig.GhostPresenceModeItem.DELAYED && !request.offline) {
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

    /**
     * Whether stock's "reset ignoreSetOnline on pause" (LaunchActivity.onPause) should be
     * skipped — Ghost Mode's hidden/delayed presence relies on ignoreSetOnline staying true
     * across background/foreground cycles, otherwise every resume silently re-enables the
     * stock auto-online logic and undoes the "always hidden" setting.
     */
    @JvmStatic
    fun shouldKeepIgnoringOnline(): Boolean {
        return InuConfig.GHOST_PRESENCE_MODE.value != InuConfig.GhostPresenceModeItem.NORMAL
    }

    /**
     * Re-asserts the desired presence right after a settings change:
     *  - hide/delay presence → send offline immediately (drop the stale "online")
     *  - normal presence → send online to restore stock presence
     */
    @JvmStatic
    fun syncPresence(account: Int) {
        val controller = MessagesController.getInstance(account)
        if (InuConfig.GHOST_PRESENCE_MODE.value != InuConfig.GhostPresenceModeItem.NORMAL) {
            controller?.ignoreSetOnline = true
            sendStatus(account, offline = true)
        } else {
            controller?.ignoreSetOnline = false
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
        offlineRunnable?.let { Utilities.stageQueue.cancelRunnable(it) }
        val runnable = Runnable {
            if (InuConfig.GHOST_PRESENCE_MODE.value == InuConfig.GhostPresenceModeItem.DELAYED) {
                sendStatus(account, offline = true)
            }
        }
        offlineRunnable = runnable
        Utilities.stageQueue.postRunnable(runnable, 1500L)
    }
}
