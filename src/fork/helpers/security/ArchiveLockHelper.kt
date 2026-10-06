package desu.inugram.helpers.security

import android.content.Context
import desu.inugram.InuConfig
import org.telegram.ui.ActionBar.BaseFragment
import java.util.Collections
import java.util.WeakHashMap

object ArchiveLockHelper {
    private var unlockedThisSession = false

    // entiny: onResume fires again after the prompt and after returning from a chat, so ask once per opened archive screen
    private val handled: MutableSet<BaseFragment> = Collections.newSetFromMap(WeakHashMap())

    @JvmStatic
    fun shouldGate(fragment: BaseFragment, folderId: Int): Boolean {
        if (folderId != 1 || !InuConfig.BIOMETRIC_LOCK_ARCHIVE.value || !BiometricHelper.isSupported()) return false
        if (handled.contains(fragment)) return false
        if (!InuConfig.BIOMETRIC_LOCK_ARCHIVE_EVERY_TIME.value && unlockedThisSession) return false
        handled.add(fragment)
        return true
    }

    @JvmStatic
    fun gate(context: Context?, onSuccess: Runnable, onCancel: Runnable) {
        BiometricHelper.gate(context, true, Runnable {
            unlockedThisSession = true
            onSuccess.run()
        }, onCancel)
    }
}
