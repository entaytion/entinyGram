package desu.inugram.helpers.pillstack

import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.io.BufferedReader
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

// entiny: spot gold price (1 troy oz in USD) from gold-api.com, a free keyless endpoint. Same cache/coalescing shape as ExchangeRates.
object GoldPrice {

    private const val URL_STRING = "https://api.gold-api.com/price/XAU"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val sync = Any()
    private val pendingCallbacks = ArrayList<Utilities.Callback<BigDecimal?>>()
    private var requestInFlight = false
    private var cacheValue: BigDecimal? = null
    private var cacheTimestamp = 0L

    fun clearCache() {
        cacheTimestamp = 0
    }

    private fun isStale(): Boolean =
        getCached() == null || cacheTimestamp == 0L || System.currentTimeMillis() - cacheTimestamp >= CACHE_TTL_MS

    fun getCached(): BigDecimal? {
        if (cacheValue == null) {
            val raw = InuConfig.PILL_STACK_GOLD_CACHE.value
            if (raw.isNotEmpty()) {
                cacheValue = runCatching { BigDecimal(raw) }.getOrNull()
                cacheTimestamp = InuConfig.PILL_STACK_GOLD_CACHE_TIME.value
            }
        }
        return cacheValue
    }

    fun fetch(callback: Utilities.Callback<BigDecimal?>) {
        val cached = getCached()
        if (cached != null && !isStale()) {
            AndroidUtilities.runOnUIThread { callback.run(cached) }
            return
        }
        var startRequest: Boolean
        synchronized(sync) {
            pendingCallbacks.add(callback)
            startRequest = !requestInFlight
            requestInFlight = true
        }
        if (!startRequest) return
        Utilities.globalQueue.postRunnable {
            val price = try {
                fetchBlocking()
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
            if (price != null) {
                cacheValue = price
                cacheTimestamp = System.currentTimeMillis()
                InuConfig.PILL_STACK_GOLD_CACHE.value = price.toPlainString()
                InuConfig.PILL_STACK_GOLD_CACHE_TIME.value = System.currentTimeMillis()
            }
            complete(price ?: getCached())
        }
    }

    private fun complete(price: BigDecimal?) {
        val callbacks: List<Utilities.Callback<BigDecimal?>>
        synchronized(sync) {
            requestInFlight = false
            callbacks = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
        }
        AndroidUtilities.runOnUIThread {
            for (callback in callbacks) callback.run(price)
        }
    }

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
