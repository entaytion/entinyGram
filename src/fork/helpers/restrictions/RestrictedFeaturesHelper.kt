package desu.inugram.helpers.restrictions

import desu.inugram.InuConfig
import desu.inugram.helpers.chat.ChatHelper
import desu.inugram.helpers.security.SelfDestructHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessageSuggestionParams
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RestrictedFeaturesHelper {
    private const val RESTRICTED_FORWARD_TIMEOUT_MS = 10 * 60 * 1000L

    internal val restrictedForwardQueue by lazy { DispatchQueue("inuRestrictedForward") }

    @JvmStatic
    fun noforwards(message: TLRPC.Message?): Boolean =
        message?.noforwards == true && !InuConfig.ALLOW_FORWARD_RESTRICTED.value

    // entiny: check raw noforwards flags because isPeerNoForwards returns false when toggle is on
    private fun isSourceNoForwards(controller: MessagesController, msg: MessageObject): Boolean {
        if (msg.messageOwner?.noforwards == true) return true
        val dialogId = msg.dialogId
        if (dialogId < 0) {
            val chat = controller.getChat(-dialogId) ?: return false
            val migratedTo = chat.migrated_to?.let { controller.getChat(it.channel_id) }
            return (migratedTo ?: chat).noforwards
        }
        val userFull = controller.getUserFull(dialogId) ?: return false
        return userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled
    }

    // entiny: server rejects replies to protected messages from another chat, so the quote goes in as bold title + blockquote
    @JvmStatic
    fun restrictedReplyPrefix(
        account: Int,
        quote: ChatActivity.ReplyQuote?,
        peer: Long,
        entities: MutableList<TLRPC.MessageEntity>,
    ): String? {
        val source = quote?.message ?: return null
        val controller = MessagesController.getInstance(account)
        if (!InuConfig.ALLOW_FORWARD_RESTRICTED.value || source.dialogId == peer) return null
        if (!isSourceNoForwards(controller, source)) return null
        val text = (quote.text?.toString()?.takeIf { it.isNotEmpty() } ?: source.messageText?.toString()).orEmpty()
        val title = if (source.dialogId < 0) controller.getChat(-source.dialogId)?.title.orEmpty() else UserObject.getUserName(controller.getUser(source.dialogId))
        if (text.isEmpty() && title.isEmpty()) return ""
        val header = if (title.isEmpty()) "" else title + "\n"
        val body = if (text.isEmpty()) "" else text + "\n"
        val prefix = header + body
        entities.forEach { it.offset += prefix.length }
        entities.add(0, TLRPC.TL_messageEntityBlockquote().apply { offset = 0; length = header.length + text.length })
        if (title.isNotEmpty()) {
            entities.add(1, TLRPC.TL_messageEntityBold().apply { offset = 0; length = title.length })
        }
        return prefix
    }

    @JvmStatic
    fun splitRestrictedForward(messages: MutableList<MessageObject>, controller: MessagesController): ArrayList<MessageObject>? {
        var restricted: ArrayList<MessageObject>? = null
        val it = messages.iterator()
        while (it.hasNext()) {
            val msg = it.next()
            if (msg.id <= 0) continue
            // entiny: cloud view-once media joins the force-forward re-upload path when the one-time gate is bypassed
            val onceView = (msg.messageOwner?.media?.ttl_seconds ?: 0) != 0 &&
                SelfDestructHelper.shouldPreserveMedia(msg.dialogId) &&
                !DialogObject.isEncryptedDialog(msg.dialogId)
            if (!onceView) {
                if (msg.needDrawBluredPreview()) continue
                if ((msg.messageOwner?.media?.ttl_seconds ?: 0) != 0) continue
                if (DialogObject.isEncryptedDialog(msg.dialogId)) continue
                if (!isSourceNoForwards(controller, msg)) continue
            }
            if (restricted == null) restricted = ArrayList()
            restricted.add(msg)
            it.remove()
        }
        return restricted
    }

    @JvmStatic
    fun sendRestrictedForward(
        helper: SendMessagesHelper,
        account: Int,
        messages: List<MessageObject>,
        did: Long,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        threadMsg: MessageObject?,
        mono: Long,
        suggest: MessageSuggestionParams?,
        hideCaption: Boolean,
        payStars: Long,
    ) {
        val limit = if (UserConfig.getInstance(account).isPremium) FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM else FileLoader.DEFAULT_MAX_FILE_SIZE
        val (tooBig, fits) = messages.partition { (it.document?.size ?: 0L) > limit }
        // entiny: re-upload hits the account upload limit, so skip oversized files up front instead of a silent "!"
        if (tooBig.isNotEmpty()) showForwardToast(LocaleController.formatString(R.string.InuForwardTooLarge, AndroidUtilities.formatFileSize(limit)))
        val batch = ArrayList(fits)
        if (batch.isEmpty()) return
        restrictedForwardQueue.postRunnable {
            val temporaryFiles = ArrayList<File>()
            val files = awaitRestrictedMedia(account, batch, temporaryFiles)
            val missingViewOnce = batch.any { isCloudViewOnce(it) && needsMediaReupload(it) && files[it] == null }
            val actions = buildRestrictedForwardActions(
                helper, account, batch, files, did, notify, scheduleDate, scheduleRepeatPeriod,
                threadMsg, mono, suggest, hideCaption, payStars,
            )
            if (missingViewOnce) {
                AndroidUtilities.runOnUIThread { ChatHelper.showOneTimeMediaError() }
            }
            if (actions.isEmpty()) {
                temporaryFiles.forEach { it.delete() }
                return@postRunnable
            }
            watchRestrictedForwardUploads(account, temporaryFiles)
            AndroidUtilities.runOnUIThread { actions.forEach { it.run() } }
        }
    }

    private fun buildRestrictedForwardActions(
        helper: SendMessagesHelper,
        account: Int,
        messages: List<MessageObject>,
        files: Map<MessageObject, File>,
        did: Long,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        threadMsg: MessageObject?,
        mono: Long,
        suggest: MessageSuggestionParams?,
        hideCaption: Boolean,
        payStars: Long,
    ): List<Runnable> {
        val actions = ArrayList<Runnable>()
        var index = 0
        while (index < messages.size) {
            val target = messages[index]
            val groupId = target.groupId
            var end = index + 1
            if (groupId != 0L) {
                while (end < messages.size && messages[end].groupId == groupId) end++
            }

            val album = if (end - index > 1) {
                messages.subList(index, end).mapNotNull { msg ->
                    // entiny: exclude stickers and voice from albums to prevent replacing shared group DelayedMessage
                    if (msg.isAnyKindOfSticker || msg.isVoice || (needsMediaReupload(msg) && files[msg] == null)) null
                    else ChatHelper.buildResendParams(
                        account, msg, did, null, threadMsg, notify, scheduleDate,
                        scheduleRepeatPeriod, hideCaption, files[msg], isCloudViewOnce(msg),
                    )?.takeIf { it.photo != null || it.document != null }
                }
            } else {
                emptyList<SendMessagesHelper.SendMessageParams>()
            }

            if (album.size > 1) {
                val newGroupId = Utilities.random.nextLong()
                album.forEachIndexed { i, params ->
                    params.params = HashMap<String, String>().apply {
                        put("groupId", newGroupId.toString())
                        if (i == album.size - 1) put("final", "1")
                    }
                    params.monoForumPeer = mono
                    params.suggestionParams = suggest
                    // entiny: pass payStars explicitly because grouped send skips sendMessage's paid confirmation
                    params.payStars = payStars
                    actions.add(Runnable { helper.sendMessage(params) })
                }
            } else {
                for (i in index until end) {
                    val msg = messages[i]
                    // entiny: by-reference send of protected media is always rejected by the server
                    if (needsMediaReupload(msg) && files[msg] == null) continue
                    ChatHelper.buildResendAction(
                        helper, account, msg, did, null, threadMsg, null, notify, scheduleDate,
                        scheduleRepeatPeriod, mono, suggest, null, hideCaption, payStars, files[msg],
                        isCloudViewOnce(msg),
                    )?.let { actions.add(it) }
                }
            }
            index = end
        }
        return actions
    }

    internal fun awaitRestrictedMedia(
        account: Int,
        messages: List<MessageObject>,
        temporaryFiles: MutableList<File>,
    ): Map<MessageObject, File> {
        val loader = FileLoader.getInstance(account)
        val resolved = HashMap<MessageObject, File>()
        val pending = LinkedHashMap<String, MessageObject>()

        for (msg in messages) {
            if (!needsMediaReupload(msg)) continue
            val existing = localMediaFile(loader, msg)
            if (existing != null) {
                forwardableMediaFile(existing, temporaryFiles)?.let { resolved[msg] = it }
                continue
            }
            val key = downloadKey(msg) ?: continue
            pending[key] = msg
        }
        if (pending.isEmpty()) return resolved

        val totalBytes = pending.values.sumOf { it.document?.size ?: 0L }
        showForwardToast(LocaleController.formatString(R.string.InuForwardDownloading, AndroidUtilities.formatFileSize(totalBytes)))
        val waiter = MediaDownloadWaiter(account, pending.keys)
        // entiny: subscribe NotificationCenter observer on main thread before starting downloads to close race
        AndroidUtilities.runOnUIThread {
            waiter.subscribe()
            for ((key, msg) in pending) {
                if (localMediaFile(loader, msg) != null || !startMediaLoad(loader, msg)) {
                    waiter.complete(key)
                }
            }
            waiter.settle()
        }
        waiter.await(RESTRICTED_FORWARD_TIMEOUT_MS)

        for (msg in messages) {
            if (!needsMediaReupload(msg) || resolved.containsKey(msg)) continue
            // entiny: path DB lags behind fileLoaded, so fall back to the file the notification delivered
            val loaded = localMediaFile(loader, msg) ?: downloadKey(msg)?.let { waiter.loadedFile(it) }
            loaded?.let { forwardableMediaFile(it, temporaryFiles) }?.let { resolved[msg] = it }
        }
        if (pending.values.any { !resolved.containsKey(it) }) showForwardToast(LocaleController.getString(R.string.InuForwardDownloadFailed))
        return resolved
    }

    private fun showForwardToast(text: String) {
        AndroidUtilities.runOnUIThread {
            android.widget.Toast.makeText(ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun forwardableMediaFile(source: File, temporaryFiles: MutableList<File>): File? {
        if (!source.name.endsWith(".enc")) return source
        val keyFile = File(FileLoader.getInternalCacheDir(), source.name + ".key")
        if (!keyFile.exists()) return null
        val cacheDir = File(FileLoader.getInternalCacheDir(), "inu_forward_once")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null
        val extension = source.name.removeSuffix(".enc").substringAfterLast('.', "")
        val target = File(cacheDir, "${UUID.randomUUID()}${if (extension.isEmpty()) "" else ".$extension"}")
        if (!ChatHelper.decryptOneTimeFile(source, keyFile, target)) {
            target.delete()
            return null
        }
        temporaryFiles.add(target)
        return target
    }

    private fun watchRestrictedForwardUploads(account: Int, files: List<File>) {
        if (files.isEmpty()) return
        val pending = HashSet<String>().apply { files.forEach { add(it.absolutePath) } }
        val center = NotificationCenter.getInstance(account)
        lateinit var observer: NotificationCenter.NotificationCenterDelegate
        fun finish() {
            center.removeObserver(observer, NotificationCenter.fileUploaded)
            center.removeObserver(observer, NotificationCenter.fileUploadFailed)
            files.forEach { it.delete() }
        }
        observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                val path = args.getOrNull(0) as? String ?: return
                if (!pending.remove(File(path).absolutePath)) return
                if (pending.isEmpty()) finish()
            }
        }
        center.addObserver(observer, NotificationCenter.fileUploaded)
        center.addObserver(observer, NotificationCenter.fileUploadFailed)
        Utilities.globalQueue.postRunnable({
            AndroidUtilities.runOnUIThread { finish() }
        }, 24 * 60 * 60 * 1000L)
    }

    private fun needsMediaReupload(message: MessageObject): Boolean {
        if (message.isAnyKindOfSticker) return false
        val media = message.messageOwner?.media ?: return false
        return media.photo is TLRPC.TL_photo || media.document is TLRPC.TL_document
    }

    private fun isCloudViewOnce(message: MessageObject): Boolean {
        val media = message.messageOwner?.media ?: return false
        return media.ttl_seconds != 0 &&
            SelfDestructHelper.shouldBypassOneTimeGate(message.dialogId) &&
            !DialogObject.isEncryptedDialog(message.dialogId) &&
            (media.photo is TLRPC.TL_photo || media.document is TLRPC.TL_document)
    }

    private fun localMediaFile(loader: FileLoader, message: MessageObject): File? {
        val attach = message.messageOwner?.attachPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (attach != null && attach.exists() && attach.length() > 0L) return attach
        return loader.getPathToMessage(message.messageOwner)?.takeIf { it.exists() && it.length() > 0L }
    }

    private fun downloadKey(message: MessageObject): String? {
        val media = message.messageOwner?.media ?: return null
        (media.document as? TLRPC.TL_document)?.let { return FileLoader.getAttachFileName(it) }
        return fullPhotoSize(media)?.let { FileLoader.getAttachFileName(it) }
    }

    // entiny: match the photo size FileLoader.getPathToMessage resolves so download and lookup paths align
    private fun fullPhotoSize(media: TLRPC.MessageMedia): TLRPC.PhotoSize? {
        val photo = media.photo as? TLRPC.TL_photo ?: return null
        return FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true)
    }

    private fun startMediaLoad(loader: FileLoader, message: MessageObject): Boolean {
        val media = message.messageOwner?.media ?: return false
        val cacheType = if (message.shouldEncryptPhotoOrVideo()) 2 else 0
        val document = media.document as? TLRPC.TL_document
        if (document != null) {
            loader.loadFile(document, message, FileLoader.PRIORITY_NORMAL, cacheType)
            return true
        }
        val photo = media.photo as? TLRPC.TL_photo ?: return false
        val size = fullPhotoSize(media) ?: return false
        val location = ImageLocation.getForPhoto(size, photo) ?: return false
        loader.loadFile(location, message, null, FileLoader.PRIORITY_NORMAL, cacheType)
        return true
    }

    private class MediaDownloadWaiter(
        private val account: Int,
        keys: Collection<String>,
    ) : NotificationCenter.NotificationCenterDelegate {
        private val pending = HashSet(keys)
        private val latch = CountDownLatch(1)
        private val loaded = java.util.concurrent.ConcurrentHashMap<String, File>()

        fun loadedFile(key: String): File? = loaded[key]?.takeIf { it.exists() && it.length() > 0L }

        fun subscribe() {
            val center = NotificationCenter.getInstance(account)
            center.addObserver(this, NotificationCenter.fileLoaded)
            center.addObserver(this, NotificationCenter.fileLoadFailed)
        }

        fun complete(key: String) {
            pending.remove(key)
        }

        fun settle() {
            if (pending.isNotEmpty()) return
            unsubscribe()
            latch.countDown()
        }

        fun await(timeoutMs: Long) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            AndroidUtilities.runOnUIThread { unsubscribe() }
        }

        private fun unsubscribe() {
            val center = NotificationCenter.getInstance(account)
            center.removeObserver(this, NotificationCenter.fileLoaded)
            center.removeObserver(this, NotificationCenter.fileLoadFailed)
        }

        // entiny: treat load failure like completion so caller falls back to by-reference send instead of stalling
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            val key = args.getOrNull(0) as? String ?: return
            (args.getOrNull(1) as? File)?.let { loaded[key] = it }
            if (!pending.remove(key)) return
            settle()
        }
    }
}
