package desu.inugram.helpers.chat

import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.MediaController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatAttachAlert

object StickerSendHelper {
    private const val STICKER_SIZE = 512
    private const val ARM_TTL_MS = 8000L

    private var armedAt = 0L

    @JvmStatic
    fun arm() {
        armedAt = System.currentTimeMillis()
    }

    @JvmStatic
    fun isPhoto(entry: Any?): Boolean =
        entry is MediaController.PhotoEntry && !entry.isVideo && !entry.isLivePhoto()

    @JvmStatic
    fun canSendPhoto(chat: ChatActivity?, current: Any?): Boolean =
        chat != null && !chat.isSecretChat && isPhoto(current)

    @JvmStatic
    fun canSendSelected(alert: ChatAttachAlert): Boolean {
        val chat = alert.baseFragment as? ChatActivity ?: return false
        if (chat.isSecretChat) return false
        val layout = alert.photoLayout ?: return false
        val photos = layout.selectedPhotos
        val order = layout.selectedPhotosOrder
        return order.isNotEmpty() && order.all { isPhoto(photos[it]) }
    }

    @JvmStatic
    fun applyIfArmed(media: ArrayList<SendMessagesHelper.SendingMediaInfo>) {
        val fresh = System.currentTimeMillis() - armedAt < ARM_TTL_MS
        armedAt = 0L
        if (!fresh) return
        for (info in media) {
            if (info.isVideo || info.isLivePhoto || info.searchImage != null || info.inlineResult != null) continue
            val source = info.path ?: continue
            val sticker = convert(source) ?: continue
            info.path = sticker.absolutePath
            info.uri = null
            info.thumbPath = null
            info.caption = null
            info.entities = null
            info.masks = null
            info.ttl = 0
            info.forceImage = false
            info.highQuality = false
            info.hasMediaSpoilers = false
            info.videoEditedInfo = null
            info.canDeleteAfter = true
        }
    }

    private fun convert(path: String): File? {
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            bitmap = ImageLoader.loadBitmap(path, null, STICKER_SIZE.toFloat(), STICKER_SIZE.toFloat(), true) ?: return null
            val scale = STICKER_SIZE / maxOf(bitmap.width, bitmap.height).toFloat()
            scaled = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, Math.round(bitmap.width * scale)),
                maxOf(1, Math.round(bitmap.height * scale)),
                true,
            )
            val out = File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "${SharedConfig.getLastLocalId()}_inu_sticker.webp")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            FileOutputStream(out).use { scaled.compress(format, 100, it) }
            out
        } catch (e: Throwable) {
            FileLog.e(e)
            null
        } finally {
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            bitmap?.recycle()
        }
    }
}
