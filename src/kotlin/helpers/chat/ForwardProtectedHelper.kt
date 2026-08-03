package desu.inugram.helpers.chat

import android.util.Log
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-sends forwarded messages as fresh own messages: media is downloaded locally
 * and re-uploaded as a new send. For content-protected (noforwards) chats this is
 * the only way the server accepts a forward (it rejects `messages.forwardMessages`
 * for protected ids with CHAT_FORWARDS_RESTRICTED regardless of client-side fields).
 *
 * Pattern mirrors the proven AyuGram/NagramXF AyuForward implementation: only the
 * download waiter is ported; send ordering and album assembly are handled by the
 * stock SendMessagesHelper DelayedMessage machinery.
 */
object ForwardProtectedHelper {

    private const val TAG = "InuForwardProtected"
    private const val DOWNLOAD_TIMEOUT_SECONDS = 300L

    private val resendQueue = DispatchQueue("InuResendForwardQueue")

    private val SAFE_TEXT_ENTITIES = listOf(
        TLRPC.TL_messageEntityBold::class.java,
        TLRPC.TL_messageEntityItalic::class.java,
        TLRPC.TL_messageEntityPre::class.java,
        TLRPC.TL_messageEntityCode::class.java,
        TLRPC.TL_messageEntityTextUrl::class.java,
        TLRPC.TL_messageEntitySpoiler::class.java,
        TLRPC.TL_messageEntityCustomEmoji::class.java,
    )

    /**
     * True only when the batch actually contains content that cannot be forwarded
     * normally: the source chat has forwarding restricted (noforwards) or the
     * message itself carries the noforwards flag. A pure toggle-on must NOT reroute
     * forwards from unrestricted chats through the re-upload path.
     */
    @JvmStatic
    fun needsBypass(currentAccount: Int, messages: ArrayList<MessageObject>): Boolean {
        val controller = org.telegram.messenger.MessagesController.getInstance(currentAccount)
        for (messageObject in messages) {
            val owner = messageObject?.messageOwner ?: continue
            if (owner.noforwards) return true
            val dialogId = messageObject.getDialogId()
            if (dialogId < 0) {
                // Chat/channel: raw flag, independent of the bypass toggle.
                var chat = controller.getChat(-dialogId)
                if (chat?.migrated_to != null) {
                    chat = controller.getChat(chat.migrated_to.channel_id)
                }
                if (chat != null && chat.noforwards) return true
            } else if (dialogId > 0) {
                val userFull = controller.getUserFull(dialogId)
                if (userFull != null && (userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled)) {
                    return true
                }
            }
        }
        return false
    }

