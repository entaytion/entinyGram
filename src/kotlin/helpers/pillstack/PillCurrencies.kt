package desu.inugram.helpers.pillstack

import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// entiny: target-currency list + fiat formatting for rate pills, using java.util.Currency instead of per-code resource strings exteraGram had (we don't carry those translations).
object PillCurrencies {

    const val AUTO = "AUTO"

    val TARGET_CURRENCIES = arrayOf(
        AUTO, "USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN", "AED", "CNY", "JPY", "BYN", "ILS", "CZK", "INR", "IRR"
    )

    fun normalize(code: String?): String = code?.trim()?.uppercase(Locale.ROOT).orEmpty()

    fun getTargetCurrencies(excludeCode: String?): Array<String> {
        if (excludeCode.isNullOrEmpty()) return TARGET_CURRENCIES
        return TARGET_CURRENCIES.filter { !it.equals(excludeCode, ignoreCase = true) }.toTypedArray()
    }

    fun getTargetCurrencyLabel(code: String?): CharSequence {
        if (code.isNullOrEmpty() || AUTO.equals(code, ignoreCase = true)) {
            return LocaleController.getString(R.string.InuPillStackCurrencyAuto)
        }
        val normalized = normalize(code)
        val name = runCatching { Currency.getInstance(normalized).getDisplayName(Locale.getDefault()) }.getOrNull()
        return if (name.isNullOrEmpty()) normalized else "$name — $normalized"
    }

    fun getTargetCurrencySubtext(code: String?): CharSequence {
        if (code.isNullOrEmpty() || AUTO.equals(code, ignoreCase = true)) {
            return LocaleController.getString(R.string.InuPillStackCurrencyAuto)
        }
        return normalize(code)
    }

    fun formatFiatPrice(value: BigDecimal?, code: String?): String? {
        if (value == null || code.isNullOrEmpty()) return null
        val normalized = normalize(code)
        return try {
            val exp = runCatching { Currency.getInstance(normalized).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
            val scaled = value.setScale(exp, RoundingMode.HALF_UP)
            val format = NumberFormat.getNumberInstance(Locale.US)
            format.isGroupingUsed = true
            format.minimumFractionDigits = exp
            format.maximumFractionDigits = exp
            val formatted = format.format(scaled)
            val symbol = runCatching { Currency.getInstance(normalized).getSymbol(Locale.US) }.getOrNull()
            if (symbol.isNullOrEmpty() || symbol.equals(normalized, ignoreCase = true)) {
                "$formatted $normalized"
            } else {
                "$symbol$formatted"
            }
        } catch (e: Exception) {
            null
        }
    }
}
