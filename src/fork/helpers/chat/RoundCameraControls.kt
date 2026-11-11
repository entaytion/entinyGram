package desu.inugram.helpers.chat

import android.view.Gravity
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider
import org.telegram.utils.recorder.round.RoundVideoSession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class RoundCameraControls(
    private val host: FrameLayout,
    resourcesProvider: Theme.ResourcesProvider?,
    private val sessionSource: RoundRecorderHelper.RoundSessionProvider,
) {
    private var lens: RoundVideoSession.CameraCapabilities? = null
    private var settleTries = 0
    private var neutralPending = false
    private var pinchAnchor = 0f
    private var pinchEngaged = false

    private val dial: RoundZoomDial? = if (InuConfig.ROUND_RECORDER_ZOOM_SLIDER.value) {
        RoundZoomDial(host.context, resourcesProvider).also { view ->
            view.sink = { ratio -> sessionSource.get()?.setZoomRatio(ratio) }
            host.addView(view, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 54f,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                12f, 0f, 12f, 62f,
            ))
        }
    } else null

    private val rail: RoundExposureRail? = if (InuConfig.ROUND_RECORDER_EXPOSURE_LEVELS.value) {
        val right = InuConfig.ROUND_RECORDER_EV_SLIDER_RIGHT.value
        RoundExposureRail(host.context, right).also { view ->
            view.onLevel = { level -> sessionSource.get()?.setExposureEv(evFor(level)) }
            host.addView(view, LayoutHelper.createFrame(
                36, 168,
                (if (right) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL,
            ))
        }
    } else null

    private val settleRetry = Runnable { trySettle() }

    fun applyGlass(factory: BlurredBackgroundDrawableViewFactory, provider: BlurredBackgroundColorProvider) {
        dial?.glass(factory, provider)
    }

    fun onCapabilities(capabilities: RoundVideoSession.CameraCapabilities?) {
        val old = lens
        lens = capabilities
        if (!controllable()) return
        val moved = old == null || capabilities == null
            || old.activeFacing != capabilities.activeFacing
            || old.minimumZoomRatio != capabilities.minimumZoomRatio
            || old.maximumZoomRatio != capabilities.maximumZoomRatio
        if (moved || dial?.isAttachedToLens == false) settle(false)
        syncRail()
    }

    fun onState(info: RoundVideoSession.StateInfo, previous: RoundVideoSession.State?) {
        val recording = info.state == RoundVideoSession.State.RECORDING
        if (recording && previous != RoundVideoSession.State.RECORDING) {
            settle(true)
            rail?.reveal()
        } else if (!recording && previous == RoundVideoSession.State.RECORDING) {
            host.removeCallbacks(settleRetry)
            dial?.show(false)
            dial?.release()
            rail?.conceal()
        }
    }

    fun onSwitchStarted() {
        host.removeCallbacks(settleRetry)
        dial?.show(false)
        dial?.release()
    }

    fun onSwitchCompleted() {
        settle(true)
    }

    fun onTap() {
        val session = sessionSource.get() ?: return
        if (session.stateInfo.state == RoundVideoSession.State.RECORDING) rail?.reveal()
    }

    fun pinchStart(distance: Float) {
        pinchAnchor = distance
        pinchEngaged = false
    }

    fun pinchMove(distance: Float) {
        if (pinchAnchor <= 0f || distance <= 0f) return
        if (!pinchEngaged) {
            if (abs(distance - pinchAnchor) < AndroidUtilities.getPixelsInCM(PINCH_DEAD_CM, false)) return
            pinchEngaged = true
            pinchAnchor = distance
            return
        }
        val factor = (distance / pinchAnchor).pow(PINCH_GAIN)
        pinchAnchor = distance
        val view = dial
        if (view != null && view.isAttachedToLens) {
            view.scaleBy(factor)
        } else {
            val session = sessionSource.get() ?: return
            session.setZoomRatio(session.zoomRatio * factor)
        }
    }

    fun pinchEnd() {
        pinchAnchor = 0f
        pinchEngaged = false
    }

    fun release() {
        host.removeCallbacks(settleRetry)
        dial?.release()
        rail?.conceal()
    }

    private fun controllable(): Boolean {
        val session = sessionSource.get() ?: return false
        return lens?.activeFacing != null && session.stateInfo.canControlCamera()
    }

    private fun settle(neutral: Boolean) {
        if (dial == null && rail == null) return
        host.removeCallbacks(settleRetry)
        settleTries = 0
        if (neutral) neutralPending = true
        trySettle()
    }

    private fun trySettle() {
        val session = sessionSource.get()
        val caps = lens
        if (session == null || caps == null || !controllable()) {
            dial?.show(false)
            if (++settleTries <= SETTLE_ATTEMPTS) host.postDelayed(settleRetry, SETTLE_RETRY_MS)
            return
        }
        if (neutralPending && rail != null) session.setExposureEv(0f)
        neutralPending = false
        syncRail()
        val view = dial ?: return
        view.attach(
            caps.minimumZoomRatio,
            caps.maximumZoomRatio,
            caps.activeFacing == RoundVideoSession.CameraFacing.FRONT,
            session.zoomRatio,
        )
        view.show(true)
    }

    private fun syncRail() {
        val view = rail ?: return
        val session = sessionSource.get() ?: return
        val span = evSpan()
        view.setLevel(if (span <= 0f) 0.5f else 0.5f + session.exposureEv / (2f * span))
    }

    private fun evSpan(): Float {
        val session = sessionSource.get() ?: return 0f
        val range = session.exposureCompensationRange ?: return 0f
        val step = session.exposureCompensationStep ?: return 0f
        val unit = step.toFloat()
        if (unit <= 0f) return 0f
        return max(abs(range.lower), abs(range.upper)) * unit
    }

    private fun evFor(level: Float): Float = (level * 2f - 1f) * evSpan()

    private companion object {
        const val PINCH_DEAD_CM = 0.3f
        const val PINCH_GAIN = 2.2f
        const val SETTLE_ATTEMPTS = 10
        const val SETTLE_RETRY_MS = 140L
    }
}
