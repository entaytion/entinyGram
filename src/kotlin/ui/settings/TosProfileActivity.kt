package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TosProfileActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTosProfile)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLocalPremium)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LOCAL_PREMIUM,
                R.string.InuLocalPremium,
                R.string.InuLocalPremiumInfo,
                InuConfig.LOCAL_PREMIUM.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStarGiftsSection)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDDEN_STAR_GIFTS,
                R.string.InuHiddenStarGifts,
                R.string.InuHiddenStarGiftsInfo,
                InuConfig.HIDDEN_STAR_GIFTS.value,
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_LOCAL_PREMIUM -> {
                val new = InuConfig.LOCAL_PREMIUM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                val userConfig = UserConfig.getInstance(currentAccount)
                if (new) {
                    desu.inugram.helpers.LocalPremiumHelper.applyToSelfUser(userConfig.getCurrentUser(), currentAccount)
                } else {
                    desu.inugram.helpers.LocalPremiumHelper.clearSelfUser(userConfig.getCurrentUser())
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.currentUserPremiumStatusChanged)
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.premiumStatusChangedGlobal)
            }

            TOGGLE_HIDDEN_STAR_GIFTS -> {
                val new = InuConfig.HIDDEN_STAR_GIFTS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_LOCAL_PREMIUM = InuUtils.generateId()
        private val TOGGLE_HIDDEN_STAR_GIFTS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "tos-profile",
            titleRes = R.string.InuTosProfile,
            iconRes = R.drawable.inu_tabler_crown,
            factory = ::TosProfileActivity,
            entries = listOf(
                SearchRegistry.Entry("local-premium", R.string.InuLocalPremium, TOGGLE_LOCAL_PREMIUM),
                SearchRegistry.Entry("hidden-star-gifts", R.string.InuHiddenStarGifts, TOGGLE_HIDDEN_STAR_GIFTS),
            ),
        )
    }
}
