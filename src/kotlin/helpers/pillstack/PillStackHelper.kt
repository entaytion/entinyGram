package desu.inugram.helpers.pillstack

import android.view.ViewGroup
import android.widget.EditText
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.FragmentSearchField

// entiny: entry point wired from the stock hook in DialogsActivity where fragmentSearchField is built.
// entiny: always creates the controller -- visibility is decided in rebuild() so enabling the toggle applies live without reopening the screen
object PillStackHelper {

    @JvmStatic
    fun attach(container: ViewGroup, editText: EditText?): PillStackController {
        return PillStackController(container, null, editText)
    }

    // entiny: the niche placement toggle picks between the search field and the action bar menu (left of the icons)
    @JvmStatic
    fun attach(fragment: BaseFragment, field: FragmentSearchField, editText: EditText?): PillStackController {
        return PillStackController(field, fragment.getActionBar()?.menu, editText)
    }
}
