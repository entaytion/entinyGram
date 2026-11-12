package desu.inugram.helpers.media

import android.os.Looper
import android.os.SystemClock
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController

object SleepTimerHelper {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 180
    const val STEP_MINUTES = 1

    private var deadlineMs = 0L
    private var finishCurrentTrack = false
    private var waitingForTrackEnd = false
    private val listeners = mutableSetOf<Runnable>()

    private val expireRunnable = Runnable(::onExpired)

    @JvmStatic
    fun isActive(): Boolean = deadlineMs != 0L

    @JvmStatic
    fun remainingMs(): Long =
        if (deadlineMs == 0L) 0L else (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    @JvmStatic
    fun currentMinutes(): Int =
        if (deadlineMs == 0L) {
            0
        } else {
            ((remainingMs() + 59_999) / 60_000).toInt().coerceIn(MIN_MINUTES, MAX_MINUTES)
        }

    @JvmStatic
    fun isFinishingCurrentTrack(): Boolean =
        if (deadlineMs == 0L) InuConfig.SLEEP_TIMER_END_OF_TRACK.value else finishCurrentTrack

    @JvmStatic
    fun set(minutes: Int, endOfTrack: Boolean) {
        AndroidUtilities.cancelRunOnUIThread(expireRunnable)
        val clamped = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        deadlineMs = SystemClock.elapsedRealtime() + clamped * 60_000L
        finishCurrentTrack = endOfTrack
        waitingForTrackEnd = false
        InuConfig.SLEEP_TIMER_END_OF_TRACK.value = endOfTrack
        AndroidUtilities.runOnUIThread(expireRunnable, clamped * 60_000L)
        notifyChanged()
    }

    @JvmStatic
    fun clear() {
        if (deadlineMs == 0L && !waitingForTrackEnd) return
        AndroidUtilities.cancelRunOnUIThread(expireRunnable)
        deadlineMs = 0L
        finishCurrentTrack = false
        waitingForTrackEnd = false
        notifyChanged()
    }

    @JvmStatic
    fun onTrackEnded(): Boolean {
        if (!waitingForTrackEnd) return false
        deadlineMs = 0L
        finishCurrentTrack = false
        waitingForTrackEnd = false
        notifyChanged()
        return true
    }

    @JvmStatic
    fun addListener(listener: Runnable) {
        listeners += listener
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        listeners -= listener
    }

    @JvmStatic
    fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> LocaleController.formatPluralString("Minutes", m)
            m == 0 -> LocaleController.formatPluralString("Hours", h)
            else -> LocaleController.formatPluralString("Hours", h) + " " + LocaleController.formatPluralString("Minutes", m)
        }
    }

    @JvmStatic
    fun formatRemaining(): String = formatMinutes(currentMinutes())

    private fun onExpired() {
        if (deadlineMs == 0L) return
        val controller = MediaController.getInstance()
        val playing = controller.getPlayingMessageObject()
        if (playing != null && playing.isMusic && finishCurrentTrack && !controller.isMessagePaused()) {
            waitingForTrackEnd = true
            notifyChanged()
            return
        }
        if (playing != null && (playing.isMusic || playing.isVoice) && !controller.isMessagePaused()) {
            controller.pauseMessage(playing)
        }
        deadlineMs = 0L
        finishCurrentTrack = false
        notifyChanged()
    }

    private fun notifyChanged() {
        if (listeners.isEmpty()) return
        val copy = listeners.toList()
        if (Looper.getMainLooper().isCurrentThread) {
            copy.forEach(Runnable::run)
        } else {
            AndroidUtilities.runOnUIThread { copy.forEach(Runnable::run) }
        }
    }
}
