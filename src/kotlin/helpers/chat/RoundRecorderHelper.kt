package desu.inugram.helpers.chat

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.messenger.camera.Camera2Session
import org.telegram.messenger.camera.CameraSession
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ZoomControlView
import org.telegram.ui.Stories.recorder.FlashViews
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RoundRecorderHelper {
    // physical finger spread is capped by the small recorder container;
    // 3x finger ratio is what comfortably reaches edge-to-edge.
    private const val MAX_PINCH_RATIO = 3f

    @JvmStatic
    fun pinchDeltaT(fingerRatio: Float): Float {
        val dR = fingerRatio - 1f
        val magnitude = minOf(1f, kotlin.math.abs(dR) / (MAX_PINCH_RATIO - 1f))
        return kotlin.math.sign(dR) * magnitude * magnitude
    }

    @JvmStatic
    fun mapZoomT(t: Float, min: Float, max: Float): Float =
        if (InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.value && min > 0f) {
            (min * Math.pow((max / min).toDouble(), t.toDouble())).toFloat()
        } else {
            min + t * (max - min)
        }

    private fun tFromZoom(zoom: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        val t = if (InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.value && min > 0f) {
            (Math.log((zoom / min).toDouble()) / Math.log((max / min).toDouble())).toFloat()
        } else {
            (zoom - min) / (max - min)
        }
        return t.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun zoomToT(zoom: Float, min: Float, max: Float): Float = tFromZoom(zoom, min, max)

    // fixed levels a round-camera button row can snap to, capped by what the active lens supports
    private val ZOOM_BUTTON_CANDIDATES = floatArrayOf(1f, 2f, 3f, 5f, 10f)

    @JvmStatic
    fun zoomLevelsFor(maxZoom: Float): List<Float> =
        ZOOM_BUTTON_CANDIDATES.filter { it <= maxZoom + 0.01f }.ifEmpty { listOf(1f) }

    // c2 and c1 are mutually exclusive (depends on useCamera2); pass both, only the live one matters
    @JvmStatic
    fun currentZoomT(c2: Camera2Session?, c1: CameraSession?): Float = when {
        c2 != null -> tFromZoom(c2.zoom, c2.minZoom, c2.maxZoom)
        c1 != null -> c1.currentZoom
        else -> 0f
    }

    @JvmStatic
    fun applyZoomT(slider: ZoomControlView?, c2: Camera2Session?, c1: CameraSession?, t: Float) {
        val clamped = t.coerceIn(0f, 1f)
        // Camera1 driver maps [0,1] through a log ratio table; Camera2 gets the explicit zoom factor.
        c2?.setZoom(mapZoomT(clamped, c2.minZoom, c2.maxZoom))
        c1?.setZoom(clamped)
        slider?.setZoom(clamped, false)
    }

    @JvmStatic
    fun syncSlider(slider: ZoomControlView?, c2: Camera2Session?, c1: CameraSession?) {
        slider?.setZoom(currentZoomT(c2, c1), false)
    }

    @JvmStatic
    fun attachZoomSlider(parent: FrameLayout, onZoom: Utilities.Callback<Float>): ZoomControlView? {
        if (!InuConfig.ROUND_RECORDER_ZOOM_SLIDER.value) return null
        val view = ZoomControlView(parent.context)
        view.alpha = 0f
        view.visibility = View.GONE
        view.setZoom(0f, false)
        view.setDelegate { z -> onZoom.run(z) }
        parent.addView(
            view,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 50f,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
                24f, 0f, 24f, 70f,
            ),
        )
        return view
    }

    @JvmStatic
    fun setSliderVisible(slider: ZoomControlView?, visible: Boolean) {
        if (slider == null) return
        slider.animate().cancel()
        if (visible) {
            slider.visibility = View.VISIBLE
            slider.animate().alpha(1f).setDuration(180).start()
        } else {
            slider.animate().alpha(0f).setDuration(180)
                .withEndAction { slider.visibility = View.GONE }
                .start()
        }
    }

    @JvmStatic
    fun attachZoomButtons(parent: FrameLayout, onLevel: Utilities.Callback<Float>): ZoomLevelButtonsView? {
        if (!InuConfig.ROUND_RECORDER_ZOOM_BUTTONS.value) return null
        val view = ZoomLevelButtonsView(parent.context)
        view.alpha = 0f
        view.visibility = View.GONE
        view.setDelegate(onLevel)
        parent.addView(
            view,
            LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 30f,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
                24f, 0f, 24f, 110f,
            ),
        )
        return view
    }

    @JvmStatic
    fun setButtonsVisible(buttons: ZoomLevelButtonsView?, visible: Boolean) {
        if (buttons == null) return
        buttons.animate().cancel()
        if (visible) {
            buttons.visibility = View.VISIBLE
            buttons.animate().alpha(1f).setDuration(180).start()
        } else {
            buttons.animate().alpha(0f).setDuration(180)
                .withEndAction { buttons.visibility = View.GONE }
                .start()
        }
    }

    // c2/c1 mutually exclusive, same convention as currentZoomT/syncSlider
    @JvmStatic
    fun syncZoomButtons(buttons: ZoomLevelButtonsView?, c2: Camera2Session?, c1: CameraSession?) {
        if (buttons == null) return
        if (c2 == null) {
            // Camera1 has no real x-ratio to snap to (see Camera2Session.getMinZoom TODO), hide the row
            buttons.setLevels(emptyList())
            return
        }
        buttons.setLevels(zoomLevelsFor(c2.maxZoom))
        buttons.setActiveLevel(nearestLevel(c2.zoom, c2.maxZoom))
    }

    // cheaper than syncZoomButtons: only re-highlights the closest button, doesn't rebuild the row.
    // call this after every zoom change (slider drag, pinch move, reset animation) so the highlighted
    // level never goes stale relative to the actual camera zoom.
    @JvmStatic
    fun refreshActiveFromZoom(buttons: ZoomLevelButtonsView?, c2: Camera2Session?, c1: CameraSession?) {
        if (buttons == null || c2 == null) return
        buttons.setActiveLevel(nearestLevel(c2.zoom, c2.maxZoom))
    }

    private fun nearestLevel(currentZoom: Float, maxZoom: Float): Float? {
        val levels = zoomLevelsFor(maxZoom)
        val closest = levels.minByOrNull { kotlin.math.abs(it - currentZoom) } ?: return null
        return if (kotlin.math.abs(closest - currentZoom) <= 0.05f * closest) closest else null
    }

    @Volatile
    private var pendingDualCleanup: CountDownLatch? = null

    @JvmStatic
    fun awaitPendingDualCleanup() {
        val latch = pendingDualCleanup ?: return
        if (latch.count == 0L) return
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    // cancel triggers cameraDevice.close() which can take 100-300ms per camera.
    // run sync closes off the UI thread in parallel and gate the next dual open on completion
    // to avoid HAL re-open races with not-yet-released sensors.
    @JvmStatic
    fun destroyDualAsync(sessions: Array<Camera2Session?>) {
        val toClose = sessions.copyOf()
        for (i in sessions.indices) sessions[i] = null
        val latch = CountDownLatch(1)
        pendingDualCleanup = latch
        Thread({
            val inner = CountDownLatch(toClose.size)
            for (s in toClose) {
                if (s == null) { inner.countDown(); continue }
                Thread({
                    try { s.destroy(false) } finally { inner.countDown() }
                }, "inu-camera2-close").start()
            }
            try { inner.await() } catch (_: InterruptedException) {}
            latch.countDown()
        }, "inu-camera2-cleanup").start()
    }

    // configuring both CaptureSessions concurrently races in the HAL and silently starves
    // the secondary's stream (rear-first init repro). serialize: primary first, secondary chains via whenDone.
    @JvmStatic
    fun openDualSerialized(
        sessions: Array<Camera2Session?>,
        index: Int,
        primary: Int,
        surface: SurfaceTexture,
    ) {
        val self = sessions[index] ?: return
        if (index == primary) {
            self.open(surface)
        } else {
            sessions[primary]?.whenDone {
                sessions[index]?.open(surface)
            }
        }
    }

    // Pure -- no shared state. The result is stashed by the caller on its own Camera2Session
    // (see Camera2Session.negotiatedFps) rather than here, because in dual-camera mode two
    // sessions (front + back) call this almost concurrently on session open; a single shared
    // field here would let whichever call finishes last silently clobber the other's result.
    @JvmStatic
    fun selectFpsRange(characteristics: CameraCharacteristics?, request60Fps: Boolean): Range<Int> {
        if (!request60Fps || characteristics == null) return Range(30, 30)
        val availableRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(30, 30)

        // Only accept a strict 60 FPS range. Wide ranges like [30, 60] or [15, 60]
        // are still legal on some HALs, but they force the recorder to use a variable
        // frame envelope. That is the combination most likely to cause a stall or a
        // post-record send failure, so we safely degrade to the stock 30 FPS profile.
        val fixed60 = availableRanges.firstOrNull { it.lower == 60 && it.upper == 60 }
        if (fixed60 != null) return fixed60

        return Range(30, 30)
    }

    // Single source of truth for the whole record chain (camera FPS range, dt clamp,
    // encoder KEY_FRAME_RATE, framerate metadata). Returns 60 only when the toggle is
    // on AND the given session's HAL actually negotiated a strict 60 FPS range (see
    // Camera2Session.negotiatedFps); otherwise stock 30. Reading it from the session itself
    // (instead of a global) keeps the encoder/muxer in sync with the camera that is actually
    // feeding it -- declaring 60 while the camera delivers 30 corrupts timestamps and breaks
    // send, and a shared global can't tell two concurrently-negotiating sessions apart.
    @JvmStatic
    fun getTargetFps(session: Camera2Session?): Int =
        if (InuConfig.ROUND_RECORDER_60FPS.value && session?.negotiatedFps == 60) 60 else 30

    @JvmStatic
    fun getVideoBitrate(defaultBitrate: Int, session: Camera2Session?): Int {
        // Scale the payload only when true 60 FPS is actually negotiated. Inflating
        // bitrate for a 30 FPS fallback just bloats the file on devices that cannot
        // sustain the requested recording profile.
        return if (InuConfig.ROUND_RECORDER_60FPS.value && session?.negotiatedFps == 60) {
            (defaultBitrate * 1.5f).toInt()
        } else {
            defaultBitrate
        }
    }

    fun interface SessionProvider {
        fun get(): Camera2Session?
    }

    @JvmStatic
    fun isAeLocked(session: Camera2Session?): Boolean {
        return session?.isAeLocked() ?: InuConfig.ROUND_RECORDER_LOCK_EXPOSURE.value
    }

    @JvmStatic
    fun attachAeLockButton(
        parent: LinearLayout,
        provider: SessionProvider,
    ): FlashViews.ImageViewInvertable? {
        if (!InuConfig.ROUND_RECORDER_EXPOSURE_BUTTON.value) return null
        val context = parent.context
        val button = FlashViews.ImageViewInvertable(context)
        button.scaleType = ImageView.ScaleType.CENTER
        button.contentDescription = LocaleController.getString(R.string.InuRoundRecorderLockExposure)
        updateAeButtonIcon(button, isAeLocked(provider.get()))
        button.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val session = provider.get() ?: return@setOnClickListener
            val newLocked = !session.isAeLocked()
            session.setAeLocked(newLocked)
            updateAeButtonIcon(button, isAeLocked(session))
        }
        parent.addView(button, LayoutHelper.createLinear(44, 44))
        return button
    }

    @JvmStatic
    fun syncAeButton(button: FlashViews.ImageViewInvertable?, session: Camera2Session?) {
        if (button == null) return
        updateAeButtonIcon(button, isAeLocked(session))
    }

    private fun updateAeButtonIcon(button: FlashViews.ImageViewInvertable, locked: Boolean) {
        val resId = if (locked) R.drawable.inu_camera_ae_locked else R.drawable.inu_camera_ae_unlocked
        button.setImageResource(resId)
    }
}
