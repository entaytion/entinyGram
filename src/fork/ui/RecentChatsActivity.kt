package desu.inugram.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import android.widget.EditText
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.RecentChatsHelper
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

class RecentChatsActivity : SettingsPageActivity() {

    private var query = ""
    private var tab = TAB_ALL
    private var chips: ChipsRow? = null
    private val rows = HashMap<Int, Long>()
    private val refresh = Runnable { listView?.adapter?.update(true) }

    override fun getTitle(): CharSequence = getString(R.string.InuRecentChats)

    override fun createView(context: Context): View {
        return super.createView(context).also {
            val menu = actionBar.createMenu()
            val searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true)
            searchItem.setSearchFieldHint(getString(R.string.Search))
            searchItem.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(searchField: EditText) {
                    query = searchField.text.toString().trim().lowercase(Locale.getDefault())
                    listView?.adapter?.update(false)
                }
            })
            menu.addItem(MENU_CLEAR, R.drawable.msg_delete)
            actionBar.setActionBarMenuOnItemClick(object : org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_CLEAR -> confirmClear()
                    }
                }
            })
        }
    }

    override fun onFragmentCreate(): Boolean {
        RecentChatsHelper.addListener(refresh)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        RecentChatsHelper.removeListener(refresh)
        super.onFragmentDestroy()
    }

    private fun confirmClear() {
        val ctx = parentActivity ?: return
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(getString(R.string.InuClearRecentChats))
            .setMessage(getString(R.string.InuClearRecentChatsAlert))
            .setPositiveButton(getString(R.string.ClearButton).uppercase()) { _, _ ->
                RecentChatsHelper.clearRecentDialogs(currentAccount)
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .show()
    }

    private fun resolve(dialogId: Long): TLObject? {
        val controller = MessagesController.getInstance(currentAccount)
        return if (dialogId < 0) controller.getChat(-dialogId) else controller.getUser(dialogId)
    }

    private fun tabOf(obj: TLObject): Int = when {
        obj is TLRPC.Chat && ChatObject.isChannel(obj) && !obj.megagroup -> TAB_CHANNELS
        obj is TLRPC.Chat -> TAB_GROUPS
        else -> TAB_USERS
    }

    private fun titleOf(obj: TLObject): String = when (obj) {
        is TLRPC.Chat -> obj.title ?: ""
        is TLRPC.User -> UserObject.getUserName(obj)
        else -> ""
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        rows.clear()
        val all = RecentChatsHelper.entries(currentAccount).mapNotNull { id -> resolve(id)?.let { id to it } }
        val ctx = context ?: return
        val chipsRow = chips ?: ChipsRow(ctx).also { chips = it }
        chipsRow.bind(
            listOf(
                "${getString(R.string.FilterAllChats)} (${all.size})",
                "${getString(R.string.FilterChannels)} (${all.count { tabOf(it.second) == TAB_CHANNELS }})",
                "${getString(R.string.FilterGroups)} (${all.count { tabOf(it.second) == TAB_GROUPS }})",
                "${getString(R.string.InuRecentChatsUsers)} (${all.count { tabOf(it.second) == TAB_USERS }})",
            ),
            tab,
        ) { which ->
            tab = which
            listView?.adapter?.update(true)
        }
        items.add(UItem.asCustom(chipsRow, 52))
        items.add(UItem.asShadow(null))

        val shown = all.filter { (_, obj) ->
            (tab == TAB_ALL || tabOf(obj) == tab) &&
                (query.isEmpty() || titleOf(obj).lowercase(Locale.getDefault()).contains(query))
        }
        if (shown.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.InuRecentChatsEmpty)))
            return
        }
        for ((index, pair) in shown.withIndex()) {
            val id = ROW_BASE + index
            rows[id] = pair.first
            items.add(UItem.asProfileCell(pair.second).also { it.id = id })
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val dialogId = rows[item.id] ?: return
        RecentChatsHelper.openChat(this, dialogId)
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val dialogId = rows[item.id] ?: return false
        val opts = ItemOptions.makeOptions(this, view)
        opts.add(R.drawable.msg_openprofile, getString(R.string.OpenProfile)) {
            RecentChatsHelper.openProfile(this, dialogId)
        }
        opts.add(R.drawable.msg_delete, getString(R.string.Remove), true) {
            RecentChatsHelper.removeRecentDialog(currentAccount, dialogId)
        }
        opts.show()
        return true
    }

    companion object {
        private const val TAB_ALL = 0
        private const val TAB_CHANNELS = 1
        private const val TAB_GROUPS = 2
        private const val TAB_USERS = 3
        private const val ROW_BASE = 30_000
        private val MENU_SEARCH = InuUtils.generateId()
        private val MENU_CLEAR = InuUtils.generateId()
    }
}

private class ChipsRow(context: Context) : HorizontalScrollView(context) {
    private val row = LinearLayout(context)

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
    }

    fun bind(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        row.removeAllViews()
        labels.forEachIndexed { index, text ->
            val chip = TextView(context)
            chip.text = text
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14f), 0, dp(14f), 0)
            val on = index == selected
            chip.setTextColor(Theme.getColor(if (on) Theme.key_windowBackgroundWhiteBlueText else Theme.key_windowBackgroundWhiteGrayText))
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(if (on) Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), 0.15f) else 0)
            }
            chip.setOnClickListener { onSelect(index) }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)).apply { rightMargin = dp(4f) })
        }
    }
}
