package desu.inugram.helpers.pillstack

import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.Utilities
import java.io.BufferedReader
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

// entiny: spot gold price (1 troy oz in USD) from gold-api.com, a free keyless endpoint.
object GoldPrice {

    private const val URL_STRING = "https://api.gold-api.com/price/XAU"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val cache = CachedFetch(
        ttlMs = CACHE_TTL_MS,
        storedRaw = { InuConfig.PILL_STACK_GOLD_CACHE.value },
        storedTime = { InuConfig.PILL_STACK_GOLD_CACHE_TIME.value },
        decode = { raw -> runCatching { BigDecimal(raw) }.getOrNull() },
        persist = { price ->
            InuConfig.PILL_STACK_GOLD_CACHE.value = price.toPlainString()
            InuConfig.PILL_STACK_GOLD_CACHE_TIME.value = System.currentTimeMillis()
        },
        fetchBlocking = ::fetchBlocking,
    )

    fun clearCache() = cache.clearCache()

    fun getCached(): BigDecimal? = cache.getCached()

    fun fetch(callback: Utilities.Callback<BigDecimal?>) = cache.fetch(callback)

    private fun fetchBlocking(): BigDecimal? {
        val connection = URL(URL_STRING).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val price = JSONObject(body).optDouble("price", 0.0)
            if (price > 0) BigDecimal.valueOf(price) else null
        } finally {
            connection.disconnect()
        }
    }
}
