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

    fun normalize(code: String?): String = code?.trim()?.uppercase(Locale.ROOT).orEmpty()

    // entiny: every code the rate source can convert, not a hand-picked shortlist.
    fun getTargetCurrencies(excludeCode: String?): Array<String> {
        val codes = ArrayList(ExchangeRates.allSupportedCodes())
        codes.removeAll { it.equals(excludeCode, ignoreCase = true) }
        codes.add(0, AUTO)
        return codes.toTypedArray()
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
            val javaCurrency = runCatching { Currency.getInstance(normalized) }.getOrNull()
            // entiny: crypto targets (BTC, TON...) have no java.util.Currency and tiny unit values -- widen the scale instead of rounding to 0.00
            val exp = javaCurrency?.defaultFractionDigits?.coerceAtLeast(0)
                ?: if (value < BigDecimal("0.01")) 6 else if (value < BigDecimal(1)) 4 else 2
            val scaled = value.setScale(exp, RoundingMode.HALF_UP)
            val format = NumberFormat.getNumberInstance(Locale.US)
            format.isGroupingUsed = true
            format.minimumFractionDigits = exp
            format.maximumFractionDigits = exp
            val formatted = format.format(scaled)
            val symbol = runCatching { javaCurrency!!.getSymbol(Locale.US) }.getOrNull()
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
