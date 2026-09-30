package desu.inugram.helpers.pillstack

import org.json.JSONObject
import org.telegram.messenger.Utilities
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

// entiny: proxy host -> country flag through a free GeoIP service; asked once per host, remembered for the session
object ProxyGeoHelper {
    private const val NEGATIVE_TTL_MS = 10 * 60 * 1000L

    private val flags = ConcurrentHashMap<String, String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // entiny: returns the cached flag immediately and starts a lookup in the background when none is known yet
    @JvmStatic
    fun flagFor(host: String?): String? {
        val key = host?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        flags[key]?.let { return it }
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < NEGATIVE_TTL_MS) return null
        if (inFlight.add(key)) {
            Utilities.globalQueue.postRunnable {
                try {
                    val code = lookup(key)
                    if (code != null) flags[key] = flag(code) else failedAt[key] = System.currentTimeMillis()
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    private fun lookup(host: String): String? = runCatching {
        val address = InetAddress.getByName(host)
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return null
        val ip = address.hostAddress ?: return null
        countryIs(ip) ?: ipWho(ip)
    }.getOrNull()

    private fun countryIs(ip: String): String? = runCatching {
        JSONObject(get("https://api.country.is/$ip")).optString("country").takeIf { it.length == 2 }
    }.getOrNull()

    private fun ipWho(ip: String): String? = runCatching {
        val json = JSONObject(get("https://ipwho.is/$ip?fields=success,country_code"))
        if (!json.optBoolean("success", false)) null else json.optString("country_code").takeIf { it.length == 2 }
    }.getOrNull()

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun flag(code: String): String {
        val upper = code.uppercase()
        val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
        return String(first) + String(second)
    }
}
