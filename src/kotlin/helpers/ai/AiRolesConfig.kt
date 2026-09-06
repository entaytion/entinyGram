package desu.inugram.helpers.ai

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject

data class AiRole(
    val id: String,
    val text: String,
    val prompt: String = "",
)

/** List of user-saved AI persona presets, persisted as JSON in SharedPreferences. */
class AiRolesConfig(key: String) : InuConfig.Item<List<AiRole>>(key, emptyList()) {

    override val prefType = InuConfig.PrefType.STRING

    override fun read(prefs: SharedPreferences): List<AiRole> {
        val json = prefs.getString(key, "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<AiRole>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(AiRole(id = o.getString("id"), text = o.optString("text", ""), prompt = o.optString("prompt", "")))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun SharedPreferences.Editor.write() {
        val arr = JSONArray()
        for (r in value) {
            arr.put(JSONObject().apply { put("id", r.id); put("text", r.text); put("prompt", r.prompt) })
        }
        putString(key, arr.toString())
    }
}
