package desu.inugram.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.media.SleepTimerHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.LayoutHelper

object SleepTimerDialog {
    @JvmStatic
    fun show(context: Context, resourcesProvider: Theme.ResourcesProvider?) {
        val builder = AlertDialog.Builder(context, resourcesProvider)
        builder.setTitle(LocaleController.getString(R.string.InuSleepTimer))

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val status = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider))
            text = if (SleepTimerHelper.isActive()) {
                String.format(
                    LocaleController.getString(R.string.InuSleepTimerStatusOn),
                    SleepTimerHelper.formatRemaining(),
                )
            } else {
                LocaleController.getString(R.string.InuSleepTimerStatusOff)
            }
        }
        container.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))

        val dial = SleepTimerDialView(context, resourcesProvider)
        container.addView(dial, LayoutHelper.createLinear(232, 232, Gravity.CENTER_HORIZONTAL, 0f, 12f, 0f, 0f))

        val duration = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider))
            text = LocaleController.getString(R.string.InuSleepTimerOff)
        }
        container.addView(duration, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 4f))

        var endOfTrack = SleepTimerHelper.isFinishingCurrentTrack()
        val check = CheckBoxCell(context, 1, resourcesProvider)
        check.setText(LocaleController.getString(R.string.InuSleepTimerEndOfTrack), "", endOfTrack, false)
        check.setOnClickListener {
            endOfTrack = !endOfTrack
            check.setChecked(endOfTrack, true)
        }
        container.addView(check, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        var okButton: View? = null
        dial.onMinutesChanged = { m ->
            duration.text = if (m == 0) {
                LocaleController.getString(R.string.InuSleepTimerOff)
            } else {
                SleepTimerHelper.formatMinutes(m)
            }
            okButton?.isEnabled = m > 0
        }
        dial.setMinutes(SleepTimerHelper.currentMinutes())

        builder.setNeutralButton(
            LocaleController.getString(R.string.InuSleepTimerDisable),
            AlertDialog.OnButtonClickListener { _, _ -> SleepTimerHelper.clear() },
        )
        builder.setNegativeButton(
            LocaleController.getString(R.string.Cancel),
            AlertDialog.OnButtonClickListener { _, _ -> },
        )
        builder.setPositiveButton(
            LocaleController.getString(R.string.OK),
            AlertDialog.OnButtonClickListener { _, _ -> SleepTimerHelper.set(dial.getMinutes(), endOfTrack) },
        )
        builder.setView(container)
        val dialog = builder.show()
        okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton?.isEnabled = dial.getMinutes() > 0
    }
}
