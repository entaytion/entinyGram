package desu.inugram.helpers.dialogs

import android.view.View
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import kotlin.math.ceil

object MutualContactHelper {
    @JvmStatic
    fun isLeft(): Boolean = InuConfig.MUTUAL_CONTACT_ICON_LEFT.value

    @JvmStatic
    fun iconMargin(tag: TextView?): Int {
        val text = tag?.text
        if (!isLeft() || tag == null || tag.visibility != View.VISIBLE || text.isNullOrEmpty()) return dp(8f)
        val width = ceil(tag.paint.measureText(text, 0, text.length)).toInt() + tag.paddingLeft + tag.paddingRight
        return dp(23f) + width - tag.translationX.toInt()
    }
}
