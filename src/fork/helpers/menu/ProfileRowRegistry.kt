package desu.inugram.helpers.menu

import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject

object ProfileRowRegistry {
    private const val PREF_KEY = "profile_settings_discovered"
    private const val ORDINAL_BASE = 100

    private val rows = LinkedHashMap<Int, DynamicProfileRow>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = runCatching { InuConfig.prefs.getString(PREF_KEY, "") }.getOrNull().orEmpty()
        if (json.isEmpty()) return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getInt("id")
                rows[id] = DynamicProfileRow(id, o.optString("t"), o.optInt("i"), ORDINAL_BASE + rows.size)
            }
        }
    }

    @Synchronized
    fun byKey(key: String): DynamicProfileRow? {
        ensureLoaded()
        val id = key.removePrefix("row_").takeIf { it != key }?.toIntOrNull() ?: return null
        return rows[id]
    }

    @Synchronized
    fun discover(settingsId: Int, title: CharSequence?, iconRes: Int): DynamicProfileRow {
        ensureLoaded()
        val label = title?.toString().orEmpty().ifEmpty { "#$settingsId" }
        val existing = rows[settingsId]
        if (existing != null) {
            if (existing.label.toString() != label || existing.iconRes != iconRes) {
                existing.label = label
                existing.iconRes = iconRes
                runCatching { persist() }
            }
            return existing
        }
        val row = DynamicProfileRow(settingsId, label, iconRes, ORDINAL_BASE + rows.size)
        rows[settingsId] = row
        runCatching {
            persist()
            val config = InuConfig.PROFILE_SETTINGS_ROWS
            if (config.value.none { it.item == row }) config.value = config.value + MenuOrderEntry(row, true)
        }
        return row
    }

    private fun persist() {
        val arr = JSONArray()
        for (row in rows.values) {
            arr.put(JSONObject().put("id", row.settingsId).put("t", row.label.toString()).put("i", row.iconRes))
        }
        InuConfig.prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }
}
