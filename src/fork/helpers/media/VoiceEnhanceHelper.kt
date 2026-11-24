package desu.inugram.helpers.media

import android.media.AudioEffect
import android.media.AutomaticGainControl
import android.media.NoiseSuppressor
import desu.inugram.InuConfig

object VoiceEnhanceHelper {
    // entiny: platform effects on the voice recorder session, skipped when the stock RNNoise is already on
    @JvmStatic
    fun attach(sessionId: Int, platformNoiseSuppression: Boolean): VoiceEnhanceSession? {
        if (!InuConfig.VOICE_ENHANCE.value || sessionId == 0) return null
        val effects = ArrayList<AudioEffect>(2)
        tryEnable(effects) { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId) else null }
        if (platformNoiseSuppression) {
            tryEnable(effects) { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null }
        }
        return if (effects.isEmpty()) null else VoiceEnhanceSession(effects)
    }

    private inline fun tryEnable(effects: MutableList<AudioEffect>, create: () -> AudioEffect?) {
        try {
            val effect = create() ?: return
            effect.enabled = true
            effects.add(effect)
        } catch (_: RuntimeException) {
        }
    }
}

class VoiceEnhanceSession(private val effects: List<AudioEffect>) {
    fun release() {
        effects.forEach {
            try {
                it.release()
            } catch (_: RuntimeException) {
            }
        }
    }
}
