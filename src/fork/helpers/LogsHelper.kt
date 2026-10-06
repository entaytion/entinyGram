package desu.inugram.helpers

import android.content.Context
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogsHelper {
    private const val SYSTEM_PREFS = "systemConfig"
    private const val LOGS_ENABLED_KEY = "logsEnabled"
    private const val MAX_LOGS_BYTES = 25L * 1024 * 1024

    // entiny: BuildVars reads this pref at startup and installs FileLog.fatal as the uncaught handler only when logs were on then
    private val handlerFromStartup = BuildVars.LOGS_ENABLED
    private var handlerInstalled = false

    // FileLog names the session file "<dd_MM_yyyy_HH_mm_ss>.txt"; siblings carry _mtproto/_net/_tonlib suffixes
    private val SESSION_LOG_NAME = Regex("""\d{2}_\d{2}_\d{4}_\d{2}_\d{2}_\d{2}\.txt""")

    fun isEnabled(): Boolean = BuildVars.LOGS_ENABLED

    fun setEnabled(enabled: Boolean) {
        if (BuildVars.LOGS_ENABLED == enabled) return
        BuildVars.LOGS_ENABLED = enabled
        ApplicationLoader.applicationContext.getSharedPreferences(SYSTEM_PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(LOGS_ENABLED_KEY, enabled)
        }
        if (enabled) {
            installFatalHandler()
            trimIfNeeded()
        } else {
            FileLog.cleanupLogs()
        }
    }

    private fun installFatalHandler() {
        if (handlerFromStartup || handlerInstalled) return
        handlerInstalled = true
        val past = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            FileLog.fatal(exception, false)
            past?.uncaughtException(thread, exception)
        }
    }

    // entiny: keep the logs folder bounded now that every release build can write logs
    private val trimQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    fun trimIfNeeded() {
        if (!BuildVars.LOGS_ENABLED) return
        if (!trimQueued.compareAndSet(false, true)) return
        Utilities.globalQueue.postRunnable {
            try {
                val files = (AndroidUtilities.getLogsDir()?.listFiles() ?: return@postRunnable)
                    .filter { it.isFile }
                    .map { it to it.lastModified() }
                    .sortedBy { it.second }
                    .toMutableList()
                var total = files.sumOf { it.first.length() }
                while (total > MAX_LOGS_BYTES && files.size > 1) {
                    val oldest = files.removeAt(0).first
                    total -= oldest.length()
                    oldest.delete()
                }
            } finally {
                trimQueued.set(false)
            }
        }
    }

    fun computeSize(): Long {
        val dir = AndroidUtilities.getLogsDir() ?: return 0L
        return dirSize(dir)
    }

    private fun dirSize(dir: File): Long {
        var sum = 0L
        dir.listFiles()?.forEach { sum += if (it.isFile) it.length() else dirSize(it) }
        return sum
    }

    fun shareCurrent(activity: LaunchActivity, onDone: (ok: Boolean) -> Unit) {
        stageThenShare(activity, onDone, mime = "text/plain") { stageCurrentLog() }
    }

    fun shareZip(activity: LaunchActivity, onDone: (ok: Boolean) -> Unit) {
        stageThenShare(activity, onDone, mime = "application/zip") { stageZip() }
    }

    private fun stageThenShare(
        activity: LaunchActivity,
        onDone: (ok: Boolean) -> Unit,
        mime: String,
        stage: () -> File?,
    ) {
        Utilities.globalQueue.postRunnable {
            val staged = try {
                stage()
            } catch (_: Throwable) {
                null
            }
            AndroidUtilities.runOnUIThread {
                if (staged == null) {
                    onDone(false)
                    return@runOnUIThread
                }
                onDone(true)
                SharePicker.shareFile(activity, staged, mime)
            }
        }
    }

    private fun stageCurrentLog(): File? {
        val src = currentLogFile() ?: return null
        val cacheDir = AndroidUtilities.getCacheDir().apply { mkdirs() }
        val dst = File(cacheDir, "entinygram-log.txt").apply { if (exists()) delete() }
        FileOutputStream(dst).buffered().use { out ->
            out.write(SystemInfo.build().toByteArray(Charsets.UTF_8))
            out.write("\n\n".toByteArray(Charsets.UTF_8))
            src.inputStream().buffered().use { it.copyTo(out) }
        }
        return dst
    }

    /** Latest FileLog session file, or null. */
    fun currentLogFile(): File? {
        val dir = AndroidUtilities.getLogsDir() ?: return null
        return dir.listFiles { f ->
            f.isFile && SESSION_LOG_NAME.matches(f.name)
        }?.maxByOrNull { it.lastModified() }
    }

    private fun stageZip(): File? {
        val dir = AndroidUtilities.getLogsDir() ?: return null
        val cacheDir = AndroidUtilities.getCacheDir().apply { mkdirs() }
        val dst = File(cacheDir, "entinygram-logs.zip").apply { if (exists()) delete() }
        ZipOutputStream(FileOutputStream(dst).buffered()).use { out ->
            out.putNextEntry(ZipEntry("system_info.txt"))
            out.write(SystemInfo.build().toByteArray(Charsets.UTF_8))
            out.closeEntry()
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().buffered().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return dst
    }

}
