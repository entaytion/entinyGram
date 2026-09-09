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
        // ItemOptions only dismisses after the click runnable, and its dismiss is animated, so a
        // second tap can still land on another entry and re-run the whole update.
        var handled = false
        items.forEachIndexed { index, text ->
            options.addChecked(index == selectedIndex, text) {
                if (handled || index == selectedIndex) return@addChecked
                handled = true
                onSelect(index)
                // UItem.equals() compares textValue, so an animated diff sees a changed value as
                // remove+insert and cross-fades the old and new name over each other. Rebind
                // without animation instead — the value row updates in place.
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
