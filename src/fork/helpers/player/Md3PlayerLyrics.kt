package desu.inugram.helpers.player

import android.text.TextUtils
import android.util.LruCache
import desu.inugram.helpers.translate.engine.ProviderConfigException
import desu.inugram.helpers.translate.engine.httpJson
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.Utilities
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class Md3Lyrics private constructor(
    val lines: List<Line>,
    val synced: Boolean,
    val instrumental: Boolean,
    val online: Boolean,
) {
    class Line(val time: Long, val text: String)

    fun toText(): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (synced) {
                val t = max(0L, line.time)
                sb.append(String.format(Locale.US, "[%02d:%02d.%02d]", t / 60000, t / 1000 % 60, t % 1000 / 10))
            }
            sb.append(line.text).append('\n')
        }
        return sb.toString()
    }

    fun indexAt(ms: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].time <= ms) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        private val TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val WORD_TIME = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
        private val OFFSET = Regex("^\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
        private val META = Regex("^\\[[a-zA-Z#]+:.*]$")

        fun instrumental(online: Boolean) = Md3Lyrics(emptyList(), false, true, online)

        fun parse(text: String?, online: Boolean): Md3Lyrics? {
            if (text.isNullOrEmpty()) return null
            val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            var offset = 0L
            val timed = ArrayList<Line>()
            for (row in raw) {
                val line = row.trim()
                val off = OFFSET.find(line)
                if (off != null) {
                    off.groupValues[1].toLongOrNull()?.let { offset = it }
                    continue
                }
                var end = 0
                val times = ArrayList<Long>()
                var m = TIME.find(line)
                while (m != null && m.range.first == end) {
                    times.add(toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                    end = m.range.last + 1
                    m = m.next()
                }
                if (times.isEmpty()) continue
                val content = WORD_TIME.replace(line.substring(end), "").trim()
                for (t in times) timed.add(Line(max(0L, t - offset), content))
            }
            if (timed.isNotEmpty()) {
                timed.sortBy { it.time }
                val out = ArrayList<Line>(timed.size)
                for (i in timed.indices) {
                    val l = timed[i]
                    if (l.text.isEmpty() && (out.isEmpty() || out.last().text.isEmpty() || i == timed.size - 1)) continue
                    out.add(l)
                }
                return if (out.isEmpty()) null else Md3Lyrics(out, true, false, online)
            }
            val plain = ArrayList<Line>()
            for (row in raw) {
                val line = row.trim()
                if (META.matches(line)) continue
                if (line.isEmpty() && (plain.isEmpty() || plain.last().text.isEmpty())) continue
                plain.add(Line(-1, line))
            }
            while (plain.isNotEmpty() && plain.last().text.isEmpty()) plain.removeAt(plain.size - 1)
            return if (plain.isEmpty()) null else Md3Lyrics(plain, false, false, online)
        }

        private fun toMillis(min: String, sec: String, frac: String): Long {
            var ms = min.toLong() * 60_000L + sec.toLong() * 1000L
            if (frac.isNotEmpty()) {
                val f = frac.toLong()
                ms += when (frac.length) {
                    1 -> f * 100L
                    2 -> f * 10L
                    else -> f
                }
            }
            return ms
        }
    }
}

object Md3OnlineLyrics {
    const val OK = 0
    const val NOT_FOUND = 1
    const val ERROR = 2
    const val PROVIDER = "LRCLIB"

    class Query(artist: String?, title: String?, val album: String?, val duration: Int) {
        val artist: String?
        val title: String?

        init {
            var a = clean(artist)
            var t = clean(title)?.let { AUDIO_EXT.replaceFirst(it, "").replace('_', ' ').trim() }
            if (a.isNullOrEmpty() && t != null) {
                val dash = t.indexOf(" - ")
                if (dash > 0) {
                    a = t.substring(0, dash).trim()
                    t = t.substring(dash + 3).trim()
                }
            }
            this.artist = a
            this.title = t?.let { s -> DECORATION.replace(s, "").trim().ifEmpty { s } }
        }

        fun key(): String = (artist?.lowercase(Locale.ROOT) ?: "") + "\u0001" + (title?.lowercase(Locale.ROOT) ?: "") + "\u0001" + duration

        fun valid(): Boolean = !title.isNullOrEmpty()
    }

