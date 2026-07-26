package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TosSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTOS)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSelfDestructMedia)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_SELF_DESTRUCT,
                R.string.InuSaveSelfDestruct,
                R.string.InuSaveSelfDestructInfo,
                InuConfig.SAVE_SELF_DESTRUCT.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_ANY_STORY,
                R.string.InuSaveAnyStory,
                R.string.InuSaveAnyStoryInfo,
                InuConfig.SAVE_ANY_STORY.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_DELETED_MESSAGES,
                R.string.InuSaveDeletedMessages,
                R.string.InuSaveDeletedMessagesInfo,
                InuConfig.SAVE_DELETED_MESSAGES.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_EDITED_MESSAGES,
                R.string.InuSaveEditedMessages,
                R.string.InuSaveEditedMessagesInfo,
                InuConfig.SAVE_EDITED_MESSAGES.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_SPONSORED_MESSAGES,
                R.string.InuHideSponsoredMessages,
                R.string.InuHideSponsoredMessagesInfo,
                InuConfig.HIDE_SPONSORED_MESSAGES.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_SAVE_SELF_DESTRUCT -> {
                val new = InuConfig.SAVE_SELF_DESTRUCT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_ANY_STORY -> {
                val new = InuConfig.SAVE_ANY_STORY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_DELETED_MESSAGES -> {
                val new = InuConfig.SAVE_DELETED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_EDITED_MESSAGES -> {
                val new = InuConfig.SAVE_EDITED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_HIDE_SPONSORED_MESSAGES -> {
                val new = InuConfig.HIDE_SPONSORED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_SAVE_SELF_DESTRUCT = InuUtils.generateId()
        private val TOGGLE_SAVE_ANY_STORY = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_SAVE_EDITED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_HIDE_SPONSORED_MESSAGES = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "tos",
            titleRes = R.string.InuTOS,
            iconRes = R.drawable.msg_autodelete,
            factory = ::TosSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("save-self-destruct", R.string.InuSaveSelfDestruct, TOGGLE_SAVE_SELF_DESTRUCT),
                SearchRegistry.Entry("save-any-story", R.string.InuSaveAnyStory, TOGGLE_SAVE_ANY_STORY),
                SearchRegistry.Entry("save-deleted-messages", R.string.InuSaveDeletedMessages, TOGGLE_SAVE_DELETED_MESSAGES),
                SearchRegistry.Entry("save-edited-messages", R.string.InuSaveEditedMessages, TOGGLE_SAVE_EDITED_MESSAGES),
                SearchRegistry.Entry("hide-sponsored-messages", R.string.InuHideSponsoredMessages, TOGGLE_HIDE_SPONSORED_MESSAGES),
            ),
        )
    }
}
