package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.UserConfig
import java.util.UUID

class FeedCategories(private val account: Int) {
    data class Category(val id: String, val title: String, val channels: Set<Long>, val hidden: Boolean = false)

    private var cachedSource: String? = null
    private var cachedOwner = 0L
    private var cachedCategories = emptyList<Category>()
    private var cachedSelection = ""

    private fun read() {
        val source = InuConfig.FEED_CATEGORIES.value
        val owner = UserConfig.getInstance(account).clientUserId
        if (source == cachedSource && owner == cachedOwner) return
        cachedSource = source
        cachedOwner = owner
        val state = runCatching { JSONObject(source).optJSONObject(owner.toString()) }.getOrNull()
        val entries = state?.optJSONArray("categories") ?: JSONArray()
        val categories = ArrayList<Category>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val id = entry.optString("id")
            val title = entry.optString("title").trim()
            if (id.isEmpty() || title.isEmpty() || categories.any { it.id == id }) continue
            val ids = entry.optJSONArray("channels") ?: JSONArray()
            val channels = HashSet<Long>()
            for (i in 0 until ids.length()) {
                val dialogId = ids.optLong(i)
                if (dialogId < 0) channels.add(dialogId)
            }
            categories.add(Category(id, title, channels, entry.optBoolean("hidden")))
        }
        cachedCategories = categories
        cachedSelection = state?.optString("selected").orEmpty().takeIf { id ->
            categories.any { it.id == id && !it.hidden }
        }.orEmpty()
    }

    val categories: List<Category>
        get() { read(); return cachedCategories }

    val selected: Category?
        get() { read(); return cachedCategories.firstOrNull { it.id == cachedSelection } }

    fun select(id: String) {
        val entries = categories
        if (id.isNotEmpty() && entries.none { it.id == id && !it.hidden }) return
        if (selected?.id.orEmpty() != id) write(entries, id)
    }

    fun create(title: String): Category {
        val category = Category(UUID.randomUUID().toString(), title.trim().take(64), emptySet())
        save(category)
        return category
    }

    fun save(category: Category) {
        if (category.title.isBlank()) return
        val entries = categories.toMutableList()
        val index = entries.indexOfFirst { it.id == category.id }
        val normalized = category.copy(title = category.title.trim().take(64), channels = HashSet(category.channels))
        if (index < 0) entries.add(normalized) else entries[index] = normalized
        write(entries, selected?.id.orEmpty())
    }

    fun delete(id: String) {
        write(categories.filterNot { it.id == id }, selected?.id.orEmpty())
    }

    fun reorder(ids: List<String>) {
        val byId = categories.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull { byId[it] }.toMutableList()
        ordered.addAll(categories.filterNot { it.id in ids })
        write(ordered, selected?.id.orEmpty())
    }

    private fun write(categories: List<Category>, selectedId: String) {
        val owner = UserConfig.getInstance(account).clientUserId
        if (owner == 0L) return
        val root = runCatching { JSONObject(InuConfig.FEED_CATEGORIES.value) }.getOrElse { JSONObject() }
        val entries = JSONArray()
        for (category in categories) {
            entries.put(JSONObject().apply {
                put("id", category.id)
                put("title", category.title)
                put("hidden", category.hidden)
                put("channels", JSONArray(category.channels.toList()))
            })
        }
        root.put(owner.toString(), JSONObject().apply {
            put("categories", entries)
            put("selected", selectedId.takeIf { id -> categories.any { it.id == id && !it.hidden } }.orEmpty())
        })
        InuConfig.FEED_CATEGORIES.value = root.toString()
        FeedChannelSet.invalidate()
    }
}
