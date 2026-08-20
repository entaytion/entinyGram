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

class ContentProtectionSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuContentProtectionBypass)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuContentProtectionBypass)))
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
                TOGGLE_ALLOW_FORWARD_RESTRICTED,
                R.string.InuAllowForwardRestricted,
                R.string.InuAllowForwardRestrictedInfo,
                InuConfig.ALLOW_FORWARD_RESTRICTED.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ALLOW_SCREENSHOTS,
                R.string.InuAllowScreenshots,
                R.string.InuAllowScreenshotsInfo,
                InuConfig.ALLOW_SCREENSHOTS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_USER_INFO,
                R.string.InuSaveUserInfo,
                R.string.InuSaveUserInfoInfo,
                InuConfig.SAVE_USER_INFO.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION,
                R.string.InuSuppressScreenshotNotification,
                R.string.InuSuppressScreenshotNotificationInfo,
                InuConfig.SUPPRESS_SCREENSHOT_NOTIFICATION.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_SAVE_ANY_STORY -> {
                val new = InuConfig.SAVE_ANY_STORY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_ALLOW_FORWARD_RESTRICTED -> {
                val new = InuConfig.ALLOW_FORWARD_RESTRICTED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_ALLOW_SCREENSHOTS -> {
                val new = InuConfig.ALLOW_SCREENSHOTS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_USER_INFO -> {
                val new = InuConfig.SAVE_USER_INFO.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION -> {
                val new = InuConfig.SUPPRESS_SCREENSHOT_NOTIFICATION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_SAVE_ANY_STORY = InuUtils.generateId()
        private val TOGGLE_ALLOW_FORWARD_RESTRICTED = InuUtils.generateId()
        private val TOGGLE_ALLOW_SCREENSHOTS = InuUtils.generateId()
        private val TOGGLE_SAVE_USER_INFO = InuUtils.generateId()
        private val TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "content-protection",
            titleRes = R.string.InuContentProtectionBypass,
            iconRes = R.drawable.inu_tabler_shield_lock,
            factory = ::ContentProtectionSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("save-any-story", R.string.InuSaveAnyStory, TOGGLE_SAVE_ANY_STORY),
                SearchRegistry.Entry("allow-forward-restricted", R.string.InuAllowForwardRestricted, TOGGLE_ALLOW_FORWARD_RESTRICTED),
                SearchRegistry.Entry("allow-screenshots", R.string.InuAllowScreenshots, TOGGLE_ALLOW_SCREENSHOTS),
                SearchRegistry.Entry("save-user-info", R.string.InuSaveUserInfo, TOGGLE_SAVE_USER_INFO),
                SearchRegistry.Entry("suppress-screenshot-notification", R.string.InuSuppressScreenshotNotification, TOGGLE_SUPPRESS_SCREENSHOT_NOTIFICATION),
            ),
        )
    }
}
