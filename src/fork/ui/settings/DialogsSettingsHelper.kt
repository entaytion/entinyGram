package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.DialogsFabHelper
import desu.inugram.helpers.feed.FeedHelper
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.menu.MenuOrderEntry
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.UItem

object DialogsSettingsHelper {
    private val KEY_FILTER_PREVIEW = InuUtils.generateId()
    private val KEY_TABS_STATE = InuUtils.generateId()

    class TabsState(var entries: MutableList<MenuOrderEntry<MainTabsMenuConfig.Item>>) {
        var preview: MainTabsPreviewCell? = null
    }

    private fun tabsState(page: SchemaPageActivity): TabsState =
        page.remember(KEY_TABS_STATE) { TabsState(InuConfig.BOTTOM_TABS_ORDER.value.toMutableList()) }

    fun searchTabEnabled(): Boolean =
        InuConfig.BOTTOM_TABS_ORDER.value.any { it.item == MainTabsMenuConfig.Item.SEARCH && it.enabled }

    fun filterPreview(page: SchemaPageActivity): FilterTabsPreviewCell? = page.peek(KEY_FILTER_PREVIEW)

    fun filterPreviewItem(page: SchemaPageActivity, id: Int): UItem {
        val existing = filterPreview(page)
        val cell = existing ?: page.remember(KEY_FILTER_PREVIEW) { FilterTabsPreviewCell(page.context) }
        if (existing != null) cell.refresh()
        return UItem.asCustom(id, cell)
    }

    fun mainTabsPreviewItem(page: SchemaPageActivity, id: Int): UItem {
        val state = tabsState(page)
        val preview = state.preview ?: MainTabsPreviewCell(
            page.context,
            onToggle = { item -> toggleMainTab(page, item) },
            onReorder = { newOrder -> reorderMainTabs(page, newOrder) },
        ).also { state.preview = it }
        refreshMainTabsPreview(page)
        return UItem.asCustom(id, preview)
    }

    fun refreshMainTabsPreview(page: SchemaPageActivity) {
        val state = tabsState(page)
        val cell = state.preview ?: return
        val shown = state.entries.filter { it.item != MainTabsMenuConfig.Item.FEED || FeedHelper.isEnabled() }
        cell.setState(
            shown.map { it.item },
            shown.filter { it.enabled || it.item == MainTabsMenuConfig.Item.CHATS }.map { it.item }.toSet(),
            InuConfig.BOTTOM_TABS_SEARCH_SEPARATE.value,
        )
    }

    private fun toggleMainTab(page: SchemaPageActivity, item: MainTabsMenuConfig.Item) {
        if (item == MainTabsMenuConfig.Item.CHATS) return
        val state = tabsState(page)
        val idx = state.entries.indexOfFirst { it.item == item }
        if (idx < 0) return
        state.entries[idx] = state.entries[idx].copy(enabled = !state.entries[idx].enabled)
        InuConfig.BOTTOM_TABS_ORDER.value = state.entries
        refreshMainTabsPreview(page)
        page.refresh()
        page.showRestart()
    }

    private fun reorderMainTabs(page: SchemaPageActivity, newOrder: List<MainTabsMenuConfig.Item>) {
        val state = tabsState(page)
        val byItem = state.entries.associateBy { it.item }
        val moved = newOrder.distinct().mapNotNull { byItem[it] }
        val movedItems = moved.mapTo(HashSet()) { it.item }
        // entiny: emit moved entries once at the first moved slot so filtered-out entries keep their position
        val result = ArrayList<MenuOrderEntry<MainTabsMenuConfig.Item>>(state.entries.size)
        var movedEmitted = false
        for (entry in state.entries) {
            if (entry.item in movedItems) {
                if (!movedEmitted) {
                    result.addAll(moved)
                    movedEmitted = true
                }
            } else {
                result.add(entry)
            }
        }
        if (!movedEmitted) result.addAll(moved)
        state.entries = result
        InuConfig.BOTTOM_TABS_ORDER.value = state.entries
        refreshMainTabsPreview(page)
        page.showRestart()
    }

    fun resetMainTabs(page: SchemaPageActivity) {
        val state = tabsState(page)
        InuConfig.BOTTOM_TABS_ORDER.resetToDefault()
        state.entries = InuConfig.BOTTOM_TABS_ORDER.default.toMutableList()
        refreshMainTabsPreview(page)
        page.refresh()
        page.showRestart()
    }

