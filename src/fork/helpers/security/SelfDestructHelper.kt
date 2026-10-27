package desu.inugram.helpers.security

import desu.inugram.InuConfig
import desu.inugram.helpers.archive.DeletedMessagesHelper
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController

object SelfDestructHelper {
    private var forceViewed = false

    // entiny: opening one-time media does not tell the sender unless the user marks it as viewed
    @JvmStatic
    fun shouldSkipViewed(dialogId: Long): Boolean =
        !forceViewed && InuConfig.ONE_TIME_PEEK.value && shouldPreserveMedia(dialogId)

    @JvmStatic
    fun shouldSkipViewed(message: MessageObject?): Boolean =
        message != null && !message.isOutOwner && (message.messageOwner?.media?.ttl_seconds ?: 0) != 0 && shouldSkipViewed(message.dialogId)

    @JvmStatic
    fun markViewed(message: MessageObject) {
        forceViewed = true
        try {
            MessagesController.getInstance(message.currentAccount).markMessageContentAsRead(message)
        } finally {
            forceViewed = false
        }
    }

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

    // entiny: 0 as the deletion date defaults to now so the deletion bulletin doesn't misreport send date as deletion time
    @JvmStatic
    fun filterTimedDeletions(account: Int, mids: ArrayList<Int>?) {
        if (!InuConfig.SAVE_TIMED_MESSAGES.value || mids.isNullOrEmpty()) return
        val loaded = MessagesController.getInstance(account).dialogMessagesByIds
        for (id in mids) {
            val obj = loaded.get(id) ?: continue
            if (obj.messageOwner.ttl_period != 0) DeletedMessagesHelper.markDeleted(account, obj.dialogId, id, obj, 0, true)
        }
    }

    @JvmStatic
    fun preserveTimedDeletions(account: Int, dialogId: Long, mids: ArrayList<Int>) {
        val controller = MessagesController.getInstance(account)
        if (!shouldPreserveMessage(dialogId, controller.dialogs_dict.get(dialogId)?.ttl_period ?: 0)) return
        for (id in mids) DeletedMessagesHelper.markDeleted(account, dialogId, id, controller.dialogMessagesByIds.get(id), 0, true)
    }
}
