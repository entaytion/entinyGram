package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.RoundRecorderHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.DualCameraView
import org.telegram.utils.camera.roundvideo.RoundVideoSession
import org.telegram.utils.settings.SharedSettings

class CameraSoundSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCameraSound)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val defaultLabel = LocaleController.getString(R.string.InuRoundBitrateDefault)
        val newRecorder = SharedSettings.roundVideoCamera2Enabled.get()

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRoundVideos)))
        items.add(
            UItem.asButton(
                BUTTON_ROUND_DEFAULT_CAMERA,
                R.drawable.inu_tabler_camera_rotate,
                LocaleController.getString(R.string.InuRoundDefaultCamera),
                when (InuConfig.ROUND_DEFAULT_CAMERA.value) {
                    2 -> LocaleController.getString(R.string.InuRoundCameraRear)
                    3 -> LocaleController.getString(R.string.InuRoundCameraAsk)
                    else -> LocaleController.getString(R.string.InuRoundCameraFront)
                }
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_ROUND_NEW_RECORDER,
                LocaleController.getString(R.string.RoundVideoUseNewRecorder),
            ).setChecked(newRecorder)
        )
        if (newRecorder) {
            items.add(
                UItem.asButton(
                    BUTTON_ROUND_VIDEO_RESOLUTION,
                    LocaleController.getString(R.string.RoundVideoOutputResolution),
                    roundResolutionLabel(defaultLabel),
                )
            )
            items.add(UItem.asButton(BUTTON_ROUND_CAMERA_RESOLUTION, LocaleController.getString(R.string.RoundVideoCameraResolution), roundCameraResolutionLabel(SharedSettings.roundVideoCameraResolution.get())))
            items.add(UItem.asButton(BUTTON_ROUND_FRAME_RATE, LocaleController.getString(R.string.RoundVideoFrameRate), SharedSettings.roundVideoFrameRate.get().value.toString() + " FPS"))
            items.add(UItem.asButton(BUTTON_ROUND_BITRATE, LocaleController.getString(R.string.RoundVideoBitrate), formatRoundBitrate(RoundRecorderHelper.videoBitrate(SharedSettings.roundVideoVideoBitrate.get()))))
            items.add(
                UItem.asCheck(TOGGLE_ROUND_COMPOSITION, LocaleController.getString(R.string.RoundVideoCompositionEnabled))
                    .setChecked(SharedSettings.roundVideoComposition.get())
            )
        } else {
            items.add(
                UItem.asCheck(
                    TOGGLE_ROUND_RECORDER_ZOOM_SLIDER,
                    LocaleController.getString(R.string.InuRoundRecorderZoomSlider),
                ).setChecked(InuConfig.ROUND_RECORDER_ZOOM_SLIDER.value)
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_ZOOM_BUTTONS,
                    R.string.InuRoundRecorderZoomButtons,
                    R.string.InuRoundRecorderZoomButtonsInfo,
                    InuConfig.ROUND_RECORDER_ZOOM_BUTTONS.value
                )
            )
            items.add(
                UItem.asCheck(
                    TOGGLE_ROUND_RECORDER_KEEP_ZOOM,
                    LocaleController.getString(R.string.InuRoundRecorderKeepZoom),
                ).setChecked(InuConfig.ROUND_RECORDER_KEEP_ZOOM.value)
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM,
                    R.string.InuRoundRecorderExponentialZoom,
                    R.string.InuRoundRecorderExponentialZoomInfo,
                    InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.value
                )
            )
            if (DualCameraView.roundDualAvailableStatic(this.context)) {
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_ROUND_RECORDER_DUAL_CAMERA,
                        R.string.InuRoundRecorderDualCamera,
                        R.string.InuRoundRecorderDualCameraInfo,
                        InuConfig.ROUND_RECORDER_DUAL_CAMERA.value
                    )
                )
            }
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_60FPS,
                    R.string.InuRoundRecorder60Fps,
                    R.string.InuRoundRecorder60FpsInfo,
                    InuConfig.ROUND_RECORDER_60FPS.value
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_LOCK_EXPOSURE,
                    R.string.InuRoundRecorderLockExposure,
                    R.string.InuRoundRecorderLockExposureInfo,
                    InuConfig.ROUND_RECORDER_LOCK_EXPOSURE.value
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_EXPOSURE_BUTTON,
                    R.string.InuRoundRecorderExposureButton,
                    R.string.InuRoundRecorderExposureButtonInfo,
                    InuConfig.ROUND_RECORDER_EXPOSURE_BUTTON.value
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ROUND_RECORDER_EXPOSURE_LEVELS,
                    R.string.InuRoundRecorderExposureLevels,
                    R.string.InuRoundRecorderExposureLevelsInfo,
                    InuConfig.ROUND_RECORDER_EXPOSURE_LEVELS.value
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAttachCamera)))
        items.add(
            UItem.asButton(
                BUTTON_ATTACH_CAMERA_MODE,
                LocaleController.getString(R.string.InuAttachCameraMode),
                attachCameraModeLabel(InuConfig.ATTACH_CAMERA_MODE.value),
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ATTACH_CAMERA_SQUARE,
                R.string.InuAttachCameraSquare,
                R.string.InuAttachCameraSquareInfo,
                InuConfig.ATTACH_CAMERA_SQUARE.value,
            )
        )
        val cameraApiText = LocaleController.formatString(
            R.string.InuRoundRecorderCameraApi,
            if (SharedConfig.isUsingCamera2(currentAccount)) "Camera2" else "Camera1",
            if (SharedConfig.isUsingCamera2(currentAccount)) "Camera1" else "Camera2",
        )
        items.add(
            UItem.asShadow(
                AndroidUtilities.replaceArrows(
                    AndroidUtilities.replaceSingleTag(cameraApiText) {
                        SharedConfig.toggleUseCamera2(currentAccount)
                        listView.adapter.update(true)
                    },
                    true,
                )
            )
        )

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuVoiceMessages)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_KEEP_SCREEN_ON_RECORDING,
                R.string.InuKeepScreenOnRecording,
                R.string.InuKeepScreenOnRecordingInfo,
                InuConfig.KEEP_SCREEN_ON_RECORDING.value,
            )
        )
        if (InuConfig.ATTACH_CAMERA_MODE.value != InuConfig.AttachCameraModeItem.FAB) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_CHAT_VOICE_IN_ATTACH,
                    R.string.InuChatVoiceInAttach,
                    R.string.InuChatVoiceInAttachInfo,
                    InuConfig.CHAT_VOICE_IN_ATTACH.value,
                    experimental = true,
                )
            )
        }
        items.add(
            UItem.asCheck(
                TOGGLE_CONFIRM_SEND_VOICE,
                LocaleController.getString(R.string.InuConfirmSendVoice),
            ).setChecked(InuConfig.CONFIRM_SEND_VOICE.value)
        )
        items.add(UItem.asButton(BUTTON_ROUND_AUDIO_BITRATE, LocaleController.getString(R.string.InuRoundAudioBitrate), roundAudioBitrateLabel()))
        items.add(UItem.asButton(BUTTON_ROUND_AUDIO_SOURCE, LocaleController.getString(R.string.InuRoundAudioSource), roundAudioSourceLabels()[InuConfig.ROUND_AUDIO_SOURCE.value.coerceIn(0, 4)]))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_ATTACH_CAMERA_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuAttachCameraModeInstant),
                    LocaleController.getString(R.string.InuAttachCameraModeStatic),
                    LocaleController.getString(R.string.InuAttachCameraModeFab),
                    LocaleController.getString(R.string.InuAttachCameraModeTab),
                ),
                InuConfig.ATTACH_CAMERA_MODE.value,
            ) { which ->
                InuConfig.ATTACH_CAMERA_MODE.value = which
                listView.adapter.update(true)
            }

            TOGGLE_KEEP_SCREEN_ON_RECORDING -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.KEEP_SCREEN_ON_RECORDING.toggle()
            TOGGLE_CHAT_VOICE_IN_ATTACH -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.CHAT_VOICE_IN_ATTACH.toggle()
            TOGGLE_CONFIRM_SEND_VOICE -> (view as? TextCheckCell)?.isChecked = InuConfig.CONFIRM_SEND_VOICE.toggle()
            TOGGLE_ATTACH_CAMERA_SQUARE -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.ATTACH_CAMERA_SQUARE.toggle()
            BUTTON_ROUND_DEFAULT_CAMERA -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuRoundCameraFront),
                    LocaleController.getString(R.string.InuRoundCameraRear),
                    LocaleController.getString(R.string.InuRoundCameraAsk),
                ),
                (InuConfig.ROUND_DEFAULT_CAMERA.value - 1).coerceIn(0, 2),
            ) { which ->
                InuConfig.ROUND_DEFAULT_CAMERA.value = which + 1
            }

            TOGGLE_ROUND_NEW_RECORDER -> {
                SharedSettings.roundVideoCamera2Enabled.toggle()
                listView.adapter.update(true)
            }
            TOGGLE_ROUND_COMPOSITION -> (view as? TextCheckCell)?.isChecked = SharedSettings.roundVideoComposition.toggle()
            BUTTON_ROUND_VIDEO_RESOLUTION -> RadioItemOptions.show(
                this, view,
                listOf(LocaleController.getString(R.string.InuRoundBitrateDefault), "360p", "480p", "720p"),
                InuConfig.ROUND_VIDEO_RESOLUTION.value.coerceIn(0, 3),
            ) { which ->
                InuConfig.ROUND_VIDEO_RESOLUTION.value = which
                listView.adapter.update(true)
            }
            BUTTON_ROUND_CAMERA_RESOLUTION -> RadioItemOptions.show(
                this, view,
                RoundVideoSession.CameraResolution.values().map { roundCameraResolutionLabel(it) },
                SharedSettings.roundVideoCameraResolution.get().ordinal,
            ) { which ->
                SharedSettings.roundVideoCameraResolution.set(RoundVideoSession.CameraResolution.values()[which])
                listView.adapter.update(true)
            }
            BUTTON_ROUND_FRAME_RATE -> RadioItemOptions.show(
                this, view, listOf("30 FPS", "60 FPS"),
                if (SharedSettings.roundVideoFrameRate.get() == RoundVideoSession.FrameRate.FPS_30) 0 else 1,
            ) { which ->
                SharedSettings.roundVideoFrameRate.set(if (which == 0) RoundVideoSession.FrameRate.FPS_30 else RoundVideoSession.FrameRate.FPS_60)
                listView.adapter.update(true)
            }
            BUTTON_ROUND_BITRATE -> {
                val bitrates = roundBitrates()
                RadioItemOptions.show(
                    this, view, bitrates.map { if (it == 0) LocaleController.getString(R.string.InuRoundBitrateDefault) else formatRoundBitrate(it) },
                    bitrates.indexOf(InuConfig.ROUND_VIDEO_BITRATE.value).coerceAtLeast(0),
                ) { which ->
                    InuConfig.ROUND_VIDEO_BITRATE.value = bitrates[which]
                    listView.adapter.update(true)
                }
            }
            BUTTON_ROUND_AUDIO_BITRATE -> RadioItemOptions.show(
                this, view, roundAudioBitrates.map { if (it == 0) LocaleController.getString(R.string.InuRoundBitrateDefault) else "$it kbps" },
                roundAudioBitrates.indexOf(InuConfig.ROUND_AUDIO_BITRATE.value).coerceAtLeast(0),
            ) { which ->
                InuConfig.ROUND_AUDIO_BITRATE.value = roundAudioBitrates[which]
                listView.adapter.update(true)
            }
            BUTTON_ROUND_AUDIO_SOURCE -> RadioItemOptions.show(
                this, view, roundAudioSourceLabels(), InuConfig.ROUND_AUDIO_SOURCE.value.coerceIn(0, 4),
            ) { which ->
                InuConfig.ROUND_AUDIO_SOURCE.value = which
                listView.adapter.update(true)
            }
            TOGGLE_ROUND_RECORDER_ZOOM_SLIDER -> (view as? TextCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_ZOOM_SLIDER.toggle()
            TOGGLE_ROUND_RECORDER_ZOOM_BUTTONS ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_ZOOM_BUTTONS.toggle()
            TOGGLE_ROUND_RECORDER_KEEP_ZOOM -> (view as? TextCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_KEEP_ZOOM.toggle()
            TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_EXPONENTIAL_ZOOM.toggle()
            TOGGLE_ROUND_RECORDER_DUAL_CAMERA -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_DUAL_CAMERA.toggle()
            TOGGLE_ROUND_RECORDER_60FPS ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_60FPS.toggle()
            TOGGLE_ROUND_RECORDER_LOCK_EXPOSURE ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_LOCK_EXPOSURE.toggle()
            TOGGLE_ROUND_RECORDER_EXPOSURE_BUTTON ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_EXPOSURE_BUTTON.toggle()
            TOGGLE_ROUND_RECORDER_EXPOSURE_LEVELS ->
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.ROUND_RECORDER_EXPOSURE_LEVELS.toggle()
        }
    }

    private fun roundResolutionLabel(defaultLabel: String): String = when (InuConfig.ROUND_VIDEO_RESOLUTION.value) {
        1 -> "360p"
        2 -> "480p"
        3 -> "720p"
        else -> "$defaultLabel (${SharedSettings.roundVideoOutputResolution.get().size}p)"
    }

    companion object {
        private val BUTTON_ATTACH_CAMERA_MODE = InuUtils.generateId()
        private val TOGGLE_KEEP_SCREEN_ON_RECORDING = InuUtils.generateId()
        private val TOGGLE_CHAT_VOICE_IN_ATTACH = InuUtils.generateId()
        private val TOGGLE_CONFIRM_SEND_VOICE = InuUtils.generateId()
        private val TOGGLE_ATTACH_CAMERA_SQUARE = InuUtils.generateId()
        private val BUTTON_ROUND_DEFAULT_CAMERA = InuUtils.generateId()
        private val TOGGLE_ROUND_NEW_RECORDER = InuUtils.generateId()
        private val TOGGLE_ROUND_COMPOSITION = InuUtils.generateId()
        private val BUTTON_ROUND_VIDEO_RESOLUTION = InuUtils.generateId()
        private val BUTTON_ROUND_CAMERA_RESOLUTION = InuUtils.generateId()
        private val BUTTON_ROUND_FRAME_RATE = InuUtils.generateId()
        private val BUTTON_ROUND_BITRATE = InuUtils.generateId()
        private val BUTTON_ROUND_AUDIO_BITRATE = InuUtils.generateId()
        private val BUTTON_ROUND_AUDIO_SOURCE = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_ZOOM_SLIDER = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_ZOOM_BUTTONS = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_KEEP_ZOOM = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_DUAL_CAMERA = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_60FPS = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_LOCK_EXPOSURE = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_EXPOSURE_BUTTON = InuUtils.generateId()
        private val TOGGLE_ROUND_RECORDER_EXPOSURE_LEVELS = InuUtils.generateId()

        private val roundAudioBitrates = listOf(0, 32, 64, 96, 128, 192)

        private fun roundBitrates(): List<Int> = listOf(0, 750_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000)

        private fun roundAudioBitrateLabel(): String =
            InuConfig.ROUND_AUDIO_BITRATE.value.let { if (it == 0) LocaleController.getString(R.string.InuRoundBitrateDefault) else "$it kbps" }

        private fun roundAudioSourceLabels(): List<String> = listOf(
            LocaleController.getString(R.string.InuRoundBitrateDefault),
            LocaleController.getString(R.string.InuRoundAudioSourceStandard),
            LocaleController.getString(R.string.InuRoundAudioSourceMic),
            LocaleController.getString(R.string.InuRoundAudioSourceCamcorder),
            LocaleController.getString(R.string.InuRoundAudioSourceUnprocessed),
        )

        private fun formatRoundBitrate(bitrate: Int): String = when {
            bitrate % 1_000_000 == 0 -> "${bitrate / 1_000_000} Mbps"
            bitrate > 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f)
            else -> "${bitrate / 1_000} kbps"
        }

        private fun roundCameraResolutionLabel(resolution: RoundVideoSession.CameraResolution): String = LocaleController.getString(
            when (resolution) {
                RoundVideoSession.CameraResolution.HIGH -> R.string.RoundVideoCameraResolutionHigh
                RoundVideoSession.CameraResolution.MEDIUM -> R.string.RoundVideoCameraResolutionMedium
                else -> R.string.RoundVideoCameraResolutionLow
            }
        )

        private fun attachCameraModeLabel(value: Int): String = when (value) {
            InuConfig.AttachCameraModeItem.INSTANT -> LocaleController.getString(R.string.InuAttachCameraModeInstant)
            InuConfig.AttachCameraModeItem.FAB -> LocaleController.getString(R.string.InuAttachCameraModeFab)
            InuConfig.AttachCameraModeItem.TAB -> LocaleController.getString(R.string.InuAttachCameraModeTab)
            else -> LocaleController.getString(R.string.InuAttachCameraModeStatic)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "camera-sound",
            titleRes = R.string.InuCameraSound,
            iconRes = R.drawable.inu_tabler_camera_rotate,
            factory = ::CameraSoundSettingsActivity,
            aliases = listOf("camera", "round video", "voice"),
            entries = listOf(
                SearchRegistry.Entry("attach-camera-mode", R.string.InuAttachCameraMode, BUTTON_ATTACH_CAMERA_MODE),
                SearchRegistry.Entry("keep-screen-on-recording", R.string.InuKeepScreenOnRecording, TOGGLE_KEEP_SCREEN_ON_RECORDING),
                SearchRegistry.Entry("chat-voice-in-attach", R.string.InuChatVoiceInAttach, TOGGLE_CHAT_VOICE_IN_ATTACH),
                SearchRegistry.Entry("confirm-send-voice", R.string.InuConfirmSendVoice, TOGGLE_CONFIRM_SEND_VOICE),
                SearchRegistry.Entry("attach-camera-square", R.string.InuAttachCameraSquare, TOGGLE_ATTACH_CAMERA_SQUARE),
                SearchRegistry.Entry("round-default-camera", R.string.InuRoundDefaultCamera, BUTTON_ROUND_DEFAULT_CAMERA),
                SearchRegistry.Entry("round-recorder-zoom-slider", R.string.InuRoundRecorderZoomSlider, TOGGLE_ROUND_RECORDER_ZOOM_SLIDER),
                SearchRegistry.Entry("round-recorder-zoom-buttons", R.string.InuRoundRecorderZoomButtons, TOGGLE_ROUND_RECORDER_ZOOM_BUTTONS),
                SearchRegistry.Entry("round-recorder-keep-zoom", R.string.InuRoundRecorderKeepZoom, TOGGLE_ROUND_RECORDER_KEEP_ZOOM),
                SearchRegistry.Entry("round-recorder-exponential-zoom", R.string.InuRoundRecorderExponentialZoom, TOGGLE_ROUND_RECORDER_EXPONENTIAL_ZOOM),
                SearchRegistry.Entry("round-recorder-dual-camera", R.string.InuRoundRecorderDualCamera, TOGGLE_ROUND_RECORDER_DUAL_CAMERA),
                SearchRegistry.Entry("round-recorder-60fps", R.string.InuRoundRecorder60Fps, TOGGLE_ROUND_RECORDER_60FPS),
                SearchRegistry.Entry("round-new-recorder", R.string.RoundVideoUseNewRecorder, TOGGLE_ROUND_NEW_RECORDER),
                SearchRegistry.Entry("round-output-resolution", R.string.RoundVideoOutputResolution, BUTTON_ROUND_VIDEO_RESOLUTION),
                SearchRegistry.Entry("round-camera-resolution", R.string.RoundVideoCameraResolution, BUTTON_ROUND_CAMERA_RESOLUTION),
                SearchRegistry.Entry("round-frame-rate", R.string.RoundVideoFrameRate, BUTTON_ROUND_FRAME_RATE),
                SearchRegistry.Entry("round-bitrate", R.string.RoundVideoBitrate, BUTTON_ROUND_BITRATE),
                SearchRegistry.Entry("round-audio-bitrate", R.string.InuRoundAudioBitrate, BUTTON_ROUND_AUDIO_BITRATE),
                SearchRegistry.Entry("round-audio-source", R.string.InuRoundAudioSource, BUTTON_ROUND_AUDIO_SOURCE),
                SearchRegistry.Entry("round-composition", R.string.RoundVideoCompositionEnabled, TOGGLE_ROUND_COMPOSITION),
                SearchRegistry.Entry("round-recorder-lock-exposure", R.string.InuRoundRecorderLockExposure, TOGGLE_ROUND_RECORDER_LOCK_EXPOSURE),
                SearchRegistry.Entry("round-recorder-exposure-button", R.string.InuRoundRecorderExposureButton, TOGGLE_ROUND_RECORDER_EXPOSURE_BUTTON),
                SearchRegistry.Entry("round-recorder-exposure-levels", R.string.InuRoundRecorderExposureLevels, TOGGLE_ROUND_RECORDER_EXPOSURE_LEVELS),
            ),
        )
    }
}
