package desu.inugram.helpers.archive

import android.app.Activity
import android.util.SparseArray
import android.view.Gravity
import android.widget.FrameLayout
import androidx.collection.LongSparseArray
import androidx.core.content.res.ResourcesCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import java.io.File
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper

object DeletedMessagesHelper {
    private const val TAG = "DeletedMessagesHelper"

    // entiny: synchronous record of permanent deletions so ChatActivity drops bubbles immediately
    private val purgingMessages = HashSet<Triple<Int, Long, Int>>()

    // entiny: bypasses global toggle when user explicitly checks keep-local in delete dialog
    private val pendingKeepLocal = HashSet<Triple<Int, Long, Int>>()

    // entiny: user-initiated deletes must never be archived; timestamped because several readers poll it
    private val pendingPermanentDelete = HashMap<Triple<Int, Long, Int>, Long>()
    private const val PERMANENT_PENDING_TTL_MS = 120_000L

    @JvmStatic
    fun isSaveDeletedEnabled(): Boolean = InuConfig.SAVE_DELETED_MESSAGES.value

    @JvmStatic
    fun shouldSaveForDialog(account: Int, dialogId: Long): Boolean {
        if (!isSaveDeletedEnabled()) return false
        val controller = MessagesController.getInstance(account) ?: return true
        if (org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) {
            return InuConfig.SAVE_SELF_DESTRUCT_TEXT.value || InuConfig.SAVE_DELETED_SECRET.value
        }
        if (org.telegram.messenger.DialogObject.isUserDialog(dialogId)) {
            val user = controller.getUser(dialogId)
            if (user != null && user.bot) {
                return InuConfig.SAVE_DELETED_BOTS.value
            }
            return InuConfig.SAVE_DELETED_PRIVATE.value
        } else if (org.telegram.messenger.DialogObject.isChatDialog(dialogId)) {
            val chat = controller.getChat(-dialogId)
            if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat) && !chat.megagroup) {
                return InuConfig.SAVE_DELETED_CHANNELS.value
            }
            return InuConfig.SAVE_DELETED_GROUPS.value
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun deletePermanently(account: Int, dialogId: Long, msgIds: List<Int>, onDone: Runnable? = null) {
        dropGhostsFromChat(account, dialogId, msgIds, true, onDone)
    }

    // entiny: purgeArchive=false hides the ghost bubble but keeps the saved copy (archive row, media, edit history)
    private fun dropGhostsFromChat(account: Int, dialogId: Long, msgIds: List<Int>, purgeArchive: Boolean, onDone: Runnable? = null) {
        if (msgIds.isEmpty()) return
        val storage = MessagesStorage.getInstance(account) ?: return
        // entiny: drop from in-memory set before broadcasting deletion so ghost bubbles don't survive
        if (purgeArchive) {
            synchronized(ArchiveCache.cacheLock) {
                val ids = ArchiveCache.deletedMessageIds.get(account.toLong())?.get(dialogId)
                val dates = ArchiveCache.deletedMessageDates.get(account.toLong())?.get(dialogId)
                for (msgId in msgIds) {
                    ids?.remove(msgId)
                    dates?.remove(msgId.toLong())
                }
            }
        }
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            if (purgeArchive) {
                InuDatabaseHelper.deleteDeletedMessageEntries(db, dialogId, msgIds)
                InuDatabaseHelper.deleteEditHistory(db, dialogId, msgIds)
            }
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, msgIds)
            val channelId = ArchiveCache.getChannelId(account, dialogId)
            storage.updateDialogsWithDeletedMessages(dialogId, channelId, ArrayList(msgIds), null)
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (purgeArchive) {
                    synchronized(ArchiveCache.cacheLock) {
                        val ids = ArchiveCache.deletedMessageIds.get(account.toLong())?.get(dialogId)
                        val dates = ArchiveCache.deletedMessageDates.get(account.toLong())?.get(dialogId)
                        val edits = ArchiveCache.editHistoryCache.get(account.toLong())?.get(dialogId)
                        for (msgId in msgIds) {
                            ids?.remove(msgId)
                            dates?.remove(msgId.toLong())
                            edits?.remove(msgId.toLong())
                            ArchiveCache.editHistoryIds.get(account.toLong())?.get(dialogId)?.remove(msgId)
                            ArchiveCache.deletedMessageMediaPaths.get(account.toLong())?.get(dialogId)?.remove(msgId.toLong())
                        }
                    }
                }
                val controller = MessagesController.getInstance(account)
                val idSet = msgIds.toHashSet()
                for (msgId in msgIds) {
                    if (controller.dialogMessagesByIds.get(msgId)?.dialogId == dialogId) controller.dialogMessagesByIds.remove(msgId)
                }
                val list = controller.dialogMessage.get(dialogId)
                if (list != null) {
                    list.removeAll { it?.id != null && it.id in idSet }
                }
                org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(
                        org.telegram.messenger.NotificationCenter.messagesDeleted,
                        ArrayList(msgIds), channelId, false, false, false, 0
                    )
                synchronized(ArchiveCache.cacheLock) {
                    for (msgId in msgIds) purgingMessages.remove(Triple(account, dialogId, msgId))
                }
                onDone?.run()
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun extractPreserved(
        account: Int,
        dialogId: Long,
        msgIds: MutableList<Int>?,
        permanent: Boolean,
        fragment: BaseFragment? = null,
    ): Boolean {
        if (msgIds.isNullOrEmpty() || !isSaveDeletedEnabled()) return false
        val preserved = msgIds.filter { isMessageDeleted(account, dialogId, it) }
        if (preserved.isEmpty()) {
            val stale = findStaleArchiveEntries(account, msgIds)
            if (stale.isEmpty()) return false
            if (permanent) {
                for ((did, mids) in stale) deletePermanently(account, did, mids, null)
                showPurgedBulletin(fragment)
            }
            return true
        }
        msgIds.removeAll(preserved.toHashSet())
        synchronized(ArchiveCache.cacheLock) {
                for (id in preserved) purgingMessages.add(Triple(account, dialogId, id))
        }
        if (permanent) {
            deletePermanently(account, dialogId, preserved) { showPurgedBulletin(fragment) }
        } else {
            dropGhostsFromChat(account, dialogId, preserved, false, null)
        }
        return true
    }

