package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class SelfDestructSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSelfDestructMedia)

    private val selfDestructGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuSelfDestructMedia),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuSaveViewOnceMedia, InuConfig.SAVE_VIEW_ONCE_MEDIA, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveTimedMessages, InuConfig.SAVE_TIMED_MESSAGES, TOGGLE_SAVE_TIMED_MESSAGES),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructMedia, InuConfig.SAVE_SELF_DESTRUCT_MEDIA, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructText, InuConfig.SAVE_SELF_DESTRUCT_TEXT, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
        ),
        sectionId = SECTION_SELF_DESTRUCT_SAVE,
    ).apply { expanded = true }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        selfDestructGroup.addTo(items) { listView?.adapter?.update(true) }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        selfDestructGroup.handleClick(item, view) { listView?.adapter?.update(true) }
    }

    companion object {
        private val TOGGLE_SAVE_SELF_DESTRUCT_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_TEXT = InuUtils.generateId()
        private val TOGGLE_SAVE_VIEW_ONCE_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_TIMED_MESSAGES = InuUtils.generateId()
        private val SECTION_SELF_DESTRUCT_SAVE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "self-destruct",
            titleRes = R.string.InuSelfDestructMedia,
            iconRes = R.drawable.inu_tabler_flame,
            factory = ::SelfDestructSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("self-destruct-save", R.string.InuSelfDestructMedia, SECTION_SELF_DESTRUCT_SAVE),
                SearchRegistry.Entry("save-view-once-media", R.string.InuSaveViewOnceMedia, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
                SearchRegistry.Entry("save-timed-messages", R.string.InuSaveTimedMessages, TOGGLE_SAVE_TIMED_MESSAGES),
                SearchRegistry.Entry("save-self-destruct-media", R.string.InuSaveSelfDestructMedia, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
                SearchRegistry.Entry("save-self-destruct-text", R.string.InuSaveSelfDestructText, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
            ),
        )
    }
}
