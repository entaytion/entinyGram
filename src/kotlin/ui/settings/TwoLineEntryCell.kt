package desu.inugram.ui.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

// entiny: icon row with a title and a gray description below, built as a UItem factory so the adapter owns its background and sections
class TwoLineEntryCell(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22f), dp(10f), dp(18f), dp(10f))
        icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon))
        addView(icon, LayoutHelper.createLinear(24, 24, 0f, 0f, 18f, 0f))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.textSize = 16f
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        subtitle.textSize = 14f
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        subtitle.maxLines = 2
        subtitle.ellipsize = TextUtils.TruncateAt.END
        texts.addView(title)
        texts.addView(subtitle)
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
    }

    fun set(iconRes: Int, titleText: CharSequence?, subtitleText: CharSequence?) {
        icon.setImageResource(iconRes)
        title.text = titleText
        subtitle.text = subtitleText
    }

    class Factory : UItem.UItemFactory<TwoLineEntryCell>() {
        override fun createView(
            context: Context,
            listView: RecyclerListView?,
            currentAccount: Int,
            classGuid: Int,
            resourcesProvider: Theme.ResourcesProvider?,
        ): TwoLineEntryCell = TwoLineEntryCell(context)

        override fun bindView(view: View, item: UItem, divider: Boolean, adapter: UniversalAdapter?, listView: UniversalRecyclerView?) {
            view.id = item.id
            (view as TwoLineEntryCell).set(item.iconResId, item.text, item.subtext)
        }

        companion object {
            private var ready = false

            @JvmStatic
            fun of(id: Int, iconRes: Int, title: CharSequence, subtitle: CharSequence): UItem {
                if (!ready) {
                    UItem.UItemFactory.setup(Factory())
                    ready = true
                }
                return UItem.ofFactory(Factory::class.java).also {
                    it.id = id
                    it.iconResId = iconRes
                    it.text = title
                    it.subtext = subtitle
                }
            }
        }
    }
}
