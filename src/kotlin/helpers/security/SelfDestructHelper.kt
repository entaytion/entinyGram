package desu.inugram.helpers.security

import android.util.SparseArray
import androidx.collection.LongSparseArray
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage

object SelfDestructHelper {

    @JvmStatic
    fun shouldPreserveMedia(dialogId: Long): Boolean {
        return if (DialogObject.isEncryptedDialog(dialogId)) {
            InuConfig.SAVE_SELF_DESTRUCT_MEDIA.value
        } else {
            InuConfig.SAVE_VIEW_ONCE_MEDIA.value
        }
    }

    @JvmStatic
    fun shouldBypassOneTimeGate(dialogId: Long): Boolean {
        return shouldPreserveMedia(dialogId) && InuConfig.VIEW_ONCE_SHOW_NORMAL.value
    }

    @JvmStatic
    fun shouldPreserveMessage(dialogId: Long, ttlPeriod: Int): Boolean {
        return if (DialogObject.isEncryptedDialog(dialogId)) {
            InuConfig.SAVE_SELF_DESTRUCT_TEXT.value
        } else {
            InuConfig.SAVE_TIMED_MESSAGES.value && ttlPeriod != 0
        }
    }

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
                // entiny: 0 defaults to now so the deletion bulletin doesn't misreport send date as deletion time
                desu.inugram.helpers.chat.SavedMessagesHelper.markMessageDeleted(account, obj.getDialogId(), id, fromId, text, 0, obj.messageOwner, true)
            }
        }
    }
}
