package desu.inugram.helpers

import android.icu.text.DateFormat
import android.icu.util.ULocale
import desu.inugram.InuConfig
import org.telegram.messenger.time.FastDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CalendarHelper {
    private val cache = HashMap<String, FastDateFormat>()

    @JvmStatic
    fun isEnabled(): Boolean = keyword() != null

    // entiny: ICU "ca" locale keyword picks the calendar system: islamic = lunar Hijri, persian = solar Hijri/Jalali
    private fun keyword(): String? = when (InuConfig.CALENDAR_SYSTEM.value) {
        InuConfig.CalendarSystemItem.HIJRI -> "islamic"
        InuConfig.CalendarSystemItem.PERSIAN -> "persian"
        else -> null
    }

    // entiny: reuses stock SimpleDateFormat-style patterns (dd MMM, d MMMM yyyy, ...) but renders them
    // against ICU's islamic/persian calendar via the "ca" locale keyword, so month/day names stay locale-aware.
    @JvmStatic
    @Synchronized
    fun altFormatter(pattern: String, locale: Locale): FastDateFormat {
        val ca = keyword() ?: "islamic"
        val key = "$ca|$pattern|$locale"
        return cache.getOrPut(key) {
            val altLocale = Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("ca", ca).build()
            val icuFormat = DateFormat.getPatternInstance(pattern, ULocale.forLocale(altLocale))
            object : FastDateFormat(pattern, TimeZone.getDefault(), locale) {
                override fun format(date: Date): String = icuFormat.format(date)
                override fun format(millis: Long): String = icuFormat.format(Date(millis))
            }
        }
    }

    @JvmStatic
    fun altCalendar(locale: Locale): android.icu.util.Calendar {
        val ca = keyword() ?: "islamic"
        val altLocale = Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("ca", ca).build()
        return android.icu.util.Calendar.getInstance(ULocale.forLocale(altLocale))
    }

    @JvmStatic
    fun formatYearMonthDay(dateMillis: Long, alwaysShowYear: Boolean, locale: Locale): String {
        val cal = altCalendar(locale)
        val nowYear = cal.get(android.icu.util.Calendar.YEAR)
        cal.timeInMillis = dateMillis
        val year = cal.get(android.icu.util.Calendar.YEAR)
        val day = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)
        val monthName = altFormatter("MMM", locale).format(Date(dateMillis))
        return if (year == nowYear && !alwaysShowYear) "$monthName $day" else "$monthName $day, $year"
    }

    @JvmStatic
    fun formatYearMonth(dateMillis: Long, alwaysShowYear: Boolean, locale: Locale): String {
        val cal = altCalendar(locale)
        val nowYear = cal.get(android.icu.util.Calendar.YEAR)
        cal.timeInMillis = dateMillis
        val year = cal.get(android.icu.util.Calendar.YEAR)
        val monthName = altFormatter("MMMM", locale).format(Date(dateMillis))
        return if (year == nowYear && !alwaysShowYear) monthName else "$monthName $year"
    }
}
