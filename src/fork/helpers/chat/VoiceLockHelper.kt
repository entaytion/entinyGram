package desu.inugram.helpers.chat

import android.app.KeyguardManager
import android.content.Context
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject

object VoiceLockHelper {
    @JvmStatic
    fun blocks(message: MessageObject): Boolean {
        if (!InuConfig.QUIET_VOICE_WHEN_LOCKED.value) return false
        if (!message.isVoice && !message.isRoundVideo) return false
        val keyguard = ApplicationLoader.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isKeyguardLocked == true
    }
}
