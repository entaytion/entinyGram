package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import java.util.Calendar
import java.util.Date

// entiny: shows when the current account was last seen online, i.e. what others see while Ghost Mode hides you
@SuppressLint("ViewConstructor")
class LastSeenPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) : BasePill(context, resourcesProvider) {

    init {
        setupPill(R.drawable.phosphor_eye)
        onUpdateData(false)
    }

    override fun getPillId(): Int = PillType.LAST_SEEN.id

    override fun getRefreshInterval(): Long = 30_000L

    override fun onUpdateData(force: Boolean) {
        textView.setText(statusText(), force)
        markDataUpdated()
    }

    private fun statusText(): String {
        return when (val status = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser()?.status) {
            is TLRPC.TL_userStatusOnline -> LocaleController.getString(R.string.Online)
            is TLRPC.TL_userStatusOffline -> formatSeen(status.expires.toLong() * 1000L)
            is TLRPC.TL_userStatusRecently -> LocaleController.getString(R.string.Lately)
            else -> "—"
        }
    }

    private fun formatSeen(millis: Long): String {
        val seen = Calendar.getInstance().apply { time = Date(millis) }
        val now = Calendar.getInstance()
        val sameDay = seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) && seen.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val formatter = if (sameDay) LocaleController.getInstance().getFormatterDay() else LocaleController.getInstance().getFormatterDayMonth()
        return formatter.format(Date(millis))
    }

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = showPillMenu { addSettingsEntry() }

    override fun updateColors() = applyPillColors()
}
