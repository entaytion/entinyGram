package desu.inugram.helpers.dialogs

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme

object DrawerM3SectionsHelper {

    @JvmStatic
    fun isEnabled(): Boolean =
        InuConfig.NAVIGATION_DRAWER.value && InuConfig.DRAWER_M3_SECTIONS.value

    private val outerR get() = AndroidUtilities.dp(16f).toFloat()
    private val innerR get() = AndroidUtilities.dp(4f).toFloat()

    fun styleMenuRow(view: View, posInGroup: Int, groupSize: Int) {
        view.setStateListAnimator(null)
        val first = posInGroup == 0
        val last = posInGroup == groupSize - 1
        val radii = when {
            first && last -> FloatArray(8) { outerR }
            first -> floatArrayOf(outerR, outerR, outerR, outerR, innerR, innerR, innerR, innerR)
            last -> floatArrayOf(innerR, innerR, innerR, innerR, outerR, outerR, outerR, outerR)
            else -> FloatArray(8) { innerR }
        }
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            cornerRadii = radii
        }
        view.background = RippleDrawable(
            ColorStateList.valueOf(Theme.getColor(Theme.key_listSelector)),
            card,
            card,
        )
        val lp = view.layoutParams as RecyclerView.LayoutParams
        lp.setMargins(
            AndroidUtilities.dp(12f),
            if (first) AndroidUtilities.dp(4f) else AndroidUtilities.dp(1f),
            AndroidUtilities.dp(12f),
            if (last) AndroidUtilities.dp(4f) else AndroidUtilities.dp(1f),
        )
        view.layoutParams = lp
    }

    fun resetMenuRow(view: View) {
        view.background = null
        view.setStateListAnimator(null)
        val lp = view.layoutParams as RecyclerView.LayoutParams
        if (lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0) {
            lp.setMargins(0, 0, 0, 0)
            view.layoutParams = lp
        }
    }

    fun applySeparator(view: View) {
        view.visibility = if (isEnabled()) View.GONE else View.VISIBLE
    }
}
