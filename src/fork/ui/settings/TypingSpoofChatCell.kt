package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLObject
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

// entiny: avatar row with a name and the current spoof mode below it, built as a UItem factory like TwoLineEntryCell
class TypingSpoofChatCell(context: Context) : LinearLayout(context) {
    private val avatar = BackupImageView(context).apply { setRoundRadius(dp(23f)) }
    private val avatarDrawable = AvatarDrawable()
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val chevron = ImageView(context)

    init {
        orientation = HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16f), dp(8f), dp(18f), dp(8f))
        addView(avatar, LayoutHelper.createLinear(46, 46, 0f, 0f, 14f, 0f))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.textSize = 16f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        subtitle.textSize = 14f
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        texts.addView(title)
        texts.addView(subtitle)
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        chevron.setImageResource(R.drawable.msg_arrowright)
        chevron.scaleX = if (LocaleController.isRTL) -1f else 1f
        chevron.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
        addView(chevron, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 0f, 0f))
    }

    fun set(obj: TLObject?, titleText: CharSequence?, subtitleText: CharSequence?, active: Boolean, showChevron: Boolean) {
        avatarDrawable.setInfo(obj)
        avatar.setForUserOrChat(obj, avatarDrawable)
        title.text = titleText
        subtitle.text = subtitleText
        subtitle.setTextColor(Theme.getColor(if (active) Theme.key_windowBackgroundWhiteBlueText else Theme.key_windowBackgroundWhiteGrayText))
        chevron.visibility = if (showChevron) VISIBLE else GONE
    }

    class Factory : UItem.UItemFactory<TypingSpoofChatCell>() {
        override fun createView(
            context: Context,
            listView: RecyclerListView?,
            currentAccount: Int,
            classGuid: Int,
            resourcesProvider: Theme.ResourcesProvider?,
        ): TypingSpoofChatCell = TypingSpoofChatCell(context)

        override fun bindView(view: View, item: UItem, divider: Boolean, adapter: UniversalAdapter?, listView: UniversalRecyclerView?) {
            view.id = item.id
            (view as TypingSpoofChatCell).set(item.`object` as? TLObject, item.text, item.subtext, item.accent, !item.checked)
        }

        companion object {
            private var ready = false

            @JvmStatic
            fun of(id: Int, obj: TLObject?, title: CharSequence, subtitle: CharSequence, active: Boolean, plain: Boolean = false): UItem {
                if (!ready) {
                    UItem.UItemFactory.setup(Factory())
                    ready = true
                }
                return UItem.ofFactory(Factory::class.java).also {
                    it.id = id
                    it.`object` = obj
                    it.text = title
                    it.subtext = subtitle
                    it.accent = active
                    it.checked = plain
                }
            }
        }
    }
}
