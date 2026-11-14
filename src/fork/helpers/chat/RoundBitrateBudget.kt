package desu.inugram.helpers.chat

import android.media.MediaCodec
import android.os.Bundle
import android.util.Range
import org.telegram.utils.settings.SharedSettings
import kotlin.math.abs

// entiny: the server turns rounds over ~12 MiB into plain videos, so "Auto" lowers the encoder bitrate while recording to keep a 60 s round under budget
object RoundBitrateBudget {
    const val AUTO = -1
    const val START_BPS = 2_000_000

    private const val BUDGET_BYTES = 11_800_000L
    private const val MAX_US = 60_000_000L
    private const val FLOOR_BPS = 1_000_000L
    private const val MIN_BPS = 750_000L
    private const val OVERSHOOT = 1.06
    private const val LOOKAHEAD_S = 15.0
    private const val CHECK_US = 500_000L

    private var active = false
    private var maxBps = START_BPS
    private var range: Range<Int>? = null
    private var current = START_BPS
    private var firstPts = Long.MIN_VALUE
    private var lastCheck = Long.MIN_VALUE

    @JvmStatic
    fun begin(videoBitrate: Int, bitrateRange: Range<Int>?) {
        active = SharedSettings.roundVideoVideoBitrate.get() == AUTO
        maxBps = videoBitrate
        range = bitrateRange
        current = videoBitrate
        firstPts = Long.MIN_VALUE
        lastCheck = Long.MIN_VALUE
    }

    @JvmStatic
    fun onSample(codec: MediaCodec?, ptsUs: Long, writtenBytes: Long) {
        if (!active || codec == null) return
        if (firstPts == Long.MIN_VALUE) firstPts = ptsUs
        if (lastCheck != Long.MIN_VALUE && ptsUs - lastCheck < CHECK_US) return
        lastCheck = ptsUs
        val remainingS = maxOf(1.0, (MAX_US - (ptsUs - firstPts)) / 1_000_000.0)
        val audioBps = RoundRecorderHelper.audioBitrate(64_000).toLong()
        val reserve = (FLOOR_BPS * OVERSHOOT + audioBps) * remainingS / 8.0
        val excess = BUDGET_BYTES - writtenBytes - reserve
        val window = minOf(remainingS, LOOKAHEAD_S)
        var target = (FLOOR_BPS + excess * 8.0 / window).toLong()
        target = maxOf(MIN_BPS, minOf(maxBps.toLong(), target))
        range?.let { target = it.clamp(target.toInt()).toLong() }
        if (abs(target - current) < maxOf(50_000L, current / 20L)) return
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target.toInt()) })
            current = target.toInt()
        } catch (_: RuntimeException) {
            active = false
        }
    }
}
