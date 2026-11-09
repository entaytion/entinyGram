package desu.inugram.helpers.security

import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object TimedProfileMedia {
    @JvmStatic
    fun enabled(dialogId: Long, topicId: Long) =
        topicId == 0L && DialogObject.isUserDialog(dialogId) && SelfDestructHelper.shouldPreserveMedia(dialogId)

    private fun read(account: Int, dialogId: Long, low: Int, high: Int): List<TLRPC.Message> {
        val result = ArrayList<TLRPC.Message>()
        try {
            val db = MessagesStorage.getInstance(account).database ?: return result
            val loader = FileLoader.getInstance(account)
            val self = UserConfig.getInstance(account).clientUserId
            val cursor = db.queryFinalized(
                "SELECT data, mid FROM messages_v2 WHERE uid = ? AND out = 0 AND media = 1 AND mid BETWEEN ? AND ?",
                dialogId, low, high,
            )
            try {
                while (cursor.next()) {
                    val data = cursor.byteBufferValue(0) ?: continue
                    val message = try {
                        TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)?.also { it.readAttachPath(data, self) }
                    } finally {
                        data.reuse()
                    } ?: continue
                    if ((MessageObject.getMedia(message)?.ttl_seconds ?: 0) == 0 ||
                        MediaDataController.getMediaType(message) != MediaDataController.MEDIA_PHOTOVIDEO) continue
                    message.id = cursor.intValue(1)
                    message.dialog_id = dialogId
                    if (loader.getPathToMessage(message).isFile || loader.getPathToMessage(message, true, true).isFile) result.add(message)
                }
            } finally {
                cursor.dispose()
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return result
    }

    @JvmStatic
    fun counts(account: Int, dialogId: Long, topicId: Long): IntArray? {
        if (!enabled(dialogId, topicId)) return null
        val messages = read(account, dialogId, 1, Int.MAX_VALUE)
        val videos = messages.count { MessageObject.isVideoMessage(it) }
        return IntArray(MediaDataController.MEDIA_TYPES_COUNT).apply {
            this[MediaDataController.MEDIA_PHOTOVIDEO] = messages.size
            this[MediaDataController.MEDIA_PHOTOS_ONLY] = messages.size - videos
            this[MediaDataController.MEDIA_VIDEOS_ONLY] = videos
        }
    }

    @JvmStatic
    fun addCounts(counts: IntArray, local: IntArray?): IntArray = if (local == null) counts else counts.copyOf().apply {
        for (i in indices) if (local[i] > 0) this[i] = this[i].coerceAtLeast(0) + local[i]
    }

    @JvmStatic
    fun merge(account: Int, dialogId: Long, type: Int, maxId: Int, minId: Int, endReached: Boolean, page: ArrayList<TLRPC.Message>) {
        if (type != MediaDataController.MEDIA_PHOTOVIDEO && type != MediaDataController.MEDIA_PHOTOS_ONLY && type != MediaDataController.MEDIA_VIDEOS_ONLY) return
        val newest = page.maxOfOrNull { it.id }
        val oldest = page.minOfOrNull { it.id }
        val low = if (minId > 0) minId + 1 else if (endReached) 1 else oldest ?: return
        val high = if (minId > 0 && !endReached) newest ?: return else if (maxId > 0) maxId - 1 else Int.MAX_VALUE
        val ids = page.mapTo(HashSet()) { it.id }
        val added = read(account, dialogId, low, high).filter {
            it.id !in ids && when (type) {
                MediaDataController.MEDIA_PHOTOS_ONLY -> !MessageObject.isVideoMessage(it)
                MediaDataController.MEDIA_VIDEOS_ONLY -> MessageObject.isVideoMessage(it)
                else -> true
            }
        }
        if (added.isNotEmpty()) {
            page.addAll(added)
            page.sortByDescending { it.id }
        }
    }
}