    @JvmStatic
    fun resendMessages(
        currentAccount: Int,
        messages: ArrayList<MessageObject>,
        peer: Long,
        forwardFromMyName: Boolean,
        hideCaption: Boolean,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        replyToTopMsg: MessageObject?,
        payStars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams?
    ) {
        if (messages.isEmpty()) {
            return
        }
        Log.d(TAG, "resending ${messages.size} forwarded message(s) as own messages")
        resendQueue.postRunnable {
            try {
                // Download all media first on a background thread, then send on the UI thread.
                val pending = ArrayList<MessageObject>()
                for (messageObject in messages) {
                    if (isMediaDownloadable(messageObject) && !hasLocalCopy(currentAccount, messageObject)) {
                        pending.add(messageObject)
                    }
                }
                if (pending.isNotEmpty() && !loadDocumentsSync(currentAccount, pending)) {
                    Log.d(TAG, "download of protected media failed, aborting resend")
                    return@postRunnable
                }

                // One fresh group token per source album; "final" goes on the last item.
                val groupTotals = HashMap<Long, Int>()
                for (messageObject in messages) {
                    val groupId = messageObject.getGroupId()
                    if (groupId != 0L) {
                        groupTotals[groupId] = (groupTotals[groupId] ?: 0) + 1
                    }
                }
                val groupTokens = HashMap<Long, Long>()
                val groupRemaining = HashMap<Long, Int>()
                for ((groupId, count) in groupTotals) {
                    groupTokens[groupId] = Utilities.random.nextLong()
                    groupRemaining[groupId] = count
                }

                AndroidUtilities.runOnUIThread {
                    for (messageObject in messages) {
                        val owner = messageObject?.messageOwner ?: continue
                        try {
                            sendSingle(
                                currentAccount, messageObject, owner, peer, hideCaption, notify,
                                scheduleDate, scheduleRepeatPeriod, replyToTopMsg, payStars,
                                monoForumPeerId, suggestionParams, groupTokens, groupRemaining
                            )
                        } catch (t: Throwable) {
                            Log.d(TAG, "failed to resend message", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "resend of protected messages failed", t)
            }
        }
    }

    private fun sendSingle(
        currentAccount: Int,
        messageObject: MessageObject,
        owner: TLRPC.Message,
        peer: Long,
        hideCaption: Boolean,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        replyToTopMsg: MessageObject?,
        payStars: Long,
        monoForumPeerId: Long,
        suggestionParams: MessageSuggestionParams?,
        groupTokens: HashMap<Long, Long>,
        groupRemaining: HashMap<Long, Int>
    ) {
        val groupId = messageObject.getGroupId()
        val groupToken = groupTokens[groupId]
        val groupedParams: HashMap<String, String>? = if (groupToken != null) {
            val remaining = (groupRemaining[groupId] ?: 0) - 1
            groupRemaining[groupId] = remaining
            HashMap<String, String>().apply {
                put("groupId", groupToken.toString())
                if (remaining <= 0) {
                    put("final", "1")
                }
            }
        } else {
            null
        }

        val hasMediaSpoilers = messageObject.hasMediaSpoilers() && !messageObject.isHiddenSensitive()
        val invertMedia = owner.invert_media
        val helper = SendMessagesHelper.getInstance(currentAccount)

        // Stickers are whitelisted by the server; the original document works as-is.
        if (messageObject.isSticker() || messageObject.isAnimatedSticker()) {
            helper.sendSticker(
                messageObject.getDocument(), null, peer, replyToTopMsg, replyToTopMsg,
                null, null, null, notify, scheduleDate, 0, false, null, null, 0,
                payStars, monoForumPeerId, suggestionParams
            )
            return
        }

        val document = messageObject.getDocument()
        val photo = MessageObject.getPhoto(owner)

        if (document != null) {
            val file = resolveLocalFile(currentAccount, messageObject) ?: return
            val mapped = mapDocument(currentAccount, messageObject, document, file)
            val params = SendMessagesHelper.SendMessageParams.of(
                mapped, null, file.absolutePath, peer, replyToTopMsg, replyToTopMsg,
                if (hideCaption) null else owner.message, copyEntities(messageObject), null, groupedParams,
                notify, scheduleDate, scheduleRepeatPeriod, 0, null, null, false, hasMediaSpoilers
            )
            params.payStars = payStars
            params.monoForumPeer = monoForumPeerId
            params.suggestionParams = suggestionParams
            params.invert_media = invertMedia
            helper.sendMessage(params)
            return
        }

        if (photo != null) {
            val file = resolveLocalFile(currentAccount, messageObject) ?: return
            val mapped = mapPhoto(currentAccount, photo, file) ?: return
            var caption: String? = if (hideCaption) null else owner.message
            if (!hideCaption && !photo.caption.isNullOrEmpty()) {
                caption = photo.caption
            }
            if (groupedParams != null) {
                groupedParams.put("originalPath", resolvePhotoUploadTrackingPath(mapped) ?: "")
            }
            val params = SendMessagesHelper.SendMessageParams.of(
                mapped, file.absolutePath, peer, replyToTopMsg, replyToTopMsg,
                caption, copyEntities(messageObject), null, groupedParams,
                notify, scheduleDate, scheduleRepeatPeriod, 0, null, false, hasMediaSpoilers
            )
            params.payStars = payStars
            params.monoForumPeer = monoForumPeerId
            params.suggestionParams = suggestionParams
            params.invert_media = invertMedia
            helper.sendMessage(params)
            return
        }

        // Text-only (or unsupported media): send as a plain text message.
        val text = owner.message
        if (text.isNullOrEmpty()) {
            return
        }
        val textParams = SendMessagesHelper.SendMessageParams.of(
            text, peer, replyToTopMsg, replyToTopMsg, null, true,
            filterEntities(messageObject), null, null, notify, scheduleDate, scheduleRepeatPeriod, null, false
        )
        textParams.payStars = payStars
        textParams.monoForumPeer = monoForumPeerId
        textParams.suggestionParams = suggestionParams
        helper.sendMessage(textParams)
    }

    // --- downloads ---

    private fun isMediaDownloadable(messageObject: MessageObject): Boolean {
        if (messageObject.messageOwner == null) {
            return false
        }
        if (messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji) {
            return false
        }
        if (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isGif()) {
            return true
        }
        return messageObject.getDocument() != null && !messageObject.isSticker() && !messageObject.isAnimatedSticker()
    }

    private fun loadDocumentsSync(currentAccount: Int, messages: ArrayList<MessageObject>): Boolean {
        if (messages.isEmpty()) {
            return true
        }
        val fileLoader = FileLoader.getInstance(currentAccount)
        val waiter = FileDownloadWaiter(currentAccount)
        val loadActions = ArrayList<Runnable>()

        for (messageObject in messages) {
            if (!isMediaDownloadable(messageObject) || hasLocalCopy(currentAccount, messageObject)) {
                continue
            }
            val trackingKey = getDownloadTrackingKey(messageObject)
            if (trackingKey.isNullOrEmpty()) {
                continue
            }
            waiter.trackPendingDownload(trackingKey)
            if (fileLoader.isLoadingFile(trackingKey)) {
                continue
            }
            val action = createLoadAction(currentAccount, messageObject)
            if (action != null) {
                loadActions.add(action)
            } else {
                waiter.clearPendingDownload(trackingKey)
            }
        }

        if (!waiter.hasPendingDownloads()) {
            return true
        }

        waiter.subscribe()
        for (action in loadActions) {
            AndroidUtilities.runOnUIThread(action)
        }
        waiter.await()

        return !waiter.failed && allLocalCopiesReady(currentAccount, messages)
    }

    private fun getDownloadTrackingKey(messageObject: MessageObject): String? {
        val document = messageObject.getDocument()
        if (document != null) {
            return FileLoader.getAttachFileName(document)
        }
        if (messageObject.isPhoto()) {
            val photo = MessageObject.getPhoto(messageObject.messageOwner) ?: return null
            val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true))
            return if (size != null) FileLoader.getAttachFileName(size) else null
        }
        return null
    }

    private fun createLoadAction(currentAccount: Int, messageObject: MessageObject): Runnable? {
        val fileLoader = FileLoader.getInstance(currentAccount)
        val document = messageObject.getDocument()
        if (document != null) {
            return Runnable { fileLoader.loadFile(document, messageObject, FileLoader.PRIORITY_HIGH, 0) }
        }
        if (messageObject.isPhoto()) {
            val photo = MessageObject.getPhoto(messageObject.messageOwner) ?: return null
            val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true))
            val imageLocation = if (size != null) ImageLocation.getForPhoto(size, photo) else null
            if (imageLocation == null) {
                return null
            }
            return Runnable { fileLoader.loadFile(imageLocation, messageObject, null, FileLoader.PRIORITY_HIGH, 0) }
        }
        return null
    }

    private fun allLocalCopiesReady(currentAccount: Int, messages: ArrayList<MessageObject>): Boolean {
        for (messageObject in messages) {
            if (isMediaDownloadable(messageObject) && !hasLocalCopy(currentAccount, messageObject)) {
                return false
            }
        }
        return true
    }

    // --- fresh media mapping (never reuse original file_reference) ---

    private fun mapDocument(currentAccount: Int, messageObject: MessageObject, source: TLRPC.Document, file: File): TLRPC.TL_document {
        val mapped = TLRPC.TL_document()
        mapped.flags = source.flags
        mapped.file_reference = byteArrayOf()
        mapped.dc_id = Integer.MIN_VALUE
        mapped.user_id = source.user_id
        mapped.version = source.version
        mapped.mime_type = source.mime_type
        mapped.file_name = source.file_name
        mapped.file_name_fixed = source.file_name_fixed
        mapped.date = AccountInstance.getInstance(currentAccount).getConnectionsManager().getCurrentTime()
        mapped.size = file.length()
        mapped.localPath = file.absolutePath
        mapped.thumbs = source.thumbs
        mapped.video_thumbs = source.video_thumbs
        mapped.localThumbPath = source.localThumbPath
        mapped.attributes = source.attributes
        if (messageObject.isGif()) {
            mapped.mime_type = "video/mp4"
        }
        return mapped
    }

    private fun mapPhoto(currentAccount: Int, source: TLRPC.Photo, file: File): TLRPC.TL_photo? {
        val mapped = SendMessagesHelper.getInstance(currentAccount).generatePhotoSizes(file.absolutePath, null) ?: return null
        mapped.flags = source.flags
        mapped.has_stickers = source.has_stickers
        mapped.date = AccountInstance.getInstance(currentAccount).getConnectionsManager().getCurrentTime()
        mapped.geo = source.geo
        mapped.caption = source.caption
        return mapped
    }

    private fun resolvePhotoUploadTrackingPath(photo: TLRPC.TL_photo): String? {
        if (photo.sizes.isNullOrEmpty()) {
            return null
        }
        var photoSize = photo.sizes[photo.sizes.size - 1]
        if (photoSize.location == null) {
            photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true))
        }
        if (photoSize == null || photoSize.location == null) {
            return null
        }
        return File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "${photoSize.location.volume_id}_${photoSize.location.local_id}.jpg").absolutePath
    }

    // --- entities ---

    private fun copyEntities(messageObject: MessageObject): ArrayList<TLRPC.MessageEntity>? {
        val source = messageObject.messageOwner?.entities
        if (source.isNullOrEmpty()) {
            return null
        }
        return ArrayList(source)
    }

    private fun filterEntities(messageObject: MessageObject): ArrayList<TLRPC.MessageEntity>? {
        val source = messageObject.messageOwner?.entities
        if (source.isNullOrEmpty()) {
            return null
        }
        val entities = ArrayList<TLRPC.MessageEntity>()
        for (entity in source) {
            if (SAFE_TEXT_ENTITIES.any { it.isInstance(entity) }) {
                entities.add(entity)
            }
        }
        return if (entities.isEmpty()) null else entities
    }

    // --- local file resolution ---

    private fun hasLocalCopy(currentAccount: Int, messageObject: MessageObject): Boolean {
        return resolveLocalFile(currentAccount, messageObject) != null
    }

    private fun resolveLocalFile(currentAccount: Int, messageObject: MessageObject): File? {
        val owner = messageObject.messageOwner ?: return null
        val fileLoader = FileLoader.getInstance(currentAccount)
        try {
            val file = fileLoader.getPathToMessage(owner)
            if (file.exists() && !file.isDirectory) {
                return file
            }
        } catch (ignored: Throwable) {
        }
        val attach: org.telegram.tgnet.TLObject? = messageObject.getDocument() ?: MessageObject.getPhoto(owner)
        if (attach != null) {
            try {
                val file = fileLoader.getPathToAttach(attach, true)
                if (file.exists() && !file.isDirectory) {
                    return file
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    // --- download waiter (ported from AyuGram DummyFileDownloadWaiter / SyncWaiter) ---

    private class FileDownloadWaiter(private val currentAccount: Int) : NotificationCenter.NotificationCenterDelegate {

        private val pendingKeys = HashSet<String>()
        private val latch = CountDownLatch(1)
        private val subscribed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        @Volatile
        var failed = false
            private set

        @Volatile
        var timedOut = false
            private set

        private val notifications = listOf(
            NotificationCenter.fileLoaded,
            NotificationCenter.fileLoadFailed,
            NotificationCenter.httpFileDidLoad,
            NotificationCenter.httpFileDidFailedLoad,
        )

        fun trackPendingDownload(fileName: String) {
            if (fileName.isNotEmpty()) {
                pendingKeys.add(fileName)
            }
        }

        fun clearPendingDownload(fileName: String) {
            if (fileName.isNotEmpty()) {
                pendingKeys.remove(fileName)
            }
        }

        fun hasPendingDownloads(): Boolean = pendingKeys.isNotEmpty()

        fun subscribe() {
            runOnUiThreadBlocking {
                if (!subscribed.compareAndSet(false, true)) {
                    return@runOnUiThreadBlocking
                }
                val notificationCenter = NotificationCenter.getInstance(currentAccount)
                for (id in notifications) {
                    notificationCenter.addObserver(this, id)
                }
            }
        }

        fun await() {
            val completed = try {
                latch.await(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            timedOut = !completed
            if (!completed) {
                release()
            }
        }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (args.isEmpty() || args[0] !is String) {
                return
            }
            val loadedKey = args[0] as String
            val failure = id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad
            Utilities.globalQueue.postRunnable { process(loadedKey, failure) }
        }

        private fun process(loadedKey: String, failure: Boolean) {
            if (loadedKey.isEmpty() || pendingKeys.isEmpty()) {
                return
            }
            val iterator = pendingKeys.iterator()
            while (iterator.hasNext()) {
                val pendingKey = iterator.next()
                if (matches(pendingKey, loadedKey)) {
                    if (failure) {
                        failed = true
                        release()
                        return
                    }
                    iterator.remove()
                }
            }
            if (pendingKeys.isEmpty()) {
                release()
            }
        }

        private fun matches(pendingKey: String, loadedKey: String): Boolean {
            return pendingKey == loadedKey ||
                loadedKey.endsWith("/$pendingKey") ||
                loadedKey.endsWith("\\$pendingKey")
        }

        private fun release() {
            if (!released.compareAndSet(false, true)) {
                return
            }
            runOnUiThreadBlocking {
                if (!subscribed.compareAndSet(true, false)) {
                    return@runOnUiThreadBlocking
                }
                val notificationCenter = NotificationCenter.getInstance(currentAccount)
                for (id in notifications) {
                    notificationCenter.removeObserver(this, id)
                }
            }
            latch.countDown()
        }

        private fun runOnUiThreadBlocking(action: Runnable) {
            val handler = ApplicationLoader.applicationHandler
            if (handler != null && Thread.currentThread() === handler.looper.thread) {
                action.run()
                return
            }
            val done = CountDownLatch(1)
            AndroidUtilities.runOnUIThread {
                try {
                    action.run()
                } finally {
                    done.countDown()
                }
            }
            try {
                done.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
