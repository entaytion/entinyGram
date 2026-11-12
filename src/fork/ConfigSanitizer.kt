package desu.inugram

import android.content.SharedPreferences
import android.util.Log

object ConfigSanitizer {
    fun loadAll(prefs: SharedPreferences, items: List<InuConfig.Item<*>>) {
        var editor: SharedPreferences.Editor? = null
        for (item in items) {
            val broken = try {
                item.load(prefs)
                val v = item.value
                v is Float && !v.isFinite()
            } catch (e: Exception) {
                true
            }
            if (!broken) continue
            Log.w("ConfigSanitizer", "reset broken setting ${item.key}")
            if (editor == null) editor = prefs.edit()
            editor.remove(item.key)
            item.reset()
        }
        editor?.apply()
    }
}
