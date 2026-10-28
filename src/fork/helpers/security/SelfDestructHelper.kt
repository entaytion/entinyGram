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

    // entiny: stock reports the read from SecretMediaViewer only; normal-viewer mode opens PhotoViewer and must report on open
    @JvmStatic
    fun shouldReportOneTimeRead(dialogId: Long, message: MessageObject?): Boolean {
        if (message == null || message.isOutOwner || !message.isSecretMedia) return false
        return shouldBypassOneTimeGate(dialogId) && !shouldSkipViewed(dialogId) && !message.isVideo
    }

    // entiny: photo/video one-time views are read receipts, so only "hide read receipts" may swallow them; voice notes keep the "played" gate
    @JvmStatic
    fun shouldAllowMediaViewRead(dialogId: Long, message: MessageObject?): Boolean {
        if (message == null || message.isOutOwner) return false
        if (message.isVoiceOnce || message.isRoundOnce) return false
        return !GhostHelper.shouldSuppress(dialogId, SuppressKind.READ)
    }

    @JvmStatic
    fun markViewed(message: MessageObject) {
        if (shouldAllowMediaViewRead(message.dialogId, message)) GhostHelper.allowReadContents(message.id)
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

    // entiny: normal-viewer mode bypasses SecretMediaViewer, so the photo viewer close is the only save trigger
    @JvmStatic
    fun shouldAutoSaveOneTime(dialogId: Long, message: MessageObject?): Boolean {
        return message != null && !message.isOutOwner && message.isSecretMedia &&
            InuConfig.AUTO_SAVE_ONE_TIME.value && shouldPreserveMedia(dialogId)
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
