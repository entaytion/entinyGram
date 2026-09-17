package desu.inugram.helpers.pillstack

import desu.inugram.InuConfig
import org.telegram.PhoneFormat.PhoneFormat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.json.JSONObject
import java.io.BufferedReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.Currency
import java.util.Locale

// entiny: exchange rates from Coinbase's public endpoint (no auth). Stored as "USD per unit", so any pair derives by division.
object ExchangeRates {

    private const val URL_STRING = "https://api.coinbase.com/v2/exchange-rates?currency=USD"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    val MAIN_CURRENCIES = arrayOf(
        "USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN", "AED", "CNY", "JPY", "BYN", "ILS", "CZK", "INR", "IRR",
        "TON", "BTC", "ETH"
    )

    class State(private val usdRates: Map<String, BigDecimal>) {
        fun getUsdRate(code: String?): BigDecimal? = code?.let { usdRates[it] }

        /** How many units of [target] one unit of [base] is worth. */
        fun getRate(base: String, target: String): BigDecimal? {
            val baseRate = getUsdRate(base) ?: return null
            val targetRate = getUsdRate(target) ?: return null
            if (targetRate.signum() == 0) return null
            return baseRate.divide(targetRate, 12, RoundingMode.HALF_UP)
        }
    }

    private val sync = Any()
    private val pendingCallbacks = ArrayList<Utilities.Callback<State?>>()
    private var requestInFlight = false
    private var cacheValue: State? = null
    private var cacheTimestamp = 0L

    fun clearCache() {
        cacheTimestamp = 0
    }

    private fun isStale(): Boolean =
        getCached() == null || cacheTimestamp == 0L || System.currentTimeMillis() - cacheTimestamp >= CACHE_TTL_MS

    fun getCached(): State? {
        if (cacheValue == null) {
            val raw = InuConfig.PILL_STACK_RATE_CACHE.value
            if (raw.isNotEmpty()) {
                cacheValue = deserialize(raw)
                cacheTimestamp = InuConfig.PILL_STACK_RATE_CACHE_TIME.value
            }
        }
        return cacheValue
    }

    fun fetch(callback: Utilities.Callback<State?>) {
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
            val state = try {
                fetchBlocking()
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
            if (state != null) {
                cacheValue = state
                cacheTimestamp = System.currentTimeMillis()
                saveCache(state)
            }
            complete(state ?: getCached())
        }
    }

    private fun complete(state: State?) {
        val callbacks: List<Utilities.Callback<State?>>
        synchronized(sync) {
            requestInFlight = false
            callbacks = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
        }
        AndroidUtilities.runOnUIThread {
            for (callback in callbacks) callback.run(state)
        }
    }

    private fun fetchBlocking(): State? {
        val connection = URL(URL_STRING).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): State? = try {
        val rates = JSONObject(json).getJSONObject("data").getJSONObject("rates")
        val result = HashMap<String, BigDecimal>()
        for (code in MAIN_CURRENCIES) {
            val usdRate = parseUsdRate(code, rates)
            if (usdRate != null) result[code] = usdRate
        }
        if (result.isEmpty()) null else State(result)
    } catch (e: Exception) {
        FileLog.e(e)
        null
    }

    /** Coinbase reports "units of currency per 1 USD"; we want the inverse. */
    private fun parseUsdRate(code: String, rates: JSONObject): BigDecimal? {
        if (code == "USD") return BigDecimal.ONE
        val value = rates.optString(code)
        if (value.isEmpty()) return null
        return try {
            val decimal = BigDecimal(value)
            if (decimal.signum() == 0) null else BigDecimal.ONE.divide(decimal, 16, RoundingMode.HALF_UP)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCache(state: State) {
        InuConfig.PILL_STACK_RATE_CACHE.value = serialize(state)
        InuConfig.PILL_STACK_RATE_CACHE_TIME.value = System.currentTimeMillis()
    }

    private fun serialize(state: State): String {
        val builder = StringBuilder()
        // usdRates is private; re-derive from MAIN_CURRENCIES since that's all we ever store.
        for (code in MAIN_CURRENCIES) {
            val rate = state.getUsdRate(code) ?: continue
            if (builder.isNotEmpty()) builder.append(',')
            builder.append(code).append('=').append(rate.toPlainString())
        }
        return builder.toString()
    }

    private fun deserialize(raw: String): State? {
        val result = HashMap<String, BigDecimal>()
        for (part in raw.split(",")) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            try {
                result[part.substring(0, index)] = BigDecimal(part.substring(index + 1))
            } catch (e: Exception) {
                // skip malformed entry
            }
        }
        return if (result.isEmpty()) null else State(result)
    }

    fun isSupportedCurrency(code: String?): Boolean = MAIN_CURRENCIES.contains(PillCurrencies.normalize(code))

    /** AUTO -> the current account phone number's country currency, falling back to USD. */
    fun resolveTargetCurrency(selection: String?): String {
        val normalized = PillCurrencies.normalize(selection)
        if (!PillCurrencies.AUTO.equals(normalized, ignoreCase = true)) {
            return if (normalized.isEmpty() || !isSupportedCurrency(normalized)) "USD" else normalized
        }
        return currencyOfCountry(phoneCountry()) ?: "USD"
    }

    private fun phoneCountry(): String? = try {
        val user = UserConfig.getInstance(UserConfig.selectedAccount).currentUser
        val phone = user?.phone
        if (phone.isNullOrEmpty()) null else {
            val stripped = PhoneFormat.stripExceptNumbers(phone)
            val info = PhoneFormat.getInstance().findCallingCodeInfo(stripped)
            when {
                info == null -> null
                info.callingCode == "7" -> if (stripped.startsWith("76") || stripped.startsWith("77")) "KZ" else "RU"
                info.countries.isEmpty() -> null
                else -> info.countries[0].uppercase(Locale.US)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun currencyOfCountry(country: String?): String? {
        if (country.isNullOrEmpty() || country.length != 2) return null
        return try {
            val code = PillCurrencies.normalize(Currency.getInstance(Locale("", country)).currencyCode)
            if (isSupportedCurrency(code)) code else null
        } catch (e: Exception) {
            null
        }
    }
}
