package desu.inugram.helpers.text

import android.text.Spannable
import android.text.style.URLSpan

// entiny: trim leading Unicode labels matched as host prefix by stock LinkifyPort regex
object LinkHelper {

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    private val LEADING_UNICODE_LABELS = Regex("^(?:[^\\p{ASCII}]+?\\.)+")

    @JvmStatic
    fun fixUrlSpans(text: Spannable) {
        if (text.isEmpty()) {
            return
        }
        val spans = text.getSpans(0, text.length, URLSpan::class.java)
        for (span in spans) {
            // entiny: skip custom URLSpan subclasses created elsewhere
            if (span.javaClass != URLSpan::class.java) {
                continue
            }
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) {
                continue
            }
            val covered = text.subSequence(start, end).toString()
            if (covered.isEmpty() || covered[0].code < 0x80) {
                continue
            }
            if (SCHEME_PREFIX.containsMatchIn(covered)) {
                continue
            }
            val match = LEADING_UNICODE_LABELS.find(covered) ?: continue
            val remainder = covered.substring(match.value.length)
            if (remainder.isEmpty() || remainder.indexOf('.') < 0) {
                text.removeSpan(span)
                continue
            }
            text.removeSpan(span)
            text.setSpan(URLSpan("http://" + remainder), start + match.value.length, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
