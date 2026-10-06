package desu.inugram.helpers.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.Components.VideoPlayer

class TransientAudioFocus(private val player: VideoPlayer) : AudioManager.OnAudioFocusChangeListener {
    private val audioManager = ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var enabled = false
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var request: AudioFocusRequest? = null
    private var hasFocus = false
    private var pausedByFocus = false

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        update()
    }

    fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        this.playWhenReady = playWhenReady
        this.playbackState = playbackState
        update()
    }

    fun release() {
        enabled = false
        update()
    }

    private fun update() {
        val wantsFocus = enabled && playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
        if (wantsFocus) {
            if (hasFocus) return
            pausedByFocus = false
            val req = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(this, Handler(Looper.getMainLooper()))
                .build()
                .also { request = it }
            if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasFocus = true
            } else {
                player.pause()
            }
        } else if (request != null && (!enabled || !pausedByFocus)) {
            abandon()
        }
    }

    private fun abandon() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
        hasFocus = false
        pausedByFocus = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (pausedByFocus) {
                    pausedByFocus = false
                    player.play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                if (playWhenReady) {
                    pausedByFocus = true
                    player.pause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                abandon()
                player.pause()
            }
            // LOSS_TRANSIENT_CAN_DUCK: the system ducks on its own since API 26
        }
    }
}
