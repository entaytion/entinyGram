package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.DateFormat
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import java.util.Date

@SuppressLint("ViewConstructor")
class ClockPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) : BasePill(context, resourcesProvider) {

    init {
        setupPill(R.drawable.phosphor_clock)
        onUpdateData(false)
    }

    override fun getPillId(): Int = PillType.CLOCK.id

    override fun getRefreshInterval(): Long = 30_000L

    override fun onUpdateData(force: Boolean) {
        val timeFormat = DateFormat.getTimeFormat(context)
        textView.setText(timeFormat.format(Date()), force)
        markDataUpdated()
    }

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = showPillMenu { addSettingsEntry() }

    override fun updateColors() = applyPillColors()
}
