package desu.inugram.helpers.security

import android.content.Context
import desu.inugram.InuConfig

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
