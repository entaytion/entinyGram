package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.DialogsFabHelper
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

    fun filterPreview(page: SchemaPageActivity): FilterTabsPreviewCell? = page.peek(KEY_FILTER_PREVIEW)

    fun filterPreviewItem(page: SchemaPageActivity, id: Int): UItem {
        val existing = filterPreview(page)
        val cell = existing ?: page.remember(KEY_FILTER_PREVIEW) { FilterTabsPreviewCell(page.context) }
        if (existing != null) cell.refresh()
        return UItem.asCustom(id, cell)
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
            background = Theme.createEditTextDrawable(ctx, true)
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

    fun showFabAction(page: SchemaPageActivity, anchor: View, item: InuConfig.IntItem) {
        val options = DialogsFabHelper.Action.entries.filter {
            item !== InuConfig.DIALOGS_FAB_MAIN_ACTION || it != DialogsFabHelper.Action.NONE
        }
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