    fun resetUnreadCounters() {
        // entiny: resetAllUnreadCounters dispatches updateInterfaces so DialogsActivity and MainTabsActivity update
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(i).isClientActivated) continue
            val storage = MessagesStorage.getInstance(i)
            storage.storageQueue.postRunnable { storage.resetAllUnreadCounters(false) }
        }
    }

    fun foldersDisplayLabel(): String = LocaleController.getString(
        when (InuConfig.FOLDERS_DISPLAY_MODE.value) {
            InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS -> R.string.InuShowOnTabsMix
            InuConfig.FoldersDisplayModeItem.ICONS_ONLY -> R.string.InuShowOnTabsIcons
            else -> R.string.InuShowOnTabsTitles
        }
    )

    fun showFoldersDisplayMode(page: SchemaPageActivity, view: View) {
        RadioItemOptions.show(
            page, view,
            listOf(
                LocaleController.getString(R.string.InuShowOnTabsTitles),
                LocaleController.getString(R.string.InuShowOnTabsIcons),
                LocaleController.getString(R.string.InuShowOnTabsMix),
            ),
            when (InuConfig.FOLDERS_DISPLAY_MODE.value) {
                InuConfig.FoldersDisplayModeItem.ICONS_ONLY -> 1
                InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS -> 2
                else -> 0
            },
        ) { which ->
            InuConfig.FOLDERS_DISPLAY_MODE.value = when (which) {
                1 -> InuConfig.FoldersDisplayModeItem.ICONS_ONLY
                2 -> InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS
                else -> InuConfig.FoldersDisplayModeItem.TITLES
            }
            filterPreview(page)?.refresh()
            page.rebuildAll()
        }
    }

    fun titleTextLabel(value: Int): String = when (value) {
        InuConfig.DialogsTitleTextItem.USERNAME -> LocaleController.getString(R.string.Username)
        InuConfig.DialogsTitleTextItem.FIRST_NAME -> LocaleController.getString(R.string.FirstNameSmall)
        InuConfig.DialogsTitleTextItem.CHATS -> LocaleController.getString(R.string.FilterChats)
        InuConfig.DialogsTitleTextItem.FOLDER -> LocaleController.getString(R.string.InuTitleTextFolder)
        InuConfig.DialogsTitleTextItem.CUSTOM -> InuConfig.DIALOGS_TITLE_TEXT_CUSTOM_TEXT.value.ifBlank {
            LocaleController.getString(R.string.InuTitleTextCustom)
        }

        else -> LocaleController.getString(R.string.AppName)
    }

    fun showTitleText(page: SchemaPageActivity, view: View) {
        RadioItemOptions.show(
            page, view,
            listOf(
                LocaleController.getString(R.string.AppName),
                LocaleController.getString(R.string.Username),
                LocaleController.getString(R.string.FirstNameSmall),
                LocaleController.getString(R.string.FilterChats),
                LocaleController.getString(R.string.InuTitleTextFolder),
                LocaleController.getString(R.string.InuTitleTextCustom),
            ),
            InuConfig.DIALOGS_TITLE_TEXT.value - 1,
        ) { which ->
            if (which + 1 == InuConfig.DialogsTitleTextItem.CUSTOM) {
                showCustomTitleTextDialog(page)
            } else {
                InuConfig.DIALOGS_TITLE_TEXT.value = which + 1
                page.rebuildAll()
            }
        }
    }

    private fun showCustomTitleTextDialog(page: SchemaPageActivity) {
        val ctx = page.context ?: return
        val input = EditTextBoldCursor(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setCursorColor(Theme.getColor(Theme.key_dialogTextBlack))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
            hint = LocaleController.getString(R.string.AppName)
            setText(InuConfig.DIALOGS_TITLE_TEXT_CUSTOM_TEXT.value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            textSize = 16f
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        page.showDialog(
            AlertDialog.Builder(ctx, page.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuTitleTextCustom))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                    InuConfig.DIALOGS_TITLE_TEXT_CUSTOM_TEXT.value = input.text.toString().trim()
                    InuConfig.DIALOGS_TITLE_TEXT.value = InuConfig.DialogsTitleTextItem.CUSTOM
                    page.refresh()
                    page.rebuildAll()
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
        )
    }

    fun pullDownActionLabel(value: Int): String = when (value) {
        InuConfig.PullDownActionItem.DISABLED -> LocaleController.getString(R.string.InuPullDownActionDisabled)
        InuConfig.PullDownActionItem.OPEN_ARCHIVE -> LocaleController.getString(R.string.InuPullDownActionOpenArchive)
        InuConfig.PullDownActionItem.SAVED_MESSAGES -> LocaleController.getString(R.string.SavedMessages)
        InuConfig.PullDownActionItem.SEARCH -> LocaleController.getString(R.string.Search)
        else -> LocaleController.getString(R.string.InuPullDownActionRevealArchive)
    }

    fun showPullDownAction(page: SchemaPageActivity) {
        val context = page.context ?: return
        val values = intArrayOf(
            InuConfig.PullDownActionItem.DISABLED,
            InuConfig.PullDownActionItem.REVEAL_ARCHIVE,
            InuConfig.PullDownActionItem.OPEN_ARCHIVE,
            InuConfig.PullDownActionItem.SAVED_MESSAGES,
            InuConfig.PullDownActionItem.SEARCH,
        )
        val items = values.map { value ->
            if (value == InuConfig.PullDownActionItem.DISABLED) {
                RadioDialogBuilder.Item(pullDownActionLabel(value), LocaleController.getString(R.string.InuPullDownActionDisabledInfo))
            } else {
                RadioDialogBuilder.Item(pullDownActionLabel(value))
            }
        }
        page.showDialog(
            RadioDialogBuilder(context, page.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuPullDownAction))
                .setItems(items, values.indexOf(InuConfig.PULL_DOWN_ACTION.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.PULL_DOWN_ACTION.value == newValue) return@setItems
                    InuConfig.PULL_DOWN_ACTION.value = newValue
                    page.refresh()
                    page.rebuildAll()
                }.create()
        )
    }

    fun communityDisplayModeLabel(value: Int): String = when (value) {
        InuConfig.CommunityDisplayModeItem.LONG_TAP -> LocaleController.getString(R.string.InuCommunityDisplayModeLongTap)
        InuConfig.CommunityDisplayModeItem.INVISIBLE -> LocaleController.getString(R.string.InuCommunityDisplayModeInvisible)
        else -> LocaleController.getString(R.string.InuCommunityDisplayModeRegular)
    }

    fun showCommunityDisplayMode(page: SchemaPageActivity) {
        val context = page.context ?: return
        val values = intArrayOf(
            InuConfig.CommunityDisplayModeItem.REGULAR,
            InuConfig.CommunityDisplayModeItem.LONG_TAP,
            InuConfig.CommunityDisplayModeItem.INVISIBLE,
        )
        val items = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCommunityDisplayModeRegular)),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuCommunityDisplayModeLongTap),
                LocaleController.getString(R.string.InuCommunityDisplayModeLongTapInfo),
            ),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuCommunityDisplayModeInvisible),
                LocaleController.getString(R.string.InuCommunityDisplayModeInvisibleInfo),
            ),
        )
        page.showDialog(
            RadioDialogBuilder(context, page.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuCommunityDisplayMode))
                .setItems(items, values.indexOf(InuConfig.COMMUNITY_DISPLAY_MODE.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.COMMUNITY_DISPLAY_MODE.value == newValue) return@setItems
                    InuConfig.COMMUNITY_DISPLAY_MODE.value = newValue
                    page.refresh()
                    page.rebuildAll()
                }.create()
        )
    }

    private fun startTabItems(page: SchemaPageActivity) = tabsState(page).entries
        .filter { it.item != MainTabsMenuConfig.Item.SEARCH && (it.enabled || it.item == MainTabsMenuConfig.Item.CHATS) }
        .map { it.item }

    fun startTabLabel(page: SchemaPageActivity): String {
        val current = startTabItems(page).firstOrNull { it.index == InuConfig.START_TAB.value } ?: MainTabsMenuConfig.Item.CHATS
        return LocaleController.getString(current.labelRes)
    }

    fun showStartTab(page: SchemaPageActivity) {
        val context = page.context ?: return
        val items = startTabItems(page)
        val current = items.indexOfFirst { it.index == InuConfig.START_TAB.value }.coerceAtLeast(0)
        page.showDialog(
            RadioDialogBuilder(context, page.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuStartTab))
                .setItems(items.map { RadioDialogBuilder.Item(LocaleController.getString(it.labelRes)) }, current) { _, which ->
                    InuConfig.START_TAB.value = items[which].index
                    page.refresh()
                    page.showRestart()
                }.create()
        )
    }

    fun showFabAction(page: SchemaPageActivity, anchor: View, item: InuConfig.IntItem) {
        val options = DialogsFabHelper.Action.entries
        RadioItemOptions.show(
            page, anchor,
            options.map { it.label() },
            options.indexOfFirst { it.value == item.value }.coerceAtLeast(0),
        ) { which ->
            item.value = options[which].value
            page.rebuildAll()
        }
    }
}
