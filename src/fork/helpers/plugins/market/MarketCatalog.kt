package desu.inugram.helpers.plugins.market

import desu.inugram.core.plugins.PluginManifest
import desu.inugram.helpers.plugins.PluginDependencies
import desu.inugram.helpers.plugins.PluginLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader

/** the entinyGram plugins: `catalog.json` of the plugins repository's release, built by its CI from the bundles' headers */
object MarketCatalog {
    const val CATALOG_URL = "https://github.com/entinyGram/plugins/releases/download/plugins/catalog.json"

    private const val TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cacheFile by lazy { File(ApplicationLoader.applicationContext.cacheDir, "market_catalog.json") }

    /** off the ui thread. The network's answer, or the last one that arrived when the network has none */
    fun load(): List<MarketEntry>? {
        val fresh = runCatching { download(CATALOG_URL, MAX_BYTES).toString(Charsets.UTF_8) }
            .onFailure { PluginLog.HOST.w("market", "catalog download failed: ${it.message}") }
            .getOrNull()
        if (fresh != null) {
            val parsed = runCatching { parse(fresh) }.onFailure { PluginLog.HOST.w("market", "bad catalog: ${it.message}") }.getOrNull()
            if (parsed != null) {
                runCatching { cacheFile.writeText(fresh) }
                return parsed
            }
        }
        return runCatching { parse(cacheFile.readText()) }.getOrNull()
    }

    /** off the ui thread; follows redirects (release assets live on another host) */
    fun download(address: String, limit: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    check(out.size() <= limit) { "too large" }
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun parse(json: String): List<MarketEntry> {
        val plugins = JSONObject(json).getJSONArray("plugins")
        return (0 until plugins.length()).mapNotNull { i -> plugins.optJSONObject(i)?.let(::entryOf) }
    }

    private fun entryOf(o: JSONObject): MarketEntry? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val file = o.optJSONObject("file") ?: return null
        val url = file.optString("url").takeIf { it.isNotEmpty() } ?: return null
        val tags = MarketTags.fromHeader(o.optJSONArray("tags").strings())
        val descriptions = HashMap<String, String>()
        o.optJSONObject("description")?.let { d -> d.keys().forEach { key -> d.optString(key).takeIf { it.isNotEmpty() }?.let { descriptions[key.lowercase()] = it } } }
        val manifest = PluginManifest(
            name = o.optString("name").ifEmpty { id },
            author = o.optString("author").takeIf { it.isNotEmpty() },
            declaredId = id,
            version = o.optString("version").takeIf { it.isNotEmpty() },
            description = descriptions["en"] ?: descriptions.values.firstOrNull(),
            localizedDescriptions = descriptions.filterKeys { it != "en" },
            icon = if (o.isNull("icon")) null else o.optString("icon").takeIf { it.isNotEmpty() },
            grants = o.optJSONArray("grants").strings(),
            pluginApi = if (o.has("api")) o.optInt("api") else null,
            platform = "android",
            raw = mapOf(PluginDependencies.KEY to o.optJSONArray("requires").strings()),
        )
        val changelog = o.optJSONObject("changelog")?.optString("en")?.takeIf { it.isNotEmpty() }
        return MarketEntry(
            id = id,
            manifest = manifest,
            tags = tags,
            source = MarketSource.OFFICIAL,
            changelog = changelog,
            file = MarketFile(url, file.optLong("size"), file.optString("sha256")),
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
}
