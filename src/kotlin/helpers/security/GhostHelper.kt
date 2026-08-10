package desu.inugram.helpers.security

import desu.inugram.InuConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_stories

/**
 * Ghost mode ("invisible mode"), modeled after AyuGram's ghost mode.
 *
 * Hooks into [ConnectionsManager.sendRequestInternal] — the single choke point for every
 * outgoing MTProto request — and silently drops or rewrites the requests that would leak
 * activity to the other side:
 *  - `messages.*read*` → no read receipts (blue checkmarks)
 *  - `stories.readStories` / `stories.incrementStoryViews` → no story views
 *  - `messages.setTyping` / `messages.setEncryptedTyping` → no typing / recording / upload states
 *  - `account.updateStatus` → rewritten to offline (hide online), or followed by an offline
 *    packet shortly after going online (offline-after-online)
 *
 * Everything is gated behind [InuConfig.GHOST_MODE]; while it is off, requests pass through
 * untouched, so stock behaviour is unchanged.
 */
object GhostHelper {

    @JvmStatic
    fun isGhostActive(): Boolean = InuConfig.GHOST_MODE.value

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
        if (!isGhostActive()) return false
        return when (request) {
            is TLRPC.TL_messages_setTyping, is TLRPC.TL_messages_setEncryptedTyping -> {
                if (InuConfig.GHOST_HIDE_TYPING.value) {
                    // fire the callback so the sender's typing state machine resets
                    // (stock relies on it to release the per-thread typing lock)
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
            is TLRPC.TL_messages_readMessageContents,
            is TLRPC.TL_messages_readSavedHistory,
            is TLRPC.TL_channels_readHistory,
            is TLRPC.TL_channels_readMessageContents,
            is TLRPC.TL_messages_markDialogUnread -> InuConfig.GHOST_HIDE_READ.value
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
            else -> false
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
            // only fire while the offline-after-online mode is still active
            if (isGhostActive() && !InuConfig.GHOST_HIDE_ONLINE.value && InuConfig.GHOST_OFFLINE_AFTER_ONLINE.value) {
                sendStatus(account, offline = true)
            }
        }, 4000L)
    }
}
