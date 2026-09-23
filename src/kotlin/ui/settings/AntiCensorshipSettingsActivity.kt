package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.network.CensorshipHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AntiCensorshipSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAntiCensorship)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAntiCensorshipTransport)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_WS_TUNNEL,
                R.string.InuWsTunnel,
                R.string.InuWsTunnelInfo,
                InuConfig.ANTICENSOR_WS_TUNNEL.value,
                experimental = true,
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuWsTunnelFooter)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAntiCensorshipPrivacy)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_LEAK_GUARD,
                R.string.InuLeakGuard,
                R.string.InuLeakGuardInfo,
                InuConfig.LEAK_GUARD.value,
                experimental = true,
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuLeakGuardFooter)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_WS_TUNNEL -> {
                val new = !InuConfig.ANTICENSOR_WS_TUNNEL.value
                CensorshipHelper.setTunnelEnabled(new)
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_LEAK_GUARD -> {
                val new = InuConfig.LEAK_GUARD.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_WS_TUNNEL = InuUtils.generateId()
        private val TOGGLE_LEAK_GUARD = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "anti-censorship",
            titleRes = R.string.InuAntiCensorship,
            iconRes = R.drawable.inu_tabler_world,
            factory = ::AntiCensorshipSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ws-tunnel", R.string.InuWsTunnel, TOGGLE_WS_TUNNEL),
                SearchRegistry.Entry("leak-guard", R.string.InuLeakGuard, TOGGLE_LEAK_GUARD),
            ),
        )
    }
}
