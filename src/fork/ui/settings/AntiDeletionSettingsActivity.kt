package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.archive.ArchiveMediaHelper
import desu.inugram.helpers.archive.ArchiveScope
import desu.inugram.helpers.security.ArchiveExportHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AntiDeletionSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAntiDeletion)

    private var deletedPreview: DeletedMessagePreviewCell? = null
    private var deletedMarkCell: DeletedMarkCell? = null

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    private val keepGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuSelfDestructKeep),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuSaveViewOnceMedia, InuConfig.SAVE_VIEW_ONCE_MEDIA, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveTimedMessages, InuConfig.SAVE_TIMED_MESSAGES, TOGGLE_SAVE_TIMED_MESSAGES),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructMedia, InuConfig.SAVE_SELF_DESTRUCT_MEDIA, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructText, InuConfig.SAVE_SELF_DESTRUCT_TEXT, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
        ),
        sectionId = SECTION_SELF_DESTRUCT_SAVE,
    ).apply { expanded = true }

    private val oneTimeToggles = listOf(
        ExpandableBoolGroup.Option(R.string.InuAutoSaveOneTime, InuConfig.AUTO_SAVE_ONE_TIME, TOGGLE_AUTO_SAVE_ONE_TIME),
        ExpandableBoolGroup.Option(R.string.InuViewOnceShowNormal, InuConfig.VIEW_ONCE_SHOW_NORMAL, TOGGLE_VIEW_ONCE_SHOW_NORMAL),
    )

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (InuConfig.SAVE_DELETED_MESSAGES.value) {
            if (deletedPreview == null) deletedPreview = DeletedMessagePreviewCell(this.context, this)
            items.add(UItem.asCustom(deletedPreview))
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSaveDeletedMessages)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_DELETED_MESSAGES,
                R.string.InuSaveDeletedMessages,
                R.string.InuSaveDeletedMessagesInfo,
                InuConfig.SAVE_DELETED_MESSAGES.value,
            )
        )
        if (InuConfig.SAVE_DELETED_MESSAGES.value) {
            if (deletedMarkCell == null) {
                deletedMarkCell = DeletedMarkCell(this.context) {
                    deletedPreview?.refresh()
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
                }
            }
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_DELETED_MESSAGES_TRANSPARENT,
                    R.string.InuDeletedMessagesTransparent,
                    R.string.InuDeletedMessagesTransparentInfo,
                    InuConfig.DELETED_MESSAGES_TRANSPARENT.value,
                )
            )
            items.add(UItem.asCustom(deletedMarkCell).apply { id = CELL_DELETED_MARK })
            items.add(UItem.asButton(BUTTON_ARCHIVE_SCOPE, R.drawable.inu_tabler_file_search, LocaleController.getString(R.string.InuArchiveScope), ArchiveScopeActivity.summary(ArchiveScope.Kind.DELETED)))
            items.add(UItem.asCheck(TOGGLE_SAVE_DELETED_OWN, LocaleController.getString(R.string.InuSaveDeletedOwn)).setChecked(InuConfig.SAVE_DELETED_OWN.value))
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuEditHistory)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_EDITED_MESSAGES,
                R.string.InuSaveEditedMessages,
                R.string.InuSaveEditedMessagesInfo,
                InuConfig.SAVE_EDITED_MESSAGES.value,
            )
        )
        if (InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(UItem.asButton(BUTTON_SCOPE_EDITED, R.drawable.inu_tabler_file_search, LocaleController.getString(R.string.InuArchiveScope), ArchiveScopeActivity.summary(ArchiveScope.Kind.EDITED)))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_EDIT_HISTORY_DIFF,
                    R.string.InuEditHistoryDiff,
                    R.string.InuEditHistoryDiffInfo,
                    InuConfig.SHOW_EDIT_HISTORY_DIFF.value,
                )
            )
        }

        if (InuConfig.SAVE_DELETED_MESSAGES.value || InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(UItem.asShadow(null))
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuArchiveAttachments)))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ARCHIVE_MEDIA_ENABLED,
                    R.string.InuArchiveAttachmentsSave,
                    R.string.InuArchiveAttachmentsInfo,
                    InuConfig.ARCHIVE_MEDIA_ENABLED.value,
                )
            )
            if (InuConfig.ARCHIVE_MEDIA_ENABLED.value) {
                items.add(UItem.asButton(BUTTON_SCOPE_MEDIA, R.drawable.inu_tabler_file_search, LocaleController.getString(R.string.InuArchiveScope), ArchiveScopeActivity.summary(ArchiveScope.Kind.MEDIA)))
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_ARCHIVE_MEDIA_PUBLIC,
                        R.string.InuArchiveMediaPublic,
                        R.string.InuArchiveMediaPublicInfo,
                        InuConfig.ARCHIVE_MEDIA_PUBLIC.value,
                    )
                )
            }
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuOneTimeHeader)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ONE_TIME_PEEK,
                R.string.InuOneTimePeek,
                R.string.InuOneTimePeekInfo,
                InuConfig.ONE_TIME_PEEK.value,
            )
        )
        keepGroup.addTo(items) { listView?.adapter?.update(true) }
        for (option in oneTimeToggles) {
            items.add(UItem.asCheck(option.id, LocaleController.getString(option.labelRes)).setChecked(option.config.value))
        }

        if (InuConfig.SAVE_DELETED_MESSAGES.value || InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(UItem.asShadow(null))
            items.add(mkSubPageButton(BUTTON_SEARCH, R.drawable.inu_tabler_file_search, LocaleController.getString(R.string.InuDeletedMessageSearch)))
            items.add(UItem.asButton(BUTTON_EXPORT, R.drawable.msg_shareout, LocaleController.getString(R.string.InuExportArchive)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (keepGroup.handleClick(item, view) { listView?.adapter?.update(true) }) return
        val toggle = oneTimeToggles.firstOrNull { it.id == item.id }
        if (toggle != null) {
            toggle.config.toggle()
            listView?.adapter?.update(true)
            return
        }
        when (item.id) {
            TOGGLE_ONE_TIME_PEEK -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.ONE_TIME_PEEK.toggle()
            TOGGLE_SAVE_DELETED_MESSAGES -> {
                val new = InuConfig.SAVE_DELETED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_ARCHIVE_MEDIA_PUBLIC -> {
                val new = InuConfig.ARCHIVE_MEDIA_PUBLIC.toggle()
                if (new) ArchiveMediaHelper.exportExistingArchive(currentAccount) else ArchiveMediaHelper.removeMirror()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_EDITED_MESSAGES -> {
                val new = InuConfig.SAVE_EDITED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_EDIT_HISTORY_DIFF -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.SHOW_EDIT_HISTORY_DIFF.toggle()
            }
            TOGGLE_DELETED_MESSAGES_TRANSPARENT -> {
                val new = InuConfig.DELETED_MESSAGES_TRANSPARENT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                deletedPreview?.refresh()
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }
            BUTTON_ARCHIVE_SCOPE -> presentFragment(ArchiveScopeActivity(ArchiveScope.Kind.DELETED))
            BUTTON_SCOPE_EDITED -> presentFragment(ArchiveScopeActivity(ArchiveScope.Kind.EDITED))
            BUTTON_SCOPE_MEDIA -> presentFragment(ArchiveScopeActivity(ArchiveScope.Kind.MEDIA))
            TOGGLE_ARCHIVE_MEDIA_ENABLED -> {
                val new = InuConfig.ARCHIVE_MEDIA_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_SAVE_DELETED_OWN -> {
                InuConfig.SAVE_DELETED_OWN.toggle()
                listView?.adapter?.update(true)
            }
            BUTTON_SEARCH -> presentFragment(DeletedMessageSearchActivity())
            BUTTON_EXPORT -> ArchiveExportHelper.export(this)
        }
    }

    companion object {
        private val TOGGLE_SAVE_DELETED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_ARCHIVE_MEDIA_PUBLIC = InuUtils.generateId()
        private val TOGGLE_DELETED_MESSAGES_TRANSPARENT = InuUtils.generateId()
        private val CELL_DELETED_MARK = InuUtils.generateId()
        private val BUTTON_ARCHIVE_SCOPE = InuUtils.generateId()
        private val BUTTON_SCOPE_EDITED = InuUtils.generateId()
        private val BUTTON_SCOPE_MEDIA = InuUtils.generateId()
        private val TOGGLE_ARCHIVE_MEDIA_ENABLED = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_OWN = InuUtils.generateId()
        private val TOGGLE_SAVE_EDITED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_EDIT_HISTORY_DIFF = InuUtils.generateId()
        private val BUTTON_SEARCH = InuUtils.generateId()
        private val BUTTON_EXPORT = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_TEXT = InuUtils.generateId()
        private val TOGGLE_SAVE_VIEW_ONCE_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_TIMED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_VIEW_ONCE_SHOW_NORMAL = InuUtils.generateId()
        private val TOGGLE_ONE_TIME_PEEK = InuUtils.generateId()
        private val TOGGLE_AUTO_SAVE_ONE_TIME = InuUtils.generateId()
        private val SECTION_SELF_DESTRUCT_SAVE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "anti-deletion",
            titleRes = R.string.InuAntiDeletion,
            iconRes = R.drawable.inu_tabler_trash_off,
            factory = ::AntiDeletionSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("archive-scope", R.string.InuArchiveScope, BUTTON_ARCHIVE_SCOPE),
                SearchRegistry.Entry("archive-scope-edited", R.string.InuArchiveScope, BUTTON_SCOPE_EDITED),
                SearchRegistry.Entry("archive-scope-media", R.string.InuArchiveScope, BUTTON_SCOPE_MEDIA),
                SearchRegistry.Entry("archive-media-enabled", R.string.InuArchiveAttachmentsSave, TOGGLE_ARCHIVE_MEDIA_ENABLED),
                SearchRegistry.Entry("save-deleted-messages", R.string.InuSaveDeletedMessages, TOGGLE_SAVE_DELETED_MESSAGES),
                SearchRegistry.Entry("archive-media-public", R.string.InuArchiveMediaPublic, TOGGLE_ARCHIVE_MEDIA_PUBLIC),
                SearchRegistry.Entry("deleted-transparent", R.string.InuDeletedMessagesTransparent, TOGGLE_DELETED_MESSAGES_TRANSPARENT),
                SearchRegistry.Entry("deleted-mark", R.string.InuDeletedMark, CELL_DELETED_MARK),
                SearchRegistry.Entry("save-deleted-own", R.string.InuSaveDeletedOwn, TOGGLE_SAVE_DELETED_OWN),
                SearchRegistry.Entry("save-edited-messages", R.string.InuSaveEditedMessages, TOGGLE_SAVE_EDITED_MESSAGES),
                SearchRegistry.Entry("edit-history-diff", R.string.InuEditHistoryDiff, TOGGLE_EDIT_HISTORY_DIFF),
                SearchRegistry.Entry("self-destruct-save", R.string.InuSelfDestructMedia, SECTION_SELF_DESTRUCT_SAVE),
                SearchRegistry.Entry("save-view-once-media", R.string.InuSaveViewOnceMedia, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
                SearchRegistry.Entry("save-timed-messages", R.string.InuSaveTimedMessages, TOGGLE_SAVE_TIMED_MESSAGES),
                SearchRegistry.Entry("save-self-destruct-media", R.string.InuSaveSelfDestructMedia, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
                SearchRegistry.Entry("save-self-destruct-text", R.string.InuSaveSelfDestructText, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
                SearchRegistry.Entry("view-once-show-normal", R.string.InuViewOnceShowNormal, TOGGLE_VIEW_ONCE_SHOW_NORMAL),
                SearchRegistry.Entry("one-time-peek", R.string.InuOneTimePeek, TOGGLE_ONE_TIME_PEEK),
                SearchRegistry.Entry("auto-save-one-time", R.string.InuAutoSaveOneTime, TOGGLE_AUTO_SAVE_ONE_TIME),
            ),
        )
    }
}