    private const val API = "https://lrclib.net/api/"
    private const val MAX_DURATION_DIFF = 3
    private val AUDIO_EXT = Regex("\\.(mp3|m4a|flac|ogg|oga|opus|wav|aac|alac|wma)$", RegexOption.IGNORE_CASE)
    private val DECORATION = Regex("\\s*[(\\[][^)\\]]*(official|lyric|video|audio|visualizer|remaster|hq|hd)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val queue by lazy { DispatchQueue("md3lyrics") }
    private val memory = LruCache<String, Md3Lyrics>(24)
    private val missing = HashSet<String>()

    private fun clean(s: String?): String? = s?.trim()?.ifEmpty { null }

    fun cached(query: Query): Md3Lyrics? = memory.get(query.key())

    fun knownMissing(query: Query): Boolean = missing.contains(query.key())

    fun loadCached(query: Query, callback: (Md3Lyrics?, Int) -> Unit) {
        val key = query.key()
        memory.get(key)?.let {
            callback(it, OK)
            return
        }
        queue.postRunnable {
            val disk = readDisk(key)
            AndroidUtilities.runOnUIThread {
                if (disk != null) memory.put(key, disk)
                callback(disk, if (disk != null) OK else NOT_FOUND)
            }
        }
    }

    fun fetch(query: Query, callback: (Md3Lyrics?, Int) -> Unit) {
        val key = query.key()
        memory.get(key)?.let {
            callback(it, OK)
            return
        }
        queue.postRunnable {
            var result = readDisk(key)
            var status = OK
            if (result == null) {
                try {
                    result = request(query)
                    if (result != null) writeDisk(key, result) else status = NOT_FOUND
                } catch (e: Throwable) {
                    android.util.Log.d("Md3Player", "lyrics fetch failed", e)
                    status = ERROR
                }
            }
            val lyrics = result
            AndroidUtilities.runOnUIThread {
                if (lyrics != null) {
                    memory.put(key, lyrics)
                    missing.remove(key)
                } else if (status == NOT_FOUND) {
                    missing.add(key)
                }
                callback(lyrics, status)
            }
        }
    }

    private fun request(q: Query): Md3Lyrics? {
        val title = q.title ?: return null
        if (!q.artist.isNullOrEmpty()) {
            val url = StringBuilder(API).append("get?artist_name=").append(enc(q.artist)).append("&track_name=").append(enc(title))
            if (!q.album.isNullOrEmpty()) url.append("&album_name=").append(enc(q.album))
            if (q.duration > 0) url.append("&duration=").append(q.duration)
            val body = get(url.toString())
            if (body != null) {
                val found = fromJson(JSONObject(body))
                if (found != null) return found
            }
        }
        val url = StringBuilder(API).append("search?track_name=").append(enc(title))
        if (!q.artist.isNullOrEmpty()) url.append("&artist_name=").append(enc(q.artist))
        val body = get(url.toString()) ?: return null
        val arr = JSONArray(body)
        var best: Md3Lyrics? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val found = fromJson(o) ?: continue
            var score = if (found.synced) 40 else 0
            if (q.duration > 0 && o.has("duration")) {
                val diff = abs(o.optDouble("duration", 0.0).roundToLong() - q.duration).toInt()
                score -= if (diff <= MAX_DURATION_DIFF) diff else 20 + min(diff, 60)
            }
            if (score > bestScore) {
                bestScore = score
                best = found
            }
        }
        return best
    }

    private fun optString(o: JSONObject, name: String): String? {
        if (o.isNull(name)) return null
        val s = o.optString(name, "")
        return if (TextUtils.isEmpty(s)) null else s
    }

    private fun fromJson(o: JSONObject): Md3Lyrics? {
        if (o.optBoolean("instrumental", false)) return Md3Lyrics.instrumental(true)
        val synced = Md3Lyrics.parse(optString(o, "syncedLyrics"), true)
        if (synced != null && synced.synced) return synced
        return Md3Lyrics.parse(optString(o, "plainLyrics"), true) ?: synced
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(url: String): String? = try {
        httpJson(
            url,
            headers = mapOf(
                "User-Agent" to "entinyGram/" + BuildVars.BUILD_VERSION_STRING + " (https://github.com/entaytion/entinyGram)",
                "Accept" to "application/json",
            ),
        )
    } catch (e: ProviderConfigException) {
        null
    }

    private fun cacheFile(key: String): File? {
        val dir = File(ApplicationLoader.applicationContext.cacheDir, "lrclib")
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, Utilities.MD5(key) + ".json")
    }

    private fun readDisk(key: String): Md3Lyrics? = try {
        val f = cacheFile(key)
        if (f == null || !f.exists()) {
            null
        } else {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            if (o.optBoolean("instrumental", false)) Md3Lyrics.instrumental(true) else Md3Lyrics.parse(optString(o, "text"), true)
        }
    } catch (e: Throwable) {
        null
    }

    private fun writeDisk(key: String, lyrics: Md3Lyrics) {
        try {
            val f = cacheFile(key) ?: return
            val o = JSONObject()
            o.put("instrumental", lyrics.instrumental)
            o.put("text", lyrics.toText())
            f.writeText(o.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            android.util.Log.d("Md3Player", "lyrics cache write failed", e)
        }
    }
}
