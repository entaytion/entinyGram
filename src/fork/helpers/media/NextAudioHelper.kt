package desu.inugram.helpers.media

import desu.inugram.InuConfig

object NextAudioHelper {
    @JvmStatic
    fun skipPreload(): Boolean = InuConfig.DISABLE_NEXT_AUDIO_PRELOAD.value

    @JvmStatic
    fun stopAfterMusic(): Boolean = InuConfig.DISABLE_NEXT_MUSIC_AUTOPLAY.value

    @JvmStatic
    fun stopAfterVoice(): Boolean = InuConfig.DISABLE_NEXT_VOICE_AUTOPLAY.value
}
