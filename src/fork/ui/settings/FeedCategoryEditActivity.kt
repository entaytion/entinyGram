package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import desu.inugram.helpers.feed.FeedCategories
import desu.inugram.helpers.feed.FeedConfig
import desu.inugram.helpers.feed.FeedController
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

class FeedCategoryEditActivity(private val categoryId: String) : SettingsPageActivity() {
    private val channels = ArrayList<TLRPC.Chat>()
    private var query = ""
    private val categories get() = FeedConfig.getInstance(currentAccount).categories
    private val category get() = categories.categories.firstOrNull { it.id == categoryId }

    override fun getTitle(): CharSequence = category?.title ?: LocaleController.getString(R.string.InuFeedCategories)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        addSearch(0, Locale.ROOT, trim = true, onExpand = { expanded ->
            if (!expanded) {
                query = ""
                listView?.adapter?.update(true)
            }
        }) {
            query = it.orEmpty()
            listView?.adapter?.update(true)
        }
        FeedController.getInstance(currentAccount).loadChannels { loaded, _ ->
            channels.clear()
            channels.addAll(loaded.sortedBy { it.title?.lowercase(Locale.ROOT) })
            listView?.adapter?.update(true)
        }
        return view
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val current = category ?: return
        if (query.isEmpty()) {
            items.add(UItem.asButton(RENAME, R.drawable.msg_edit, LocaleController.getString(R.string.InuFeedCategoryName), current.title))
            items.add(UItem.asCheck(HIDDEN, LocaleController.getString(R.string.InuFeedCategoryHidden)).setChecked(current.hidden))
            items.add(UItem.asButton(SELECT_ALL, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll)))
            items.add(UItem.asButton(DESELECT_ALL, R.drawable.msg_cancel, LocaleController.getString(R.string.DeselectAll)))
            items.add(UItem.asButton(DELETE, R.drawable.entiny_trash, LocaleController.getString(R.string.Delete)).red())
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuFeedCategoriesInfo)))
        }
        for ((index, chat) in channels.withIndex()) {
            if (query.isNotEmpty() && chat.title?.lowercase(Locale.ROOT)?.contains(query) != true) continue
            items.add(UItem.asUserCheckbox(CHANNEL_BASE + index, chat).setChecked(-chat.id in current.channels))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val current = category ?: return
        val chat = item.`object` as? TLRPC.Chat
        when {
            chat != null -> {
                val included = current.channels.toMutableSet()
                if (!included.add(-chat.id)) included.remove(-chat.id)
                save(current.copy(channels = included))
            }
            item.id == RENAME -> showInputDialog(
                this, LocaleController.getString(R.string.InuFeedCategoryName), initialText = current.title, selectAll = true
            ) { title ->
                if (title.isBlank()) return@showInputDialog false
                save(current.copy(title = title))
                true
            }
            item.id == HIDDEN -> save(current.copy(hidden = !current.hidden))
            item.id == SELECT_ALL -> save(current.copy(channels = channels.mapTo(HashSet()) { -it.id }))
            item.id == DESELECT_ALL -> save(current.copy(channels = emptySet()))
            item.id == DELETE -> {
                val context = context ?: return
                showDialog(AlertDialog.Builder(context, resourceProvider)
                    .setTitle(current.title)
                    .setMessage(LocaleController.getString(R.string.InuFeedCategoryDeleteConfirm))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                        categories.delete(categoryId)
                        finishFragment()
                    }.create())
            }
        }
    }

    private fun save(value: FeedCategories.Category) {
        categories.save(value)
        simpleActionBar?.setTitle(getTitle())
        listView?.adapter?.update(true)
    }

    private companion object {
        const val RENAME = 1
        const val HIDDEN = 2
        const val DELETE = 3
        const val SELECT_ALL = 4
        const val DESELECT_ALL = 5
        const val CHANNEL_BASE = 100_000
    }
}
