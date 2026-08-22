package desu.inugram.ui.settings

import android.view.View
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.SavedMessagesHelper
import desu.inugram.helpers.security.PresenceHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Overview of the fork's on-device caches (deleted/edited-message history, presence logs)
 * with one-tap clearing. Deliberately excludes data that lives in the same DB but isn't
 * cache — local pins, custom-folder overlays, ghost whitelist, presence watch list are user
 * configuration, not reclaimable junk, and stay managed from their own pages.
 */
class CacheManagementSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCacheManagement)

    private var messageCacheStat: InuDatabaseHelper.DialogCacheStat? = null
    private var presenceLogStat: InuDatabaseHelper.DialogCacheStat? = null
    private var summaryCell: CacheSummaryCell? = null
    private var loading = true

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (summaryCell == null) summaryCell = CacheSummaryCell(context) { confirmClearAll() }
        bindSummary()
        items.add(UItem.asCustom(summaryCell))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCacheCategories)))
        items.add(
            UItem.asButton(BUTTON_MESSAGE_CACHE, R.drawable.inu_tabler_trash_x, LocaleController.getString(R.string.InuCacheMessagesCategory)).also {
                it.subtext = categorySubtitle(messageCacheStat)
            }
        )
        items.add(
            UItem.asButton(BUTTON_PRESENCE_CACHE, R.drawable.inu_tabler_user_scan, LocaleController.getString(R.string.InuCachePresenceCategory)).also {
                it.subtext = categorySubtitle(presenceLogStat)
            }
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuCacheManagementInfo)))
    }

    private fun refreshStats() {
        loading = true
        val account = UserConfig.selectedAccount
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val messageStats = InuDatabaseHelper.getDeletedMessagesStats(db)
            val messageTotal = InuDatabaseHelper.DialogCacheStat(
                0L,
                messageStats.sumOf { it.count },
                messageStats.sumOf { it.estimatedSize },
            )
            val presenceTotal = InuDatabaseHelper.getPresenceLogsStats(db)
            AndroidUtilities.runOnUIThread {
                messageCacheStat = messageTotal
                presenceLogStat = presenceTotal
                loading = false
                listView?.adapter?.update(true)
            }
        }
    }

    private fun bindSummary() {
        val cell = summaryCell ?: return
        if (loading) {
            cell.bind("…", LocaleController.getString(R.string.InuCacheCalculating), clearEnabled = false)
            return
        }
        val totalSize = (messageCacheStat?.estimatedSize ?: 0L) + (presenceLogStat?.estimatedSize ?: 0L)
        val totalCount = (messageCacheStat?.count ?: 0) + (presenceLogStat?.count ?: 0)
        cell.bind(
            AndroidUtilities.formatFileSize(totalSize),
            LocaleController.formatString(R.string.InuCacheSummarySubtitle, totalCount),
            clearEnabled = totalSize > 0,
        )
    }

    private fun categorySubtitle(stat: InuDatabaseHelper.DialogCacheStat?): String {
        if (loading || stat == null) return LocaleController.getString(R.string.InuCacheCalculating)
        if (stat.count == 0) return LocaleController.getString(R.string.InuCacheEmpty)
        return LocaleController.formatString(
            R.string.InuCacheEntriesFormat,
            AndroidUtilities.formatFileSize(stat.estimatedSize),
            stat.count,
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_MESSAGE_CACHE -> presentFragment(AntiDeletionSettingsActivity())
            BUTTON_PRESENCE_CACHE -> presentFragment(PresenceWatchListSettingsActivity())
        }
    }

    /** Bottom sheet letting the user pick exactly which cache categories to clear. */
    private fun confirmClearAll() {
        val context = context ?: return
        val categories = buildList {
            messageCacheStat?.takeIf { it.count > 0 }?.let {
                add(Category(LocaleController.getString(R.string.InuCacheMessagesCategory), it.count, it.estimatedSize, isMessages = true))
            }
            presenceLogStat?.takeIf { it.count > 0 }?.let {
                add(Category(LocaleController.getString(R.string.InuCachePresenceCategory), it.count, it.estimatedSize, isMessages = false))
            }
        }
        if (categories.isEmpty()) return

        val selected = BooleanArray(categories.size) { true }

        val builder = org.telegram.ui.ActionBar.BottomSheet.Builder(context)
        builder.setTitle(LocaleController.getString(R.string.InuCacheClearAll))

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
        }
        val linearLayout = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }

        val buttonTextView = android.widget.TextView(context).apply {
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
            setGravity(android.view.Gravity.CENTER)
            setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_buttonText))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTypeface(AndroidUtilities.bold())
            background = org.telegram.ui.ActionBar.Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8f),
                org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButton),
                org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButtonPressed),
            )
        }

        fun selectedSize(): Long = categories.filterIndexed { i, _ -> selected[i] }.sumOf { it.size }

        fun updateButtonText() {
            buttonTextView.text = LocaleController.getString(R.string.Delete) + " (" + AndroidUtilities.formatFileSize(selectedSize()) + ")"
            val any = selected.any { it }
            buttonTextView.isEnabled = any
            buttonTextView.alpha = if (any) 1f else 0.5f
        }

        categories.forEachIndexed { i, cat ->
            val text = "${cat.title} (${AndroidUtilities.formatFileSize(cat.size)})"
            val value = LocaleController.formatString(R.string.InuCacheEntriesShort, cat.count)
            val cell = org.telegram.ui.Cells.CheckBoxCell(context, 1, resourceProvider).apply {
                setText(text, value, true, true)
                setChecked(selected[i], false)
                setTag(i)
                setOnClickListener {
                    val idx = tag as Int
                    selected[idx] = !selected[idx]
                    setChecked(selected[idx], true)
                    updateButtonText()
                }
            }
            linearLayout.addView(cell)
        }

        val scrollView = android.widget.ScrollView(context).apply { addView(linearLayout) }
        val scrollParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f,
        ).apply { bottomMargin = AndroidUtilities.dp(12f) }
        container.addView(scrollView, scrollParams)
        container.addView(
            buttonTextView,
            org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT),
        )
        updateButtonText()

        builder.setCustomView(container)
        val sheet = builder.create()
        buttonTextView.setOnClickListener {
            val clearMessages = categories.filterIndexed { i, c -> selected[i] && c.isMessages }.isNotEmpty()
            val clearPresence = categories.filterIndexed { i, c -> selected[i] && !c.isMessages }.isNotEmpty()
            if (clearMessages || clearPresence) {
                sheet.dismiss()
                clearSelected(clearMessages, clearPresence)
            }
        }
        showDialog(sheet)
    }

    private data class Category(val title: String, val count: Int, val size: Long, val isMessages: Boolean)

    private fun clearSelected(clearMessages: Boolean, clearPresence: Boolean) {
        val account = UserConfig.selectedAccount
        var pending = (if (clearMessages) 1 else 0) + (if (clearPresence) 1 else 0)
        fun onOneDone() {
            pending--
            if (pending <= 0) {
                refreshStats()
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone),
                ).show()
            }
        }
        if (clearMessages) SavedMessagesHelper.clearCache(account) { onOneDone() }
        if (clearPresence) PresenceHelper.clearLogs(account) { onOneDone() }
    }

    companion object {
        private val BUTTON_MESSAGE_CACHE = InuUtils.generateId()
        private val BUTTON_PRESENCE_CACHE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "cache-management",
            titleRes = R.string.InuCacheManagement,
            iconRes = R.drawable.inu_tabler_trash_x,
            factory = ::CacheManagementSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("cache-messages", R.string.InuCacheMessagesCategory, BUTTON_MESSAGE_CACHE),
                SearchRegistry.Entry("cache-presence", R.string.InuCachePresenceCategory, BUTTON_PRESENCE_CACHE),
            ),
        )
    }
}
