package desu.inugram.helpers.media

import android.content.Context
import desu.inugram.helpers.InuPrefs
import org.telegram.messenger.MediaController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import java.io.File

object StoryAutoSaveHelper {
    private const val PREFS = "inu_auto_saved_stories"
    private const val KEY_IDS = "ids"
    private const val MAX_REMEMBERED = 2000
    private const val RETRY_DELAY_MS = 1000L
    private const val MAX_ATTEMPTS = 3

    private val saved by lazy {
        val raw = InuPrefs.of(PREFS).getString(KEY_IDS, "") ?: ""
        LinkedHashSet(raw.split(',').filter { it.isNotEmpty() })
    }

    private var curAccount = -1
    private var curDialogId = 0L
    private var curStoryId = -1
    private var curDone = false
    private var attempts = 0
    private var lastAttemptAt = 0L

    // entiny: called from the story draw loop; only a confirmed save is remembered, so a failed or early attempt retries
    @JvmStatic
    fun onStoryShown(context: Context?, account: Int, dialogId: Long, storyId: Int, path: File?, isVideo: Boolean) {
        if (context == null || dialogId == UserConfig.getInstance(account).clientUserId) return
        if (account != curAccount || dialogId != curDialogId || storyId != curStoryId) {
            curAccount = account
            curDialogId = dialogId
            curStoryId = storyId
            curDone = saved.contains(keyOf(account, dialogId, storyId))
            attempts = 0
            lastAttemptAt = 0L
        }
        if (curDone || attempts >= MAX_ATTEMPTS) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < RETRY_DELAY_MS) return
        if (path == null || !path.exists()) return
        lastAttemptAt = now
        attempts++
        val key = keyOf(account, dialogId, storyId)
        MediaController.saveFile(path.toString(), context, if (isVideo) 1 else 0, null, null, Utilities.Callback { _ -> markSaved(key) }, false)
    }

    private fun keyOf(account: Int, dialogId: Long, storyId: Int) = "$account:$dialogId:$storyId"

    private fun markSaved(key: String) {
        saved.add(key)
        while (saved.size > MAX_REMEMBERED) saved.remove(saved.first())
        InuPrefs.of(PREFS).edit().putString(KEY_IDS, saved.joinToString(",")).apply()
        if (key == keyOf(curAccount, curDialogId, curStoryId)) curDone = true
    }
}
