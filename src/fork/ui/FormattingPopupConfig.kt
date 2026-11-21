package desu.inugram.ui

import android.content.SharedPreferences
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.R

class FormattingPopupConfig(key: String) : InuConfig.Item<List<FormattingPopupConfig.Entry>>(key, DEFAULT) {
    enum class Item(val key: String, val labelRes: Int, val iconRes: Int) {
        SELECT_ALL("select_all", R.string.SelectAll, R.drawable.entiny_select_all),
        COPY("copy", R.string.Copy, R.drawable.entiny_copy),
        CUT("cut", R.string.InuCut, R.drawable.entiny_scissors),
        PASTE("paste", R.string.Paste, R.drawable.entiny_clipboard),
        DIVIDER("divider", R.string.InuFormattingPopupDivider, 0),
        BOLD("bold", R.string.Bold, R.drawable.entiny_bold),
        ITALIC("italic", R.string.Italic, R.drawable.entiny_italic),
        UNDERLINE("underline", R.string.Underline, R.drawable.entiny_underline),
        STRIKE("strike", R.string.Strike, R.drawable.entiny_strikethrough),
        MONO("mono", R.string.Mono, R.drawable.entiny_code),
        CODE_BLOCK("code_block", R.string.InuCodeBlock, R.drawable.entiny_terminal),
        SPOILER("spoiler", R.string.Spoiler, R.drawable.entiny_eye_off),
        QUOTE("quote", R.string.Quote, R.drawable.entiny_quote),
        LINK("link", R.string.CreateLink, R.drawable.entiny_link),
        CLEAR("clear", R.string.Regular, R.drawable.entiny_remove_formatting);
    }

    data class Entry(val item: Item, val enabled: Boolean)

    override val prefType = InuConfig.PrefType.STRING

    override fun read(prefs: SharedPreferences): List<Entry> {
        val json = prefs.getString(key, "") ?: ""
        if (json.isEmpty()) return DEFAULT
        return try {
            val arr = JSONArray(json)
            val seen = HashSet<Item>()
            val out = ArrayList<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val itemKey = obj.getString("k")
                val item = Item.entries.firstOrNull { it.key == itemKey } ?: continue
                if (!seen.add(item)) continue
                out.add(Entry(item, obj.optBoolean("e", true)))
            }
            for (it in Item.entries) {
                if (!seen.contains(it)) out.add(Entry(it, true))
            }
            out
        } catch (_: Exception) {
            DEFAULT
        }
    }

    override fun SharedPreferences.Editor.write() {
        val arr = JSONArray()
        for (e in value) {
            arr.put(JSONObject().apply {
                put("k", e.item.key)
                put("e", e.enabled)
            })
        }
        putString(key, arr.toString())
    }

    fun resetToDefault() {
        value = DEFAULT
    }

    companion object {
        val DEFAULT: List<Entry> = Item.entries.map { Entry(it, true) }
    }
}
