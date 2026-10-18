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

class RetroSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuRetroTitle)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRetroScreens)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_RETRO_PROFILE,
                R.string.InuRetroProfile,
                R.string.InuRetroProfileInfo,
                InuConfig.RETRO_PROFILE.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_RETRO_LISTS,
                R.string.InuRetroLists,
                R.string.InuRetroListsInfo,
                InuConfig.RETRO_LISTS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_RETRO_SETTINGS,
                R.string.InuRetroSettings,
                R.string.InuRetroSettingsInfo,
                InuConfig.RETRO_SETTINGS.value,
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuRetroCredit)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_RETRO_PROFILE -> {
                val new = InuConfig.RETRO_PROFILE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_RETRO_LISTS -> {
                val new = InuConfig.RETRO_LISTS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_RETRO_SETTINGS -> {
                val new = InuConfig.RETRO_SETTINGS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
                showRestartBulletin()
            }
        }
    }

    companion object {
        private val TOGGLE_RETRO_PROFILE = InuUtils.generateId()
        private val TOGGLE_RETRO_LISTS = InuUtils.generateId()
        private val TOGGLE_RETRO_SETTINGS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "retro",
            titleRes = R.string.InuRetroTitle,
            iconRes = R.drawable.inu_tabler_list,
            factory = ::RetroSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("retro-profile", R.string.InuRetroProfile, TOGGLE_RETRO_PROFILE),
                SearchRegistry.Entry("retro-lists", R.string.InuRetroLists, TOGGLE_RETRO_LISTS),
                SearchRegistry.Entry("retro-settings", R.string.InuRetroSettings, TOGGLE_RETRO_SETTINGS),
            ),
        )
    }
}