    // entiny: archive rows stored under a wrong dialog key (storage-guess / saved-subfolder mismatch) stay purgeable
    private fun findStaleArchiveEntries(account: Int, msgIds: List<Int>): Map<Long, List<Int>> {
        val stale = HashMap<Long, ArrayList<Int>>()
        synchronized(ArchiveCache.cacheLock) {
            val dialogs = ArchiveCache.deletedMessageIds.get(account.toLong()) ?: return@synchronized
            for (mid in msgIds) {
                for (i in 0 until dialogs.size()) {
                    if (dialogs.valueAt(i)?.contains(mid) == true) {
                        stale.getOrPut(dialogs.keyAt(i)) { ArrayList() }.add(mid)
                    }
                }
            }
        }
        return stale
    }

    private fun showPurgedBulletin(fragment: BaseFragment?) {
        val activity = fragment?.parentActivity ?: return
        val drawable = ResourcesCompat.getDrawable(activity.resources, R.drawable.inu_tabler_trash_x, null)?.mutate() ?: return
        BulletinFactory.of(fragment).createSimpleBulletin(
            drawable,
            LocaleController.getString(R.string.InuDeletePermanentlyDone),
        ).show()
    }

    @JvmStatic
    fun hasPreservedSelection(
        account: Int,
        selectedMessage: MessageObject?,
        selectedMessages: Array<SparseArray<MessageObject>?>?,
        selectedGroup: MessageObject.GroupedMessages?,
    ): Boolean {
        if (!isSaveDeletedEnabled()) return false
        val targets = ArrayList<Pair<Long, Int>>()
        if (selectedMessage != null) {
            if (selectedGroup != null) {
                for (msg in selectedGroup.messages) targets.add(msg.dialogId to msg.id)
            } else {
                targets.add(selectedMessage.dialogId to selectedMessage.id)
            }
        } else if (selectedMessages != null) {
            for (sparse in selectedMessages) {
                if (sparse == null) continue
                for (i in 0 until sparse.size()) {
                    val msg = sparse.valueAt(i) ?: continue
                    targets.add(msg.dialogId to msg.id)
                }
            }
        }
        return targets.any { isMessageDeleted(account, it.first, it.second) }
    }

