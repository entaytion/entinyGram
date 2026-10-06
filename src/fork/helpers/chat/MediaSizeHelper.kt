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

    private class Label(val text: String, val width: Float, val size: Long)

    private val labels = WeakHashMap<MessageObject, Label>()

    @JvmStatic
    fun draw(cell: ChatMessageCell, canvas: Canvas, image: ImageReceiver) {
        val msg = cell.messageObject ?: return
        if (msg.type != MessageObject.TYPE_PHOTO || !image.visible || msg.needDrawBluredPreview()) return
        if (image.imageWidth < dp(96f)) return
        val label = labelOf(msg) ?: return
        val x = image.imageX + dp(8f)
        val y = image.imageY + dp(8f)
        rect.set(x, y, x + label.width + dp(12f), y + dp(20f))
        canvas.drawRoundRect(rect, dp(10f).toFloat(), dp(10f).toFloat(), bgPaint)
        canvas.drawText(label.text, x + dp(6f), y + dp(14.5f), textPaint)
    }

    // entiny: formatFileSize is String.format, so cache per message rather than per frame
    private fun labelOf(msg: MessageObject): Label? {
        labels[msg]?.let { if (it.size == sizeOf(msg)) return it }
        val size = sizeOf(msg)
        if (size <= 0) return null
        val text = AndroidUtilities.formatFileSize(size)
        val label = Label(text, textPaint.measureText(text), size)
        labels[msg] = label
        return label
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
