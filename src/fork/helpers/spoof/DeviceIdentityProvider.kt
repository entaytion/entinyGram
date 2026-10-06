package desu.inugram.helpers.spoof

import android.util.Log
import desu.inugram.InuConfig

object DeviceIdentityProvider {
    private const val TAG = "DeviceSpoof"

    fun resolve(real: DeviceIdentity): DeviceIdentity {
        if (!InuConfig.DEVICE_SPOOF.value) return real
        val chosen = configured()
        return DeviceIdentity(
            chosen.deviceModel ?: real.deviceModel,
            chosen.systemVersion ?: real.systemVersion,
            chosen.appVersion ?: real.appVersion,
        )
    }

    fun configured(): DeviceIdentity {
        val preset = DevicePresets.find(InuConfig.DEVICE_SPOOF_PRESET.value)
        val model = preset?.deviceModel ?: DeviceIdentityValidator.model(InuConfig.DEVICE_SPOOF_MODEL.value)
        val system = preset?.systemVersion ?: DeviceIdentityValidator.system(InuConfig.DEVICE_SPOOF_SYSTEM.value)
        val app = DeviceIdentityValidator.app(InuConfig.DEVICE_SPOOF_APP.value)
        return DeviceIdentity(model, system, app)
    }

    @JvmStatic
    fun deviceModel(real: String): String = safe("device_model", real) { resolve(DeviceIdentity(real, null, null)).deviceModel }

    @JvmStatic
    fun systemVersion(real: String): String = safe("system_version", real) { resolve(DeviceIdentity(null, real, null)).systemVersion }

    @JvmStatic
    fun appVersion(real: String): String = safe("app_version", real) { resolve(DeviceIdentity(null, null, real)).appVersion }

    @JvmStatic
    fun installer(real: String): String =
        safe("installer", real) { if (InuConfig.DEVICE_SPOOF.value && InuConfig.DEVICE_SPOOF_HIDE_INSTALLER.value) "" else real }

    @JvmStatic
    fun timezoneOffset(real: Int): Int =
        try {
            if (InuConfig.DEVICE_SPOOF.value && InuConfig.DEVICE_SPOOF_HIDE_TZ.value) {
                Log.d(TAG, "tz_offset=0")
                0
            } else {
                real
            }
        } catch (_: Throwable) {
            real
        }

    private inline fun safe(name: String, real: String, block: () -> String?): String =
        try {
            val value = block() ?: real
            if (InuConfig.DEVICE_SPOOF.value) Log.d(TAG, "$name=$value")
            value
        } catch (_: Throwable) {
            real
        }
}
