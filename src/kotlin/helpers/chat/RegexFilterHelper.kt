package desu.inugram.helpers.chat

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import java.util.UUID
import java.util.regex.Pattern

/**
 * Regex message filters — global (shared across every chat) or scoped to one dialog, with
 * per-filter enabled/case-insensitive/reversed(allow-list) flags and per-chat exclusions for
 * global filters. Feeds into [BlockedMessagesHelper]'s existing hide/mask pipeline, which is
 * already wired into stock via patches/feature/hide-blocked-messages.patch — this object only
 * owns the filter set and the match decision, not rendering.
 */
object RegexFilterHelper {
    private const val TAG = "RegexFilterHelper"

    data class FilterEntry(
        val id: String,
        val pattern: String,
        val dialogId: Long?, // null = global/shared
        val enabled: Boolean,
        val caseInsensitive: Boolean,
        val reversed: Boolean,
    )

    private data class MatchCacheEntry(val textHash: Int, val matched: Boolean)

    /** access-order LRU bounded per dialog; a message's cached entry self-invalidates on edit
     * because the stored textHash no longer matches the (changed) message text. */
    private class BoundedCache(private val maxSize: Int) : LinkedHashMap<Int, MatchCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, MatchCacheEntry>?) = size > maxSize
    }

    private val lock = Any()
    @Volatile private var globalFilters: List<FilterEntry>? = null
    @Volatile private var chatFilters: Map<Long, List<FilterEntry>>? = null
    @Volatile private var exclusions: Map<Long, Set<String>>? = null
    private val compiledPatterns = HashMap<String, Pattern?>()
    private val matchCache = HashMap<Long, BoundedCache>()

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.REGEX_FILTER_ENABLED.value

    @JvmStatic
    fun getMode(): Int = InuConfig.REGEX_FILTER_MODE.value

    // ---- message-level API used by BlockedMessagesHelper ----

    @JvmStatic
    fun isMessageFiltered(messageObject: MessageObject?): Boolean {
        if (!isEnabled() || messageObject?.messageOwner == null) return false
        val dialogId = messageObject.dialogId
        val text = buildString {
            messageObject.messageText?.let { if (it.isNotEmpty()) append(it).append('\n') }
            messageObject.caption?.let { if (it.isNotEmpty()) append(it) }
        }
        if (text.isEmpty()) return false
        val textHash = text.hashCode()
        synchronized(lock) {
            matchCache[dialogId]?.get(messageObject.id)?.let {
                if (it.textHash == textHash) return it.matched
            }
        }
        val result = matches(text, dialogId)
        synchronized(lock) {
            matchCache.getOrPut(dialogId) { BoundedCache(500) }[messageObject.id] = MatchCacheEntry(textHash, result)
        }
        return result
    }

    private fun matches(text: CharSequence, dialogId: Long?): Boolean {
        ensureLoaded()
        if (dialogId != null) {
            chatFilters!![dialogId]?.forEach { if (isMatch(it, text)) return true }
        }
        val excludedIds = if (dialogId != null) exclusions!![dialogId].orEmpty() else emptySet()
        for (filter in globalFilters!!) {
            if (filter.id in excludedIds) continue
            if (isMatch(filter, text)) return true
        }
        return false
    }

    private fun isMatch(filter: FilterEntry, text: CharSequence): Boolean {
        if (!filter.enabled) return false
        val pattern = getCompiledPattern(filter) ?: return false
        val matched = try {
            pattern.matcher(text).find()
        } catch (e: Exception) {
            false
        }
        return if (filter.reversed) !matched else matched
    }

    private fun getCompiledPattern(filter: FilterEntry): Pattern? {
        synchronized(lock) {
            if (compiledPatterns.containsKey(filter.id)) return compiledPatterns[filter.id]
        }
        val flags = if (filter.caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        val compiled = try {
            Pattern.compile(filter.pattern, flags)
        } catch (e: Exception) {
            Log.e(TAG, "invalid regex pattern: ${filter.pattern}", e)
            null
        }
        synchronized(lock) { compiledPatterns[filter.id] = compiled }
        return compiled
    }

    // ---- CRUD ----

    @JvmStatic
    fun getGlobalFilters(): List<FilterEntry> {
        ensureLoaded()
        return globalFilters!!
    }

    @JvmStatic
    fun getChatFilters(dialogId: Long): List<FilterEntry> {
        ensureLoaded()
        return chatFilters!![dialogId].orEmpty()
    }

    @JvmStatic
    fun getFilter(id: String): FilterEntry? {
        ensureLoaded()
        return globalFilters!!.find { it.id == id } ?: chatFilters!!.values.flatten().find { it.id == id }
    }

    @JvmStatic
    @JvmOverloads
    fun addFilter(pattern: String, dialogId: Long?, caseInsensitive: Boolean = true, reversed: Boolean = false): FilterEntry {
        ensureLoaded()
        val entry = FilterEntry(UUID.randomUUID().toString(), pattern, dialogId, true, caseInsensitive, reversed)
        saveAll(allFilters() + entry)
        return entry
    }

    @JvmStatic
    fun updateFilter(id: String, pattern: String, enabled: Boolean, caseInsensitive: Boolean, reversed: Boolean) {
        ensureLoaded()
        val updated = allFilters().map {
            if (it.id == id) it.copy(pattern = pattern, enabled = enabled, caseInsensitive = caseInsensitive, reversed = reversed) else it
        }
        saveAll(updated)
    }

    @JvmStatic
    fun removeFilter(id: String) {
        ensureLoaded()
        saveAll(allFilters().filterNot { it.id == id })
        removeExclusionsForFilter(id)
    }

    private fun allFilters(): List<FilterEntry> = globalFilters!! + chatFilters!!.values.flatten()

    // ---- import / export ----

    @JvmStatic
    fun exportJson(): String {
        ensureLoaded()
        val filtersArr = JSONArray()
        for (entry in allFilters()) filtersArr.put(entryToJson(entry))
        val exclusionsArr = JSONArray()
        for ((dialogId, filterIds) in exclusions!!) {
            for (filterId in filterIds) {
                exclusionsArr.put(JSONObject().apply {
                    put("dialogId", dialogId)
                    put("filterId", filterId)
                })
            }
        }
        return JSONObject().apply {
            put("filters", filtersArr)
            put("exclusions", exclusionsArr)
        }.toString()
    }

    /** Merges filters from [json] (as produced by [exportJson]) into the existing set — never
     * replaces/wipes. Imported filters always get fresh ids to avoid colliding with existing
     * ones; exclusions are remapped to the new ids. Returns how many filters were imported. */
    @JvmStatic
    fun importJson(json: String): Int {
        ensureLoaded()
        return try {
            val root = JSONObject(json)
            val filtersArr = root.optJSONArray("filters") ?: JSONArray()
            val exclusionsArr = root.optJSONArray("exclusions") ?: JSONArray()
            val idRemap = HashMap<String, String>()
            val imported = ArrayList<FilterEntry>()
            for (i in 0 until filtersArr.length()) {
                val obj = filtersArr.getJSONObject(i)
                val oldId = obj.optString("id")
                val newId = UUID.randomUUID().toString()
                if (oldId.isNotEmpty()) idRemap[oldId] = newId
                imported.add(
                    FilterEntry(
                        id = newId,
                        pattern = obj.optString("pattern"),
                        dialogId = if (obj.isNull("dialogId")) null else obj.optLong("dialogId"),
                        enabled = obj.optBoolean("enabled", true),
                        caseInsensitive = obj.optBoolean("caseInsensitive", true),
                        reversed = obj.optBoolean("reversed", false),
                    )
                )
            }
            if (imported.isEmpty()) return 0
            saveAll(allFilters() + imported)
            val currentExclusions = HashMap(exclusions!!.mapValues { HashSet(it.value) })
            for (i in 0 until exclusionsArr.length()) {
                val obj = exclusionsArr.getJSONObject(i)
                val dialogId = obj.optLong("dialogId")
                val newFilterId = idRemap[obj.optString("filterId")] ?: continue
                currentExclusions.getOrPut(dialogId) { HashSet() }.add(newFilterId)
            }
            saveExclusions(currentExclusions)
            imported.size
        } catch (e: Exception) {
            Log.e(TAG, "failed to import regex filters", e)
            0
        }
    }

    // ---- exclusions (a dialog opting out of a global filter) ----

    @JvmStatic
    fun isExcluded(dialogId: Long, filterId: String): Boolean {
        ensureLoaded()
        return exclusions!![dialogId]?.contains(filterId) == true
    }

    @JvmStatic
    fun setExcluded(dialogId: Long, filterId: String, excluded: Boolean) {
        ensureLoaded()
        val current = HashMap(exclusions!!.mapValues { HashSet(it.value) })
        val set = current.getOrPut(dialogId) { HashSet() }
        if (excluded) set.add(filterId) else set.remove(filterId)
        saveExclusions(current)
    }

    private fun removeExclusionsForFilter(filterId: String) {
        ensureLoaded()
        val current = HashMap(exclusions!!.mapValues { HashSet(it.value) })
        val iterator = current.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(filterId)
            if (entry.value.isEmpty()) iterator.remove()
        }
        saveExclusions(current)
    }

    // ---- persistence ----

    private fun ensureLoaded() {
        if (globalFilters != null && chatFilters != null && exclusions != null) return
        synchronized(lock) {
            if (globalFilters != null && chatFilters != null && exclusions != null) return
            migrateLegacyIfNeeded()
            val all = parseFilters(InuConfig.REGEX_FILTERS_JSON.value)
            globalFilters = all.filter { it.dialogId == null }
            chatFilters = all.filter { it.dialogId != null }.groupBy { it.dialogId!! }
            exclusions = parseExclusions(InuConfig.REGEX_FILTER_EXCLUSIONS_JSON.value)
        }
    }

    private fun migrateLegacyIfNeeded() {
        if (InuConfig.REGEX_FILTERS_MIGRATED.value) return
        InuConfig.REGEX_FILTERS_MIGRATED.value = true
        val legacyPattern = InuConfig.REGEX_FILTER_PATTERNS.value
        if (legacyPattern.isBlank()) return
        val entry = FilterEntry(UUID.randomUUID().toString(), legacyPattern, null, true, true, false)
        InuConfig.REGEX_FILTERS_JSON.value = JSONArray().put(entryToJson(entry)).toString()
    }

    private fun parseFilters(raw: String): List<FilterEntry> {
        val list = ArrayList<FilterEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    FilterEntry(
                        id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        pattern = obj.optString("pattern"),
                        dialogId = if (obj.isNull("dialogId")) null else obj.optLong("dialogId"),
                        enabled = obj.optBoolean("enabled", true),
                        caseInsensitive = obj.optBoolean("caseInsensitive", true),
                        reversed = obj.optBoolean("reversed", false),
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse regex filters", e)
        }
        return list
    }

    private fun entryToJson(entry: FilterEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("pattern", entry.pattern)
        put("dialogId", entry.dialogId ?: JSONObject.NULL)
        put("enabled", entry.enabled)
        put("caseInsensitive", entry.caseInsensitive)
        put("reversed", entry.reversed)
    }

    private fun parseExclusions(raw: String): Map<Long, Set<String>> {
        val map = HashMap<Long, HashSet<String>>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dialogId = obj.optLong("dialogId")
                val filterId = obj.optString("filterId")
                if (filterId.isEmpty()) continue
                map.getOrPut(dialogId) { HashSet() }.add(filterId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse regex filter exclusions", e)
        }
        return map
    }

    private fun saveAll(entries: List<FilterEntry>) {
        val arr = JSONArray()
        for (entry in entries) arr.put(entryToJson(entry))
        InuConfig.REGEX_FILTERS_JSON.value = arr.toString()
        synchronized(lock) {
            globalFilters = entries.filter { it.dialogId == null }
            chatFilters = entries.filter { it.dialogId != null }.groupBy { it.dialogId!! }
            compiledPatterns.clear()
            matchCache.clear()
        }
        notifyChanged()
    }

    private fun saveExclusions(map: Map<Long, Set<String>>) {
        val arr = JSONArray()
        for ((dialogId, filterIds) in map) {
            for (filterId in filterIds) {
                arr.put(JSONObject().apply {
                    put("dialogId", dialogId)
                    put("filterId", filterId)
                })
            }
        }
        InuConfig.REGEX_FILTER_EXCLUSIONS_JSON.value = arr.toString()
        synchronized(lock) {
            exclusions = map
            matchCache.clear()
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(
            NotificationCenter.updateInterfaces,
            MessagesController.UPDATE_MASK_ALL,
        )
    }
}
