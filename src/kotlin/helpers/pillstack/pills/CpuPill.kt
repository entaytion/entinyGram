package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import java.io.BufferedReader
import java.io.FileReader

// entiny: CPU load from /proc/stat -- share of busy ticks between two reads; first read only sets the baseline.
@SuppressLint("ViewConstructor")
class CpuPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.pillstack_cpu) {

    companion object {
        private var previousTotal = -1L
        private var previousIdle = 0L
    }

    override fun getPillId(): Int = PillType.CPU.id

    override fun getRefreshInterval(): Long = 3_000L

    override fun measureText(): String? {
        val stat = readCpuStat() ?: return null
        val (idle, total) = stat
        if (previousTotal < 0) {
            previousIdle = idle
            previousTotal = total
            return null
        }
        val deltaTotal = total - previousTotal
        val deltaIdle = idle - previousIdle
        previousIdle = idle
        previousTotal = total
        if (deltaTotal <= 0) return null
        val percent = (deltaTotal - deltaIdle) * 100 / deltaTotal
        return "${percent.coerceIn(0, 100)}%"
    }

    /** first=idle+iowait, second=sum of all ticks */
    private fun readCpuStat(): Pair<Long, Long>? = try {
        BufferedReader(FileReader("/proc/stat")).use { reader ->
            val line = reader.readLine()
            if (line == null || !line.startsWith("cpu ")) return null
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) return null
            var total = 0L
            for (i in 1..8) if (i < parts.size) total += parts[i].toLong()
            val idle = parts[4].toLong() + parts[5].toLong()
            idle to total
        }
    } catch (e: Exception) {
        FileLog.e(e)
        null
    }
}
