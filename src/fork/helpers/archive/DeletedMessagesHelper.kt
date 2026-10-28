package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import java.io.File
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory

object DeletedMessagesHelper {
    @JvmStatic
    fun isSaveDeletedEnabled(): Boolean = InuConfig.SAVE_DELETED_MESSAGES.value

    @JvmStatic
    @JvmOverloads
    fun shouldSaveForDialog(account: Int, dialogId: Long, message: TLRPC.Message? = null): Boolean {
        if (!isSaveDeletedEnabled()) return false
        if (DialogObject.isEncryptedDialog(dialogId) && InuConfig.SAVE_SELF_DESTRUCT_TEXT.value) return true
        return ArchiveScope.allows(account, dialogId, message)
    }

    // entiny: secret chats number their messages with non-positive ids
    private fun hasValidId(dialogId: Long, msgId: Int): Boolean =
        msgId > 0 || (msgId != 0 && DialogObject.isEncryptedDialog(dialogId))

    @JvmStatic
    fun shouldArchive(account: Int, dialogId: Long, message: TLRPC.Message?): Boolean {
        if (message == null || !hasValidId(dialogId, message.id)) return false
        if (!isSaveDeletedEnabled()) return false
        if (!hasPreservableData(message.message, message)) return false
        if (!shouldSaveForDialog(account, dialogId, message)) return false
        if (message.out && !InuConfig.SAVE_DELETED_OWN.value) return false
        return true
    }

    @JvmStatic
    fun keepInChat(account: Int, dialogId: Long, message: TLRPC.Message?, userInitiated: Boolean): Boolean {
        if (message == null || !hasValidId(dialogId, message.id) || userInitiated) return false
        if (DialogObject.isEncryptedDialog(dialogId) && message.ttl != 0) return false
        if (message.action != null && message.action !is TLRPC.TL_messageActionEmpty) return false
        if (message.ttl_period != 0) return false
        if ((message.media?.ttl_seconds ?: 0) != 0) return false
        return shouldArchive(account, dialogId, message)
    }

    // entiny: with save-deleted on the row stays in messages_v2; this decision keeps or drops it
    @JvmStatic
    fun filterInChat(account: Int, dialogId: Long, ids: ArrayList<Int>, userInitiated: Boolean, lookup: (Int) -> MessageObject?): ArrayList<Int>? {
        if (ids.isEmpty()) return null
        ArchiveCache.ensureAccountLoaded(account)
        val now = System.currentTimeMillis() / 1000L
        var keptAny = false
        val result = ArrayList<Int>(ids.size)
        for (id in ids) {
            val owner = lookup(id)?.messageOwner
            if (userInitiated) synchronized(ArchiveCache.cacheLock) { ArchiveCache.forgetKept(account, dialogId, id) }
            if (!userInitiated && !ArchiveCache.consumePurged(account, dialogId, id) && keepInChat(account, dialogId, owner, false)) {
                rememberKept(account, dialogId, id, now)
                keptAny = true
                continue
            }
            result.add(id)
        }
        return if (keptAny) result else null
    }

    @JvmStatic
    fun rememberKept(account: Int, dialogId: Long, msgId: Int, date: Long) =
        ArchiveCache.rememberKept(account, dialogId, msgId, date)

    @JvmStatic
    fun isKept(account: Int, dialogId: Long, msgId: Int): Boolean {
        if (!isSaveDeletedEnabled() && !InuConfig.SAVE_TIMED_MESSAGES.value && !InuConfig.SAVE_SELF_DESTRUCT_TEXT.value) return false
        ArchiveCache.ensureAccountLoaded(account)
        return synchronized(ArchiveCache.cacheLock) {
            ArchiveCache.deletedMessageIds.get(account.toLong())?.get(dialogId)?.contains(msgId) == true
        }
    }

