package desu.inugram.helpers.chat

import android.util.Log
import desu.inugram.InuConfig
import java.util.regex.Pattern

object RegexFilterHelper {
    private const val TAG = "RegexFilterHelper"
    
    private var cachedPatternString: String? = null
    private var compiledPattern: Pattern? = null

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.REGEX_FILTER_ENABLED.value

    @JvmStatic
    fun getMode(): Int = InuConfig.REGEX_FILTER_MODE.value

    private fun getCompiledPattern(): Pattern? {
        val currentStr = InuConfig.REGEX_FILTER_PATTERNS.value
        if (currentStr.isBlank()) return null
        if (currentStr == cachedPatternString && compiledPattern != null) {
            return compiledPattern
        }
        return try {
            val pattern = Pattern.compile(currentStr, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            cachedPatternString = currentStr
            compiledPattern = pattern
            pattern
        } catch (e: Exception) {
            Log.e(TAG, "Invalid regex pattern: $currentStr", e)
            null
        }
    }

    @JvmStatic
    fun shouldFilter(text: CharSequence?): Boolean {
        if (!isEnabled() || text.isNull_or_empty()) return false
        val pattern = getCompiledPattern() ?: return false
        return try {
            pattern.matcher(text).find()
        } catch (e: Exception) {
            false
        }
    }

    private fun CharSequence?.isNull_or_empty(): Boolean = this == null || this.isEmpty()
}
