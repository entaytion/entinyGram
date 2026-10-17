package desu.inugram.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.BookmarksHelper
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

// entiny: inspired by NagramX bookmarks
class BookmarksManagerActivity : SettingsPageActivity() {

    private var query = ""
    private var tab = TAB_ALL
    private var chips: ChipsRow? = null
    private val rows = HashMap<Int, Long>()

    override fun getTitle(): CharSequence = getString(R.string.InuBookmarks)

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
            actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_CLEAR -> confirmClear()
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(false)
    }

    private fun confirmClear() {
        val ctx = parentActivity ?: return
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(getString(R.string.InuBookmarksClearAll))
            .setPositiveButton(getString(R.string.ClearButton).uppercase()) { _, _ ->
                BookmarksHelper.clearAll(currentAccount)
                listView?.adapter?.update(true)
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
        obj is TLRPC.User && obj.bot -> TAB_BOTS
        else -> TAB_USERS
    }

    private fun titleOf(obj: TLObject): String = when (obj) {
        is TLRPC.Chat -> obj.title ?: ""
        is TLRPC.User -> UserObject.getUserName(obj)
        else -> ""
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        rows.clear()
        val all = BookmarksHelper.counts(currentAccount).keys.mapNotNull { id -> resolve(id)?.let { id to it } }
        val ctx = context ?: return
        val chipsRow = chips ?: ChipsRow(ctx).also { chips = it }
        chipsRow.bind(
            listOf(
                "${getString(R.string.FilterAllChats)} (${all.size})",
                "${getString(R.string.FilterChannels)} (${all.count { tabOf(it.second) == TAB_CHANNELS }})",
                "${getString(R.string.FilterGroups)} (${all.count { tabOf(it.second) == TAB_GROUPS }})",
                "${getString(R.string.InuRecentChatsUsers)} (${all.count { tabOf(it.second) == TAB_USERS }})",
                "${getString(R.string.FilterBots)} (${all.count { tabOf(it.second) == TAB_BOTS }})",
            ),
            tab,
        ) { which ->
            tab = which
            listView?.adapter?.update(true)
        }
        items.add(UItem(UniversalAdapter.VIEW_TYPE_CUSTOM, true).also { it.view = chipsRow; it.intValue = 52 })
        items.add(UItem.asShadow(null))

        val shown = all.filter { (_, obj) ->
            (tab == TAB_ALL || tabOf(obj) == tab) &&
                (query.isEmpty() || titleOf(obj).lowercase(Locale.getDefault()).contains(query))
        }
        if (shown.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.InuBookmarksEmpty)))
            return
        }
        for ((index, pair) in shown.withIndex()) {
            val id = ROW_BASE + index
            rows[id] = pair.first
            items.add(UItem.asProfileCell(pair.second).also { it.id = id })
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.view === chips) {
            chips?.tap(x)
            return
        }
        val dialogId = rows[item.id] ?: return
        presentFragment(BookmarksActivity(dialogId))
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val dialogId = rows[item.id] ?: return false
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_delete, getString(R.string.Remove), true) {
                for (id in BookmarksHelper.ids(currentAccount, dialogId)) BookmarksHelper.remove(currentAccount, dialogId, id)
                listView?.adapter?.update(true)
            }
            .show()
        return true
    }

    companion object {
        private const val TAB_ALL = 0
        private const val TAB_CHANNELS = 1
        private const val TAB_GROUPS = 2
        private const val TAB_USERS = 3
        private const val TAB_BOTS = 4
        private const val ROW_BASE = 30_000
        private val MENU_SEARCH = InuUtils.generateId()
        private val MENU_CLEAR = InuUtils.generateId()
    }
}