    private val previewObjects: MutableSet<MessageObject> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<MessageObject, Boolean>())

    @JvmStatic
    fun markAsPreview(msg: MessageObject) {
        synchronized(previewObjects) { previewObjects.add(msg) }
    }

    @JvmStatic
    fun isKept(msg: MessageObject?): Boolean {
        if (msg == null) return false
        if (synchronized(previewObjects) { previewObjects.contains(msg) }) return true
        if (!isSaveDeletedEnabled()) return false
        if (EditHistoryHelper.isHistoryPreview(msg)) return false
        return isKept(msg.currentAccount, msg.getDialogId(), msg.id)
    }

    // entiny: storage decides on the deletion; the row survives unless it was already kept once
    @JvmStatic
    fun archiveMessage(db: SQLiteDatabase, account: Int, dialogId: Long, message: TLRPC.Message?): Boolean {
        if (!shouldArchive(account, dialogId, message)) return false
        persist(db, account, dialogId, message!!.id, archiveText(message.message, message), message.date, message)
        return true
    }

    @JvmStatic
    fun restoreMissingMessages(storage: MessagesStorage, db: SQLiteDatabase, account: Int, dialogId: Long, ids: ArrayList<Int>, userInitiated: Boolean) {
        if (ids.isEmpty() || !isSaveDeletedEnabled()) return
        val existing = HashSet<Int>()
        val cursor = if (dialogId != 0L) {
            db.queryFinalized("SELECT mid FROM messages_v2 WHERE uid = ? AND mid IN (${ids.joinToString(",")})", dialogId)
        } else {
            db.queryFinalized("SELECT mid FROM messages_v2 WHERE mid IN (${ids.joinToString(",")}) AND is_channel = 0")
        }
        try {
            while (cursor.next()) existing.add(cursor.intValue(0))
        } finally {
            cursor.dispose()
        }
        val restored = ArrayList<TLRPC.Message>()
        for (id in ids) {
            if (id in existing) continue
            val message = ArchiveCache.messageSnapshot(account, dialogId, id) ?: continue
            val did = if (dialogId != 0L) dialogId else MessageObject.getDialogId(message)
            if (did == 0L) continue
            if (keepInChat(account, did, message, userInitiated)) {
                restored.add(message)
            } else {
                archiveMessage(db, account, did, message)
            }
        }
        if (restored.isNotEmpty()) storage.putMessages(restored, true, false, false, 0, 0, 0)
    }

    @JvmStatic
    fun wasArchived(db: SQLiteDatabase, dialogId: Long, msgId: Int): Boolean =
        ArchiveStore.isDeletedMessageArchived(db, dialogId, msgId)

    @JvmStatic
    fun markPurged(account: Int, dialogId: Long, msgId: Int) =
        ArchiveCache.markPurged(account, dialogId, msgId)

    // entiny: timed, self-destructing and view-once messages are saved to the archive without staying in the chat
    @JvmStatic
    @JvmOverloads
    fun markDeleted(account: Int, dialogId: Long, msgId: Int, obj: MessageObject?, date: Int, force: Boolean = false) {
        if (!hasValidId(dialogId, msgId) || dialogId == 0L) return
        val message = obj?.messageOwner ?: return
        val storage = MessagesStorage.getInstance(account) ?: return
        val run = Runnable {
            val db = storage.database ?: return@Runnable
            if (!force && !shouldSaveForDialog(account, dialogId)) return@Runnable
            persist(db, account, dialogId, msgId, archiveText(message.message, message), if (date > 0) date else message.date, message)
        }
        if (Thread.currentThread() === storage.storageQueue) run.run() else storage.storageQueue.postRunnable(run)
    }

    private fun persist(db: SQLiteDatabase, account: Int, dialogId: Long, msgId: Int, text: String, date: Int, message: TLRPC.Message) {
        if (!hasPreservableData(text, message)) return
        val fromId = message.from_id?.let { DialogObject.getPeerDialogId(it) } ?: 0L
        val deletedAt = (System.currentTimeMillis() / 1000L).toInt()
        val savedPath = ArchiveStore.deletedMessageMediaPath(db, dialogId, msgId)?.takeIf { File(it).isFile }
        ArchiveStore.saveDeletedMessage(db, dialogId, msgId, fromId, text, date, deletedAt, savedPath, message)
        if (savedPath != null) {
            ArchiveCache.rememberDeletedMediaPath(account, dialogId, msgId, savedPath)
            return
        }
        val mediaCopy = ArchiveMediaHelper.planNewMediaCopy(account, message) ?: return
        ArchiveMediaHelper.copyThenStore(account, "d:$account:$dialogId:$msgId", mediaCopy) { copyDb, copied ->
            val stored = ArchiveMediaHelper.updateMediaPath(copyDb, "inu_deleted_messages", "dialog_id = $dialogId AND msg_id = $msgId", copied)
            if (stored) ArchiveCache.rememberDeletedMediaPath(account, dialogId, msgId, copied)
            stored
        }
    }

    @JvmStatic
    fun removeArchived(db: SQLiteDatabase, dialogId: Long, msgId: Int) {
        ArchiveStore.deleteDeletedMessageEntries(db, dialogId, listOf(msgId))
    }

    @JvmStatic
    fun purge(account: Int, dialogId: Long, msgIds: List<Int>, onDone: Runnable? = null) {
        if (msgIds.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        synchronized(ArchiveCache.cacheLock) {
            for (msgId in msgIds) ArchiveCache.forgetKept(account, dialogId, msgId)
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            purgeInStorage(account, storage, db, dialogId, msgIds)
            AndroidUtilities.runOnUIThread { onDone?.run() }
        }
    }

    @JvmStatic
    fun purgeInStorage(account: Int, storage: MessagesStorage, db: SQLiteDatabase, dialogId: Long, msgIds: List<Int>) {
        if (msgIds.isEmpty()) return
        ArchiveStore.deleteDeletedMessageEntries(db, dialogId, msgIds)
        ArchiveStore.deleteSavedMessages(db, dialogId, msgIds)
        val channelId = ArchiveCache.getChannelId(account, dialogId)
        storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(msgIds), null)
        AndroidUtilities.runOnUIThread {
            synchronized(ArchiveCache.cacheLock) {
                val mediaPaths = ArchiveCache.deletedMessageMediaPaths.get(account.toLong())?.get(dialogId)
                for (msgId in msgIds) {
                    ArchiveCache.forgetKept(account, dialogId, msgId)
                    mediaPaths?.remove(msgId.toLong())
                }
            }
            val controller = MessagesController.getInstance(account)
            val idSet = msgIds.toHashSet()
            for (msgId in msgIds) {
                if (controller.dialogMessagesByIds.get(msgId)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(msgId)
            }
            controller.dialogMessage.get(dialogId)?.removeAll { it != null && it.id in idSet }
            org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                org.telegram.messenger.NotificationCenter.messagesDeleted,
                ArrayList(msgIds), channelId, false, false, false, 0, null, true
            )
        }
    }

    internal fun archiveText(text: String?, message: TLRPC.Message?): String {
        if (!text.isNullOrBlank()) return text
        return mediaLabel(message?.media)
    }

    internal fun mediaLabel(media: TLRPC.MessageMedia?): String {
        if (media == null) return ""
        val res = when (media) {
            is TLRPC.TL_messageMediaPoll -> return "📊 " + (media.poll?.question?.text ?: LocaleController.getString(R.string.Poll))
            is TLRPC.TL_messageMediaPhoto -> R.string.AttachPhoto
            is TLRPC.TL_messageMediaGeo, is TLRPC.TL_messageMediaVenue -> R.string.AttachLocation
            is TLRPC.TL_messageMediaGeoLive -> R.string.AttachLiveLocation
            is TLRPC.TL_messageMediaContact -> R.string.AttachContact
            is TLRPC.TL_messageMediaDocument -> {
                val doc = media.document
                when {
                    doc == null -> R.string.AttachDocument
                    MessageObject.isStickerDocument(doc) -> R.string.AttachSticker
                    MessageObject.isRoundVideoDocument(doc) -> R.string.AttachRound
                    MessageObject.isVoiceDocument(doc) -> R.string.AttachAudio
                    MessageObject.isGifDocument(doc) -> R.string.AttachGif
                    MessageObject.isVideoDocument(doc) -> R.string.AttachVideo
                    MessageObject.isMusicDocument(doc) -> R.string.AttachMusic
                    else -> R.string.AttachDocument
                }
            }
            else -> return ""
        }
        return LocaleController.getString(res)
    }

    private fun hasPreservableData(text: String?, message: TLRPC.Message?): Boolean {
        if (!text.isNullOrBlank()) return true
        val media = message?.media ?: return false
        return media !is TLRPC.TL_messageMediaEmpty
    }

    @JvmStatic
    fun getDeletedDate(account: Int, dialogId: Long, msgId: Int): Long {
        ArchiveCache.ensureAccountLoaded(account)
        return synchronized(ArchiveCache.cacheLock) {
            ArchiveCache.deletedMessageIds.get(account.toLong())?.get(dialogId)?.dateOf(msgId) ?: 0L
        }
    }

    @JvmStatic
    fun showDeletionTimeBulletin(context: android.content.Context?, activity: org.telegram.ui.ChatActivity?, msg: MessageObject) {
        if (context == null) return
        val date = getDeletedDate(msg.currentAccount, msg.dialogId, msg.id)
        val text = if (date > 0) {
            LocaleController.formatString(R.string.InuDeletedAt, LocaleController.formatDateAudio(date, true))
        } else {
            LocaleController.getString(R.string.InuSaveDeletedMessages)
        }
        val bulletinFactory = if (activity != null) BulletinFactory.of(activity) else BulletinFactory.global()
        bulletinFactory?.createSimpleBulletin(R.drawable.msg_delete, text)?.show()
    }
}
