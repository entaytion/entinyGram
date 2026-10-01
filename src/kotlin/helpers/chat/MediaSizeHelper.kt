package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessageObject
import org.telegram.ui.Cells.ChatMessageCell
import java.io.File
import java.util.WeakHashMap

object MediaSizeHelper {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f).toFloat()
    }
    private val rect = RectF()
    private val uploadSizes = WeakHashMap<MessageObject, Long>()

    @JvmStatic
    fun draw(cell: ChatMessageCell, canvas: Canvas, image: ImageReceiver) {
        val msg = cell.messageObject ?: return
        if (msg.type != MessageObject.TYPE_PHOTO || !image.visible || msg.needDrawBluredPreview()) return
        if (image.imageWidth < dp(96f)) return
        val size = sizeOf(msg)
        if (size <= 0) return
        val text = AndroidUtilities.formatFileSize(size)
        val x = image.imageX + dp(8f)
        val y = image.imageY + dp(8f)
        rect.set(x, y, x + textPaint.measureText(text) + dp(12f), y + dp(20f))
        canvas.drawRoundRect(rect, dp(10f).toFloat(), dp(10f).toFloat(), bgPaint)
        canvas.drawText(text, x + dp(6f), y + dp(14.5f), textPaint)
    }

    private fun sizeOf(msg: MessageObject): Long {
        if (msg.isSending) {
            val path = msg.messageOwner?.attachPath
            if (!path.isNullOrEmpty()) {
                val cached = uploadSizes[msg]
                if (cached != null) return cached
                val length = File(path).length()
                if (length > 0) uploadSizes[msg] = length
                return length
            }
        }
        return (FileLoader.getClosestPhotoSizeWithSize(msg.photoThumbs, AndroidUtilities.getPhotoSize())?.size ?: 0).toLong()
    }
}
