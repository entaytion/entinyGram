package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

@SuppressLint("ViewConstructor")
class RamPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.pillstack_ram) {

    override fun getPillId(): Int = PillType.RAM.id

    override fun getRefreshInterval(): Long = 3_000L

    override fun measureText(): String? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (info.totalMem <= 0) return null
        val used = info.totalMem - info.availMem
        return "${used * 100 / info.totalMem}%"
    }
}
