package desu.inugram.helpers.plugins

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.telegram.messenger.ApplicationLoader

object PluginCrashLog {
    private const val MAX_BYTES = 128 * 1024
    private const val LOG = "errors.log"
    private const val REPORT = "report"

    class Report(val reason: String, val culprit: String?)

    private val dir: File get() = File(ApplicationLoader.applicationContext.filesDir, "inu_plugins_guard")

    @Synchronized
    fun record(tag: String, level: String, message: String) {
        try {
            dir.mkdirs()
            val file = File(dir, LOG)
            if (file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast(MAX_BYTES / 2)
                file.writeText(tail.substringAfter('\n', tail))
            }
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$time $level/$tag: $message\n")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun text(): String = runCatching { File(dir, LOG).readText() }.getOrDefault("")

    fun tail(lines: Int): String = text().trimEnd().lines().takeLast(lines).joinToString("\n")

    @Synchronized
    fun markReport(reason: String, culprit: String?) {
        try {
            dir.mkdirs()
            File(dir, REPORT).writeText("$reason\n${culprit.orEmpty()}")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun takeReport(): Report? {
        val file = File(dir, REPORT)
        val lines = runCatching { file.readText().lines() }.getOrNull() ?: return null
        file.delete()
        return Report(lines.firstOrNull().orEmpty(), lines.getOrNull(1)?.takeIf { it.isNotBlank() })
    }
}
