package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.security.GhostHelper
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class GhostModeSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuGhostMode)

    private val ghostGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuGhostFeatures),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuGhostHideRead, InuConfig.GHOST_HIDE_READ, TOGGLE_HIDE_READ),
            ExpandableBoolGroup.Option(R.string.InuGhostHideVoiceRead, InuConfig.GHOST_HIDE_VOICE_READ, TOGGLE_HIDE_VOICE_READ),
            ExpandableBoolGroup.Option(R.string.InuGhostHideStoryRead, InuConfig.GHOST_HIDE_STORY_READ, TOGGLE_HIDE_STORY_READ),
            ExpandableBoolGroup.Option(R.string.InuGhostHideTyping, InuConfig.GHOST_HIDE_TYPING, TOGGLE_HIDE_TYPING),
        ),
        sectionId = SECTION_GHOST_MODE,
    ).apply { expanded = true }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // Master switch: mirrors the drawer/burger quick-toggle (GhostHelper.toggleGhostMode),
        // so the settings page itself has an obvious single on/off instead of only exposing the
        // sub-toggle group below it.
        items.add(
            UItem.asCheck(TOGGLE_MASTER, LocaleController.getString(R.string.InuGhostModeMaster))
                .setChecked(GhostHelper.isGhostActive())
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostModeInfo)))
        ghostGroup.addTo(items) {
            GhostHelper.syncPresence(currentAccount)
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
            listView?.adapter?.update(true)
        }
        items.add(
            UItem.asButton(
                BUTTON_PRESENCE_MODE,
                LocaleController.getString(R.string.InuGhostPresenceMode),
                presenceModeLabel(),
            )
        )
        items.add(mkSubPageButton(BUTTON_MANAGE_WHITELIST, LocaleController.getString(R.string.InuGhostWhitelist)))
        items.add(UItem.asShadow(null))
        // Independent of the group above — not part of "is ghost active" (same as
        // AyuGram/NagramX's markReadAfterSend, which lives outside ghostToggleItems).
        items.add(
            UItem.asCheck(
                TOGGLE_READ_ON_SEND,
                LocaleController.getString(R.string.InuGhostReadOnSend),
            ).setChecked(InuConfig.GHOST_READ_ON_SEND.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_MARK_READ_LOCALLY,
                LocaleController.getString(R.string.InuGhostMarkReadLocally),
            ).setChecked(InuConfig.GHOST_MARK_READ_LOCALLY.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostMarkReadLocallyInfo)))
    }

    private fun presenceModeLabel(): String = when (InuConfig.GHOST_PRESENCE_MODE.value) {
        InuConfig.GhostPresenceModeItem.HIDDEN -> LocaleController.getString(R.string.InuGhostPresenceModeHidden)
        InuConfig.GhostPresenceModeItem.DELAYED -> LocaleController.getString(R.string.InuGhostPresenceModeDelayed)
        else -> LocaleController.getString(R.string.InuGhostPresenceModeNormal)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (ghostGroup.handleClick(item, view) { _ ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
            listView?.adapter?.update(true)
        }) return
        when (item.id) {
            TOGGLE_MASTER -> {
                val new = GhostHelper.toggleGhostMode()
                (view as? TextCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            BUTTON_PRESENCE_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuGhostPresenceModeNormal),
                    LocaleController.getString(R.string.InuGhostPresenceModeHidden),
                    LocaleController.getString(R.string.InuGhostPresenceModeDelayed),
                ),
                InuConfig.GHOST_PRESENCE_MODE.value,
            ) { which ->
                InuConfig.GHOST_PRESENCE_MODE.value = which
                GhostHelper.syncPresence(currentAccount)
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
                listView?.adapter?.update(true)
            }
            BUTTON_MANAGE_WHITELIST -> presentFragment(GhostWhitelistSettingsActivity())
            TOGGLE_READ_ON_SEND -> {
                val new = InuConfig.GHOST_READ_ON_SEND.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }
            TOGGLE_MARK_READ_LOCALLY -> {
                val new = InuConfig.GHOST_MARK_READ_LOCALLY.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_MASTER = InuUtils.generateId()
        private val SECTION_GHOST_MODE = InuUtils.generateId()
        private val TOGGLE_HIDE_READ = InuUtils.generateId()
        private val TOGGLE_READ_ON_SEND = InuUtils.generateId()
        private val TOGGLE_MARK_READ_LOCALLY = InuUtils.generateId()
        private val TOGGLE_HIDE_VOICE_READ = InuUtils.generateId()
        private val TOGGLE_HIDE_STORY_READ = InuUtils.generateId()
        private val TOGGLE_HIDE_TYPING = InuUtils.generateId()
        private val BUTTON_PRESENCE_MODE = InuUtils.generateId()
        private val BUTTON_MANAGE_WHITELIST = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ghost-mode",
            titleRes = R.string.InuGhostMode,
            iconRes = R.drawable.inu_ghost_filled,
            factory = ::GhostModeSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ghost-mode-master", R.string.InuGhostModeMaster, TOGGLE_MASTER),
                SearchRegistry.Entry("ghost-mode-section", R.string.InuGhostMode, SECTION_GHOST_MODE),
                SearchRegistry.Entry("ghost-hide-read", R.string.InuGhostHideRead, TOGGLE_HIDE_READ),
                SearchRegistry.Entry("ghost-read-on-send", R.string.InuGhostReadOnSend, TOGGLE_READ_ON_SEND),
                SearchRegistry.Entry("ghost-mark-read-locally", R.string.InuGhostMarkReadLocally, TOGGLE_MARK_READ_LOCALLY),
                SearchRegistry.Entry("ghost-hide-voice-read", R.string.InuGhostHideVoiceRead, TOGGLE_HIDE_VOICE_READ),
                SearchRegistry.Entry("ghost-hide-story-read", R.string.InuGhostHideStoryRead, TOGGLE_HIDE_STORY_READ),
                SearchRegistry.Entry("ghost-hide-typing", R.string.InuGhostHideTyping, TOGGLE_HIDE_TYPING),
                SearchRegistry.Entry("ghost-presence-mode", R.string.InuGhostPresenceMode, BUTTON_PRESENCE_MODE),
            ),
        )
    }
}
