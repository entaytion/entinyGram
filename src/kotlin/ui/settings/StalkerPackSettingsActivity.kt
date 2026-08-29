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

class StalkerPackSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuStalkerPack)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStalkerPack)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_PRESENCE_LOGGER_NOTIFY,
                R.string.InuPresenceLoggerNotify,
                R.string.InuPresenceLoggerNotifyInfo,
                InuConfig.PRESENCE_LOGGER_NOTIFY.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_FORCE_RELAY_CALLS,
                R.string.InuForceRelayCalls,
                R.string.InuForceRelayCallsInfo,
                InuConfig.FORCE_RELAY_CALLS.value,
            )
        )
        items.add(mkSubPageButton(BUTTON_WATCH_LIST, R.drawable.inu_tabler_user_search, LocaleController.getString(R.string.InuPresenceWatchList)))
        items.add(mkSubPageButton(BUTTON_TYPING_SPOOF_LIST, R.drawable.inu_tabler_keyboard, LocaleController.getString(R.string.InuTypingSpoof)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_PRESENCE_LOGGER_NOTIFY -> {
                val new = InuConfig.PRESENCE_LOGGER_NOTIFY.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_FORCE_RELAY_CALLS -> {
                val new = InuConfig.FORCE_RELAY_CALLS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            BUTTON_WATCH_LIST -> presentFragment(PresenceWatchListSettingsActivity())
            BUTTON_TYPING_SPOOF_LIST -> presentFragment(TypingSpoofQuickListSettingsActivity())
        }
    }

    companion object {
        private val TOGGLE_PRESENCE_LOGGER_NOTIFY = InuUtils.generateId()
        private val TOGGLE_FORCE_RELAY_CALLS = InuUtils.generateId()
        private val BUTTON_WATCH_LIST = InuUtils.generateId()
        private val BUTTON_TYPING_SPOOF_LIST = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "stalker-pack",
            titleRes = R.string.InuStalkerPack,
            iconRes = R.drawable.inu_tabler_radar,
            factory = ::StalkerPackSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("presence-logger-notify", R.string.InuPresenceLoggerNotify, TOGGLE_PRESENCE_LOGGER_NOTIFY),
                SearchRegistry.Entry("force-relay-calls", R.string.InuForceRelayCalls, TOGGLE_FORCE_RELAY_CALLS),
            ),
        )
    }
}
