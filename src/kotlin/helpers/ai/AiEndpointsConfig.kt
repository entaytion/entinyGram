package desu.inugram.helpers.ai

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject

data class AiEndpoint(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String,
    val model: String,
)

/**
 * List of user-configured AI compose endpoints, persisted as JSON in SharedPreferences.
 * API keys are not exported by settings backup ([exportable] = false).
 */
class AiEndpointsConfig(key: String) : InuConfig.Item<List<AiEndpoint>>(key, emptyList(), exportable = false) {

    override val prefType = InuConfig.PrefType.STRING

    override fun read(prefs: SharedPreferences): List<AiEndpoint> {
        val json = prefs.getString(key, "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<AiEndpoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    AiEndpoint(
                        id = o.getString("id"),
                        name = o.optString("name", ""),
                        url = o.optString("url", ""),
                        apiKey = o.optString("key", ""),
                        model = o.optString("model", ""),
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun SharedPreferences.Editor.write() {
        val arr = JSONArray()
        for (e in value) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("url", e.url)
                    put("key", e.apiKey)
                    put("model", e.model)
                }
            )
        }
        putString(key, arr.toString())
    }
}
