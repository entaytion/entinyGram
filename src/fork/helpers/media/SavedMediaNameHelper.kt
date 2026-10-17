package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SavedMediaNameHelper {
    @JvmStatic
    fun name(message: MessageObject?, path: String?, original: String?, video: Boolean): String? {
        if (!InuConfig.SAVE_FILENAME_DATE.value) return original
        val date = message?.messageOwner?.date ?: return original
        if (date <= 0) return original
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(date * 1000L))
        if (original.isNullOrEmpty()) {
            val ext = path?.let { FileLoader.getFileExtension(File(it)) }?.takeIf { it.isNotEmpty() } ?: if (video) "mp4" else "jpg"
            return "${if (video) "VID" else "IMG"}_$stamp.$ext"
        }
        val dot = original.lastIndexOf('.')
        return if (dot > 0) "${original.substring(0, dot)}_$stamp${original.substring(dot)}" else "${original}_$stamp"
    }
}