    @JvmStatic
    fun decorateDeleteAlert(
        builder: AlertDialog.Builder,
        box: FrameLayout?,
        activity: Activity,
        resourcesProvider: Theme.ResourcesProvider?,
        account: Int,
        selectedMessage: MessageObject?,
        selectedMessages: Array<SparseArray<MessageObject>?>?,
        selectedGroup: MessageObject.GroupedMessages?,
        state: BooleanArray,
    ) {
        if (!hasPreservedSelection(account, selectedMessage, selectedMessages, selectedGroup)) return
        var container = box
        if (container == null) {
            container = FrameLayout(activity)
            builder.setView(container)
            builder.setCustomViewOffset(9)
        }
        val cell = addCheckbox(activity, container, resourcesProvider, R.string.InuDeletePermanently, 48f * container.childCount, state)
        // entiny: manual delete of a ghost defaults to a full purge, matching the old menu item
        state[0] = true
        cell.setChecked(true, false)
    }

    @JvmStatic
    fun addKeepLocalCheckbox(activity: Activity, container: FrameLayout, resourcesProvider: Theme.ResourcesProvider?, state: BooleanArray) {
        if (!InuConfig.PROMPT_KEEP_LOCAL_ON_DELETE.value || isSaveDeletedEnabled()) return
        addCheckbox(activity, container, resourcesProvider, R.string.InuKeepLocalCopy, 48f, state)
    }

