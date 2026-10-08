package desu.inugram.helpers.dialogs

import desu.inugram.helpers.InuPrefs
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader

object FolderTagsHelper {
    private fun prefs() = InuPrefs.of("inugram_folder_tags")

    private fun key(account: Int) = "local_only_$account"

    @JvmStatic
    fun isLocalOnly(account: Int): Boolean = InuConfig.LOCAL_PREMIUM.value && prefs().getBoolean(key(account), false)

    @JvmStatic
    fun onToggleResult(account: Int, failed: Boolean) {
        if (!InuConfig.LOCAL_PREMIUM.value) return
        prefs().edit().putBoolean(key(account), failed).apply()
    }
}
