package desu.inugram.helpers.chat

import android.view.Choreographer
import desu.inugram.InuConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog

object AnimationDebugHelper {
    private const val EXPECTED_FRAME_NANOS = 16_666_667L
    private const val JANK_THRESHOLD_NANOS = EXPECTED_FRAME_NANOS * 2

    private var watching = false
    private var lastFrameNanos = 0L
    private var frameCount = 0
    private var jankFrameCount = 0
    private var worstFrameNanos = 0L

    @JvmStatic
    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED && InuConfig.EXTRA_DEBUG_LOGS.value

    @JvmStatic
    fun onChatOpenAnimationStart() {
        if (!isEnabled()) return
        watching = true
        lastFrameNanos = 0L
        frameCount = 0
        jankFrameCount = 0
        worstFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(::onFrame)
    }

    @JvmStatic
    fun onChatOpenAnimationEnd(elapsedMs: Long) {
        if (!isEnabled() || !watching) return
        watching = false
        FileLog.d(
            "entinyAnim chat open took ${elapsedMs}ms frames=$frameCount" +
                " jank=$jankFrameCount worst=${worstFrameNanos / 1_000_000}ms"
        )
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!watching) return
        frameCount++
        if (lastFrameNanos != 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            if (delta > JANK_THRESHOLD_NANOS) jankFrameCount++
            if (delta > worstFrameNanos) worstFrameNanos = delta
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(::onFrame)
    }
}
