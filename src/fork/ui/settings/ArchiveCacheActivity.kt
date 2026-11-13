package desu.inugram.ui.settings

import desu.inugram.helpers.archive.ArchiveStore
import android.content.Context
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.archive.ArchiveCache
import desu.inugram.helpers.archive.ArchiveChatHistory
import desu.inugram.helpers.security.ArchiveExportHelper
import desu.inugram.ui.ChipsRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CheckBox2
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

class ArchiveCacheActivity(
    private var tab: Int = TAB_ALL,
) : BaseFragment() {
    private class Merged(
        val dialogId: Long,
        val deleted: InuDatabaseHelper.DialogCacheStat?,
        val edited: InuDatabaseHelper.DialogCacheStat?,
    ) {
        fun stat(tab: Int): InuDatabaseHelper.DialogCacheStat? = when (tab) {
            TAB_DELETED -> deleted
            TAB_EDITED -> edited
            else -> if (deleted != null && edited != null) {
                if (deleted.lastDate >= edited.lastDate) deleted else edited
            } else deleted ?: edited
        }

        fun count(tab: Int): Int = when (tab) {
            TAB_DELETED -> deleted?.count ?: 0
            TAB_EDITED -> edited?.count ?: 0
            else -> (deleted?.count ?: 0) + (edited?.count ?: 0)
        }
    }

    private var all = emptyList<Merged>()
    private var stats = emptyList<Merged>()
    private var chips: ChipsRow? = null
    private var loading = true
    private var clearing = false
    private var selecting = false
    private val selected = LinkedHashSet<Long>()
    private var listView: RecyclerListView? = null
    private var adapter: ArchiveAdapter? = null
    private var emptyView: TextView? = null

    private fun categoryTitle() = LocaleController.getString(
        when (tab) {
            TAB_DELETED -> R.string.InuCacheDeletedCategory
            TAB_EDITED -> R.string.InuCacheEditsCategory
            else -> R.string.InuArchiveTitle
        }
    )

    private fun applyTab() {
        stats = all.filter { it.count(tab) > 0 }
        chips?.bind(
            listOf(
                "${LocaleController.getString(R.string.InuArchiveAll)} (${all.size})",
                "${LocaleController.getString(R.string.InuCacheDeletedCategory)} (${all.count { it.deleted != null }})",
                "${LocaleController.getString(R.string.InuCacheEditsCategory)} (${all.count { it.edited != null }})",
            ),
            tab,
        ) {
            tab = it
            cancelSelection()
            actionBar?.setTitle(categoryTitle())
            applyTab()
            refreshRows()
        }
    }

    private fun dialogTitle(id: Long): String = when (id) {
        0L, userConfig.clientUserId -> LocaleController.getString(R.string.SavedMessages)
        else -> messagesController.getUser(id)?.let { UserObject.getUserName(it) }
            ?: messagesController.getChat(-id)?.title ?: "ID $id"
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(categoryTitle())
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> if (selecting) cancelSelection() else finishFragment()
                    MENU_SEARCH -> presentFragment(DeletedMessageSearchActivity())
                    MENU_EXPORT -> ArchiveExportHelper.export(this@ArchiveCacheActivity)
                    MENU_SELECT_ALL -> selectAll()
                    MENU_CLEAR -> confirmClear()
                }
            }
        })
        val menu = actionBar.createMenu()
        menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).contentDescription =
            LocaleController.getString(R.string.Search)
        val other = menu.addItem(0, R.drawable.ic_ab_other)
        other.contentDescription = LocaleController.getString(R.string.AccDescrMoreOptions)
        other.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll))
        other.addSubItem(MENU_EXPORT, R.drawable.msg_shareout, LocaleController.getString(R.string.InuExportArchive))
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, LocaleController.getString(R.string.Delete)).setColorKeys(Theme.key_text_RedRegular, Theme.key_text_RedRegular)

        val root = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        }
        val list = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        adapter = ArchiveAdapter(context)
        list.adapter = adapter
        listView = list
        list.setOnItemClickListener { _, position, _, _ ->
            val stat = stats.getOrNull(position) ?: return@setOnItemClickListener
            if (selecting) toggleSelection(stat.dialogId) else openArchive(stat)
        }
        list.setOnItemLongClickListener { _, position ->
            stats.getOrNull(position)?.let {
                selecting = true
                selected.add(it.dialogId)
                refreshRows()
            }
            true
        }
        val chipsRow = ChipsRow(context).also { chips = it }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(chipsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52))
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        applyTab()

        val empty = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            textSize = 14f
            setPadding(
                AndroidUtilities.dp(32f),
                AndroidUtilities.dp(24f),
                AndroidUtilities.dp(32f),
                AndroidUtilities.dp(24f)
            )
            visibility = View.GONE
        }
        emptyView = empty
        root.addView(
            empty,
            LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
        )
        fragmentView = root
        refreshRows()
        return root
    }

    private fun refreshStats() {
        loading = true
        refreshRows()
        val storage = MessagesStorage.getInstance(currentAccount)
        storage.storageQueue.postRunnable {
            val result = runCatching {
                val db = checkNotNull(storage.database)
                ArchiveStore.getDeletedMessagesStats(db, false) to ArchiveStore.getDeletedMessagesStats(db, true)
            }
            AndroidUtilities.runOnUIThread {
                if (isFinished) return@runOnUIThread
                result.onSuccess { (deleted, edits) ->
                    val deletedById = deleted.associateBy { it.dialogId }
                    val editedById = edits.associateBy { it.dialogId }
                    all = (deletedById.keys + editedById.keys)
                        .map { Merged(it, deletedById[it], editedById[it]) }
                        .sortedByDescending { it.stat(TAB_ALL)?.lastDate ?: 0L }
                    applyTab()
                }.onFailure {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred))
                        .show()
                }
                selected.retainAll(stats.map { it.dialogId }.toSet())
                loading = false
                refreshRows()
            }
        }
    }

    private fun refreshRows() {
        adapter?.notifyDataSetChanged()
        emptyView?.text = if (loading) {
            LocaleController.getString(R.string.InuCacheCalculating)
        } else {
            LocaleController.formatString(R.string.InuCacheEntriesShort, 0)
        }
        emptyView?.visibility = if (loading || stats.isEmpty()) View.VISIBLE else View.GONE
        actionBar?.setSubtitle(if (selecting) "${selected.size}" else null)
    }

    private fun openArchive(item: Merged) {
        val dialogId = item.dialogId
        val showEdits = when (tab) {
            TAB_EDITED -> true
            TAB_DELETED -> false
            else -> item.deleted == null
        }
        ArchiveChatHistory.open(this, dialogId, if (showEdits) ArchiveChatHistory.KIND_EDITED else ArchiveChatHistory.KIND_DELETED)
    }

    private fun toggleSelection(dialogId: Long) {
        if (!selected.add(dialogId)) selected.remove(dialogId)
        if (selected.isEmpty()) selecting = false
        refreshRows()
    }

    private fun selectAll() {
        if (stats.isEmpty()) return
        if (selecting && selected.size == stats.size) {
            cancelSelection()
        } else {
            selecting = true
            selected.clear()
            selected.addAll(stats.map { it.dialogId })
            refreshRows()
        }
    }

    private fun cancelSelection() {
        selecting = false
        selected.clear()
        refreshRows()
    }

    private fun confirmClear() {
        if (clearing || stats.isEmpty()) return
        val ids = if (selecting) selected.toList() else stats.map { it.dialogId }
        if (ids.isEmpty()) return
        val activity = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.InuCacheClearAll))
                .setMessage("${categoryTitle()} — ${ids.size}")
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ -> clearDialogs(ids) }
                .create())
    }

    private fun clearDialogs(ids: List<Long>) {
        clearing = true
        refreshRows()
        val done = Runnable {
            clearing = false
            cancelSelection()
            if (!isFinished) {
                refreshStats()
                BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone))
                    .show()
            }
        }
        when (tab) {
            TAB_EDITED -> ArchiveCache.clearEditHistory(currentAccount, ids, done)
            TAB_DELETED -> ArchiveCache.clearCache(currentAccount, ids, deletedOnly = true, onDone = done)
            else -> ArchiveCache.clearCache(currentAccount, ids, deletedOnly = true) {
                ArchiveCache.clearEditHistory(currentAccount, ids, done)
            }
        }
    }

    private fun previewText(stat: InuDatabaseHelper.DialogCacheStat, edited: Boolean): CharSequence {
        val body = stat.preview
        if (body.isEmpty() || edited) return body
        val chat = if (stat.dialogId < 0) messagesController.getChat(-stat.dialogId) else null
        if (chat == null || ChatObject.isChannelAndNotMegaGroup(chat)) return body
        val fromId = stat.latestFromId
        val name = when {
            fromId == userConfig.clientUserId -> LocaleController.getString(R.string.FromYou)
            fromId > 0 -> messagesController.getUser(fromId)?.let { UserObject.getUserName(it) } ?: ""
            fromId < 0 -> messagesController.getChat(-fromId)?.title ?: ""
            else -> ""
        }
        return if (name.isEmpty()) body else "$name: $body"
    }

    private fun lastTimeLabel(context: Context, date: Long): CharSequence {
        if (date <= 0L) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = date * 1000L }
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a"
            return SimpleDateFormat(pattern, LocaleController.getInstance().currentLocale).format(Date(date * 1000L))
        }
        return LocaleController.stringForMessageListDate(date)
    }

    private inner class ArchiveAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {
        override fun getItemCount(): Int = if (loading || stats.isEmpty()) 0 else stats.size

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            RecyclerListView.Holder(ArchiveRow(context))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = stats[position]
            (holder.itemView as ArchiveRow).bind(item, selecting, item.dialogId in selected)
        }
    }

    private inner class ArchiveRow(context: Context) : LinearLayout(context) {
        private val avatar = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(28f)) }
        private val checkbox = CheckBox2(context, 21, null).apply {
            setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck)
            visibility = View.GONE
        }
        private val title = TextView(context).apply {
            setTextSize(16f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val time = TextView(context).apply {
            setTextSize(12f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            maxLines = 1
            gravity = Gravity.END
        }
        private val preview = TextView(context).apply {
            setTextSize(14f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val count = TextView(context).apply {
            setTextSize(12f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            gravity = Gravity.CENTER
            minWidth = AndroidUtilities.dp(24f)
            setPadding(AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(7f), 0)
            background =
                Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_windowBackgroundGray))
        }

        init {
            orientation = VERTICAL
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    AndroidUtilities.dp(16f),
                    AndroidUtilities.dp(8f),
                    AndroidUtilities.dp(16f),
                    AndroidUtilities.dp(8f)
                )
                background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            }
            val avatarFrame = FrameLayout(context)
            avatarFrame.addView(avatar, LayoutHelper.createFrame(56, 56f))
            avatarFrame.addView(checkbox, LayoutHelper.createFrame(26, 26, Gravity.CENTER))
            line.addView(avatarFrame, LayoutHelper.createLinear(56, 56))

            val content = LinearLayout(context).apply { orientation = VERTICAL }
            val heading = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            heading.addView(
                time,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 8, 0, 0, 0)
            )
            content.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            val details = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            details.addView(preview, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            details.addView(count, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 24, 0f, 8, 0, 0, 0))
            content.addView(
                details,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2, 0, 0, 0)
            )
            line.addView(content, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0))
            addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                View(context).apply { setBackgroundColor(Theme.getColor(Theme.key_divider)) },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0f, 84, 0, 0, 0)
            )
        }

        fun bind(item: Merged, selecting: Boolean, isSelected: Boolean) {
            val stat = item.stat(tab) ?: return
            val statEdited = stat === item.edited
            val peer: org.telegram.tgnet.TLObject? = when {
                stat.dialogId == 0L -> null
                stat.dialogId > 0 -> messagesController.getUser(stat.dialogId)
                else -> messagesController.getChat(-stat.dialogId)
            }
            val drawable = AvatarDrawable()
            when (peer) {
                is org.telegram.tgnet.TLRPC.User -> drawable.setInfo(peer)
                is org.telegram.tgnet.TLRPC.Chat -> drawable.setInfo(peer)
                else -> drawable.setInfo(stat.dialogId, dialogTitle(stat.dialogId), null)
            }
            avatar.setForUserOrChat(peer, drawable)
            title.text = dialogTitle(stat.dialogId)
            time.text = lastTimeLabel(context, stat.lastDate)
            preview.text = previewText(stat, statEdited)
            count.text = item.count(tab).toString()
            checkbox.visibility = if (selecting) View.VISIBLE else View.GONE
            checkbox.setChecked(isSelected, false)
            count.visibility = if (selecting) View.GONE else View.VISIBLE
        }
    }

    companion object {
        const val TAB_ALL = 0
        const val TAB_DELETED = 1
        const val TAB_EDITED = 2
        private const val MENU_SELECT_ALL = 1
        private const val MENU_CLEAR = 2
        private const val MENU_SEARCH = 3
        private const val MENU_EXPORT = 4
    }
}
