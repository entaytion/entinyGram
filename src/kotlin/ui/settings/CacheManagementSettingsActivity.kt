package desu.inugram.ui.settings

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.CacheStatsHelper
import desu.inugram.helpers.CacheStatsHelper.Kind
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.SavedMessagesHelper
import desu.inugram.helpers.security.PresenceHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class CacheManagementSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCacheManagement)

    private var stats: List<CacheStatsHelper.Stat> = emptyList()
    private var summaryCell: CacheSummaryCell? = null
    private var loading = true

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (summaryCell == null) summaryCell = CacheSummaryCell(context, { confirmClearAll() }, { openCategory(stats[it].kind) })
        bindSummary()
        items.add(UItem.asCustom(summaryCell))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuCacheManagementInfo)))

        items.add(UItem.asButton(BUTTON_MESSAGES_TTL, R.drawable.inu_tabler_clock_hour_4, LocaleController.getString(R.string.InuCacheTtl)).also {
            it.subtext = ttlLabel(InuConfig.DELETED_MESSAGES_TTL.value)
        })
        items.add(UItem.asButton(BUTTON_LOGS_TTL, R.drawable.inu_tabler_clock_hour_4, LocaleController.getString(R.string.InuPresenceLogsTtl)).also {
            it.subtext = ttlLabel(InuConfig.PRESENCE_LOGS_TTL.value)
        })
        items.add(UItem.asShadow(null))
        items.add(UItem.asButton(BUTTON_CLEAR_DELETED_CACHE, R.drawable.inu_tabler_trash_x, LocaleController.getString(R.string.InuClearDeletedCache)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuClearDeletedCacheAlert)))
    }

    private fun ttlLabel(days: Int): String = when (days) {
        InuConfig.PresenceLogsTtlItem.ONE_DAY -> LocaleController.getString(R.string.InuCacheTtlDay)
        InuConfig.PresenceLogsTtlItem.ONE_WEEK -> LocaleController.getString(R.string.InuCacheTtlWeek)
        InuConfig.PresenceLogsTtlItem.ONE_MONTH -> LocaleController.getString(R.string.InuCacheTtlMonth)
        else -> LocaleController.getString(R.string.InuCacheTtlNever)
    }

    private fun showTtlDialog(title: Int, info: Int, current: Int, onPick: (Int) -> Unit) {
        val context = context ?: return
        val values = intArrayOf(
            InuConfig.PresenceLogsTtlItem.NEVER,
            InuConfig.PresenceLogsTtlItem.ONE_DAY,
            InuConfig.PresenceLogsTtlItem.ONE_WEEK,
            InuConfig.PresenceLogsTtlItem.ONE_MONTH,
        )
        val radioItems = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlNever)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlDay)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlWeek)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlMonth)),
        )
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(title))
                .setSubtitle(LocaleController.getString(info))
                .setItems(radioItems, values.indexOf(current).coerceAtLeast(0)) { _, which ->
                    if (values[which] != current) {
                        onPick(values[which])
                        listView?.adapter?.update(true)
                    }
                }
                .create()
        )
    }

    private fun refreshStats() {
        loading = true
        CacheStatsHelper.load(UserConfig.selectedAccount) {
            stats = it
            loading = false
            listView?.adapter?.update(true)
        }
    }

    private fun kindTitle(kind: Kind): String = LocaleController.getString(
        when (kind) {
            Kind.DELETED -> R.string.InuCacheDeletedCategory
            Kind.EDITS -> R.string.InuCacheEditsCategory
            Kind.REACTIONS -> R.string.InuCacheReactionsCategory
            Kind.MEDIA -> R.string.InuCacheMediaCategory
            Kind.PRESENCE -> R.string.InuCachePresenceCategory
            Kind.RECENT -> R.string.InuCacheRecentCategory
            Kind.PILLS -> R.string.InuCachePillsCategory
            Kind.TEMP -> R.string.InuCacheTempCategory
            Kind.LOGS -> R.string.InuCacheLogsCategory
        }
    )

    private fun kindIcon(kind: Kind): Int = when (kind) {
        Kind.DELETED -> R.drawable.msg_delete
        Kind.EDITS -> R.drawable.msg_edit
        Kind.REACTIONS -> R.drawable.msg_reactions
        Kind.MEDIA -> R.drawable.msg_photos
        Kind.PRESENCE -> R.drawable.msg_recent
        Kind.RECENT -> R.drawable.msg_clear_recent
        Kind.PILLS -> R.drawable.msg2_trending
        Kind.TEMP -> R.drawable.msg_shareout
        Kind.LOGS -> R.drawable.msg_report
    }

    private fun kindColor(kind: Kind): Int = when (kind) {
        Kind.DELETED -> 0xFFE5534B.toInt()
        Kind.EDITS -> 0xFFF5A623.toInt()
        Kind.REACTIONS -> 0xFFEB5FA0.toInt()
        Kind.MEDIA -> 0xFF9B6BDF.toInt()
        Kind.PRESENCE -> 0xFF3390EC.toInt()
        Kind.RECENT -> 0xFF26B6C7.toInt()
        Kind.PILLS -> 0xFFB5A23A.toInt()
        Kind.TEMP -> 0xFF8E8E93.toInt()
        Kind.LOGS -> 0xFF6C7A89.toInt()
    }

    private fun bindSummary() {
        val cell = summaryCell ?: return
        if (loading && stats.isEmpty()) {
            cell.bind("…", LocaleController.getString(R.string.InuCacheCalculating), emptyList(), clearEnabled = false)
            return
        }
        val rows = stats.map {
            CacheSummaryCell.Row(
                kindColor(it.kind), kindIcon(it.kind), kindTitle(it.kind),
                LocaleController.formatString(R.string.InuCacheEntriesShort, it.count), it.size,
            )
        }
        val totalSize = stats.sumOf { it.size }
        cell.bind(
            AndroidUtilities.formatFileSize(totalSize),
            LocaleController.formatString(R.string.InuCacheSummarySubtitle, stats.sumOf { it.count }),
            rows,
            clearEnabled = totalSize > 0,
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_MESSAGES_TTL -> showTtlDialog(R.string.InuCacheTtl, R.string.InuCacheTtlInfo, InuConfig.DELETED_MESSAGES_TTL.value) {
                InuConfig.DELETED_MESSAGES_TTL.value = it
                if (it != InuConfig.DeletedMessagesTtlItem.NEVER) SavedMessagesHelper.pruneIfNeeded(UserConfig.selectedAccount)
            }
            BUTTON_LOGS_TTL -> showTtlDialog(R.string.InuPresenceLogsTtl, R.string.InuPresenceLogsTtlInfo, InuConfig.PRESENCE_LOGS_TTL.value) {
                InuConfig.PRESENCE_LOGS_TTL.value = it
                if (it != InuConfig.PresenceLogsTtlItem.NEVER) PresenceHelper.pruneIfNeeded(UserConfig.selectedAccount)
            }
            BUTTON_CLEAR_DELETED_CACHE -> showClearDeletedCacheDialog()
        }
    }

    private class SheetRow(val title: String, val detail: String, val size: Long)

    // entiny: one multi-select bottom sheet for every cache clear flow; withAll adds a select-all row
    private fun showSelectSheet(title: String, rows: List<SheetRow>, withAll: Boolean, onConfirm: (List<Int>) -> Unit) {
        val context = context ?: return
        val selected = BooleanArray(rows.size) { true }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val button = TextView(context).apply {
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.bold()
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(12f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed),
            )
        }
        var allCell: CheckBoxCell? = null
        fun refresh() {
            val size = rows.filterIndexed { i, _ -> selected[i] }.sumOf { it.size }
            button.text = LocaleController.getString(R.string.Delete) + " (" + AndroidUtilities.formatFileSize(size) + ")"
            val any = selected.any { it }
            button.isEnabled = any
            button.alpha = if (any) 1f else 0.5f
            val all = selected.all { it }
            allCell?.setText(LocaleController.getString(if (all) R.string.DeselectAll else R.string.SelectAll), "", true, true)
            allCell?.setChecked(all, false)
        }
        if (withAll) {
            allCell = CheckBoxCell(context, 1, resourceProvider).apply {
                setOnClickListener {
                    val target = !selected.all { it }
                    selected.fill(target)
                    for (c in 0 until list.childCount) {
                        val cell = list.getChildAt(c) as? CheckBoxCell ?: continue
                        (cell.tag as? Int)?.let { cell.setChecked(selected[it], false) }
                    }
                    refresh()
                }
            }
            list.addView(allCell)
        }
        rows.forEachIndexed { i, row ->
            list.addView(CheckBoxCell(context, 1, resourceProvider).apply {
                setText("${row.title} (${AndroidUtilities.formatFileSize(row.size)})", row.detail, true, true)
                setChecked(true, false)
                tag = i
                setOnClickListener {
                    selected[i] = !selected[i]
                    setChecked(selected[i], true)
                    refresh()
                }
            })
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
            addView(
                ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = AndroidUtilities.dp(12f) },
            )
            addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        refresh()
        val sheet = BottomSheet.Builder(context).setTitle(title).setCustomView(container).create()
        button.setOnClickListener {
            val picked = rows.indices.filter { selected[it] }
            if (picked.isNotEmpty()) {
                sheet.dismiss()
                onConfirm(picked)
            }
        }
        showDialog(sheet)
    }

    private fun confirmClearAll() {
        val present = stats.filter { it.count > 0 || it.size > 0 }
        if (present.isEmpty()) return
        val rows = present.map { SheetRow(kindTitle(it.kind), LocaleController.formatString(R.string.InuCacheEntriesShort, it.count), it.size) }
        showSelectSheet(LocaleController.getString(R.string.InuCacheClearAll), rows, withAll = true) { picked ->
            clearKinds(picked.map { present[it].kind })
        }
    }

    private fun openCategory(kind: Kind) {
        val stat = stats.firstOrNull { it.kind == kind } ?: return
        if (stat.count == 0 && stat.size == 0L) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuClearDeletedCacheEmpty)).show()
            return
        }
        if (kind == Kind.DELETED) {
            showClearDeletedCacheDialog()
            return
        }
        val row = SheetRow(kindTitle(kind), LocaleController.formatString(R.string.InuCacheEntriesShort, stat.count), stat.size)
        showSelectSheet(kindTitle(kind), listOf(row), withAll = false) { clearKinds(listOf(kind)) }
    }

    private fun clearKinds(kinds: List<Kind>) {
        val account = UserConfig.selectedAccount
        var pending = kinds.size
        for (kind in kinds) {
            CacheStatsHelper.clear(account, kind) {
                if (--pending <= 0) {
                    refreshStats()
                    BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone),
                    ).show()
                }
            }
        }
    }

    private fun showClearDeletedCacheDialog() {
        val account = UserConfig.selectedAccount
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val dialogStats = InuDatabaseHelper.getDeletedMessagesStats(db)
            AndroidUtilities.runOnUIThread {
                if (dialogStats.isEmpty()) {
                    BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuClearDeletedCacheEmpty))
                        .show()
                    return@runOnUIThread
                }
                val controller = MessagesController.getInstance(account)
                val rows = dialogStats.map { stat ->
                    val name = if (stat.dialogId == 0L) {
                        LocaleController.getString(R.string.SavedMessages)
                    } else {
                        controller.getUser(stat.dialogId)?.let { UserObject.getUserName(it) }
                            ?: controller.getChat(-stat.dialogId)?.title
                            ?: "ID ${stat.dialogId}"
                    }
                    SheetRow(name, LocaleController.formatPluralString("messages", stat.count), stat.estimatedSize)
                }
                showSelectSheet(LocaleController.getString(R.string.InuClearDeletedCache), rows, withAll = true) { picked ->
                    val ids = picked.map { dialogStats[it].dialogId }
                    SavedMessagesHelper.clearCache(account, if (ids.size == dialogStats.size) null else ids) {
                        refreshStats()
                        BulletinFactory.of(this)
                            .createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuClearDeletedCacheDone))
                            .show()
                    }
                }
            }
        }
    }

    companion object {
        private val BUTTON_MESSAGES_TTL = InuUtils.generateId()
        private val BUTTON_LOGS_TTL = InuUtils.generateId()
        private val BUTTON_CLEAR_DELETED_CACHE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "cache-management",
            titleRes = R.string.InuCacheManagement,
            iconRes = R.drawable.inu_tabler_trash_x,
            factory = ::CacheManagementSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("cache-ttl", R.string.InuCacheTtl, BUTTON_MESSAGES_TTL),
                SearchRegistry.Entry("presence-logs-ttl", R.string.InuPresenceLogsTtl, BUTTON_LOGS_TTL),
                SearchRegistry.Entry("clear-deleted-cache", R.string.InuClearDeletedCache, BUTTON_CLEAR_DELETED_CACHE),
            ),
        )
    }
}
