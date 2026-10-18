package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme

// entiny: base for local-telemetry pills (RAM, CPU, net speed, DC ping) -- no network, tap refreshes.
@SuppressLint("ViewConstructor")
abstract class TelemetryPill(context: Context, resourcesProvider: Theme.ResourcesProvider?, iconResId: Int) :
    BasePill(context, resourcesProvider) {

    @Volatile
    private var measureGeneration = 0

    init {
        setupPill(iconResId)
        textView.visibility = GONE
        startLoading()
    }

    protected abstract fun measureText(): String?

    override fun onUpdateData(force: Boolean) {
        val generation = ++measureGeneration
        Utilities.globalQueue.postRunnable {
            val text = runCatching { measureText() }.getOrNull()
            AndroidUtilities.runOnUIThread {
                if (generation != measureGeneration) return@runOnUIThread
                if (text == null) {
                    // entiny: keep shimmer when baseline seed returns null instead of flashing a placeholder
                    startLoading()
                    return@runOnUIThread
                }
                stopLoading()
                val old = textView.text
                if (textView.visibility != VISIBLE || old == null || old.toString() != text) {
                    animateSizeChange()
                    textView.setText(text, textView.visibility == VISIBLE)
                    textView.visibility = VISIBLE
                }
                markDataUpdated()
            }
        }
    }

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = showPillMenu {
        add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh)) { onUpdateData(true) }
        addSettingsEntry()
    }

    override fun applyIconColor(color: Int) {
        iconView.setColorFilter(color)
    }

    override fun updateColors() = applyPillColors()
}
