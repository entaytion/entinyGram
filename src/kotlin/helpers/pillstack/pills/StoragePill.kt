package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.os.StatFs
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

// entiny: free space on the device's data partition -- distinct from CachePill, which shows Telegram's own cache size.
@SuppressLint("ViewConstructor")
class StoragePill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    TelemetryPill(context, resourcesProvider, R.drawable.phosphor_hard_drive) {

    override fun getPillId(): Int = PillType.STORAGE.id

    override fun getRefreshInterval(): Long = 60_000L

    override fun measureText(): String? = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        AndroidUtilities.formatFileSize(stat.availableBytes)
    } catch (e: Exception) {
        null
    }
}
