package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
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
    private val chevron = ImageView(context)

    init {
        orientation = HORIZONTAL
        // entiny: factory views get wrap_content from RecyclerView otherwise, which shrinks rows with short descriptions
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
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
        chevron.setImageResource(R.drawable.msg_arrowright)
        chevron.scaleX = if (LocaleController.isRTL) -1f else 1f // plain bitmap, never auto-mirrored
        chevron.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
        chevron.visibility = GONE
        addView(chevron, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 0f, 0f))
    }

    fun set(iconRes: Int, titleText: CharSequence?, subtitleText: CharSequence?, showChevron: Boolean) {
        chevron.visibility = if (showChevron) VISIBLE else GONE
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
            (view as TwoLineEntryCell).set(item.iconResId, item.text, item.subtext, item.checked)
        }

        companion object {
            private var ready = false

            @JvmStatic
            fun of(id: Int, iconRes: Int, title: CharSequence, subtitle: CharSequence, chevron: Boolean = false): UItem {
                if (!ready) {
                    UItem.UItemFactory.setup(Factory())
                    ready = true
                }
                return UItem.ofFactory(Factory::class.java).also {
                    it.id = id
                    it.iconResId = iconRes
                    it.text = title
                    it.subtext = subtitle
                    it.checked = chevron
                }
            }
        }
    }
}
