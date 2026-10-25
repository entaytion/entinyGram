package desu.inugram.helpers.media

import android.graphics.Bitmap
import android.util.LruCache
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.Utilities
import org.telegram.messenger.audioinfo.AudioInfo
import java.io.File
import java.util.Optional
import java.util.function.Consumer

object AudioCoverHelper {
    private val covers = LruCache<String, Optional<Bitmap>>(64)
    private val pending = HashMap<String, MutableList<Consumer<Bitmap>>>()

    @JvmStatic
    fun loadSmallCover(messageObject: MessageObject, callback: Consumer<Bitmap>) {
        val file = messageObject.messageOwner.attachPath?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
            ?: FileLoader.getInstance(messageObject.currentAccount).getPathToAttach(messageObject.document)
        if (file.length() == 0L) return

        val key = file.absolutePath
        covers.get(key)?.let { cached ->
            cached.ifPresent(callback)
            return
        }
        pending[key]?.let {
            it.add(callback)
            return
        }
        pending[key] = mutableListOf(callback)
        Utilities.globalQueue.postRunnable {
            val cover = AudioInfo.getAudioInfo(file)?.smallCover
            AndroidUtilities.runOnUIThread {
                covers.put(key, Optional.ofNullable(cover))
                val callbacks = pending.remove(key) ?: return@runOnUIThread
                if (cover != null) callbacks.forEach { it.accept(cover) }
            }
        }
    }
}
