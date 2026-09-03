package desu.inugram.helpers.security

import android.util.SparseArray
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage

/**
 * Local preservation of self-destruct content, gated by per-category TOS toggles.
 *
 * Every method returns stock behavior (false / identity) when the corresponding
 * toggles are off, so default-off stays exactly stock.
 */
object SelfDestructHelper {

    /**
     * Guard for `MessagesStorage.emptyMessagesMedia` (the media-wipe choke point), and for the
     * file-cache/redisplay checks in ChatActivity/FileLoader/MessageObject (which read
     * [InuConfig.SAVE_SELF_DESTRUCT_MEDIA] directly — same flag, single source of truth).
     * Encrypted dialogs: secret self-destructing media. Regular dialogs: view-once media.
     */
    @JvmStatic
    fun shouldPreserveMedia(dialogId: Long): Boolean {
        return if (DialogObject.isEncryptedDialog(dialogId)) {
            InuConfig.SAVE_SELF_DESTRUCT_MEDIA.value
        } else {
            InuConfig.SAVE_VIEW_ONCE_MEDIA.value
        }
    }

    /**
     * Guard for the "show as a regular reopenable photo" bypass (stock blur/one-time-reveal/
     * no-forward gates in MessageObject/FileLoader/ChatActivity). Only bypasses when the media is
     * actually preserved locally *and* the user opted into [InuConfig.VIEW_ONCE_SHOW_NORMAL] — the
     * default (gated) mode keeps the stock ephemeral UI even though the file survives, so it can
     * still be recovered via the Save/Burn message-menu actions.
     */
    @JvmStatic
    fun shouldBypassOneTimeGate(dialogId: Long): Boolean {
        return shouldPreserveMedia(dialogId) && InuConfig.VIEW_ONCE_SHOW_NORMAL.value
    }

    /**
     * Guard for the full row-deletion branch of `MessagesController.checkDeletingTask`
     * (`enc_tasks_v4` tasks with media = 0).
     * Encrypted dialogs: self-destructing text (scheduled by `createTaskForSecretChat`).
     * Regular dialogs: auto-delete (ttl_period) chats.
     */
    @JvmStatic
    fun shouldPreserveMessage(dialogId: Long, ttlPeriod: Int): Boolean {
        return if (DialogObject.isEncryptedDialog(dialogId)) {
            InuConfig.SAVE_SELF_DESTRUCT_TEXT.value
        } else {
            InuConfig.SAVE_TIMED_MESSAGES.value && ttlPeriod != 0
        }
    }

    // Preserved message IDs logic removed in favor of SavedMessagesHelper

    @JvmStatic
    fun filterTimedDeletions(account: Int, mids: ArrayList<Int>, dialogMessagesByIds: SparseArray<MessageObject>?) {
        if (!InuConfig.SAVE_TIMED_MESSAGES.value || mids.isEmpty() || dialogMessagesByIds == null) {
            return
        }
        for (i in 0 until mids.size) {
            val id = mids[i]
            val obj = dialogMessagesByIds.get(id)
            if (obj != null && obj.messageOwner.ttl_period != 0) {
                val fromId = if (obj.messageOwner.from_id != null) org.telegram.messenger.DialogObject.getPeerDialogId(obj.messageOwner.from_id) else 0L
                val text = obj.messageOwner.message ?: ""
                val date = obj.messageOwner.date
                desu.inugram.helpers.chat.SavedMessagesHelper.markMessageDeleted(account, obj.getDialogId(), id, fromId, text, date, obj.messageOwner, true)
            }
        }
    }
}
