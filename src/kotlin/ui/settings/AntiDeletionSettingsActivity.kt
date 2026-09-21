package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AntiDeletionSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAntiDeletion)

    private var deletedPreview: DeletedMessagePreviewCell? = null
    private var deletedMarkColorCell: DeletedMarkColorCell? = null

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    private val deletedCategoriesGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuSaveDeletedCategories),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuSaveDeletedPrivate, InuConfig.SAVE_DELETED_PRIVATE, TOGGLE_SAVE_DELETED_PRIVATE),
            ExpandableBoolGroup.Option(R.string.InuSaveDeletedGroups, InuConfig.SAVE_DELETED_GROUPS, TOGGLE_SAVE_DELETED_GROUPS),
            ExpandableBoolGroup.Option(R.string.InuSaveDeletedChannels, InuConfig.SAVE_DELETED_CHANNELS, TOGGLE_SAVE_DELETED_CHANNELS),
            ExpandableBoolGroup.Option(R.string.InuSaveDeletedBots, InuConfig.SAVE_DELETED_BOTS, TOGGLE_SAVE_DELETED_BOTS),
            ExpandableBoolGroup.Option(R.string.InuSaveDeletedOwn, InuConfig.SAVE_DELETED_OWN, TOGGLE_SAVE_DELETED_OWN),
        ),
        sectionId = SECTION_DELETED_CATEGORIES,
    ).apply { expanded = true }

    private val selfDestructGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuSelfDestructMedia),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuSaveViewOnceMedia, InuConfig.SAVE_VIEW_ONCE_MEDIA, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveTimedMessages, InuConfig.SAVE_TIMED_MESSAGES, TOGGLE_SAVE_TIMED_MESSAGES),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructMedia, InuConfig.SAVE_SELF_DESTRUCT_MEDIA, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructText, InuConfig.SAVE_SELF_DESTRUCT_TEXT, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
            ExpandableBoolGroup.Option(R.string.InuViewOnceShowNormal, InuConfig.VIEW_ONCE_SHOW_NORMAL, TOGGLE_VIEW_ONCE_SHOW_NORMAL),
        ),
        sectionId = SECTION_SELF_DESTRUCT_SAVE,
    ).apply { expanded = true }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_DELETED_MESSAGES,
                R.string.InuSaveDeletedMessages,
                R.string.InuSaveDeletedMessagesInfo,
                InuConfig.SAVE_DELETED_MESSAGES.value,
            )
        )
        if (!InuConfig.SAVE_DELETED_MESSAGES.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_PROMPT_KEEP_LOCAL_ON_DELETE,
                    R.string.InuPromptKeepLocalOnDelete,
                    R.string.InuPromptKeepLocalOnDeleteInfo,
                    InuConfig.PROMPT_KEEP_LOCAL_ON_DELETE.value,
                )
            )
        }
        if (InuConfig.SAVE_DELETED_MESSAGES.value) {
            if (deletedPreview == null) deletedPreview = DeletedMessagePreviewCell(this.context, this)
            if (deletedMarkColorCell == null) {
                deletedMarkColorCell = DeletedMarkColorCell(this.context) {
                    deletedPreview?.invalidate()
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
                }
            }
            items.add(UItem.asCustom(deletedPreview))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_DELETED_MESSAGES_TRANSPARENT,
                    R.string.InuDeletedMessagesTransparent,
                    R.string.InuDeletedMessagesTransparentInfo,
                    InuConfig.DELETED_MESSAGES_TRANSPARENT.value,
                )
            )
            items.add(
                UItem.asButton(
                    BUTTON_DELETED_MARK_STYLE,
                    LocaleController.getString(R.string.InuDeletedMark),
                    deletedMarkStyleLabel(InuConfig.DELETED_MARK_STYLE.value),
                )
            )
            if (InuConfig.DELETED_MARK_STYLE.value != InuConfig.DeletedMarkStyleItem.NOTHING) {
                items.add(UItem.asCustom(deletedMarkColorCell))
            }
            deletedCategoriesGroup.addTo(items) { listView?.adapter?.update(true) }
        }

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_EDITED_MESSAGES,
                R.string.InuSaveEditedMessages,
                R.string.InuSaveEditedMessagesInfo,
                InuConfig.SAVE_EDITED_MESSAGES.value,
            )
        )
        if (InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_SHOW_EDIT_HISTORY_DIFF,
                    R.string.InuEditHistoryDiff,
                    R.string.InuEditHistoryDiffInfo,
                    InuConfig.SHOW_EDIT_HISTORY_DIFF.value,
                )
            )
        }

        selfDestructGroup.addTo(items) { listView?.adapter?.update(true) }

        if (InuConfig.SAVE_DELETED_MESSAGES.value || InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(mkSubPageButton(BUTTON_SEARCH, R.drawable.inu_tabler_file_search, LocaleController.getString(R.string.InuDeletedMessageSearch)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (deletedCategoriesGroup.handleClick(item, view) { listView?.adapter?.update(true) }) return
        if (selfDestructGroup.handleClick(item, view) { listView?.adapter?.update(true) }) return
        when (item.id) {
            TOGGLE_SAVE_DELETED_MESSAGES -> {
                val new = InuConfig.SAVE_DELETED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_PROMPT_KEEP_LOCAL_ON_DELETE -> {
                val new = InuConfig.PROMPT_KEEP_LOCAL_ON_DELETE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_EDITED_MESSAGES -> {
                val new = InuConfig.SAVE_EDITED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_SHOW_EDIT_HISTORY_DIFF -> {
                val new = InuConfig.SHOW_EDIT_HISTORY_DIFF.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_DELETED_MESSAGES_TRANSPARENT -> {
                val new = InuConfig.DELETED_MESSAGES_TRANSPARENT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                deletedPreview?.invalidate()
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }
            BUTTON_DELETED_MARK_STYLE -> showDeletedMarkStyleSelector()
            BUTTON_SEARCH -> presentFragment(DeletedMessageSearchActivity())
        }
    }

    private fun deletedMarkStyleLabel(style: Int): String = when (style) {
        InuConfig.DeletedMarkStyleItem.NOTHING -> LocaleController.getString(R.string.InuDeletedMarkStyleNothing)
        InuConfig.DeletedMarkStyleItem.TRASH_BIN -> LocaleController.getString(R.string.InuDeletedMarkStyleTrashBin)
        InuConfig.DeletedMarkStyleItem.TRASH_BIN_OUTLINE -> LocaleController.getString(R.string.InuDeletedMarkStyleTrashBinOutline)
        InuConfig.DeletedMarkStyleItem.CROSS -> LocaleController.getString(R.string.InuDeletedMarkStyleCross)
        InuConfig.DeletedMarkStyleItem.EYE_CROSSED -> LocaleController.getString(R.string.InuDeletedMarkStyleEyeCrossed)
        else -> LocaleController.getString(R.string.InuDeletedMarkStyleTrashBin)
    }

    private fun showDeletedMarkStyleSelector() {
        val context = context ?: return
        val values = intArrayOf(
            InuConfig.DeletedMarkStyleItem.NOTHING,
            InuConfig.DeletedMarkStyleItem.TRASH_BIN,
            InuConfig.DeletedMarkStyleItem.TRASH_BIN_OUTLINE,
            InuConfig.DeletedMarkStyleItem.CROSS,
            InuConfig.DeletedMarkStyleItem.EYE_CROSSED,
        )
        val items = values.map { RadioDialogBuilder.Item(deletedMarkStyleLabel(it)) }
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuDeletedMark))
                .setItems(items, values.indexOf(InuConfig.DELETED_MARK_STYLE.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.DELETED_MARK_STYLE.value == newValue) return@setItems
                    InuConfig.DELETED_MARK_STYLE.value = newValue
                    deletedMarkColorCell?.refreshIcon()
                    deletedPreview?.invalidate()
                    listView?.adapter?.update(true)
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
                }.create()
        )
    }

    companion object {
        private val TOGGLE_SAVE_DELETED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_PROMPT_KEEP_LOCAL_ON_DELETE = InuUtils.generateId()
        private val TOGGLE_DELETED_MESSAGES_TRANSPARENT = InuUtils.generateId()
        private val BUTTON_DELETED_MARK_STYLE = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_PRIVATE = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_GROUPS = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_CHANNELS = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_BOTS = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_OWN = InuUtils.generateId()
        private val TOGGLE_SAVE_EDITED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_SHOW_EDIT_HISTORY_DIFF = InuUtils.generateId()
        private val SECTION_DELETED_CATEGORIES = InuUtils.generateId()
        private val BUTTON_SEARCH = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_TEXT = InuUtils.generateId()
        private val TOGGLE_SAVE_VIEW_ONCE_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_TIMED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_VIEW_ONCE_SHOW_NORMAL = InuUtils.generateId()
        private val SECTION_SELF_DESTRUCT_SAVE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "anti-deletion",
            titleRes = R.string.InuAntiDeletion,
            iconRes = R.drawable.inu_tabler_trash_off,
            factory = ::AntiDeletionSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("save-deleted-categories", R.string.InuSaveDeletedCategories, SECTION_DELETED_CATEGORIES),
                SearchRegistry.Entry("save-deleted-messages", R.string.InuSaveDeletedMessages, TOGGLE_SAVE_DELETED_MESSAGES),
                SearchRegistry.Entry("prompt-keep-local-on-delete", R.string.InuPromptKeepLocalOnDelete, TOGGLE_PROMPT_KEEP_LOCAL_ON_DELETE),
                SearchRegistry.Entry("deleted-transparent", R.string.InuDeletedMessagesTransparent, TOGGLE_DELETED_MESSAGES_TRANSPARENT),
                SearchRegistry.Entry("deleted-mark-style", R.string.InuDeletedMark, BUTTON_DELETED_MARK_STYLE),
                SearchRegistry.Entry("save-deleted-private", R.string.InuSaveDeletedPrivate, TOGGLE_SAVE_DELETED_PRIVATE),
                SearchRegistry.Entry("save-deleted-groups", R.string.InuSaveDeletedGroups, TOGGLE_SAVE_DELETED_GROUPS),
                SearchRegistry.Entry("save-deleted-channels", R.string.InuSaveDeletedChannels, TOGGLE_SAVE_DELETED_CHANNELS),
                SearchRegistry.Entry("save-deleted-bots", R.string.InuSaveDeletedBots, TOGGLE_SAVE_DELETED_BOTS),
                SearchRegistry.Entry("save-deleted-own", R.string.InuSaveDeletedOwn, TOGGLE_SAVE_DELETED_OWN),
                SearchRegistry.Entry("save-edited-messages", R.string.InuSaveEditedMessages, TOGGLE_SAVE_EDITED_MESSAGES),
                SearchRegistry.Entry("edit-history-diff", R.string.InuEditHistoryDiff, TOGGLE_SHOW_EDIT_HISTORY_DIFF),
                SearchRegistry.Entry("self-destruct-save", R.string.InuSelfDestructMedia, SECTION_SELF_DESTRUCT_SAVE),
                SearchRegistry.Entry("save-view-once-media", R.string.InuSaveViewOnceMedia, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
                SearchRegistry.Entry("save-timed-messages", R.string.InuSaveTimedMessages, TOGGLE_SAVE_TIMED_MESSAGES),
                SearchRegistry.Entry("save-self-destruct-media", R.string.InuSaveSelfDestructMedia, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
                SearchRegistry.Entry("save-self-destruct-text", R.string.InuSaveSelfDestructText, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
                SearchRegistry.Entry("view-once-show-normal", R.string.InuViewOnceShowNormal, TOGGLE_VIEW_ONCE_SHOW_NORMAL),
            ),
        )
    }
}
