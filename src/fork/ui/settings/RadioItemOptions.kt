package desu.inugram.ui.settings

import android.view.View
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.ItemOptions

object RadioItemOptions {
    fun show(
        fragment: BaseFragment,
        anchor: View,
        items: List<CharSequence>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val options = ItemOptions.makeOptions(fragment, anchor)
        // entiny: guard with handled flag because animated ItemOptions dismiss allows double-tap
        var handled = false
        items.forEachIndexed { index, text ->
            options.addChecked(index == selectedIndex, text) {
                if (handled) return@addChecked
                handled = true
                onSelect(index)
                // entiny: rebind without animation because UItem textValue diff would trigger remove and insert crossfade
                val adapter = (fragment as? SettingsPageActivity)?.listView?.adapter
                if (adapter != null) {
                    adapter.update(false)
                } else {
                    (anchor as? TextCell)?.setValue(text, true)
                }
            }
        }
        options.show()
    }
}
