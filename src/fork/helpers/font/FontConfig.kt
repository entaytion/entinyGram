package desu.inugram.helpers.font

import android.content.SharedPreferences
import desu.inugram.InuConfig
import desu.inugram.InuConfig.BoolItem
import desu.inugram.InuConfig.StringItem
import org.json.JSONArray
import org.json.JSONObject

object FontConfig {
    internal const val SYSTEM_STACK_ID = "@system@"
    internal const val BUNDLED_STACK_ID = "@default@"

    sealed class FontMode {
        object Bundled : FontMode()
        object System : FontMode()

        data class Custom(val fontId: FontId, val fallbacks: List<FontId>) : FontMode()

        fun toJson(): String {
            val obj = JSONObject()
            when (this) {
                Bundled -> obj.put("id", BUNDLED_STACK_ID)
                System -> obj.put("id", SYSTEM_STACK_ID)
                is Custom -> {
                    obj.put("id", fontId.token())
                    obj.put("fallbacks", JSONArray(fallbacks.map { it.token() }))
                }
            }
            return obj.toString()
        }

        companion object {
            fun fromJson(json: String): FontMode? = try {
                val obj = JSONObject(json)
                when (val id = obj.getString("id")) {
                    SYSTEM_STACK_ID -> System
                    BUNDLED_STACK_ID -> Bundled
                    else -> Custom(FontId.parseWithLegacyCompat(id), obj.optJSONArray("fallbacks").toFontIds())
                }
            } catch (_: Exception) {
                null
            }

            private fun JSONArray?.toFontIds(): List<FontId> =
                if (this == null) emptyList() else List(length()) { FontId.parse(getString(it)) }
        }
    }

    class FontModeItem : InuConfig.Item<FontMode>("font_config", FontMode.Bundled, false) {
        override val prefType = InuConfig.PrefType.STRING

        override fun read(prefs: SharedPreferences): FontMode {
            prefs.getString(key, null)?.let { return FontMode.fromJson(it) ?: default }
            // entiny: migrate legacy split keys (font_mode, active_font_id, font_fallbacks, use_system_font)
            return when {
                prefs.contains("font_mode") -> when (prefs.getInt("font_mode", 0)) {
                    1 -> FontMode.System
                    2 -> FontMode.Custom(
                        FontId.parseWithLegacyCompat(prefs.getString("active_font_id", "") ?: ""),
                        prefs.getString("font_fallbacks", null).parseFallbacks(),
                    )

                    else -> FontMode.Bundled
                }

                prefs.getBoolean("use_system_font", false) -> FontMode.System
                else -> default
            }
        }

        override fun SharedPreferences.Editor.write() {
            putString(key, value.toJson())
        }

        private fun String?.parseFallbacks(): List<FontId> = try {
            if (this == null) emptyList() else JSONArray(this).let { List(it.length()) { i -> FontId.parse(it.getString(i)) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val FONT = FontModeItem()
    val FONT_INCLUDE_SYSTEM = BoolItem("font_include_system", false)
    val MONO_FONT = StringItem("mono_font", "")

    // entiny: forces class initialization for InuConfig.load to populate items
    fun register() = Unit
}
