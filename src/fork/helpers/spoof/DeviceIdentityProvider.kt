package desu.inugram.helpers.spoof

import android.content.Context
import android.util.Log
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader

object DeviceIdentityProvider {
    private const val TAG = "DeviceSpoof"
    private const val PREFS = "inu_device_pin"
    private val FIELDS = listOf("model", "system", "app", "installer", "tz")

    private class Pin(
        val model: String?,
        val system: String?,
        val app: String?,
        val hideInstaller: Boolean,
        val hideTz: Boolean,
    )

    private var active: Pin? = null

    private val prefs
        get() = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun current(): Pin {
        if (!InuConfig.DEVICE_SPOOF.value) return Pin(null, null, null, false, false)
        val chosen = configured()
        return Pin(
            chosen.deviceModel,
            chosen.systemVersion,
            chosen.appVersion,
            InuConfig.DEVICE_SPOOF_HIDE_INSTALLER.value,
            InuConfig.DEVICE_SPOOF_HIDE_TZ.value,
        )
    }

    private fun load(key: String): Pin? {
        val p = prefs
        if (!p.getBoolean(key, false)) return null
        return Pin(
            p.getString("$key.model", null),
            p.getString("$key.system", null),
            p.getString("$key.app", null),
            p.getBoolean("$key.installer", false),
            p.getBoolean("$key.tz", false),
        )
    }

    private fun save(key: String, pin: Pin) {
        prefs.edit()
            .putBoolean(key, true)
            .putString("$key.model", pin.model)
            .putString("$key.system", pin.system)
            .putString("$key.app", pin.app)
            .putBoolean("$key.installer", pin.hideInstaller)
            .putBoolean("$key.tz", pin.hideTz)
            .apply()
    }

    private fun clear(key: String) {
        val e = prefs.edit().remove(key)
        FIELDS.forEach { e.remove("$key.$it") }
        e.apply()
    }

    // entiny: a logged-in account keeps the identity it logged in with, changing it mid-session makes the server drop the session
    @JvmStatic
    fun begin(account: Int, userId: Long) {
        active = try {
            if (userId == 0L) {
                clear("pin$account")
                current().also { save("pending$account", it) }
            } else {
                load("pin$account") ?: (load("pending$account") ?: current()).also {
                    save("pin$account", it)
                    clear("pending$account")
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolve(real: DeviceIdentity): DeviceIdentity {
        if (!InuConfig.DEVICE_SPOOF.value) return real
        val chosen = configured()
        return DeviceIdentity(
            chosen.deviceModel ?: real.deviceModel,
            chosen.systemVersion ?: real.systemVersion,
            chosen.appVersion ?: real.appVersion,
        )
    }

    fun resolveFor(account: Int, real: DeviceIdentity): DeviceIdentity {
        val pin = load("pin$account") ?: return resolve(real)
        return DeviceIdentity(pin.model ?: real.deviceModel, pin.system ?: real.systemVersion, pin.app ?: real.appVersion)
    }

    fun configured(): DeviceIdentity {
        val preset = DevicePresets.find(InuConfig.DEVICE_SPOOF_PRESET.value)
        val model = preset?.deviceModel ?: DeviceIdentityValidator.model(InuConfig.DEVICE_SPOOF_MODEL.value)
        val system = preset?.systemVersion ?: DeviceIdentityValidator.system(InuConfig.DEVICE_SPOOF_SYSTEM.value)
        val app = DeviceIdentityValidator.app(InuConfig.DEVICE_SPOOF_APP.value)
        return DeviceIdentity(model, system, app)
    }

    @JvmStatic
    fun deviceModel(real: String): String = safe("device_model", real) { active?.model }

    @JvmStatic
    fun systemVersion(real: String): String = safe("system_version", real) { active?.system }

    @JvmStatic
    fun appVersion(real: String): String = safe("app_version", real) { active?.app }

    @JvmStatic
    fun installer(real: String): String =
        safe("installer", real) { if (active?.hideInstaller == true) "" else real }

    @JvmStatic
    fun timezoneOffset(real: Int): Int =
        try {
            if (active?.hideTz == true) {
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
            if (value != real) Log.d(TAG, "$name=$value")
            value
        } catch (_: Throwable) {
            real
        }
}
