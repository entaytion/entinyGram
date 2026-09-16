package desu.inugram.helpers.pillstack

import android.widget.EditText
import android.widget.FrameLayout
import desu.inugram.InuConfig

// entiny: entry point wired from the stock hook in DialogsActivity where fragmentSearchField is built.
object PillStackHelper {

    @JvmStatic
    fun attach(container: FrameLayout, editText: EditText?): PillStackController? {
        if (!InuConfig.PILL_STACK_ENABLED.value) return null
        return PillStackController(container, editText)
    }
}
