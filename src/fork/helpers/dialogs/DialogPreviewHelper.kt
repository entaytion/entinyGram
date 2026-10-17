package desu.inugram.helpers.dialogs

import android.text.SpannableStringBuilder
import android.text.Spanned
import desu.inugram.InuConfig
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.TextStyleSpan

object DialogPreviewHelper {
    @JvmStatic
    var rev: Int = 0
        private set

    @JvmStatic
    fun refresh() {
        rev++
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    @JvmStatic
    fun spoil(text: CharSequence): CharSequence {
        if (!InuConfig.HIDE_DIALOG_PREVIEW.value || text.isEmpty()) return text
        val run = TextStyleSpan.TextStyleRun()
        run.flags = TextStyleSpan.FLAG_STYLE_SPOILER
        val out = SpannableStringBuilder(text)
        out.setSpan(TextStyleSpan(run), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }
}
