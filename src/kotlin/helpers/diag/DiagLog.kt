package desu.inugram.helpers.diag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// entiny: remote-controlled diagnostics. A tester imports a .entinylog profile that switches on
// tracepoints for chosen zones, so most bugs can be chased without shipping a new APK.
// Tracepoints write to <logs>/<date>_diag.txt (picked up by "send logs") and mirror to FileLog.
object DiagLog {

    const val FILE_SUFFIX = ".entinylog"

    // zones with tracepoints in the code; a profile may also use "*" for all of them
    val KNOWN_CATEGORIES = listOf("contacts", "push", "feed", "export", "ghost", "forward")

    private const val PREFS = "entiny_diag"
    private const val KEY_PROFILE = "profile"
    private const val DEFAULT_HOURS = 48
    private const val MAX_HOURS = 24 * 14
    private const val STACK_DEPTH = 8

    class Profile(
        val name: String,
        val categories: Set<String>,
        val watch: Set<Long>,
        val stacks: Boolean,
        val expiresAt: Long,
        val probes: DiagProbes.Spec = DiagProbes.Spec(),
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt

        fun covers(category: String) = "*" in categories || category in categories

        fun toJson(): String = JSONObject().apply {
            put("name", name)
            put("categories", JSONArray(categories.toList()))
            put("watch", JSONArray(watch.toList()))
            put("stacks", stacks)
            put("expiresAt", expiresAt)
            put("probes", probes.toJson())
        }.toString()
    }

    @Volatile
    private var profile: Profile? = null
    private var loaded = false
    private var writer: FileWriter? = null
    private var writerDay: String? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("dd_MM_yyyy", Locale.US)

    private val prefs get() = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // region profile

    fun current(): Profile? {
        if (!loaded) {
            loaded = true
            profile = prefs.getString(KEY_PROFILE, null)?.let { parseStored(it) }
        }
        val p = profile ?: return null
        if (p.isExpired) {
            stop()
            return null
        }
        return p
    }

    // returns null with an error message when the file is not a valid profile
    fun parseFile(text: String): Pair<Profile?, String?> = try {
        val json = JSONObject(text)
        if (!json.has("entinylog")) {
            null to "not an entinylog profile"
        } else {
            val categories = json.optJSONArray("categories").toStringSet().map { it.lowercase(Locale.US) }.toSet()
            val probes = DiagProbes.Spec.from(json)
            if (categories.isEmpty() && probes.isEmpty) {
                null to "nothing to log: add categories, events, requests, updates or dump"
            } else {
                val hours = json.optInt("hours", DEFAULT_HOURS).coerceIn(1, MAX_HOURS)
                Profile(
                    name = json.optString("name").ifEmpty { categories.joinToString("+") },
                    categories = categories,
                    watch = json.optJSONArray("watch").toLongSet(),
                    stacks = json.optBoolean("stacks", true),
                    expiresAt = System.currentTimeMillis() + hours * 3_600_000L,
                    probes = probes,
                ) to null
            }
        }
    } catch (e: Exception) {
        null to (e.message ?: e.javaClass.simpleName)
    }

    fun activate(p: Profile) {
        profile = p
        loaded = true
        prefs.edit().putString(KEY_PROFILE, p.toJson()).apply()
        write("diag", "profile '${p.name}' on: categories=${p.categories} watch=${p.watch} stacks=${p.stacks} probes=${p.probes.toJson()}")
        DiagProbes.apply(p.probes, "activate")
    }

    fun stop() {
        val had = profile
        profile = null
        loaded = true
        prefs.edit().remove(KEY_PROFILE).apply()
        DiagProbes.clear()
        if (had != null) write("diag", "profile '${had.name}' off")
    }

    private fun parseStored(raw: String): Profile? = try {
        val json = JSONObject(raw)
        Profile(
            name = json.optString("name"),
            categories = json.optJSONArray("categories").toStringSet(),
            watch = json.optJSONArray("watch").toLongSet(),
            stacks = json.optBoolean("stacks", true),
            expiresAt = json.optLong("expiresAt"),
            probes = DiagProbes.Spec.from(json.optJSONObject("probes")),
        )
    } catch (_: Exception) {
        null
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    }

    private fun JSONArray?.toLongSet(): Set<Long> {
        if (this == null) return emptySet()
        return (0 until length()).map { optLong(it) }.filter { it != 0L }.toSet()
    }

    // endregion

    // called once on app start: re-arms probes of a profile imported earlier
    @JvmStatic
    fun init() {
        val p = current() ?: return
        write("diag", "profile '${p.name}' resumed")
        DiagProbes.apply(p.probes, "start")
    }

    // region tracepoints

    @JvmStatic
    fun on(category: String): Boolean = current()?.covers(category) == true

    // watched ids get a marker so they are easy to grep in a long log
    @JvmStatic
    fun isWatched(id: Long): Boolean {
        val p = current() ?: return false
        return id in p.watch || -id in p.watch
    }

    @JvmStatic
    fun log(category: String, message: String) {
        if (!on(category)) return
        write(category, message)
    }

    // like log, but appends the call site when the profile asks for stacks
    @JvmStatic
    fun trace(category: String, message: String) {
        val p = current() ?: return
        if (!p.covers(category)) return
        write(category, if (p.stacks) "$message via ${caller()}" else message)
    }

    fun ids(list: Collection<Long>, max: Int = 40): String {
        val marked = list.map { if (isWatched(it)) "$it★" else it.toString() }
        return if (marked.size <= max) marked.joinToString(",") else marked.take(max).joinToString(",") + ",…(${marked.size})"
    }

    private fun caller(): String =
        Throwable().stackTrace
            .dropWhile { it.className.startsWith(DiagLog::class.java.name) }
            .take(STACK_DEPTH)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    // probes decide coverage themselves; this only requires an active profile
    internal fun forceWrite(category: String, message: String) {
        if (current() != null) write(category, message)
    }

    private fun write(category: String, message: String) {
        val line = "$category: $message"
        if (BuildVars.LOGS_ENABLED) FileLog.d("diag/$line")
        val time = Date()
        Utilities.globalQueue.postRunnable {
            try {
                val out = writerFor(time) ?: return@postRunnable
                out.write(timeFormat.format(time) + " " + line + "\n")
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun writerFor(time: Date): FileWriter? {
        val day = dayFormat.format(time)
        if (writer != null && writerDay == day) return writer
        writer?.runCatching { close() }
        val dir = AndroidUtilities.getLogsDir() ?: return null
        writer = FileWriter(File(dir, "${day}$FILE_TAIL"), true)
        writerDay = day
        return writer
    }

    const val FILE_TAIL = "_diag.txt"

    // endregion
}
