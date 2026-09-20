package desu.inugram.helpers.pillstack

import android.widget.EditText
import android.widget.FrameLayout

// entiny: entry point wired from the stock hook in DialogsActivity where fragmentSearchField is built.
// entiny: always creates the controller -- visibility is decided in rebuild() so enabling the toggle applies live without reopening the screen
object PillStackHelper {

    @JvmStatic
    fun attach(container: FrameLayout, editText: EditText?): PillStackController {
        return PillStackController(container, editText)
    }
}
