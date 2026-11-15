package desu.inugram.ui.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CheckBox2
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

// entiny: round checkbox row whose label wraps instead of fading out, the stock animated cell is single-line with a fixed height
class WrapCheckCell(context: Context) : LinearLayout(context) {
    private val box = CheckBox2(context, 21).apply {
        setDrawUnchecked(true)
        setDrawBackgroundAsArc(10)
        setColor(Theme.key_switch2TrackChecked, Theme.key_radioBackground, Theme.key_checkboxCheck)
    }
    private val label = TextView(context)
    private var boundId = -1

    init {
        orientation = HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        layoutDirection = if (LocaleController.isRTL) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50f)
        setPaddingRelative(dp(61f), dp(12f), dp(21f), dp(12f))
        addView(box, LayoutHelper.createLinear(21, 21, 0f, 0f, 18f, 0f))
        label.textSize = 16f
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
    }

    fun setChecked(checked: Boolean, animated: Boolean) = box.setChecked(checked, animated)

    fun set(id: Int, text: CharSequence?, checked: Boolean, enabled: Boolean) {
        label.text = text
        box.setChecked(checked, boundId == id)
        boundId = id
        alpha = if (enabled) 1f else 0.5f
    }

    class Factory : UItem.UItemFactory<WrapCheckCell>() {
        override fun createView(
            context: Context,
            listView: RecyclerListView?,
            currentAccount: Int,
            classGuid: Int,
            resourcesProvider: Theme.ResourcesProvider?,
        ): WrapCheckCell = WrapCheckCell(context)

        override fun bindView(view: View, item: UItem, divider: Boolean, adapter: UniversalAdapter?, listView: UniversalRecyclerView?) {
            view.id = item.id
            (view as WrapCheckCell).set(item.id, item.text, item.checked, item.enabled)
        }

        companion object {
            private var ready = false

            @JvmStatic
            fun of(id: Int, text: CharSequence, checked: Boolean, enabled: Boolean = true): UItem {
                if (!ready) {
                    UItem.UItemFactory.setup(Factory())
                    ready = true
                }
                return UItem.ofFactory(Factory::class.java).also {
                    it.id = id
                    it.text = text
                    it.checked = checked
                    it.enabled = enabled
                }
            }
        }
    }
}
