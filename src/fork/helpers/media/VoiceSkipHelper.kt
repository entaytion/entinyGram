package desu.inugram.helpers.media

import android.support.v4.media.session.PlaybackStateCompat
import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject

object VoiceSkipHelper {
    @JvmStatic
    fun allows(message: MessageObject?): Boolean = actions(message) != 0L

    @JvmStatic
    fun actions(message: MessageObject?): Long {
        if (message == null || !InuConfig.VOICE_SKIP_IN_SESSION.value || !message.isVoice) return 0L
        return PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    }
}
