package desu.inugram.helpers

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import desu.inugram.InuConfig
import org.telegram.messenger.voip.VoIPService
import org.webrtc.voiceengine.WebRtcAudioTrack

object CallAudioHelper {
    @JvmStatic
    @Volatile
    var isActive = false
        private set

    @Volatile
    private var currentRoute = VoIPService.AUDIO_ROUTE_BLUETOOTH

    @Volatile
    private var bluetoothPresent = false

    @JvmStatic
    fun evaluate(service: VoIPService): Boolean {
        isActive = InuConfig.HD_BLUETOOTH_CALL_AUDIO.value &&
            !VoIPService.hasRtmpStream() &&
            !VoIPService.USE_CONNECTION_SERVICE &&
            hasBluetoothMediaOutput(service)
        return isActive
    }

    @JvmStatic
    fun hasBluetoothMediaOutput(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_MEDIA_TYPES }
    }

    private val BLUETOOTH_MEDIA_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }

    @JvmStatic
    fun onConfigureCall(service: VoIPService): Boolean {
        bluetoothPresent = evaluate(service)
        currentRoute = VoIPService.AUDIO_ROUTE_BLUETOOTH
        WebRtcAudioTrack.inu_setPreferredDevice(null)
        return isActive
    }

    @JvmStatic
    fun getCurrentRoute(): Int = currentRoute

    @JvmStatic
    fun onDevicesChanged(service: VoIPService) {
        if (!isActive) return
        val present = hasBluetoothMediaOutput(service)
        if (present == bluetoothPresent) return
        bluetoothPresent = present
        if (present) {
            service.setAudioOutput(2)
        } else if (currentRoute == VoIPService.AUDIO_ROUTE_BLUETOOTH) {
            service.setAudioOutput(if (service.hasEarpiece()) 1 else 0)
        }
    }

    @JvmStatic
    fun handleSetAudioOutput(service: VoIPService, output: Int): Boolean {
        if (!isActive) return false
        val deviceType = when (output) {
            0 -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            1 -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            else -> null
        }
        if (deviceType == null) {
            WebRtcAudioTrack.inu_setPreferredDevice(null)
            currentRoute = VoIPService.AUDIO_ROUTE_BLUETOOTH
        } else {
            val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == deviceType }
                ?: return true
            WebRtcAudioTrack.inu_setPreferredDevice(device)
            currentRoute = if (output == 0) VoIPService.AUDIO_ROUTE_SPEAKER else VoIPService.AUDIO_ROUTE_EARPIECE
        }
        return true
    }
}
