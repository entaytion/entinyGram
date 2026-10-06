package desu.inugram.helpers.spoof

object DeviceIdentityValidator {
    const val MODEL_MAX = 64
    const val SYSTEM_MAX = 32
    const val APP_MAX = 32

    fun model(raw: String): String? = clean(raw, MODEL_MAX)

    fun system(raw: String): String? = clean(raw, SYSTEM_MAX)

    fun app(raw: String): String? = clean(raw, APP_MAX)

    private fun clean(raw: String, max: Int): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > max) return null
        if (value.any { it.isISOControl() }) return null
        return value
    }
}
