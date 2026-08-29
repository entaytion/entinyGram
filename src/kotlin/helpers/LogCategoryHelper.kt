package desu.inugram.helpers

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.Utilities
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mirrors every FileLog.d/w/e/fatal call into a per-category file, categorized automatically
 * from the calling class's package -- no per-call-site tagging needed. "Fork-<area>" for
 * desu.inugram.helpers.<area>.*, "Fork" for other desu.inugram.* classes (InuHooks, InuConfig,
 * ui.settings.*), "Stock" for org.telegram.* (all stock/UI logging), "Other" otherwise.
 */
object LogCategoryHelper {
    private val writers = HashMap<String, FileWriter>()
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @JvmStatic
    fun record(message: String?) {
        if (!BuildVars.LOGS_ENABLED || message == null) return
        val category = detectCategory() ?: return
        Utilities.globalQueue.postRunnable {
            try {
                val writer = writerFor(category)
                writer.write(timeFormat.format(Date()) + " " + message + "\n")
                writer.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun detectCategory(): String? {
        for (frame in Thread.currentThread().stackTrace) {
            val cls = frame.className
            if (cls == "org.telegram.messenger.FileLog" || cls == LogCategoryHelper::class.java.name || cls == "java.lang.Thread") continue
            return categoryFor(cls)
        }
        return null
    }

    private fun categoryFor(className: String): String = when {
        className.startsWith("desu.inugram.helpers.") -> {
            val area = className.removePrefix("desu.inugram.helpers.").substringBefore('.', "")
            if (area.isNotEmpty()) "Fork-${area.replaceFirstChar { it.uppercase() }}" else "Fork"
        }
        className.startsWith("desu.inugram.") -> "Fork"
        className.startsWith("org.telegram.") -> "Stock"
        else -> "Other"
    }

    private fun writerFor(category: String): FileWriter {
        writers[category]?.let { return it }
        val dir = File(AndroidUtilities.getLogsDir(), "inu-categories").apply { mkdirs() }
        val file = File(dir, dateFormat.format(Date()) + "_" + category + ".txt")
        val writer = FileWriter(file, true)
        writers[category] = writer
        return writer
    }

    /** Distinct category names with a log file for today (or any cached day still on disk). */
    fun availableCategories(): List<String> {
        val dir = File(AndroidUtilities.getLogsDir() ?: return emptyList(), "inu-categories")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.map { it.name.substringAfter('_').removeSuffix(".txt") }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }

    fun filesForCategories(categories: Set<String>): List<File> {
        val dir = File(AndroidUtilities.getLogsDir() ?: return emptyList(), "inu-categories")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && categories.any { c -> f.name.endsWith("_$c.txt") } }
            ?.toList() ?: emptyList()
    }
}