    private fun addCheckbox(activity: Activity, container: FrameLayout, resourcesProvider: Theme.ResourcesProvider?, text: Int, top: Float, state: BooleanArray): CheckBoxCell {
        val cell = CheckBoxCell(activity, 1, resourcesProvider)
        cell.background = Theme.getSelectorDrawable(false)
        cell.setText(LocaleController.getString(text), "", false, false)
        cell.setPadding(
            AndroidUtilities.dp(if (LocaleController.isRTL) 16f else 8f), 0,
            AndroidUtilities.dp(if (LocaleController.isRTL) 8f else 16f), 0,
        )
        container.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), 48f, Gravity.TOP or Gravity.LEFT, 0f, top, 0f, 0f))
        cell.setOnClickListener { v ->
            state[0] = !state[0]
            (v as CheckBoxCell).setChecked(state[0], true)
        }
        return cell
    }

    @JvmStatic
    fun onDeleteConfirmed(account: Int, dialogId: Long, msgIds: ArrayList<Int>?, keepLocal: Boolean, permanent: Boolean, fragment: BaseFragment?) {
        if (msgIds == null) return
        if (keepLocal) requestKeepLocal(account, dialogId, msgIds)
        requestPermanentDelete(account, dialogId, msgIds)
        extractPreserved(account, dialogId, msgIds, permanent, fragment)
    }

    @JvmStatic
    fun capturesDeletion(account: Int, dialogId: Long, msgIds: List<Int>?): Boolean =
        msgIds != null && (isSaveDeletedEnabled() || hasPendingKeepLocal(account, dialogId, msgIds))

    @JvmStatic
    fun onDialogMessagesDeleted(account: Int, dialogId: Long, msgIds: ArrayList<Int>?) {
        if (msgIds == null || !capturesDeletion(account, dialogId, msgIds)) return
        val loaded = MessagesController.getInstance(account).dialogMessage.get(dialogId)
        for (id in msgIds) {
            val forceSave = consumeKeepLocalRequest(account, dialogId, id)
            markDeleted(account, dialogId, id, loaded?.firstOrNull { it != null && it.id == id }, 0, forceSave)
        }
    }

    @JvmStatic
    fun onDeletedUpdate(account: Int, dialogId: Long, msgIds: ArrayList<Int>?, date: Int) {
        if (msgIds == null || !isSaveDeletedEnabled()) return
        val loaded = MessagesController.getInstance(account).dialogMessagesByIds
        for (id in msgIds) {
            val found = loaded.get(id)
            markDeleted(account, if (dialogId != 0L) dialogId else found?.dialogId ?: 0L, id, found, date)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun markDeleted(account: Int, dialogId: Long, msgId: Int, obj: MessageObject?, date: Int, forceSave: Boolean = false) {
        val owner = obj?.messageOwner
        val fromId = owner?.from_id?.let { org.telegram.messenger.DialogObject.getPeerDialogId(it) } ?: 0L
        markMessageDeleted(account, dialogId, msgId, fromId, owner?.message ?: "", date, owner, forceSave)
    }

    @JvmStatic
    fun requestKeepLocal(account: Int, dialogId: Long, msgIds: Collection<Int>) {
        if (msgIds.isEmpty()) return
        synchronized(ArchiveCache.cacheLock) {
            for (id in msgIds) pendingKeepLocal.add(Triple(account, dialogId, id))
        }
    }

    @JvmStatic
    fun hasPendingKeepLocal(account: Int, dialogId: Long, msgIds: Collection<Int>): Boolean {
        if (msgIds.isEmpty()) return false
        synchronized(ArchiveCache.cacheLock) {
            return msgIds.any { Triple(account, dialogId, it) in pendingKeepLocal }
        }
    }

    @JvmStatic
    fun consumeKeepLocalRequest(account: Int, dialogId: Long, msgId: Int): Boolean {
        synchronized(ArchiveCache.cacheLock) {
            return pendingKeepLocal.remove(Triple(account, dialogId, msgId))
        }
    }

    @JvmStatic
    fun requestPermanentDelete(account: Int, dialogId: Long, msgIds: Collection<Int>) {
        if (msgIds.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(ArchiveCache.cacheLock) {
            for (id in msgIds) pendingPermanentDelete[Triple(account, dialogId, id)] = now
        }
    }

    @JvmStatic
    fun isPermanentDeleteRequested(account: Int, dialogId: Long, msgId: Int): Boolean {
        val key = Triple(account, dialogId, msgId)
        val now = System.currentTimeMillis()
        synchronized(ArchiveCache.cacheLock) {
            val ts = pendingPermanentDelete[key] ?: return false
            if (now - ts > PERMANENT_PENDING_TTL_MS) {
                pendingPermanentDelete.remove(key)
                return false
            }
            return true
        }
    }

    // entiny: mids are account-unique; cancel-upload keys may be recorded under a different dialog than the archive hook
    @JvmStatic
    fun isPermanentDeleteRequestedForMid(account: Int, msgId: Int): Boolean {
        val now = System.currentTimeMillis()
        synchronized(ArchiveCache.cacheLock) {
            val iterator = pendingPermanentDelete.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > PERMANENT_PENDING_TTL_MS) {
                    iterator.remove()
                } else if (msgId < 0 && entry.key.first == account && entry.key.third == msgId) {
                    return true
                }
            }
            return false
        }
    }

    // entiny: with save-deleted on, stock skips messages_v2 cleanup for every row; delete the ones that
    // were not archived (own delete, off-category, permanent request) so their bubbles don't resurrect on reload
    @JvmStatic
    fun deleteNonPreservedFromStorage(
        account: Int,
        db: org.telegram.SQLite.SQLiteDatabase,
        dialogId: Long,
        mids: List<Int>?,
    ) {
        if (mids.isNullOrEmpty()) return
        val doomed = mids.filter {
            !isMessageDeleted(account, dialogId, it) || isPermanentDeleteRequested(account, dialogId, it) || isPermanentDeleteRequestedForMid(account, it)
        }
        if (doomed.isNotEmpty()) {
            InuDatabaseHelper.deleteSavedMessages(db, dialogId, doomed)
        }
    }

    internal fun archiveText(text: String?, message: TLRPC.Message?): String {
        if (!text.isNullOrBlank()) return text
        val media = message?.media ?: return ""
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
                    org.telegram.messenger.MessageObject.isStickerDocument(doc) -> R.string.AttachSticker
                    org.telegram.messenger.MessageObject.isRoundVideoDocument(doc) -> R.string.AttachRound
                    org.telegram.messenger.MessageObject.isVoiceDocument(doc) -> R.string.AttachAudio
                    org.telegram.messenger.MessageObject.isGifDocument(doc) -> R.string.AttachGif
                    org.telegram.messenger.MessageObject.isVideoDocument(doc) -> R.string.AttachVideo
                    org.telegram.messenger.MessageObject.isMusicDocument(doc) -> R.string.AttachMusic
                    else -> R.string.AttachDocument
                }
            }
            else -> return ""
        }
        return LocaleController.getString(res)
    }

    // entiny: a deletion is only worth archiving when we actually hold something to preserve

    private fun hasPreservableData(text: String?, message: TLRPC.Message?): Boolean {
        if (!text.isNullOrBlank()) return true
        val media = message?.media ?: return false
        return media !is TLRPC.TL_messageMediaEmpty
    }

    // entiny: full copy keeps entities, media, reply and sender for the archive screen
    private fun snapshotMessage(account: Int, dialogId: Long, msgId: Int, fromId: Long, message: TLRPC.Message?): TLRPC.Message? {
        val copy = message?.let { InuUtils.cloneTLObject(it, TLRPC.Message::TLdeserialize) }?.also {
            it.attachPath = message.attachPath
            it.dialog_id = dialogId
        }
        if (copy != null) return copy
        val shadow = ArchiveCache.shadow(account, dialogId, msgId) ?: return null
        return TLRPC.TL_message().apply {
            id = msgId
            this.dialog_id = dialogId
            date = shadow.date
            this.message = shadow.text
            if (fromId != 0L) from_id = MessagesController.getInstance(account).getPeer(fromId)
            peer_id = MessagesController.getInstance(account).getPeer(dialogId)
            out = fromId != 0L && fromId == UserConfig.getInstance(account).clientUserId
            if (!shadow.entities.isNullOrEmpty()) {
                entities = ArrayList(shadow.entities)
                flags = flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
            }
            if (shadow.media != null) {
                media = InuDatabaseHelper.cloneMedia(shadow.media)
                if (media != null) flags = flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun markMessageDeleted(account: Int, dialogIdIn: Long, msgIdIn: Int, fromIdIn: Long, textIn: String?, date: Int, messageIn: TLRPC.Message? = null, forceSave: Boolean = false) {
        var dialogId = dialogIdIn
        // entiny: dialogMessagesByIds is keyed by mid only, so a channel delete can hand over another chat's message
        val foreign = messageIn != null && dialogIdIn != 0L && MessageObject.getDialogId(messageIn) != dialogIdIn
        val message = if (foreign) null else messageIn
        var text = if (foreign) null else textIn
        var fromId = if (foreign) 0L else fromIdIn
        val msgId = msgIdIn
        if (fromId == 0L && message != null && !message.out && message.from_id == null && org.telegram.messenger.DialogObject.isUserDialog(dialogIdIn)) {
            fromId = dialogIdIn
        }
        // entiny: private deletes arrive without a dialog id, recover it from the shadow cache
        if (dialogId == 0L && message == null && msgId > 0) {
            ArchiveCache.findShadowDialog(account, msgId)?.let { dialogId = it }
        }
        if (text.isNullOrEmpty() && message == null && dialogId != 0L) {
            text = ArchiveCache.shadow(account, dialogId, msgId)?.text
        }
        // entiny: reject dialog id 0 to prevent marking matching IDs in unrelated chats as deleted
        if (dialogId == 0L) return
        // entiny: unsent or cancelled uploads carry local ids and must never reach the archive
        // entiny: secret chat messages carry negative ids, only the send state tells an unsent one apart
        if ((msgId <= 0 && !org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) || (message != null && message.send_state != 0)) return
        // entiny: a message already archived (timer path) keeps getting its real text from the storage delete even when the chat type is off
        val alreadyRecorded = isMessageDeleted(account, dialogId, msgId)
        if (!forceSave && !alreadyRecorded && !shouldSaveForDialog(account, dialogId)) return
        if (!forceSave && !InuConfig.SAVE_DELETED_OWN.value && (fromId == UserConfig.getInstance(account).clientUserId || message?.out == true)) return
        if (!forceSave && isPermanentDeleteRequested(account, dialogId, msgId)) return
        if (!forceSave && isPermanentDeleteRequestedForMid(account, msgId)) return
        // entiny: prevent empty text from subsequent delete reports overwriting preserved text
        if (alreadyRecorded && text.isNullOrEmpty() && message?.media == null) return
        // entiny: deletions never loaded locally carry no data and would only create phantom ghosts
        if (!forceSave && !alreadyRecorded && !hasPreservableData(text, message)) return
        ArchiveCache.ensureAccountLoaded(account)
        val deletionTime = if (date > 0) date.toLong() else System.currentTimeMillis() / 1000L
        val snapshot = snapshotMessage(account, dialogId, msgId, fromId, message)
        val mediaCopy = ArchiveMediaHelper.planMediaCopy(account, snapshot ?: message)
        synchronized(ArchiveCache.cacheLock) {
            var dialogs = ArchiveCache.deletedMessageIds.get(account.toLong())
            if (dialogs == null) {
                dialogs = LongSparseArray()
                ArchiveCache.deletedMessageIds.put(account.toLong(), dialogs)
            }
            var set = dialogs.get(dialogId)
            if (set == null) {
                set = HashSet()
                dialogs.put(dialogId, set)
            }
            set.add(msgId)

            var dateAcc = ArchiveCache.deletedMessageDates.get(account.toLong())
            if (dateAcc == null) {
                dateAcc = LongSparseArray()
                ArchiveCache.deletedMessageDates.put(account.toLong(), dateAcc)
            }
            var dateDialog = dateAcc.get(dialogId)
            if (dateDialog == null) {
                dateDialog = LongSparseArray()
                dateAcc.put(dialogId, dateDialog)
            }
            if (dateDialog.get(msgId.toLong()) == null) dateDialog.put(msgId.toLong(), deletionTime)
        }

        val storage = MessagesStorage.getInstance(account) ?: return
        val persist = Runnable {
            val db = storage.database ?: return@Runnable
            val savedPath = InuDatabaseHelper.deletedMessageMediaPath(db, dialogId, msgId)?.takeIf { File(it).isFile }
            InuDatabaseHelper.saveDeletedMessage(db, dialogId, msgId, fromId, archiveText(text, message ?: snapshot), deletionTime.toInt(), savedPath, snapshot)
            if (savedPath != null) {
                ArchiveCache.rememberDeletedMediaPath(account, dialogId, msgId, savedPath)
            } else if (mediaCopy != null) {
                ArchiveMediaHelper.copyThenStore(account, "d:$account:$dialogId:$msgId", mediaCopy) { copyDb, copied ->
                    val stored = ArchiveMediaHelper.updateMediaPath(copyDb, "inu_deleted_messages", "dialog_id = $dialogId AND msg_id = $msgId", copied)
                    if (stored) ArchiveCache.rememberDeletedMediaPath(account, dialogId, msgId, copied)
                    stored
                }
            }
        }
        if (Thread.currentThread() === storage.storageQueue) persist.run() else storage.storageQueue.postRunnable(persist)
    }

    // entiny: stock treats `deleted` as a bubble mid-removal (skips avatar updates), so a ghost kept in the open chat is tracked here instead
    private val liveGhosts: MutableSet<MessageObject> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<MessageObject, Boolean>())

    @JvmStatic
    fun keepAsGhost(account: Int, msg: MessageObject): Boolean {
        if (!isPreservedOrWillBe(account, msg)) return false
        synchronized(liveGhosts) { liveGhosts.add(msg) }
        return true
    }

    @JvmStatic
    fun isDeletedOrPreserved(msg: MessageObject?): Boolean {
        if (msg == null || !isSaveDeletedEnabled()) return false
        if (EditHistoryHelper.isHistoryPreview(msg)) return false
        if (msg.deleted || synchronized(liveGhosts) { msg in liveGhosts }) return true
        return isMessageDeleted(msg.currentAccount, msg.getDialogId(), msg.id)
    }

    @JvmStatic
    fun isPreservedOrWillBe(account: Int, msg: MessageObject?): Boolean {
        if (msg == null) return false
        val dialogId = msg.getDialogId()
        val msgId = msg.id
        synchronized(ArchiveCache.cacheLock) {
            if (purgingMessages.contains(Triple(account, dialogId, msgId))) return false
        }
        if (isPermanentDeleteRequested(account, dialogId, msgId)) return false
        if (isMessageDeleted(account, dialogId, msgId)) return true
        // entiny: nothing to archive means nothing to preserve, drop the bubble
        if (!hasPreservableData(msg.messageOwner?.message, msg.messageOwner)) return false
        if (!shouldSaveForDialog(account, dialogId)) return false
        if (!InuConfig.SAVE_DELETED_OWN.value && msg.isOutOwner) return false
        return true
    }

    @JvmStatic
    fun replyIdsToClear(account: Int, messagesByDialogs: LongSparseArray<ArrayList<Int>>?, mids: List<Int>?, ids: String): String {
        if (mids.isNullOrEmpty() || messagesByDialogs == null || !isSaveDeletedEnabled()) return ids
        val kept = HashSet<Int>()
        for (i in 0 until messagesByDialogs.size()) {
            val dialogId = messagesByDialogs.keyAt(i)
            messagesByDialogs.valueAt(i)?.forEach {
                if (isMessageDeleted(account, dialogId, it) && !isPermanentDeleteRequested(account, dialogId, it)) kept.add(it)
            }
        }
        return if (kept.isEmpty()) ids else mids.filter { it !in kept }.joinToString(",")
    }

    @JvmStatic
    fun dropStaleDeletedReply(account: Int, message: TLRPC.Message?) {
        if (message == null || message.replyMessage !is TLRPC.TL_messageEmpty || !isSaveDeletedEnabled()) return
        val replyId = message.reply_to?.reply_to_msg_id ?: return
        if (replyId == 0) return
        val db = MessagesStorage.getInstance(account).database ?: return
        try {
            if (InuDatabaseHelper.isDeletedMessageArchived(db, MessageObject.getReplyToDialogId(message), replyId)) message.replyMessage = null
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "stale reply check failed", e)
        }
    }

    @JvmStatic
    fun fillArchivedReplies(
        account: Int,
        db: org.telegram.SQLite.SQLiteDatabase?,
        dialogId: Long,
        ids: List<Int>?,
        owners: SparseArray<ArrayList<TLRPC.Message>>?,
        usersToLoad: ArrayList<Long>?,
        chatsToLoad: ArrayList<Long>?,
    ) {
        if (!isSaveDeletedEnabled() || db == null || ids.isNullOrEmpty() || owners == null) return
        val missing = ids.filter { id -> owners.get(id)?.any { it.replyMessage == null } == true }
        if (missing.isEmpty()) return
        try {
            for ((id, message) in InuDatabaseHelper.archivedMessages(db, dialogId, missing)) {
                message.id = id
                message.dialog_id = dialogId
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null)
                owners.get(id)?.forEach { if (it.replyMessage == null) it.replyMessage = message }
            }
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "archived reply lookup failed", e)
        }
    }

    // entiny: record deletions with dialog ids resolved by MessagesStorage from messages_v2
    @JvmStatic
    fun markMessagesDeletedFromStorage(
        account: Int,
        messagesByDialogs: LongSparseArray<ArrayList<Int>>?,
        resolved: List<TLRPC.Message>?,
    ) {
        if (!isSaveDeletedEnabled() || messagesByDialogs == null) return
        val byId = HashMap<Pair<Long, Int>, TLRPC.Message>()
        resolved?.forEach { m ->
            val dialogId = MessageObject.getDialogId(m)
            m.dialog_id = dialogId
            byId[dialogId to m.id] = m
        }
        var total = 0
        for (i in 0 until messagesByDialogs.size()) total += messagesByDialogs.valueAt(i)?.size ?: 0
        val db = if (total > 1) MessagesStorage.getInstance(account).database else null
        val started = android.os.SystemClock.elapsedRealtime()
        // entiny: one savepoint instead of an autocommit per archived row; nests safely inside stock transactions
        db?.executeFast("SAVEPOINT inu_archive_batch")?.stepThis()?.dispose()
        try {
            for (i in 0 until messagesByDialogs.size()) {
                val dialogId = messagesByDialogs.keyAt(i)
                val mids = messagesByDialogs.valueAt(i) ?: continue
                for (mid in mids) {
                    val message = byId[dialogId to mid]
                    val fromId = message?.from_id?.let { org.telegram.messenger.DialogObject.getPeerDialogId(it) } ?: 0L
                    markMessageDeleted(account, dialogId, mid, fromId, message?.message ?: "", 0, message)
                }
            }
        } finally {
            db?.executeFast("RELEASE SAVEPOINT inu_archive_batch")?.stepThis()?.dispose()
        }
        if (total > 50) android.util.Log.d(TAG, "archived $total deletions in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }

    @JvmStatic
    fun isMessageDeleted(account: Int, dialogId: Long, msgId: Int): Boolean {
        // entiny: timer and secret-chat archives keep their mark with the general toggle off
        if (!isSaveDeletedEnabled() && !InuConfig.SAVE_TIMED_MESSAGES.value && !InuConfig.SAVE_SELF_DESTRUCT_TEXT.value) return false
        ArchiveCache.ensureAccountLoaded(account)
        return synchronized(ArchiveCache.cacheLock) {
            val accMap = ArchiveCache.deletedMessageIds.get(account.toLong()) ?: return@synchronized false
            accMap.get(dialogId)?.contains(msgId) == true
        }
    }

    @JvmStatic
    fun getDeletedDate(account: Int, dialogId: Long, msgId: Int): Long {
        ArchiveCache.ensureAccountLoaded(account)
        return synchronized(ArchiveCache.cacheLock) {
            val accMap = ArchiveCache.deletedMessageDates.get(account.toLong()) ?: return@synchronized 0L
            accMap.get(dialogId)?.get(msgId.toLong()) ?: 0L
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
