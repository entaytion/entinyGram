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

class NicheSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuNicheSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNicheSettingsInfo)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_FORCE_NO_VIBRATION,
                R.string.InuForceNoVibration,
                R.string.InuForceNoVibrationInfo,
                InuConfig.FORCE_NO_VIBRATION.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_FORCE_NO_VIBRATION -> {
                val new = InuConfig.FORCE_NO_VIBRATION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_FORCE_NO_VIBRATION = InuUtils.generateId()

        // hidden until NICHE_SETTINGS_UNLOCKED; SearchRegistry skips it while locked
        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "niche",
            titleRes = R.string.InuNicheSettings,
            iconRes = R.drawable.inu_tabler_skull,
            factory = ::NicheSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("niche-force-no-vibration", R.string.InuForceNoVibration, TOGGLE_FORCE_NO_VIBRATION),
            ),
        )
    }
}
