package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.GhostHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class GhostModeActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuGhostMode)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_BETA_INFO,
                R.drawable.ic_beta_badge,
                LocaleController.getString(R.string.InuBetaFeatureTitle)
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuGhostModeInfo)))

        items.add(
            UItem.asCheck(TOGGLE_GHOST_MODE, LocaleController.getString(R.string.InuGhostModeMaster))
                .setChecked(InuConfig.GHOST_MODE.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuGhostFeatures)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_READ,
                R.string.InuGhostHideRead,
                R.string.InuGhostHideReadInfo,
                InuConfig.GHOST_HIDE_READ.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_STORY_READ,
                R.string.InuGhostHideStoryRead,
                R.string.InuGhostHideStoryReadInfo,
                InuConfig.GHOST_HIDE_STORY_READ.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_ONLINE,
                R.string.InuGhostHideOnline,
                R.string.InuGhostHideOnlineInfo,
                InuConfig.GHOST_HIDE_ONLINE.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_TYPING,
                R.string.InuGhostHideTyping,
                R.string.InuGhostHideTypingInfo,
                InuConfig.GHOST_HIDE_TYPING.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_OFFLINE_AFTER_ONLINE,
                R.string.InuGhostOfflineAfterOnline,
                R.string.InuGhostOfflineAfterOnlineInfo,
                InuConfig.GHOST_OFFLINE_AFTER_ONLINE.value
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_GHOST_MODE -> {
                val new = InuConfig.GHOST_MODE.toggle()
                (view as? TextCheckCell)?.isChecked = new
                GhostHelper.syncPresence(currentAccount)
                BulletinFactory.of(this)
                    .createSimpleBulletin(
                        R.raw.done,
                        LocaleController.getString(if (new) R.string.InuGhostEnabled else R.string.InuGhostDisabled)
                    )
                    .show()
            }
            TOGGLE_HIDE_READ -> {
                val new = InuConfig.GHOST_HIDE_READ.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_HIDE_STORY_READ -> {
                val new = InuConfig.GHOST_HIDE_STORY_READ.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_HIDE_ONLINE -> {
                val new = InuConfig.GHOST_HIDE_ONLINE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                GhostHelper.syncPresence(currentAccount)
            }
            TOGGLE_HIDE_TYPING -> {
                val new = InuConfig.GHOST_HIDE_TYPING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_OFFLINE_AFTER_ONLINE -> {
                val new = InuConfig.GHOST_OFFLINE_AFTER_ONLINE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            BUTTON_BETA_INFO -> showBetaBottomSheet()
        }
    }

    private fun showBetaBottomSheet() {
        BulletinFactory.of(this)
            .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuBetaFeatureInfo))
            .show()
    }

    companion object {
        private val BUTTON_BETA_INFO = InuUtils.generateId()
        private val TOGGLE_GHOST_MODE = InuUtils.generateId()
        private val TOGGLE_HIDE_READ = InuUtils.generateId()
        private val TOGGLE_HIDE_STORY_READ = InuUtils.generateId()
        private val TOGGLE_HIDE_ONLINE = InuUtils.generateId()
        private val TOGGLE_HIDE_TYPING = InuUtils.generateId()
        private val TOGGLE_OFFLINE_AFTER_ONLINE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ghost-mode",
            titleRes = R.string.InuGhostMode,
            iconRes = R.drawable.inu_ghost,
            factory = ::GhostModeActivity,
            entries = listOf(
                SearchRegistry.Entry("ghost-master", R.string.InuGhostModeMaster, TOGGLE_GHOST_MODE),
                SearchRegistry.Entry("ghost-hide-read", R.string.InuGhostHideRead, TOGGLE_HIDE_READ),
                SearchRegistry.Entry("ghost-hide-story-read", R.string.InuGhostHideStoryRead, TOGGLE_HIDE_STORY_READ),
                SearchRegistry.Entry("ghost-hide-online", R.string.InuGhostHideOnline, TOGGLE_HIDE_ONLINE),
                SearchRegistry.Entry("ghost-hide-typing", R.string.InuGhostHideTyping, TOGGLE_HIDE_TYPING),
                SearchRegistry.Entry("ghost-offline-after-online", R.string.InuGhostOfflineAfterOnline, TOGGLE_OFFLINE_AFTER_ONLINE),
            ),
        )
    }
}
