package desu.inugram.ui.settings

import android.text.SpannableStringBuilder
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell2
import org.telegram.ui.Components.UItem

class ExpandableBoolGroup(
    val title: CharSequence,
    val options: List<Option>,
    val sectionId: Int = InuUtils.generateId(),
) {
    class Option(
        val labelRes: Int,
        val config: InuConfig.BoolItem,
        val id: Int = InuUtils.generateId(),
    )

    var expanded = false
    var enabled = true

    fun expandIfContains(id: Int) {
        if (options.any { it.id == id }) expanded = true
    }

    /** [info] is a row of its own shown first while the group is open, for a note that belongs to the group */
    fun addTo(items: ArrayList<UItem>, info: UItem? = null, onChange: (options: List<Option>) -> Unit) {
        val checkedCount = options.count { it.config.value }
        val isChecked = checkedCount > 0
        items.add(
            // entiny: stock cell does toString() on the subtitle and its AnimatedTextView ignores spans, so the pill rides in the title; " " keeps the arrow
            UItem.asExpandableSwitch(sectionId, SpannableStringBuilder(title).append("  ").append(SettingsBadge.state(checkedCount)), " ")
                .setChecked(isChecked)
                .setCollapsed(!expanded)
                .setClickCallback {
                    if (!enabled) return@setClickCallback
                    val enable = !isChecked
                    val changed = arrayListOf<Option>()
                    for (opt in options) {
                        if (opt.config.value != enable) {
                            changed.add(opt)
                            opt.config.value = enable
                        }
                    }
                    onChange(changed)
                }
                .onBind(Utilities.Callback { view ->
                    view.alpha = if (enabled) 1f else 0.5f
                    val cell = view as? TextCheckCell2 ?: return@Callback
                    cell.checkBox.also {
                        it.setDrawIconType(0)
                        it.setColors(
                            Theme.key_switchTrack,
                            Theme.key_switchTrackChecked,
                            Theme.key_windowBackgroundWhite,
                            Theme.key_windowBackgroundWhite,
                        )
                    }
                })
        )
        if (expanded) {
            info?.let { items.add(it) }
            for (opt in options) {
                items.add(WrapCheckCell.Factory.of(opt.id, LocaleController.getString(opt.labelRes), opt.config.value, enabled))
            }
        }
    }

    fun handleClick(item: UItem, view: View, onChange: (option: Option?) -> Unit): Boolean {
        if (item.id == sectionId) {
            expanded = !expanded
            onChange(null)
            return true
        }
        val opt = options.firstOrNull { it.id == item.id } ?: return false
        if (!enabled) return true
        val new = opt.config.toggle()
        (view as? WrapCheckCell)?.setChecked(new, true)
        onChange(opt)
        return true
    }
}
