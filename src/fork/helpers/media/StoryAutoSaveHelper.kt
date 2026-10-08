package desu.inugram.helpers.media

import desu.inugram.helpers.InuPrefs
import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MediaController
import org.telegram.messenger.UserConfig
import java.io.File

object StoryAutoSaveHelper {
    private const val PREFS = "inu_auto_saved_stories"
    private const val KEY_IDS = "ids"
    private const val MAX_REMEMBERED = 2000
    private const val RETRY_DELAY_MS = 1000L

    private val saved by lazy {
        val raw = InuPrefs.of(PREFS).getString(KEY_IDS, "") ?: ""
        LinkedHashSet(raw.split(',').filter { it.isNotEmpty() })
    }
    private var lastAttemptKey: String? = null
    private var lastAttemptAt = 0L

    // entiny: called from the story draw loop; the file only exists once fully downloaded, so a miss just retries later
    @JvmStatic
    fun onStoryShown(context: Context?, account: Int, dialogId: Long, storyId: Int, path: File?, isVideo: Boolean) {
        if (context == null || dialogId == UserConfig.getInstance(account).clientUserId) return
        val key = "$account:$dialogId:$storyId"
        if (saved.contains(key)) return
        val now = System.currentTimeMillis()
        if (key == lastAttemptKey && now - lastAttemptAt < RETRY_DELAY_MS) return
        lastAttemptKey = key
        lastAttemptAt = now
        if (path == null || !path.exists()) return
        remember(key)
        MediaController.saveFile(path.toString(), context, if (isVideo) 1 else 0, null, null, null, false)
    }

    private fun remember(key: String) {
        saved.add(key)
        while (saved.size > MAX_REMEMBERED) saved.remove(saved.first())
        InuPrefs.of(PREFS)
            .edit().putString(KEY_IDS, saved.joinToString(",")).apply()
    }
}
