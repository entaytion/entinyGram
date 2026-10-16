package desu.inugram.helpers.dialogs

import android.text.Spannable
import android.text.Spanned
import desu.inugram.InuConfig
import org.telegram.ui.Components.TextStyleSpan

object DialogPreviewHelper {
    @JvmStatic
    fun spoil(text: Spannable) {
        if (!InuConfig.HIDE_DIALOG_PREVIEW.value || text.isEmpty()) return
        val run = TextStyleSpan.TextStyleRun()
        run.flags = TextStyleSpan.FLAG_STYLE_SPOILER
        text.setSpan(TextStyleSpan(run), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
