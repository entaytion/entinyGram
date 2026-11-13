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
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.archive.ArchiveCache
import desu.inugram.helpers.security.PresenceHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CacheChart
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class CacheManagementSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCacheManagement)

    private var stats: List<CacheStatsHelper.Stat> = emptyList()
    private var chart: CacheChart? = null
    private var loading = true

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val chartView = chart ?: object : CacheChart(
            context,
            KINDS.size,
            KINDS.map(::kindColorKey).toIntArray(),
            CacheChart.TYPE_CACHE,
            KINDS.map(::kindParticle).toIntArray(),
        ) {
            override fun onSectionClick(index: Int) {
                KINDS.getOrNull(index)?.let(::openCategory)
            }
        }.also { chart = it }
        bindChart()
        items.add(UItem.asCustom(chartView))
        for (kind in KINDS) {
            val stat = stats.firstOrNull { it.kind == kind }
            if (kind != Kind.DELETED && kind != Kind.EDITS && (stat == null || stat.count == 0 && stat.size == 0L)) continue
            items.add(UItem.asButton(ROW_IDS.getValue(kind), kindIcon(kind), kindTitle(kind), stat?.let { AndroidUtilities.formatFileSize(it.size) } ?: "…"))
        }
        items.add(UItem.asShadow(null))
        items.add(UItem.asButton(BUTTON_CLEAR_ALL, LocaleController.getString(R.string.InuCacheClearAll)).red()
            .setEnabled(stats.any { it.count > 0 || it.size > 0 }))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuCacheManagementInfo)))

        items.add(UItem.asButton(BUTTON_MESSAGES_TTL, R.drawable.inu_tabler_clock_hour_4, LocaleController.getString(R.string.InuCacheTtl)).also {
            it.subtext = ttlLabel(InuConfig.DELETED_MESSAGES_TTL.value)
        })
        items.add(UItem.asButton(BUTTON_LOGS_TTL, R.drawable.inu_tabler_clock_hour_4, LocaleController.getString(R.string.InuPresenceLogsTtl)).also {
            it.subtext = ttlLabel(InuConfig.PRESENCE_LOGS_TTL.value)
        })
        items.add(UItem.asShadow(null))
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

    private fun kindColorKey(kind: Kind): Int = when (kind) {
        Kind.DELETED -> Theme.key_statisticChartLine_red
        Kind.EDITS -> Theme.key_statisticChartLine_orange
        Kind.REACTIONS -> Theme.key_statisticChartLine_purple
        Kind.MEDIA -> Theme.key_statisticChartLine_lightblue
        Kind.PRESENCE -> Theme.key_statisticChartLine_blue
        Kind.RECENT -> Theme.key_statisticChartLine_cyan
        Kind.PILLS -> Theme.key_statisticChartLine_golden
        Kind.TEMP -> Theme.key_statisticChartLine_lightgreen
        Kind.LOGS -> Theme.key_statisticChartLine_indigo
    }

    private fun kindParticle(kind: Kind): Int = when (kind) {
        Kind.MEDIA -> R.raw.cache_photos
        Kind.PRESENCE, Kind.RECENT -> R.raw.cache_profile_photos
        Kind.REACTIONS -> R.raw.cache_stickers
        Kind.LOGS -> R.raw.cache_documents
        else -> R.raw.cache_other
    }

    private fun bindChart() {
        val view = chart ?: return
        if (loading && stats.isEmpty()) return
        val segments = KINDS.map { kind -> CacheChart.SegmentSize.of(stats.firstOrNull { it.kind == kind }?.size ?: 0L) }
        view.setSegments(segments.sumOf { it.size }, true, *segments.toTypedArray())
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        ROW_IDS.entries.firstOrNull { it.value == item.id }?.let {
            openCategory(it.key)
            return
        }
        when (item.id) {
            BUTTON_CLEAR_ALL -> confirmClearAll()
            BUTTON_MESSAGES_TTL -> showTtlDialog(R.string.InuCacheTtl, R.string.InuCacheTtlInfo, InuConfig.DELETED_MESSAGES_TTL.value) {
                InuConfig.DELETED_MESSAGES_TTL.value = it
                if (it != InuConfig.DeletedMessagesTtlItem.NEVER) ArchiveCache.pruneIfNeeded(UserConfig.selectedAccount)
            }
            BUTTON_LOGS_TTL -> showTtlDialog(R.string.InuPresenceLogsTtl, R.string.InuPresenceLogsTtlInfo, InuConfig.PRESENCE_LOGS_TTL.value) {
                InuConfig.PRESENCE_LOGS_TTL.value = it
                if (it != InuConfig.PresenceLogsTtlItem.NEVER) PresenceHelper.pruneIfNeeded(UserConfig.selectedAccount)
            }
        }
    }

    private class SheetRow(val title: String, val detail: String, val size: Long)

    // entiny: one multi-select bottom sheet for every cache clear flow; withAll adds a select-all row
    private fun showSelectSheet(title: String, rows: List<SheetRow>, withAll: Boolean, onOpen: ((Int) -> Unit)? = null, onConfirm: (List<Int>) -> Unit) {
        val context = context ?: return
        val selected = BooleanArray(rows.size) { true }
        var sheetRef: BottomSheet? = null
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
        if (onOpen != null) {
            list.addView(TextView(context).apply {
                text = LocaleController.getString(R.string.InuClearDeletedCacheHint)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), AndroidUtilities.dp(8f))
            })
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
                if (onOpen != null) {
                    setOnLongClickListener {
                        sheetRef?.dismiss()
                        onOpen(i)
                        true
                    }
                }
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
        sheetRef = sheet
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
        if (kind == Kind.DELETED || kind == Kind.EDITS) {
            presentFragment(ArchiveCacheActivity(if (kind == Kind.EDITS) ArchiveCacheActivity.TAB_EDITED else ArchiveCacheActivity.TAB_DELETED))
            return
        }
        val stat = stats.firstOrNull { it.kind == kind } ?: return
        if (stat.count == 0 && stat.size == 0L) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuClearDeletedCacheEmpty)).show()
            return
        }
        if (kind == Kind.MEDIA) {
            presentFragment(ArchiveMediaActivity())
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

    companion object {
        private val BUTTON_MESSAGES_TTL = InuUtils.generateId()
        private val BUTTON_LOGS_TTL = InuUtils.generateId()
        private val BUTTON_CLEAR_ALL = InuUtils.generateId()
        private val KINDS = Kind.entries
        private val ROW_IDS = KINDS.associateWith { InuUtils.generateId() }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "cache-management",
            titleRes = R.string.InuCacheManagement,
            iconRes = R.drawable.inu_tabler_trash_x,
            factory = ::CacheManagementSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("cache-ttl", R.string.InuCacheTtl, BUTTON_MESSAGES_TTL),
                SearchRegistry.Entry("presence-logs-ttl", R.string.InuPresenceLogsTtl, BUTTON_LOGS_TTL),
                SearchRegistry.Entry("clear-deleted-cache", R.string.InuClearDeletedCache, ROW_IDS.getValue(Kind.DELETED)),
            ),
        )
    }
}
