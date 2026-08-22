package desu.inugram.helpers.text

import android.text.Spannable
import android.text.style.URLSpan

/**
 * Corrects URL spans produced by the stock link extractor.
 *
 * The stock LinkifyPort regex allows Unicode (e.g. Cyrillic) characters inside host
 * labels, and Java `\b` word boundaries are Unicode-aware. As a result a run like
 * `марний.entaytion.is-a.dev` (no whitespace before the ASCII domain) is matched as a
 * single URL whose host starts with a Cyrillic label, and the whole run becomes clickable.
 *
 * This helper post-processes the spans and trims leading non-ASCII labels off the match,
 * so `марний.entaytion.is-a.dev` renders as plain `марний.` plus a clickable
 * `entaytion.is-a.dev` pointing at `https://entaytion.is-a.dev`.
 */
object LinkHelper {

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    // One or more leading labels made entirely of non-ASCII characters, each ending with a dot.
    private val LEADING_UNICODE_LABELS = Regex("^(?:[^\\p{ASCII}]+?\\.)+")

    @JvmStatic
    fun fixUrlSpans(text: Spannable) {
        if (text.isEmpty()) {
            return
        }
        val spans = text.getSpans(0, text.length, URLSpan::class.java)
        for (span in spans) {
            // Only rework plain URLSpan instances; subclasses (URLSpanNoUnderline,
            // URLSpanReplacement) are created elsewhere and must stay untouched.
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
                continue // already an ASCII URL, nothing to fix
            }
            if (SCHEME_PREFIX.containsMatchIn(covered)) {
                continue // explicit scheme, host boundary is already correct
            }
            val match = LEADING_UNICODE_LABELS.find(covered) ?: continue
            val remainder = covered.substring(match.value.length)
            if (remainder.isEmpty() || remainder.indexOf('.') < 0) {
                // No meaningful ASCII domain left (e.g. `пример.com`) -> drop the link.
                text.removeSpan(span)
                continue
            }
            text.removeSpan(span)
            text.setSpan(URLSpan("http://" + remainder), start + match.value.length, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
