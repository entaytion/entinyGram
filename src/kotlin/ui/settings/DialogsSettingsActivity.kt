package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.dialogs.DialogsFabHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.menu.MenuOrderEntry
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DialogsSettingsActivity : SettingsPageActivity() {

    private var filterTabsPreview: FilterTabsPreviewCell? = null
    private var mainTabsPreview: MainTabsPreviewCell? = null
    private var mainTabsEntries: MutableList<MenuOrderEntry<MainTabsMenuConfig.Item>> = InuConfig.BOTTOM_TABS_ORDER.value.toMutableList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMainPage)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // folders section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuFolders)))
        if (filterTabsPreview == null) {
            filterTabsPreview = FilterTabsPreviewCell(this.context)
        } else {
            filterTabsPreview?.refresh()
        }
        items.add(UItem.asCustom(filterTabsPreview))

        items.add(
            UItem.asButton(
                BUTTON_FOLDERS_DISPLAY_MODE,
                LocaleController.getString(R.string.InuShowOnTabs),
                when (InuConfig.FOLDERS_DISPLAY_MODE.value) {
                    InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS -> LocaleController.getString(R.string.InuShowOnTabsMix)
                    InuConfig.FoldersDisplayModeItem.ICONS_ONLY -> LocaleController.getString(R.string.InuShowOnTabsIcons)
                    else -> LocaleController.getString(R.string.InuShowOnTabsTitles)
                }
            )
        )

        items.add(
            UItem.asCheck(
                TOGGLE_TAB_INDICATOR_STROKE,
                LocaleController.getString(R.string.InuTabIndicatorStroke),
            ).setChecked(InuConfig.TAB_INDICATOR_STROKE.value)
        )

        items.add(
            UItem.asButton(
                BUTTON_FOLDERS_UNREAD_COUNTER_MODE,
                LocaleController.getString(R.string.InuFoldersUnreadCounter),
                when (InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value) {
                    InuConfig.FoldersUnreadCounterModeItem.HIDE -> LocaleController.getString(R.string.InuFoldersUnreadCounterHide)
                    InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED -> LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMuted)
                    InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS -> LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMutedNonDms)
                    else -> LocaleController.getString(R.string.InuFoldersUnreadCounterRegular)
                }
            )
        )

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_ALL_CHATS_TAB,
                R.string.InuHideAllChatsTab,
                R.string.InuHideAllChatsTabInfo,
                InuConfig.HIDE_ALL_CHATS_TAB.value
            )
        )

        items.add(
            UItem.asCheck(
                TOGGLE_FOLDERS_AT_BOTTOM,
                LocaleController.getString(R.string.InuFoldersAtBottom),
            ).setChecked(InuConfig.FOLDERS_AT_BOTTOM.value)
        )

        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_ARCHIVE,
                LocaleController.getString(R.string.InuHideArchive),
            ).setChecked(InuConfig.HIDE_ARCHIVE_FROM_CHAT_LIST.value)
        )

        items.add(UItem.asShadow(null))
        // end folders section

        // chat list section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatList)))
        items.add(
            UItem.asButton(
                BUTTON_TITLE_TEXT,
                LocaleController.getString(R.string.InuTitleText),
                titleTextLabel(InuConfig.DIALOGS_TITLE_TEXT.value),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_OLD_MENTION_INDICATOR,
                LocaleController.getString(R.string.InuOldMentionIndicator),
            ).setChecked(InuConfig.OLD_MENTION_INDICATOR.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_PULL_DOWN_ACTION,
                LocaleController.getString(R.string.InuPullDownAction),
                pullDownActionLabel(InuConfig.PULL_DOWN_ACTION.value),
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE,
                LocaleController.getString(R.string.InuDisableSwipeToUnarchive),
            ).setChecked(InuConfig.DISABLE_SWIPE_TO_UNARCHIVE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC,
                LocaleController.getString(R.string.InuDisableSwipeToHideGeneralTopic),
            ).setChecked(InuConfig.DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC.value)
        )
        items.add(
            UItem.asCheck(TOGGLE_BOT_WEBVIEW_BUTTON, LocaleController.getString(R.string.InuHideBotWebView))
                .setChecked(InuConfig.HIDE_BOT_WEBVIEW_DIALOGS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_INTERACTIVE_CHAT_PREVIEW,
                R.string.InuDisableChatPreviewExpand,
                R.string.InuDisableChatPreviewExpandInfo,
                InuConfig.INTERACTIVE_CHAT_PREVIEW.value
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_COMMUNITY_DISPLAY_MODE,
                R.drawable.inu_tabler_users_group,
                LocaleController.getString(R.string.InuCommunityDisplayMode),
                communityDisplayModeLabel(InuConfig.COMMUNITY_DISPLAY_MODE.value),
            )
        )
        items.add(UItem.asShadow(null))
        // end chat list section

        // bottom tabs section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuBottomTabs)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_BOTTOM_TABS_HIDE,
                R.string.InuBottomTabsHide,
                R.string.InuBottomTabsHideInfo,
                InuConfig.BOTTOM_TABS_HIDE.value
            )
        )
        if (!InuConfig.BOTTOM_TABS_HIDE.value) {
            items.add(
                UItem.asCheck(
                    TOGGLE_HIDE_CONTACTS_TAB,
                    LocaleController.getString(R.string.InuHideContactsTab),
                ).setChecked(InuConfig.BOTTOM_TABS_HIDE_CONTACTS.value)
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_COMPACT_MODE,
                    R.string.InuCompactMode,
                    R.string.InuCompactModeInfo,
                    InuConfig.BOTTOM_TABS_COMPACT_MODE.value
                )
            )
            if (!InuConfig.BOTTOM_TABS_COMPACT_MODE.value) {
                items.add(
                    UItem.asCheck(
                        TOGGLE_SHOW_TAB_TITLES,
                        LocaleController.getString(R.string.InuShowTabTitles),
                    ).setChecked(InuConfig.BOTTOM_TABS_SHOW_TITLES.value)
                )
            }
            val preview = mainTabsPreview ?: MainTabsPreviewCell(
                context,
                onToggle = { item -> toggleMainTab(item) },
                onReorder = { newOrder -> reorderMainTabs(newOrder) },
            ).also { mainTabsPreview = it }
            refreshMainTabsPreview(preview)
            items.add(UItem.asCustom(preview))
            items.add(
                UItem.asButton(
                    BUTTON_RESET_BOTTOM_TABS,
                    R.drawable.msg_reset,
                    LocaleController.getString(R.string.InuMainTabsCustomizeReset),
                )
            )
        }
        items.add(UItem.asShadow(null))
        // end bottom tabs section

        // fab section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDialogsFab)))
        val mainAction = DialogsFabHelper.Action.fromValue(InuConfig.DIALOGS_FAB_MAIN_ACTION.value)
        items.add(
            UItem.asButton(
                BUTTON_FAB_MAIN_ACTION,
                R.drawable.inu_tabler_circle_plus,
                LocaleController.getString(R.string.InuDialogsFabMainAction),
                mainAction.label(),
            )
        )
        if (mainAction != DialogsFabHelper.Action.NONE) {
            items.add(
                UItem.asButton(
                    BUTTON_FAB_SECONDARY_ACTION,
                    LocaleController.getString(R.string.InuDialogsFabSecondaryAction),
                    DialogsFabHelper.Action.fromValue(InuConfig.DIALOGS_FAB_SECONDARY_ACTION.value).label(),
                )
            )
        }
        items.add(
            UItem.asCheck(
                TOGGLE_FAB_HIDE_ON_SCROLL,
                LocaleController.getString(R.string.InuDialogsFabHideOnScroll),
            ).setChecked(InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR,
                R.string.InuDialogsFabOffsetForBottomBar,
                R.string.InuDialogsFabOffsetForBottomBarInfo,
                InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FAB_LEFT_SIDE,
                LocaleController.getString(R.string.InuDialogsFabLeftSide),
            ).setChecked(InuConfig.DIALOGS_FAB_LEFT_SIDE.value)
        )
        items.add(UItem.asShadow(null))
        // end fab section
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_FOLDERS_DISPLAY_MODE -> RadioItemOptions.show(
                this, view,
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
                filterTabsPreview?.refresh()
                softRebuild()
            }

            TOGGLE_TAB_INDICATOR_STROKE -> {
                val new = InuConfig.TAB_INDICATOR_STROKE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                (view as? TextCheckCell)?.isChecked = new
                filterTabsPreview?.refreshVisuals()
                postNotificationForAllAccounts(NotificationCenter.dialogFiltersUpdated)
            }

            BUTTON_FOLDERS_UNREAD_COUNTER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuFoldersUnreadCounterHide),
                    LocaleController.getString(R.string.InuFoldersUnreadCounterRegular),
                    LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMuted),
                    LocaleController.getString(R.string.InuFoldersUnreadCounterExcludeMutedNonDms),
                ),
                InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value,
            ) { which ->
                InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value = which
                filterTabsPreview?.refreshCounters()
                filterTabsPreview?.refresh()
                // resetAllUnreadCounters dispatches updateInterfaces; DialogsActivity/MainTabsActivity listen.
                for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                    if (!UserConfig.getInstance(i).isClientActivated) continue
                    val storage = MessagesStorage.getInstance(i)
                    storage.storageQueue.postRunnable { storage.resetAllUnreadCounters(false) }
                }
                softRebuild()
            }

            TOGGLE_HIDE_ALL_CHATS_TAB -> {
                val new = InuConfig.HIDE_ALL_CHATS_TAB.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                filterTabsPreview?.refresh()
                postNotificationForAllAccounts(NotificationCenter.dialogFiltersUpdated)
            }

            TOGGLE_FOLDERS_AT_BOTTOM -> {
                val new = InuConfig.FOLDERS_AT_BOTTOM.toggle()
                (view as? TextCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_HIDE_ARCHIVE -> {
                val new = InuConfig.HIDE_ARCHIVE_FROM_CHAT_LIST.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_BOT_WEBVIEW_BUTTON -> {
                val new = InuConfig.HIDE_BOT_WEBVIEW_DIALOGS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_OLD_MENTION_INDICATOR -> {
                val new = InuConfig.OLD_MENTION_INDICATOR.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }


            BUTTON_PULL_DOWN_ACTION -> showPullDownActionSelector()

            TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE -> {
                val new = InuConfig.DISABLE_SWIPE_TO_UNARCHIVE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC -> {
                val new = InuConfig.DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_INTERACTIVE_CHAT_PREVIEW -> {
                val new = InuConfig.INTERACTIVE_CHAT_PREVIEW.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BOTTOM_TABS_HIDE -> {
                val new = InuConfig.BOTTOM_TABS_HIDE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                softRebuild()
            }

            TOGGLE_COMPACT_MODE -> {
                val new = InuConfig.BOTTOM_TABS_COMPACT_MODE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                softRebuild()
                mainTabsPreview?.let { refreshMainTabsPreview(it) }
                showRestartBulletin()
            }

            TOGGLE_SHOW_TAB_TITLES -> {
                val new = InuConfig.BOTTOM_TABS_SHOW_TITLES.toggle()
                (view as? TextCheckCell)?.isChecked = new
                mainTabsPreview?.let { refreshMainTabsPreview(it) }
                showRestartBulletin()
            }

            BUTTON_RESET_BOTTOM_TABS -> {
                InuConfig.BOTTOM_TABS_ORDER.resetToDefault()
                mainTabsEntries = InuConfig.BOTTOM_TABS_ORDER.default.toMutableList()
                mainTabsPreview?.let { refreshMainTabsPreview(it) }
                showRestartBulletin()
            }

            BUTTON_TITLE_TEXT -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.AppName),
                    LocaleController.getString(R.string.Username),
                    LocaleController.getString(R.string.FirstNameSmall),
                    LocaleController.getString(R.string.Chats),
                ),
                InuConfig.DIALOGS_TITLE_TEXT.value - 1,
            ) { which ->
                InuConfig.DIALOGS_TITLE_TEXT.value = which + 1
                softRebuild()
            }

            BUTTON_COMMUNITY_DISPLAY_MODE -> showCommunityDisplayModeSelector()

            BUTTON_FAB_MAIN_ACTION -> showFabActionDialog(view, InuConfig.DIALOGS_FAB_MAIN_ACTION)
            BUTTON_FAB_SECONDARY_ACTION -> showFabActionDialog(view, InuConfig.DIALOGS_FAB_SECONDARY_ACTION)

            TOGGLE_FAB_HIDE_ON_SCROLL -> {
                val new = InuConfig.DIALOGS_FAB_HIDE_ON_SCROLL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR -> {
                val new = InuConfig.DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_FAB_LEFT_SIDE -> {
                val new = InuConfig.DIALOGS_FAB_LEFT_SIDE.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }
        }
    }

    private fun toggleMainTab(item: MainTabsMenuConfig.Item) {
        val idx = mainTabsEntries.indexOfFirst { it.item == item }
        if (idx < 0) return
        mainTabsEntries[idx] = mainTabsEntries[idx].copy(enabled = !mainTabsEntries[idx].enabled)
        InuConfig.BOTTOM_TABS_ORDER.value = mainTabsEntries
        mainTabsPreview?.let { refreshMainTabsPreview(it) }
        showRestartBulletin()
    }

    private fun reorderMainTabs(newOrder: List<MainTabsMenuConfig.Item>) {
        val byItem = mainTabsEntries.associateBy { it.item }
        mainTabsEntries = newOrder.mapNotNull { byItem[it] }.toMutableList()
        InuConfig.BOTTOM_TABS_ORDER.value = mainTabsEntries
        showRestartBulletin()
    }

    private fun refreshMainTabsPreview(cell: MainTabsPreviewCell) {
        cell.setState(
            mainTabsEntries.map { it.item },
            mainTabsEntries.filter { it.enabled }.map { it.item }.toSet(),
        )
    }

    private fun showCommunityDisplayModeSelector() {
        val context = context ?: return
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
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuCommunityDisplayMode))
                .setItems(items, values.indexOf(InuConfig.COMMUNITY_DISPLAY_MODE.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.COMMUNITY_DISPLAY_MODE.value == newValue) return@setItems
                    InuConfig.COMMUNITY_DISPLAY_MODE.value = newValue
                    listView.adapter.update(true)
                    softRebuild()
                }.create()
        )
    }

    private fun showPullDownActionSelector() {
        val context = context ?: return
        val values = intArrayOf(
            InuConfig.PullDownActionItem.DISABLED,
            InuConfig.PullDownActionItem.REVEAL_ARCHIVE,
            InuConfig.PullDownActionItem.OPEN_ARCHIVE,
            InuConfig.PullDownActionItem.SAVED_MESSAGES,
            InuConfig.PullDownActionItem.SEARCH,
        )
        val items = values.map { value ->
            if (value == InuConfig.PullDownActionItem.DISABLED) {
                RadioDialogBuilder.Item(
                    pullDownActionLabel(value),
                    LocaleController.getString(R.string.InuPullDownActionDisabledInfo),
                )
            } else {
                RadioDialogBuilder.Item(pullDownActionLabel(value))
            }
        }
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuPullDownAction))
                .setItems(items, values.indexOf(InuConfig.PULL_DOWN_ACTION.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.PULL_DOWN_ACTION.value == newValue) return@setItems
                    InuConfig.PULL_DOWN_ACTION.value = newValue
                    listView.adapter.update(true)
                    softRebuild()
                }.create()
        )
    }

    private fun showFabActionDialog(anchor: View, item: InuConfig.IntItem) {
        val options = DialogsFabHelper.Action.entries
        val current = options.indexOfFirst { it.value == item.value }.coerceAtLeast(0)
        RadioItemOptions.show(
            this, anchor,
            options.map { it.label() },
            current,
        ) { which ->
            item.value = options[which].value
            softRebuild()
        }
    }

    companion object {
        private val BUTTON_FOLDERS_DISPLAY_MODE = InuUtils.generateId()
        private val TOGGLE_TAB_INDICATOR_STROKE = InuUtils.generateId()
        private val BUTTON_FOLDERS_UNREAD_COUNTER_MODE = InuUtils.generateId()
        private val TOGGLE_FOLDERS_AT_BOTTOM = InuUtils.generateId()
        private val TOGGLE_HIDE_ARCHIVE = InuUtils.generateId()
        private val TOGGLE_BOT_WEBVIEW_BUTTON = InuUtils.generateId()
        private val TOGGLE_OLD_MENTION_INDICATOR = InuUtils.generateId()
        private val BUTTON_PULL_DOWN_ACTION = InuUtils.generateId()
        private val TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE = InuUtils.generateId()
        private val TOGGLE_DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC = InuUtils.generateId()
        private val TOGGLE_BOTTOM_TABS_HIDE = InuUtils.generateId()
        private val TOGGLE_COMPACT_MODE = InuUtils.generateId()
        private val TOGGLE_SHOW_TAB_TITLES = InuUtils.generateId()
        private val BUTTON_RESET_BOTTOM_TABS = InuUtils.generateId()
        private val BUTTON_FAB_MAIN_ACTION = InuUtils.generateId()
        private val BUTTON_FAB_SECONDARY_ACTION = InuUtils.generateId()
        private val TOGGLE_FAB_HIDE_ON_SCROLL = InuUtils.generateId()
        private val TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR = InuUtils.generateId()
        private val TOGGLE_FAB_LEFT_SIDE = InuUtils.generateId()
        private val TOGGLE_INTERACTIVE_CHAT_PREVIEW = InuUtils.generateId()
        private val TOGGLE_HIDE_ALL_CHATS_TAB = InuUtils.generateId()
        private val BUTTON_COMMUNITY_DISPLAY_MODE = InuUtils.generateId()
        private val BUTTON_TITLE_TEXT = InuUtils.generateId()

        private fun titleTextLabel(value: Int): String = when (value) {
            InuConfig.DialogsTitleTextItem.USERNAME -> LocaleController.getString(R.string.Username)
            InuConfig.DialogsTitleTextItem.FIRST_NAME -> LocaleController.getString(R.string.FirstNameSmall)
            InuConfig.DialogsTitleTextItem.CHATS -> LocaleController.getString(R.string.Chats)
            else -> LocaleController.getString(R.string.AppName)
        }

        private fun pullDownActionLabel(value: Int): String = when (value) {
            InuConfig.PullDownActionItem.DISABLED -> LocaleController.getString(R.string.InuPullDownActionDisabled)
            InuConfig.PullDownActionItem.OPEN_ARCHIVE -> LocaleController.getString(R.string.InuPullDownActionOpenArchive)
            InuConfig.PullDownActionItem.SAVED_MESSAGES -> LocaleController.getString(R.string.SavedMessages)
            InuConfig.PullDownActionItem.SEARCH -> LocaleController.getString(R.string.Search)
            else -> LocaleController.getString(R.string.InuPullDownActionRevealArchive)
        }

        private fun communityDisplayModeLabel(value: Int): String = when (value) {
            InuConfig.CommunityDisplayModeItem.LONG_TAP -> LocaleController.getString(R.string.InuCommunityDisplayModeLongTap)
            InuConfig.CommunityDisplayModeItem.INVISIBLE -> LocaleController.getString(R.string.InuCommunityDisplayModeInvisible)
            else -> LocaleController.getString(R.string.InuCommunityDisplayModeRegular)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "dialogs",
            titleRes = R.string.InuMainPage,
            iconRes = R.drawable.msg_viewchats,
            factory = ::DialogsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("folders-display-mode", R.string.InuShowOnTabs, BUTTON_FOLDERS_DISPLAY_MODE),
                SearchRegistry.Entry("tab-indicator-stroke", R.string.InuTabIndicatorStroke, TOGGLE_TAB_INDICATOR_STROKE),
                SearchRegistry.Entry("folders-unread-counter", R.string.InuFoldersUnreadCounter, BUTTON_FOLDERS_UNREAD_COUNTER_MODE),
                SearchRegistry.Entry("hide-all-chats-tab", R.string.InuHideAllChatsTab, TOGGLE_HIDE_ALL_CHATS_TAB),
                SearchRegistry.Entry("folders-at-bottom", R.string.InuFoldersAtBottom, TOGGLE_FOLDERS_AT_BOTTOM),
                SearchRegistry.Entry("hide-archive", R.string.InuHideArchive, TOGGLE_HIDE_ARCHIVE),
                SearchRegistry.Entry("title-text", R.string.InuTitleText, BUTTON_TITLE_TEXT),
                SearchRegistry.Entry("old-mention-indicator", R.string.InuOldMentionIndicator, TOGGLE_OLD_MENTION_INDICATOR),
                SearchRegistry.Entry("pull-down-action", R.string.InuPullDownAction, BUTTON_PULL_DOWN_ACTION),
                SearchRegistry.Entry("disable-swipe-to-unarchive", R.string.InuDisableSwipeToUnarchive, TOGGLE_DISABLE_SWIPE_TO_UNARCHIVE),
                SearchRegistry.Entry("disable-swipe-to-hide-general-topic", R.string.InuDisableSwipeToHideGeneralTopic, TOGGLE_DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC),
                SearchRegistry.Entry("hide-bot-webview-dialogs", R.string.InuHideBotWebView, TOGGLE_BOT_WEBVIEW_BUTTON),
                SearchRegistry.Entry("disable-chat-preview-expand", R.string.InuDisableChatPreviewExpand, TOGGLE_INTERACTIVE_CHAT_PREVIEW),
                SearchRegistry.Entry("community-display-mode", R.string.InuCommunityDisplayMode, BUTTON_COMMUNITY_DISPLAY_MODE),
                SearchRegistry.Entry("bottom-tabs-hide", R.string.InuBottomTabsHide, TOGGLE_BOTTOM_TABS_HIDE),
                SearchRegistry.Entry("compact-mode", R.string.InuCompactMode, TOGGLE_COMPACT_MODE),
                SearchRegistry.Entry("show-tab-titles", R.string.InuShowTabTitles, TOGGLE_SHOW_TAB_TITLES),
                SearchRegistry.Entry("customize-bottom-tabs", R.string.InuMainTabsCustomizeReset, BUTTON_RESET_BOTTOM_TABS),
                SearchRegistry.Entry("dialogs-fab-main-action", R.string.InuDialogsFabMainAction, BUTTON_FAB_MAIN_ACTION),
                SearchRegistry.Entry("dialogs-fab-secondary-action", R.string.InuDialogsFabSecondaryAction, BUTTON_FAB_SECONDARY_ACTION),
                SearchRegistry.Entry("dialogs-fab-hide-on-scroll", R.string.InuDialogsFabHideOnScroll, TOGGLE_FAB_HIDE_ON_SCROLL),
                SearchRegistry.Entry("dialogs-fab-offset-for-bottom-bar", R.string.InuDialogsFabOffsetForBottomBar, TOGGLE_FAB_OFFSET_FOR_BOTTOM_BAR),
                SearchRegistry.Entry("dialogs-fab-left-side", R.string.InuDialogsFabLeftSide, TOGGLE_FAB_LEFT_SIDE),
            ),
        )
    }
}
