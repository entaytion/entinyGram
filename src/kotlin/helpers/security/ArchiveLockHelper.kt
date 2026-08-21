package desu.inugram.helpers.security

import android.content.Context
import desu.inugram.InuConfig

/**
 * Gates entry into the Archived Chats folder (folderId == 1) behind biometric auth.
 * Session-unlock state is process-lifetime — resets on app restart, not on leaving the screen,
 * so "once per session" mode doesn't re-prompt every time you dip in and out of the archive.
 */
object ArchiveLockHelper {
    private var unlockedThisSession = false

    @JvmStatic
    fun shouldGate(folderId: Int): Boolean {
        if (folderId != 1 || !InuConfig.BIOMETRIC_LOCK_ARCHIVE.value || !BiometricHelper.isSupported()) return false
        return InuConfig.BIOMETRIC_LOCK_ARCHIVE_EVERY_TIME.value || !unlockedThisSession
    }

    @JvmStatic
    fun gate(context: Context?, onSuccess: Runnable, onCancel: Runnable) {
        BiometricHelper.gate(context, true, Runnable {
            unlockedThisSession = true
            onSuccess.run()
        }, onCancel)
    }
}
