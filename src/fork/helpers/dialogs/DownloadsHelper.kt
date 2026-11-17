package desu.inugram.helpers.dialogs

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R

object DownloadsHelper {
    // entiny: stock draws only the progress line when idle; the arrow icon carries its own bar, aligned onto that line
    private var idleIcon: Drawable? = null
    private var idleIconColor = 0

    @JvmStatic
    fun drawIdleIcon(canvas: Canvas, view: View, color: Int, lineTop: Float) {
        var icon = idleIcon
        if (icon == null) {
            icon = ContextCompat.getDrawable(view.context, R.drawable.inu_download_arrow)?.mutate() ?: return
            icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            idleIcon = icon
            idleIconColor = color
        } else if (idleIconColor != color) {
            icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            idleIconColor = color
        }
        val size = AndroidUtilities.dp(24f)
        val left = (view.measuredWidth - size) / 2
        val top = (lineTop + AndroidUtilities.dp(1f) - size * 0.85f).toInt()
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }
}
