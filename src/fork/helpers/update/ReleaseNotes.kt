package desu.inugram.helpers.update

import desu.inugram.helpers.Http
import org.json.JSONArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.Utilities

object ReleaseNotes {
    private const val LIST_URL = "https://api.github.com/repos/entinyGram/android/contents/changelogs"
    private const val RAW_URL = "https://raw.githubusercontent.com/entinyGram/android/main/changelogs/"
    private const val LIMIT = 12
    private const val TIMEOUT_MS = 6000
    private const val TTL_MS = 10L * 60 * 1000

    data class Release(val date: String, val lines: List<String>)

    private val FILE_RE = Regex("""^(\d{4})(\d{2})(\d{2})\.md$""")
    private val COMMENT_RE = Regex("""<!--[\s\S]*?-->""")
    private val SECTION_RE = Regex("""^##\s+(uk|en)\s*$""", RegexOption.IGNORE_CASE)
    private val GROUP_RE = Regex("""^###\s+.+$""")
    private val BULLET_RE = Regex("""^\s*-\s+(.*\S)\s*$""")
    private val LINK_RE = Regex("""\[([^\]]+)]\([^)]*\)""")

    @Volatile
    private var cache: List<Release>? = null

    @Volatile
    private var cacheLang = ""

    @Volatile
    private var cacheAt = 0L

    // callback runs on the ui thread; null means the list could not be loaded
    fun load(callback: (List<Release>?) -> Unit) {
        val lang = currentLang()
        val cached = cache
        if (cached != null && cacheLang == lang && System.currentTimeMillis() - cacheAt < TTL_MS) {
            callback(cached)
            return
        }
        Utilities.globalQueue.postRunnable {
            val releases = runCatching { fetch(lang) }.getOrNull()
            AndroidUtilities.runOnUIThread {
                if (releases != null) {
                    cache = releases
                    cacheLang = lang
                    cacheAt = System.currentTimeMillis()
                }
                callback(releases)
            }
        }
    }

    private fun currentLang(): String =
        if (LocaleController.getInstance().currentLocale?.language == "uk") "uk" else "en"

    private fun fetch(lang: String): List<Release>? {
        val list = Http.request(
            LIST_URL,
            headers = mapOf("Accept" to "application/vnd.github+json"),
            connectTimeout = TIMEOUT_MS,
            readTimeout = TIMEOUT_MS,
        )
        if (list.code != 200) return null
        val entries = JSONArray(list.body)
        val names = (0 until entries.length())
            .map { entries.getJSONObject(it).optString("name") }
            .filter { FILE_RE.matches(it) }
            .sortedDescending()
            .take(LIMIT)
        val releases = ArrayList<Release>(names.size)
        for (name in names) {
            val response = Http.request(RAW_URL + name, connectTimeout = TIMEOUT_MS, readTimeout = TIMEOUT_MS)
            if (response.code != 200) continue
            parse(response.body, name, lang)?.let { releases.add(it) }
        }
        return releases
    }

    // one release per file: the bullets of the current language, `###` groups flattened in file order
    private fun parse(raw: String, file: String, lang: String): Release? {
        val match = FILE_RE.find(file) ?: return null
        val (year, month, day) = match.destructured
        val lines = ArrayList<String>()
        var inSection = false
        for (line in raw.replace(COMMENT_RE, "").lines()) {
            val section = SECTION_RE.find(line)
            if (section != null) {
                inSection = section.groupValues[1].equals(lang, ignoreCase = true)
                continue
            }
            if (!inSection || GROUP_RE.matches(line)) continue
            val bullet = BULLET_RE.find(line)?.groupValues?.get(1) ?: continue
            lines.add(bullet.replace(LINK_RE, "\$1"))
        }
        if (lines.isEmpty()) return null
        return Release(date = "$day.$month.$year", lines = lines)
    }
}
